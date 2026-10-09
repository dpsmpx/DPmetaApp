"""Правила проверки репозитория (`tools/check/run.sh`, CLAUDE.md, «Проверки»).

Каждое правило — функция `(root: Path) -> list[str]`: пустой список — правило
выполнено, иначе — находки «место: что не так». Правило читает только файлы
репозитория под `root` и ничего не пишет, поэтому тесты проверяют его на
маленьких образцах во временном каталоге (`tests/test_check.py`).

Уровни (D-09): `static` — секунды, после любой правки; `full` — всё, перед
вливанием (тесты, сборка, дым в Chromium — `slow.py`).
"""

from __future__ import annotations

import ast
import json
import re
import subprocess
import sys
from pathlib import Path

# ------------------------------------------------------------- файлы

SKIP_DIRS = {".git", "node_modules", "dist", "__pycache__", ".pytest_cache"}
TEXT_SUFFIXES = {".md", ".py", ".js", ".css", ".html", ".json", ".toml", ".sh", ".txt", ".yml", ".yaml", ""}


def repo_files(root: Path) -> list[Path]:
    """Файлы репозитория: в клоне git — отслеживаемые и новые неигнорируемые; иначе — обход."""
    try:
        out = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
            capture_output=True, check=True,
        ).stdout.decode("utf-8")
        files = [root / p for p in out.split("\0") if p]
        return sorted(p for p in files if p.is_file())
    except (OSError, subprocess.CalledProcessError):
        pass
    found = []
    for path in sorted(root.rglob("*")):
        if any(part in SKIP_DIRS for part in path.relative_to(root).parts):
            continue
        if path.is_file():
            found.append(path)
    return found


def rel(root: Path, path: Path) -> str:
    return path.relative_to(root).as_posix()


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# ---------------------------------------------------- process.decisions

DECISIONS = "docs/direction/DECISIONS.md"
DECISION_HEAD = re.compile(r"^### (D-(\d{2})) — .+$", re.M)
DECISION_FIELDS = ("Статус", "Решение", "Почему", "Проверка")


def check_decisions(root: Path) -> list[str]:
    """Журнал решений: сплошная нумерация D-01…, без повторов, у каждого — четыре поля."""
    path = root / DECISIONS
    if not path.is_file():
        return [f"{DECISIONS}: нет журнала решений"]
    text = read(path)
    heads = list(DECISION_HEAD.finditer(text))
    problems = []
    if not heads:
        return [f"{DECISIONS}: нет ни одного решения «### D-NN — …»"]
    numbers = [int(m.group(2)) for m in heads]
    for i, n in enumerate(numbers, start=1):
        if n != i:
            problems.append(f"{DECISIONS}: {heads[i - 1].group(1)} стоит {i}-м — нумерация не сплошная или с повтором")
            break
    for i, m in enumerate(heads):
        end = heads[i + 1].start() if i + 1 < len(heads) else len(text)
        body = text[m.end():end]
        for field in DECISION_FIELDS:
            if not re.search(rf"\*\*{field}[.:]?\*\*", body):
                problems.append(f"{DECISIONS}: у {m.group(1)} нет поля «{field}»")
    if "## История" not in text:
        problems.append(f"{DECISIONS}: нет раздела «История»")
    return problems


# ------------------------------------------------------------ docs.refs

TZ = "docs/direction/TECHNICAL_TASK.md"
# Свои номера: решения — в журнале; цели, сценарии, требования, интеграции,
# угрозы, критерии, ограничения, риски, вопросы — в ТЗ (ТЗ, §0).
OWN_REF = re.compile(r"(?<![\w:./-])([DGSFNITALRQ])-(\d{2})(?![\w-])")
TZ_DEF = re.compile(r"^\|\s*([GSFNITALRQ]-\d{2})\s*\|", re.M)
REF_SCAN = {".md", ".py", ".js", ".toml", ".sh"}
# Пути в обратных кавычках, которые должны существовать в репозитории.
PATH_TOKEN = re.compile(r"`([^`\s]+)`")
PATH_ROOTS = ("docs/", "tools/", "tests/", "viewer/", "schemas/", "model/", "data/",
              "ROADMAP/", ".claude/", ".github/")
ROOT_FILES = {"README.md", "CLAUDE.md", "TODO.md", "LICENSE", ".gitignore"}


def defined_refs(root: Path) -> set[str]:
    out = set()
    dec = root / DECISIONS
    if dec.is_file():
        out |= {m.group(1) for m in DECISION_HEAD.finditer(read(dec))}
    tz = root / TZ
    if tz.is_file():
        out |= set(TZ_DEF.findall(read(tz)))
    return out


def _path_ref(token: str) -> str | None:
    token = re.sub(r":\d+(?:-\d+)?$", "", token.rstrip(".,;)"))
    if any(ch in token for ch in "*<>{}$|") or token.startswith("dist/"):
        return None
    if token in ROOT_FILES or token.startswith(PATH_ROOTS):
        return token.rstrip("/")
    return None


def check_refs(root: Path) -> list[str]:
    """Свои номера (`D-NN`, `F-NN` …) определены; пути в обратных кавычках существуют.

    Чужой номер пишется с именем проекта (`DPmeta:C-05`) и здесь не сверяется —
    его сверяет `DPmeta:tools/xref.py`.
    """
    known = defined_refs(root)
    problems = []
    for path in repo_files(root):
        if path.suffix not in REF_SCAN:
            continue
        name = rel(root, path)
        if name.startswith(("tests/", "viewer/test/")):
            continue
        text = read(path)
        for lineno, line in enumerate(text.splitlines(), start=1):
            for m in OWN_REF.finditer(line):
                ref = f"{m.group(1)}-{m.group(2)}"
                if ref not in known:
                    problems.append(f"{name}:{lineno}: {ref} нигде не определён (чужой номер — с именем проекта)")
            if path.suffix != ".md":
                continue
            for m in PATH_TOKEN.finditer(line):
                target = _path_ref(m.group(1))
                if target and not (root / target).exists():
                    problems.append(f"{name}:{lineno}: нет файла `{target}`")
    return problems


# ----------------------------------------------------- process.roadmap

ROADMAP = "ROADMAP/ROADMAP.md"
VERSION_FILE = "tools/dpvis/__init__.py"
STAGE_HEAD = re.compile(r'^### <a id="task-(\d+)"></a>\[([+ ])\] Этап \d+\. (.+?) — `([МСТК])`\s*$', re.M)
STEP = re.compile(r"^- \[([+ ])\] ", re.M)
VERSION_RE = re.compile(r'^VERSION = "(\d+\.\d+\.\d+)"', re.M)


def stages(root: Path) -> list[dict]:
    """Этапы ROADMAP: номер, флажок, суть, шаги сделано/всего."""
    text = read(root / ROADMAP)
    heads = list(STAGE_HEAD.finditer(text))
    out = []
    for i, m in enumerate(heads):
        end = heads[i + 1].start() if i + 1 < len(heads) else len(text)
        steps = STEP.findall(text[m.end():end])
        out.append({"n": int(m.group(1)), "done": m.group(2) == "+", "title": m.group(3),
                    "size": m.group(4), "steps": len(steps), "steps_done": steps.count("+")})
    return out


def version(root: Path) -> str | None:
    m = VERSION_RE.search(read(root / VERSION_FILE))
    return m.group(1) if m else None


def expected_version(st: list[dict]) -> str:
    """Этап N — 0.N.0; все этапы версии 1.0 — 1.0.0 (ROADMAP, вступление)."""
    last = -1
    for s in sorted(st, key=lambda s: s["n"]):
        if not s["done"] or s["n"] != last + 1:
            break
        last = s["n"]
    if st and last == max(s["n"] for s in st):
        return "1.0.0"
    return f"0.{max(last, 0)}.0"


def check_roadmap(root: Path) -> list[str]:
    """Флажок этапа совпадает с его шагами; версия пакета — по сделанным этапам."""
    if not (root / ROADMAP).is_file():
        return [f"{ROADMAP}: нет плана"]
    st = stages(root)
    if not st:
        return [f"{ROADMAP}: нет этапов «### <a id=\"task-N\"></a>[ ] Этап N. …»"]
    problems = []
    for s in st:
        all_done = s["steps"] > 0 and s["steps"] == s["steps_done"]
        if s["done"] and not all_done:
            problems.append(f"{ROADMAP}: этап {s['n']} отмечен сделанным, а шагов сделано {s['steps_done']} из {s['steps']}")
        if not s["done"] and all_done:
            problems.append(f"{ROADMAP}: все шаги этапа {s['n']} сделаны, а этап не отмечен [+]")
    have = version(root) if (root / VERSION_FILE).is_file() else None
    want = expected_version(st)
    if have != want:
        problems.append(f"{VERSION_FILE}: VERSION {have!r}, а по этапам ROADMAP — {want!r}")
    return problems


# ------------------------------------------------------- readme.status

README = "README.md"
STATUS_BEGIN = "<!-- status:begin -->"
STATUS_END = "<!-- status:end -->"


def status_block(root: Path) -> str:
    st = stages(root)
    done = sum(1 for s in st if s["done"])
    lines = [
        STATUS_BEGIN,
        "<!-- Строит tools/check/run.sh --write-status из ROADMAP/ROADMAP.md и",
        "     tools/dpvis/__init__.py — руками не править. -->",
        "",
        f"Версия **{version(root)}**. Этапы версии 1.0 (`ROADMAP/ROADMAP.md`): сделано **{done} из {len(st)}**.",
        "",
        "| Этап | Суть | Сложность | Шаги | Состояние |",
        "|---|---|---|---|---|",
    ]
    for s in st:
        state = "сделан" if s["done"] else ("в работе" if s["steps_done"] else "не начат")
        lines.append(f"| {s['n']} | {s['title']} | {s['size']} | {s['steps_done']} из {s['steps']} | {state} |")
    lines.append(STATUS_END)
    return "\n".join(lines)


def render_readme(root: Path) -> str:
    """README с новым блоком состояния; нет меток — блок дописывается в конец."""
    text = read(root / README)
    block = status_block(root)
    if STATUS_BEGIN in text and STATUS_END in text:
        head, rest = text.split(STATUS_BEGIN, 1)
        _, tail = rest.split(STATUS_END, 1)
        return head + block + tail
    return text.rstrip("\n") + "\n\n" + block + "\n"


def check_status(root: Path) -> list[str]:
    if not (root / README).is_file():
        return [f"{README}: нет файла"]
    text = read(root / README)
    if STATUS_BEGIN not in text or STATUS_END not in text:
        return [f"{README}: нет блока состояния — tools/check/run.sh --write-status"]
    if render_readme(root) != text:
        return [f"{README}: блок состояния отстал от ROADMAP — tools/check/run.sh --write-status"]
    return []


# ---------------------------------------------- model.valid, model.compiled

MODEL_SRC = "model/ecosystem.toml"
MODEL_OUT = "data/model.json"
STATE = "data/state/latest.json"


def _model_mod():
    from tools.dpvis import model
    return model


def check_model_valid(root: Path) -> list[str]:
    model = _model_mod()
    try:
        src, _ = model.load_source(root / MODEL_SRC)
    except model.ModelError as exc:
        return exc.problems
    except OSError as exc:
        return [f"{MODEL_SRC}: {exc}"]
    return [f"{MODEL_SRC}: {p}" for p in model.validate(src)]


def check_model_compiled(root: Path) -> list[str]:
    model = _model_mod()
    out = root / MODEL_OUT
    if not out.is_file():
        return [f"{MODEL_OUT}: нет сборки — python3 -m tools.dpvis model build"]
    try:
        want = model.expected_text(root / MODEL_SRC)
    except model.ModelError:
        return []  # о неверной модели говорит model.valid
    if read(out) != want:
        return [f"{MODEL_OUT}: отстал от {MODEL_SRC} — python3 -m tools.dpvis model build"]
    return []


# ------------------------------------------------------------ state.valid

def check_state(root: Path) -> list[str]:
    """Срез по схеме, связен с моделью и собран по текущей модели (D-13)."""
    from tools.dpvis import formats
    path = root / STATE
    if not path.is_file():
        return [f"{STATE}: нет среза — python3 -m tools.dpvis collect --root <клоны>"]
    try:
        state = json.loads(read(path))
        model = json.loads(read(root / MODEL_OUT))
    except (OSError, json.JSONDecodeError) as exc:
        return [f"{STATE}: не читается: {exc}"]
    problems = [f"{STATE}: {p}" for p in formats.validate_document(state)]
    if problems:
        return problems
    problems += [f"{STATE}: {p}" for p in formats.validate_pair(model, state)]
    have = state.get("model", {}).get("sha256")
    want = model.get("source", {}).get("sha256")
    if have != want:
        problems.append(f"{STATE}: срез собран по старой модели (sha256 {str(have)[:12]}…, а модель {str(want)[:12]}…) — collect заново")
    return problems


# ------------------------------------------------------------ public.leak

def check_public(root: Path) -> list[str]:
    """Публичная выжимка из текущих модели и среза проходит FORBIDDEN (D-15)."""
    from tools.dpvis import public
    try:
        model = json.loads(read(root / MODEL_OUT))
        state = json.loads(read(root / STATE)) if (root / STATE).is_file() else None
    except (OSError, json.JSONDecodeError):
        return []  # о файлах говорят model.compiled и state.valid
    try:
        public.public_bundle(model, state)
    except public.PublicError as exc:
        return [f"публичная выжимка: {p}" for p in exc.problems]
    return []


# ------------------------------------------------------------ repo.layout

ROOT_ALLOWED = ROOT_FILES | {"docs", "ROADMAP", "model", "data", "schemas", "tools", "viewer",
                             "tests", ".claude", ".github"}
FORBIDDEN_PATHS = (
    (re.compile(r"^\.github/workflows/"), "GitHub Actions не используются (D-09)"),
    (re.compile(r"(^|/)package-lock\.json$"), "просмотрщик без пакетов npm (D-05)"),
    (re.compile(r"(^|/)node_modules/"), "просмотрщик без пакетов npm (D-05)"),
    (re.compile(r"^dist/"), "сборка dist/ не коммитится (D-08)"),
    (re.compile(r"(^|/)__pycache__/|\.pyc$"), "кэш Python не коммитится"),
)


def check_layout(root: Path) -> list[str]:
    problems = []
    for path in repo_files(root):
        name = rel(root, path)
        top = name.split("/", 1)[0]
        if top not in ROOT_ALLOWED:
            problems.append(f"{name}: в корне — только точки входа и каталоги (CLAUDE.md, «Где что лежит»)")
        for pat, why in FORBIDDEN_PATHS:
            if pat.search(name):
                problems.append(f"{name}: {why}")
    return problems


# ----------------------------------------------- deps.python, deps.viewer

STDLIB = set(sys.stdlib_module_names) | {"__future__", "tools"}


def check_python_deps(root: Path) -> list[str]:
    """`tools/` — только стандартная библиотека Python (D-05)."""
    problems = []
    for path in repo_files(root):
        name = rel(root, path)
        if not (name.startswith("tools/") and path.suffix == ".py"):
            continue
        try:
            tree = ast.parse(read(path), filename=name)
        except SyntaxError as exc:
            problems.append(f"{name}:{exc.lineno}: не разбирается: {exc.msg}")
            continue
        for node in ast.walk(tree):
            mods = []
            if isinstance(node, ast.Import):
                mods = [a.name for a in node.names]
            elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
                mods = [node.module]
            for mod in mods:
                if mod.split(".", 1)[0] not in STDLIB:
                    problems.append(f"{name}:{node.lineno}: импорт {mod!r} — не стандартная библиотека (D-05)")
    return problems


JS_IMPORT = re.compile(r"""^\s*import\b[^;]*?\bfrom\s*["']([^"']+)["']|^\s*import\s*["']([^"']+)["']|\bimport\s*\(""", re.M)
JS_LOCAL = re.compile(r"^\./[\w-]+\.js$")


def check_viewer_deps(root: Path) -> list[str]:
    """`viewer/src` импортирует только соседние модули `./x.js` (D-05)."""
    problems = []
    for path in repo_files(root):
        name = rel(root, path)
        if not (name.startswith("viewer/src/") and path.suffix == ".js"):
            continue
        text = read(path)
        for m in JS_IMPORT.finditer(text):
            target = m.group(1) or m.group(2)
            line = text.count("\n", 0, m.start()) + 1
            if target is None:
                problems.append(f"{name}:{line}: динамический import() — только статический импорт ./x.js")
            elif not JS_LOCAL.match(target):
                problems.append(f"{name}:{line}: импорт {target!r} — только соседние модули ./x.js (D-05)")
    return problems


# ------------------------------------------- security.secrets, .commands

SECRET_PATTERNS = (
    (re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"), "закрытый ключ"),
    (re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b"), "токен GitHub"),
    (re.compile(r"\bgithub_pat_[A-Za-z0-9_]{40,}\b"), "токен GitHub"),
    (re.compile(r"\bsk-ant-[A-Za-z0-9_-]{20,}"), "ключ API Anthropic"),
    (re.compile(r"\bsk-[A-Za-z0-9]{32,}\b"), "ключ API"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "ключ AWS"),
    (re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}"), "токен Slack"),
    (re.compile(r"""(?i)\b(?:password|passwd|secret|token|api[_-]?key)\b\s*[:=]\s*["'][^"'\s]{8,}["']"""), "пароль или токен в коде"),
)
SECRET_FILES = re.compile(r"(^|/)(\.env(\..+)?|id_(rsa|ed25519|ecdsa|dsa)(\.pub)?|.+\.(pem|key|p12|pfx|jks|keystore))$")


def check_secrets(root: Path) -> list[str]:
    """Секретов и файлов ключей нет (CLAUDE.md, «Секреты»)."""
    problems = []
    for path in repo_files(root):
        name = rel(root, path)
        if SECRET_FILES.search(name):
            problems.append(f"{name}: файл ключей или окружения в репозитории")
            continue
        if path.suffix not in TEXT_SUFFIXES:
            continue
        try:
            text = read(path)
        except UnicodeDecodeError:
            continue
        for pat, what in SECRET_PATTERNS:
            for m in pat.finditer(text):
                line = text.count("\n", 0, m.start()) + 1
                problems.append(f"{name}:{line}: похоже на секрет — {what}")
    return problems


# Вызов программы выгрузки или удалённого входа: первый элемент argv или
# строка команды оболочки; HTTP — только чтение (D-14).
COMMAND_PATTERNS = (
    (re.compile(r"""\[\s*["'](?:rsync|ssh|scp|sftp)["']"""), "вызов rsync, ssh, scp или sftp"),
    (re.compile(r"""\b(?:os\.system|os\.popen|subprocess\.\w+)\(\s*f?["'](?:rsync|ssh|scp|sftp)\b"""), "вызов rsync, ssh, scp или sftp"),
    (re.compile(r"""method\s*=\s*["'](?:POST|PUT|PATCH|DELETE)["']"""), "запрос, который меняет данные: пробы — только GET и HEAD"),
)


def check_commands(root: Path) -> list[str]:
    """Сборщик и сервер не выгружают и не входят на чужие машины (D-14)."""
    problems = []
    for path in repo_files(root):
        name = rel(root, path)
        if not (name.startswith("tools/") and path.suffix in (".py", ".sh")) or name.startswith("tools/check/"):
            continue
        text = read(path)
        for pat, what in COMMAND_PATTERNS:
            for m in pat.finditer(text):
                line = text.count("\n", 0, m.start()) + 1
                problems.append(f"{name}:{line}: {what} (D-14)")
    return problems


# ----------------------------------------------------------------- набор

STATIC = (
    ("process.decisions", check_decisions),
    ("process.roadmap", check_roadmap),
    ("docs.refs", check_refs),
    ("readme.status", check_status),
    ("model.valid", check_model_valid),
    ("model.compiled", check_model_compiled),
    ("state.valid", check_state),
    ("public.leak", check_public),
    ("repo.layout", check_layout),
    ("deps.python", check_python_deps),
    ("deps.viewer", check_viewer_deps),
    ("security.secrets", check_secrets),
    ("security.commands", check_commands),
)
