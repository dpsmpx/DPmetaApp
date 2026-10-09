#!/usr/bin/env python3
"""Проверки репозитория DPmetaApp — tools/check/run.sh [static|full] [--verbose] [--write-status].

По идеям DP-Verify (`DP-Verify:docs/direction/TECHNICAL_TASK.md`), без его кода (D-16):
у каждой проверки — id, число просмотренных объектов и находки «путь:строка: что не так».
Пустая проверка — провал (`DP-Verify:V-07`): проверка, которой нечего проверять, только
выглядит защитой.

Уровни: static — структура, текст, секреты, ссылки, номера, процесс (секунды);
        full   — static и самотесты проверок (tests/).
Ключ --write-status переписывает блок состояния README.md из ROADMAP и выходит.

Коды возврата: 0 — пройдено; 1 — есть находки; 2 — неверный вызов;
3 — сбой самой проверки (`DP-Verify:V-10`): исходник ради него не править.
"""
import os
import re
import subprocess
import sys
import time
import traceback

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SHOW = 5

DECISIONS = "docs/direction/DECISIONS.md"
TZ = "docs/direction/TECHNICAL_TASK.md"
ROADMAP = "ROADMAP/ROADMAP.md"
README = "README.md"
TODO = "TODO.md"

# В корне — только точки входа (`DPmeta:E-06`) и мастер-ТЗ автора (D-25: на этот путь
# ссылаются другие репозитории); файлы Gradle и модули — с этапа 1 (ТЗ, §6.2).
ROOT_ALLOW = {
    "README.md", "CLAUDE.md", "TODO.md", "LICENSE", ".gitignore",
    "MESTER_TECHNICAL_TASK_ROADMAP.md",
    ".claude", ".github", "docs", "ROADMAP", "tools", "tests",
    "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
    "gradle", "build-logic", "app", "core", "cli", "config",
}
REQUIRED = [
    README, "CLAUDE.md", TODO, "LICENSE", ".gitignore", DECISIONS, TZ, ROADMAP,
    "docs/direction/REQUEST.md", "docs/PROCESS.md", "docs/PROVENANCE.md",
    ".claude/settings.json", ".claude/hooks/session-start.sh", ".github/pull_request_template.md",
]
# Пути, которые документы называют заранее: появятся на своих этапах. Путь появился —
# убрать отсюда (иначе находка): список не должен прятать опечатки.
PLANNED = {
    "docs/ARCHITECTURE.md": "как устроено — этап 1",
    "docs/SECURITY_MODEL.md": "модель угроз — этап 3",
    "docs/CHANGELOG.md": "версии правил и выпуски — с первой RULES_VERSION",
    "docs/RELEASE.md": "отпечаток ключа и порядок выпуска — этап 8",
    "docs/audits/": "срезы на дату — с первого среза",
    "tools/release/": "сборка неподписанного APK — этап 8",
    "tools/notify/": "набор хуков уведомлений Claude Code (F-56) — этап 8",
}

SECRETS = [
    ("github-token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b")),
    ("github-token", re.compile(r"\bgithub_pat_[A-Za-z0-9_]{40,}\b")),
    ("anthropic-key", re.compile(r"\bsk-ant-[a-z]{3,5}\d{2}-[A-Za-z0-9_\-]{20,}")),
    ("llm-api-key", re.compile(r"\bsk-[A-Za-z0-9_\-]{40,}\b")),
    ("aws-key", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("google-api-key", re.compile(r"\bAIza[0-9A-Za-z_\-]{35}\b")),
    ("slack-token", re.compile(r"\bxox[abprs]-[0-9A-Za-z\-]{10,}\b")),
    ("telegram-bot-token", re.compile(r"\b\d{8,10}:[A-Za-z0-9_\-]{35}\b")),
    ("private-key", re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY-----")),
]
KEY_FILES = re.compile(r"(\.jks|\.keystore|\.p12|\.pfx|\.key|(^|/)\.env(\.[^/]*)?|(^|/)\.git-credentials)$")

# Свои номера (ТЗ, §0): решения — в журнале, остальное — строками таблиц ТЗ.
OWN_REF = re.compile(r"(?<![\w:/.\-])([DGSFNITALRQ])-(\d{2})\b")
TZ_DEF = re.compile(r"^\| ([GSFNITALRQ]-\d{2}) \|")
DECISION_HEADING = re.compile(r"^### (D-(\d{2})) — \S")
DECISION_FIELDS = ("**Статус.**", "**Решение.**", "**Почему.**", "**Проверка.**")
# Чужой номер без имени проекта (`DPmeta:C-06`): номера DPmeta, DP-Verify, SocialMediaImage,
# Galdhold и DPlang, DP_game_market_analytic_system.
FOREIGN_BARE = re.compile(r"(?<![\w:/.\-])((?:C|RISK|SQ|E|M|V|ADR|IC|INV)-\d{2,}|ОР-\d+|Д\d+|Р\d+)\b")

TODO_ITEM = re.compile(r"^- `[МСТК]` ")
STAGE = re.compile(r'^### <a id="task-(\d+)"></a>\[([ +])\] Этап (\d+)\. (.+) — `([МСТК])`$')
STEP = re.compile(r"^- \[([ +])\] ")
STATUS_BEGIN = "<!-- status:begin -->"
STATUS_END = "<!-- status:end -->"
STATUS_NOTE = "<!-- Строит tools/check/run.sh --write-status из ROADMAP/ROADMAP.md — руками не править. -->"


class Result:
    def __init__(self, cid):
        self.id = cid
        self.scanned = 0
        self.findings = []
        self.error = None
        self.seconds = 0.0

    def add(self, where, msg):
        self.findings.append(f"{where}: {msg}")


class Repo:
    """Рабочее дерево: файлы под git (и новые, не игнорируемые) и их текст."""

    def __init__(self, root, planned=None):
        self.root = root
        self.planned = PLANNED if planned is None else planned
        out = subprocess.run(["git", "-C", root, "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                             capture_output=True, check=True)
        names = {f for f in out.stdout.decode("utf-8").split("\0") if f}
        self.files = sorted(f for f in names if os.path.isfile(os.path.join(root, f)))
        self.top_dirs = {f.split("/", 1)[0] for f in self.files if "/" in f}
        self._text = {}

    def path(self, rel):
        return os.path.join(self.root, rel)

    def exists(self, rel):
        return os.path.isfile(self.path(rel))

    def raw(self, rel):
        with open(self.path(rel), "rb") as f:
            return f.read()

    def text(self, rel):
        """Текст файла или None, если файл двоичный или не UTF-8."""
        if rel not in self._text:
            data = self.raw(rel)
            try:
                self._text[rel] = None if b"\0" in data[:8192] else data.decode("utf-8")
            except UnicodeDecodeError:
                self._text[rel] = None
        return self._text[rel]

    def texts(self, suffix=None):
        for rel in self.files:
            if suffix and not rel.endswith(suffix):
                continue
            t = self.text(rel)
            if t is not None:
                yield rel, t

    def is_dir(self, rel):
        prefix = rel.rstrip("/") + "/"
        return any(f.startswith(prefix) for f in self.files)


def prose_lines(text):
    """Строки Markdown вне блоков кода: (номер, строка)."""
    fence = False
    for n, line in enumerate(text.split("\n"), 1):
        if line.lstrip().startswith("```"):
            fence = not fence
            continue
        if not fence:
            yield n, line


def slug(heading):
    """Якорь заголовка, как его строит GitHub."""
    s = re.sub(r"<[^>]+>", "", heading).strip().lower()
    s = re.sub(r"[^\w\- ]", "", s)
    return s.replace(" ", "-")


def anchors(text):
    found, seen = set(), {}
    for _, line in prose_lines(text):
        found.update(re.findall(r'<a id="([^"]+)"', line))
        m = re.match(r"^#{1,6} (.+)$", line)
        if m:
            s = slug(m.group(1))
            n = seen.get(s, 0)
            seen[s] = n + 1
            found.add(s if n == 0 else f"{s}-{n}")
    return found


# ---- проверки ----

def check_layout(repo, r):
    """repo.layout — в корне только точки входа (`DPmeta:E-06`); workflow GitHub Actions нет (D-13)."""
    entries = sorted({f.split("/", 1)[0] for f in repo.files})
    r.scanned = len(entries)
    for e in entries:
        if e not in ROOT_ALLOW:
            r.add(e, "в корне — только точки входа; перенести в свой каталог (CLAUDE.md, «Где что лежит»)")
    for f in repo.files:
        if f.startswith(".github/workflows/"):
            r.add(f, "GitHub Actions не используются (D-13)")


def check_format(repo, r):
    """format.text — UTF-8, переводы строк LF, перевод строки в конце, без пробелов в конце строк."""
    for rel in repo.files:
        data = repo.raw(rel)
        if b"\0" in data[:8192]:
            continue
        r.scanned += 1
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            r.add(rel, "не UTF-8")
            continue
        if "\r" in text:
            r.add(rel, "переводы строк CRLF или CR — нужен LF")
        if text and not text.endswith("\n"):
            r.add(rel, "нет перевода строки в конце файла")
        for n, line in enumerate(text.split("\n"), 1):
            if line != line.rstrip(" \t"):
                r.add(f"{rel}:{n}", "пробелы в конце строки")
                break


def check_scripts(repo, r):
    """format.scripts — у скриптов shell есть #! и право на исполнение; Python разбирается."""
    for rel in repo.files:
        if rel.endswith(".sh"):
            r.scanned += 1
            if not repo.raw(rel).startswith(b"#!"):
                r.add(rel, "нет строки #! в начале")
            if not os.access(repo.path(rel), os.X_OK):
                r.add(rel, "нет права на исполнение (chmod +x, git update-index --chmod=+x)")
        elif rel.endswith(".py"):
            r.scanned += 1
            try:
                compile(repo.raw(rel), rel, "exec")
            except SyntaxError as e:
                r.add(f"{rel}:{e.lineno}", f"Python не разбирается: {e.msg}")


def check_secrets(repo, r):
    """security.secrets — токены и ключи в тексте, файлы ключей (D-09, D-12). Значение не печатается."""
    for rel in repo.files:
        r.scanned += 1
        if KEY_FILES.search(rel):
            r.add(rel, "файл ключа или секретов — в репозитории запрещён (CLAUDE.md, «Секреты и подпись»)")
            continue
        text = repo.text(rel)
        if text is None:
            continue
        for n, line in enumerate(text.split("\n"), 1):
            for kind, rx in SECRETS:
                if rx.search(line):
                    r.add(f"{rel}:{n}", f"похоже на {kind} — значение не печатается; убрать и отозвать")
                    break


def path_candidate(s):
    """Путь из `обратных кавычек`, который можно сверить с деревом, или None."""
    if any(ch in s for ch in " :*<>{}…|=()$~\"',;\\"):
        return None
    if s.startswith(("/", "-", "./", "../")) or (s.startswith(".") and not s.startswith((".claude/", ".github/"))):
        return None
    if "/" not in s.split("#", 1)[0]:
        return None
    return s


def check_links(repo, r):
    """docs.links — ссылки Markdown и пути в `обратных кавычках` ведут на то, что есть.

    Путь сверяется, если его первый каталог есть в репозитории; путь другого проекта
    пишется `Проект:путь` (`DPmeta:C-06`) и не сверяется. Заранее названные пути —
    PLANNED, с причиной.
    """
    anchor_cache = {}

    def anchors_of(rel):
        if rel not in anchor_cache:
            anchor_cache[rel] = anchors(repo.text(rel) or "")
        return anchor_cache[rel]

    for rel, text in repo.texts(".md"):
        base = os.path.dirname(rel)
        for n, line in prose_lines(text):
            for target in re.findall(r"\[[^\]]*\]\(([^)\s]+)\)", line):
                if re.match(r"^[a-z]+:", target):
                    continue
                r.scanned += 1
                path, _, frag = target.partition("#")
                dest = os.path.normpath(os.path.join(base, path)) if path else rel
                if path and not (repo.exists(dest) or repo.is_dir(dest)):
                    r.add(f"{rel}:{n}", f"ссылка ведёт на несуществующий `{path}`")
                elif frag and dest.endswith(".md") and frag not in anchors_of(dest):
                    r.add(f"{rel}:{n}", f"нет якоря `#{frag}` в `{dest}`")
            for code in re.findall(r"`([^`\n]+)`", line):
                cand = path_candidate(code)
                if not cand:
                    continue
                path, _, frag = cand.partition("#")
                if path.split("/", 1)[0] not in repo.top_dirs:
                    continue
                r.scanned += 1
                if path in repo.planned:
                    continue
                if path.endswith("/"):
                    ok = repo.is_dir(path)
                else:
                    ok = repo.exists(path) or repo.is_dir(path)
                if not ok:
                    r.add(f"{rel}:{n}", f"нет пути `{path}` (заранее названный — в PLANNED tools/check/check.py с причиной)")
                elif frag and path.endswith(".md") and frag not in anchors_of(path):
                    r.add(f"{rel}:{n}", f"нет якоря `#{frag}` в `{path}`")
    for path in sorted(repo.planned):
        if repo.exists(path) or (path.endswith("/") and repo.is_dir(path)):
            r.add("tools/check/check.py", f"`{path}` уже есть — убрать из PLANNED")


def check_refs(repo, r):
    """docs.refs — свои номера (D-, G-, S-, F-, N-, I-, T-, A-, L-, R-, Q-) определены; чужие — с именем проекта."""
    defined = set()
    if repo.exists(DECISIONS):
        for line in repo.text(DECISIONS).split("\n"):
            m = DECISION_HEADING.match(line)
            if m:
                defined.add(m.group(1))
    if repo.exists(TZ):
        for line in repo.text(TZ).split("\n"):
            m = TZ_DEF.match(line)
            if m:
                defined.add(m.group(1))
    for rel, text in repo.texts():
        if rel.startswith("tests/"):
            continue
        lines = prose_lines(text) if rel.endswith(".md") else enumerate(text.split("\n"), 1)
        for n, line in lines:
            for m in OWN_REF.finditer(line):
                r.scanned += 1
                ref = f"{m.group(1)}-{m.group(2)}"
                if ref not in defined:
                    r.add(f"{rel}:{n}", f"номер {ref} нигде не определён ({DECISIONS} или таблицы {TZ})")
            if rel.endswith(".md"):
                for m in FOREIGN_BARE.finditer(line):
                    r.scanned += 1
                    r.add(f"{rel}:{n}", f"чужой номер {m.group(1)} без имени проекта — `Проект:{m.group(1)}` (`DPmeta:C-06`)")


def check_decisions(repo, r):
    """process.decisions — номера D-NN сплошные с 01; у решения поля «Статус», «Решение», «Почему», «Проверка»."""
    if not repo.exists(DECISIONS):
        r.add(DECISIONS, "нет журнала решений")
        return
    lines = repo.text(DECISIONS).split("\n")
    heads = [(i, DECISION_HEADING.match(l)) for i, l in enumerate(lines)]
    heads = [(i, m) for i, m in heads if m]
    r.scanned = len(heads)
    for k, (i, m) in enumerate(heads):
        num = int(m.group(2))
        if num != k + 1:
            r.add(f"{DECISIONS}:{i + 1}", f"{m.group(1)} — ожидался D-{k + 1:02d}: номера сплошные, не переиспользуются")
        end = heads[k + 1][0] if k + 1 < len(heads) else next(
            (j for j in range(i + 1, len(lines)) if lines[j].startswith("## ")), len(lines))
        body = "\n".join(lines[i:end])
        for field in DECISION_FIELDS:
            if field not in body:
                r.add(f"{DECISIONS}:{i + 1}", f"у {m.group(1)} нет поля {field}")


def check_todo(repo, r):
    """process.todo — пункт TODO.md начинается с метки `М`/`С`/`Т`/`К`; сделанное удаляется, а не отмечается."""
    if not repo.exists(TODO):
        r.add(TODO, "нет TODO.md")
        return
    for n, line in prose_lines(repo.text(TODO)):
        if not line.startswith("- "):
            continue
        r.scanned += 1
        if re.match(r"^- \[[xX+ ]\]", line):
            r.add(f"{TODO}:{n}", "флажок в TODO.md: сделанное удаляется, план — в ROADMAP")
        elif not TODO_ITEM.match(line):
            r.add(f"{TODO}:{n}", "пункт без метки сложности: - `М` | `С` | `Т` | `К`")


def roadmap_stages(text):
    """Этапы ROADMAP: [(номер, сделан, суть, метка, [флажки шагов], строка заголовка)]."""
    stages = []
    for n, line in prose_lines(text):
        m = STAGE.match(line)
        if m:
            stages.append([int(m.group(1)), m.group(2) == "+", m.group(4), m.group(5), [], n, int(m.group(3))])
            continue
        if line.startswith("### ") and "Этап" in line:
            stages.append([None, False, line, "", [], n, None])
            continue
        s = STEP.match(line)
        if s and stages:
            stages[-1][4].append(s.group(1) == "+")
    return stages


def check_roadmap(repo, r):
    """process.roadmap — заголовки этапов по образцу, номера сплошные с 0; сделанный этап — все шаги сделаны."""
    if not repo.exists(ROADMAP):
        r.add(ROADMAP, "нет ROADMAP")
        return
    stages = roadmap_stages(repo.text(ROADMAP))
    r.scanned = len(stages)
    for k, (anchor, done, title, label, steps, n, num) in enumerate(stages):
        where = f"{ROADMAP}:{n}"
        if anchor is None:
            r.add(where, 'заголовок этапа не по образцу: ### <a id="task-N"></a>[ ] Этап N. Суть — `М`')
            continue
        if anchor != num or num != k:
            r.add(where, f"номер этапа и якорь должны быть {k}")
        if not steps:
            r.add(where, "у этапа нет шагов - [ ] …")
        if done and not all(steps):
            r.add(where, "этап отмечен сделанным, а не все шаги сделаны")
        if not done and steps and all(steps):
            r.add(where, "все шаги сделаны — отметить этап [+]")


def status_block(roadmap_text):
    stages = [s for s in roadmap_stages(roadmap_text) if s[0] is not None]
    done = sum(1 for s in stages if s[1])
    current = next((s for s in stages if not s[1]), None)
    out = [STATUS_BEGIN, STATUS_NOTE, ""]
    if current:
        out.append(f"Сделано этапов: **{done} из {len(stages)}**; текущий — **{current[0]}. {current[2]}** "
                   f"(шагов: {sum(current[4])} из {len(current[4])}).")
    else:
        out.append(f"Сделано этапов: **{done} из {len(stages)}** — все.")
    out += ["", "| Этап | Суть | Сложность | Шаги |", "|---|---|---|---|"]
    for num, _, title, label, steps, _, _ in stages:
        out.append(f"| {num} | {title} | {label} | {sum(steps)} из {len(steps)} |")
    out.append(STATUS_END)
    return "\n".join(out)


def readme_with_status(repo):
    text = repo.text(README)
    a, b = text.find(STATUS_BEGIN), text.find(STATUS_END)
    if a < 0 or b < a:
        return None
    return text[:a] + status_block(repo.text(ROADMAP)) + text[b + len(STATUS_END):]


def check_status(repo, r):
    """readme.status — блок состояния README.md совпадает с ROADMAP (`DPmeta:E-13`)."""
    if not (repo.exists(README) and repo.exists(ROADMAP)):
        r.add(README, "нет README.md или ROADMAP")
        return
    r.scanned = 1
    want = readme_with_status(repo)
    if want is None:
        r.add(README, f"нет блока {STATUS_BEGIN} … {STATUS_END}")
    elif want != repo.text(README):
        r.add(README, "блок состояния устарел — tools/check/run.sh --write-status")


def check_files(repo, r):
    """process.files — обязательные документы процесса на месте."""
    r.scanned = len(REQUIRED)
    for rel in REQUIRED:
        if not repo.exists(rel):
            r.add(rel, "обязательный файл процесса отсутствует")


CHECKS = [
    ("repo.layout", check_layout),
    ("format.text", check_format),
    ("format.scripts", check_scripts),
    ("security.secrets", check_secrets),
    ("docs.links", check_links),
    ("docs.refs", check_refs),
    ("process.decisions", check_decisions),
    ("process.todo", check_todo),
    ("process.roadmap", check_roadmap),
    ("process.files", check_files),
    ("readme.status", check_status),
]


def run_one(cid, repo):
    fn = dict(CHECKS)[cid]
    r = Result(cid)
    t0 = time.monotonic()
    try:
        fn(repo, r)
    except Exception:
        r.error = traceback.format_exc()
    r.seconds = time.monotonic() - t0
    return r


def selftest(root):
    r = Result("project.selftest")
    t0 = time.monotonic()
    p = subprocess.run([sys.executable, "-B", "-X", "utf8", "-m", "unittest", "discover", "-s", "tests"],
                       cwd=root, capture_output=True, text=True)
    r.seconds = time.monotonic() - t0
    m = re.search(r"^Ran (\d+) tests?", p.stderr, re.M)
    r.scanned = int(m.group(1)) if m else 0
    if p.returncode != 0:
        tail = [l for l in p.stderr.strip().split("\n") if l][-SHOW * 2:]
        r.findings.extend(tail or [f"unittest: код {p.returncode}"])
    return r


def report(results, level, verbose, seconds):
    failed = 0
    errors = 0
    for r in results:
        if r.error:
            errors += 1
            print(f"! {r.id} — сбой проверки")
            print("    " + r.error.strip().split("\n")[-1])
            continue
        if r.scanned == 0 and not r.findings:
            r.findings.append("пустая проверка: нечего проверять — это провал, а не зелёный (`DP-Verify:V-07`)")
        if r.findings:
            failed += 1
            print(f"✗ {r.id} — находок: {len(r.findings)}; просмотрено: {r.scanned}")
            for f in (r.findings if verbose else r.findings[:SHOW]):
                print(f"    {f}")
            if not verbose and len(r.findings) > SHOW:
                print(f"    … ещё {len(r.findings) - SHOW} (--verbose)")
        else:
            print(f"✓ {r.id} — просмотрено: {r.scanned}")
    ok = len(results) - failed - errors
    print(f"Итог ({level}, {seconds:.1f} с): пройдено {ok} из {len(results)}"
          + (f", с находками {failed}" if failed else "") + (f", сбоев {errors}" if errors else ""))
    return 3 if errors else (1 if failed else 0)


def main(argv):
    level, verbose, write = "static", False, False
    for a in argv:
        if a in ("static", "full"):
            level = a
        elif a == "--verbose":
            verbose = True
        elif a == "--write-status":
            write = True
        elif a in ("-h", "--help"):
            print(__doc__)
            return 0
        else:
            print(f"неизвестный параметр: {a} (есть static, full, --verbose, --write-status)")
            return 2
    try:
        repo = Repo(ROOT)
    except (OSError, subprocess.CalledProcessError) as e:
        print(f"! не удалось прочитать репозиторий: {e}")
        return 3
    if write:
        new = readme_with_status(repo)
        if new is None:
            print(f"! в {README} нет блока {STATUS_BEGIN} … {STATUS_END}")
            return 2
        with open(repo.path(README), "w", encoding="utf-8") as f:
            f.write(new)
        print(f"✓ блок состояния {README} переписан из {ROADMAP}")
        return 0
    t0 = time.monotonic()
    results = [run_one(cid, repo) for cid, _ in CHECKS]
    if level == "full":
        results.append(selftest(ROOT))
    return report(results, level, verbose, time.monotonic() - t0)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
