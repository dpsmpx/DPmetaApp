#!/usr/bin/env python3
"""Проверки репозитория DPmetaApp — tools/check/run.sh [static|full [--strict]] [--verbose] [--write-status].

По идеям DP-Verify (`DP-Verify:docs/direction/TECHNICAL_TASK.md`), без его кода (D-16):
у каждой проверки — id, число просмотренных объектов и находки «путь:строка: что не так».
Пустая проверка — провал (`DP-Verify:V-07`): проверка, которой нечего проверять, только
выглядит защитой.

Уровни: static — структура, текст, секреты, ссылки, номера, процесс, единое ядро, wrapper (секунды);
        full   — static, самотесты проверок (tests/), проверки подсистем (D-28) и сборка
                 Gradle из корня (`build.gradle`, ТЗ, §14); пропуск (нет JDK, нет
                 Android SDK) назван, а с --strict — ошибка.

Подсистема со своими проверками (SUBSYSTEMS) — каталог со своим процессом: её текст, ссылки
и номера проверяет её команда; здесь — секреты (второй, независимый поиск), раскладка корня
и единое ядро (кроме подсистемы со своим ядром по решению — CORE_OWN, D-30).
Ключ --write-status переписывает блок состояния README.md из ROADMAP и выходит.

Коды возврата: 0 — пройдено; 1 — есть находки; 2 — неверный вызов;
3 — сбой самой проверки (`DP-Verify:V-10`): исходник ради него не править.
"""
import os
import re
import shutil
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
# ссылаются другие репозитории); файлы Gradle и модули — с этапа 1 (ТЗ, §6.2); подсистемы (D-28).
ROOT_ALLOW = {
    "README.md", "CLAUDE.md", "TODO.md", "LICENSE", ".gitignore", ".gitattributes",
    "MESTER_TECHNICAL_TASK_ROADMAP.md",
    ".claude", ".github", "docs", "ROADMAP", "tools", "tests",
    "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
    "gradle", "build-logic", "app", "shell", "core", "cli", "config",
    "dpai", "dppass", "socialmediaimage", "dpvisualisation", "dpmeta", "dpprofile",
}
# Пакетный файл Windows создан Gradle с CRLF — так и хранится (`.gitattributes`).
CRLF_ALLOWED = {"gradlew.bat"}
# Wrapper корня — копия wrapper подсистемы dpai/ (D-28): одна версия Gradle и одна сверка
# его суммы (`dpai/docs/PROVENANCE.md`).
WRAPPER = ("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")
WRAPPER_SOURCE = "dpai/"
# Подсистемы со своим процессом и командой проверок (D-28, D-43): каталог → команда быстрого уровня;
# строка — команда с аргументом `static`, кортеж — команда со своими аргументами.
SUBSYSTEMS = {
    "dpai": "dpai/tools/verify/run.sh",
    "dppass": "dppass/tools/hostcheck/run.sh",
    "socialmediaimage": ("socialmediaimage/tools/check.sh", "--quick"),
    "dpvisualisation": "dpvisualisation/tools/check/run.sh",
    "dpmeta": ("dpmeta/tools/check.sh",),
    "dpprofile": ("dpprofile/tools/verify/run.sh", "--quick"),
}
# Строка шага в выводе команды подсистемы: «- ✓ шаг» (dpai), «  ✓ id  итог» (DP Verify у dppass, D-30),
# «✓ id — что проверено» (SocialMediaImage) или «✓ id (0.0 с)» (DPvisualisation, D-43);
# итог прогона («✓ static пройден», «✓ tools/check.sh: всё зелёное») — не шаг.
SUBSYSTEM_STEP = re.compile(r"^(?:(?:- |  )([✓✗]) (.+)|([✓✗]) (\S+ (?:— |\().+))$", re.M)
# Свой вид строки шага: у DPmeta шаг — «✓ документы: ссылки, …», итог — «✓ DPmeta: всё зелёное» (D-43).
SUBSYSTEM_STEP_OWN = {"dpmeta": re.compile(r"^([✓✗]) (?!DPmeta: )(.+)$", re.M)}
# Единое ядро (D-28): эти типы объявлены один раз — в ядре подсистемы dpai.
CORE_TYPES = ("Outcome", "OperationResult", "Diagnostic", "DiagCode", "Secret", "Clock")
CORE_HOME = "dpai/core/src/main/"
# Своё ядро подсистемы — по решению, с причиной: каталог → решение.
CORE_OWN = {"dppass/": "D-30: ядро DPpass пока своё, сведение с ядром dpai — отдельной задачей с паритетом"}
CORE_DECL = re.compile(r"^[ \t]*(?:(?:public|internal|private|sealed|data|value|enum|abstract|open|fun|inline)\s+)*"
                       r"(?:class|interface|object|typealias)\s+(" + "|".join(CORE_TYPES) + r")\b", re.M)
# Имена в обратных кавычках, похожие на путь, но не пути: с переездом подсистемы её каталог
# стал первым сегментом таких имён (D-43); срезы docs/audits/ задним числом не правятся.
NOT_PATHS = {
    "dpmeta/docs-sync": "имя ветки DPmeta в срезе docs/audits/2026-10-10-ecosystem.md, не путь",
}
# Документы автора, которые он ведёт сам (через веб-интерфейс): форма текста не проверяется,
# чтобы не править их ради проверки (D-25).
AUTHOR_FILES = {"MESTER_TECHNICAL_TASK_ROADMAP.md"}
REQUIRED = [
    README, "CLAUDE.md", TODO, "LICENSE", ".gitignore", DECISIONS, TZ, ROADMAP,
    "docs/direction/REQUEST.md", "docs/PROCESS.md", "docs/PROVENANCE.md",
    ".claude/settings.json", ".claude/hooks/session-start.sh", ".github/pull_request_template.md",
]
# Пути, которые документы называют заранее: появятся на своих этапах. Путь появился —
# убрать отсюда (иначе находка): список не должен прятать опечатки.
PLANNED = {
    "gradle/verification-metadata.xml": "контрольные суммы зависимостей (ТЗ, N-14) — с первой зависимостью модулей DPmetaApp, этап 1",
    "docs/RELEASE.md": "отпечаток ключа и порядок выпуска — этап 8",
    "tools/release/": "сборка неподписанного APK — этап 8",
    "tools/notify/": "набор хуков уведомлений Claude Code (F-56) — этап 7",
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
        # Чего проверка не сделала и почему (нет JDK, нет SDK): в --strict — находка (ТЗ, §14).
        self.skipped = []

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
        # Свои файлы — вне подсистем: текст, ссылки и номера подсистемы проверяет её команда (D-28).
        self.own = [f for f in self.files if f.split("/", 1)[0] not in SUBSYSTEMS]
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
        for rel in self.own:
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
    """format.text — UTF-8, переводы строк LF, перевод строки в конце, без пробелов в конце строк.

    Документы автора (AUTHOR_FILES) не проверяются: их ведёт автор, сессия их не правит.
    """
    for rel in repo.own:
        if rel in AUTHOR_FILES:
            continue
        data = repo.raw(rel)
        if b"\0" in data[:8192]:
            continue
        r.scanned += 1
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            r.add(rel, "не UTF-8")
            continue
        if "\r" in text and rel not in CRLF_ALLOWED:
            r.add(rel, "переводы строк CRLF или CR — нужен LF")
        if text and not text.endswith("\n"):
            r.add(rel, "нет перевода строки в конце файла")
        for n, line in enumerate(text.split("\n"), 1):
            if line != line.rstrip(" \t"):
                r.add(f"{rel}:{n}", "пробелы в конце строки")
                break


def check_scripts(repo, r):
    """format.scripts — у скриптов shell есть #! и право на исполнение; Python разбирается."""
    for rel in repo.own:
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
    PLANNED, с причиной. Имя в обратных кавычках, похожее на путь, но не путь, —
    NOT_PATHS, с причиной; такой путь появился — находка (иначе список прятал бы его).
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
                if path in NOT_PATHS:
                    if repo.exists(path) or repo.is_dir(path):
                        r.add(f"{rel}:{n}", f"`{path}` в NOT_PATHS, а такой путь есть — убрать из NOT_PATHS")
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


def check_core_single(repo, r):
    """core.single — типы ядра объявлены один раз и только в ядре dpai (D-28): второго ядра нет.

    Исключение — подсистема со своим ядром по решению (CORE_OWN, D-30)."""
    seen = {}
    for rel in repo.files:
        if not rel.endswith(".kt") or rel.startswith(tuple(CORE_OWN)):
            continue
        r.scanned += 1
        text = repo.text(rel) or ""
        for m in CORE_DECL.finditer(text):
            seen.setdefault(m.group(1), []).append((rel, text.count("\n", 0, m.start()) + 1))
    for name in CORE_TYPES:
        places = seen.get(name, [])
        home = [p for p in places if p[0].startswith(CORE_HOME)]
        for rel, line in places:
            if not rel.startswith(CORE_HOME):
                r.add(f"{rel}:{line}", f"{name} объявлен вне {CORE_HOME}: ядро одно — тип берётся оттуда (D-28)")
        if len(home) > 1:
            r.add(f"{home[1][0]}:{home[1][1]}", f"{name} объявлен в ядре {len(home)} раз — тип один (D-28)")
        if r.scanned and not home:
            r.add(CORE_HOME, f"нет объявления {name} в ядре — проверка устарела или ядро сломано (D-28)")


# Модули DPmetaApp в корне (ТЗ, §6.2; D-03, D-28): кому от кого можно зависеть.
# Ключ — модуль, значение — разрешённые зависимости: проекты корня и модули ядра dpai/.
ROOT_MODULES = {
    ":core:model": set(),
    ":core:formats": {":core:model"},
    ":core:net": set(),
    ":core:github": {":core:net", ":core:model", ":core:formats"},
    ":core:site": {":core:net", ":core:model", ":core:formats"},
    ":core:social": {":core:net", ":core:model", ":core:formats"},
    ":core:metrics": {":core:model"},
    ":core:notify": {":core:model"},
    ":core:actions": {":core:model"},
    ":core:sync": {":core:model", ":core:formats", ":core:net", ":core:github", ":core:site", ":core:social"},
}
# Оболочки и тесты зависят от всех (ТЗ, §6.2); :shell — приложение из корня (D-35, D-53).
ROOT_SHELLS = (":cli", ":app", ":shell", ":tests:")
DPAI_CORE = {"core", "storage", "security", "notifications"}
MODULE_DEP = re.compile(r'project\(\s*"(:[\w:-]+)"\s*\)|"com\.dpsmpx\.dpai:([\w-]+)"')
# Код модулей корня (не оболочек): правила кода CLAUDE.md — Android только в оболочке,
# время — Clock, печать — только cli, секрет раскрывается в одном месте сети.
MODULE_CODE_RULES = (
    (re.compile(r"^\s*import\s+(?:android|androidx)\."), "Android-типы — только в оболочке (D-03)"),
    (re.compile(r"System\.currentTimeMillis|Instant\.now\(|LocalDateTime\.now\("), "время — через Clock ядра"),
    (re.compile(r"Thread\.sleep|GlobalScope|runBlocking"), "без сна и глобальных корутин вне cli"),
    (re.compile(r"(?<![\w.])println\(|System\.out|System\.err"), "печать — только в cli; остальное — Diagnostic"),
)
REVEAL = re.compile(r"\.reveal\s*\(\s*\)")
REVEAL_HOME = "core/net/src/main/kotlin/com/dpsmpx/dpmeta/net/Auth.kt"


def module_of(rel):
    """`core/net/build.gradle.kts` → `:core:net`."""
    return ":" + rel[: -len("/build.gradle.kts")].replace("/", ":")


def check_modules(repo, r):
    """modules.root — направления зависимостей модулей корня и правила их кода (ТЗ, §6.2; D-03, D-28)."""
    builds = [rel for rel in repo.files
              if rel.endswith("/build.gradle.kts") and rel.split("/")[0] in ("core", "cli", "app", "shell", "tests")]
    for rel in builds:
        r.scanned += 1
        mod = module_of(rel)
        if mod.startswith(ROOT_SHELLS):
            continue
        if mod not in ROOT_MODULES:
            r.add(rel, f"модуль {mod} не описан в ТЗ, §6.2 — сначала решение и ROOT_MODULES")
            continue
        text = repo.text(rel) or ""
        for m in MODULE_DEP.finditer(text):
            line = text.count("\n", 0, m.start()) + 1
            dep, dpai = m.group(1), m.group(2)
            if dep is not None and dep not in ROOT_MODULES[mod]:
                r.add(f"{rel}:{line}", f"{mod} не может зависеть от {dep} (ТЗ, §6.2)")
            if dpai is not None and dpai not in DPAI_CORE:
                r.add(f"{rel}:{line}", f"{mod} зависит от подсистемы dpai:{dpai} — только от ядра {sorted(DPAI_CORE)}")
    for rel in repo.files:
        if not rel.endswith(".kt") or "/src/main/" not in rel or not rel.startswith("core/"):
            continue
        text = repo.text(rel) or ""
        for i, line in enumerate(text.splitlines(), 1):
            if line.lstrip().startswith(("//", "*", "/*")):
                continue
            for rx, why in MODULE_CODE_RULES:
                if rx.search(line):
                    r.add(f"{rel}:{i}", why)
            if REVEAL.search(line) and rel != REVEAL_HOME:
                r.add(f"{rel}:{i}", f"reveal() — только в {REVEAL_HOME} (секрет раскрывается в одном месте)")


# Запрещённое по решениям (ТЗ, §6.5; A-06, A-07): признаки в коде приложения — Kotlin и Java
# основных исходников всего репозитория, подсистемы тоже (второй, независимый поиск).
# Выгрузку сайта держит только подсистема SocialMediaImage (D-40, D-48) — её каталог
# правило выгрузки не смотрит. Автоответы и массовые действия (D-21, D-42) признаков в
# тексте не имеют — их ловят тесты :core:actions (этап 5).
FORBIDDEN_CODE = (
    (re.compile(r"\bbeget\b|\brsync\b", re.I), ("socialmediaimage/",),
     "выгрузка сайта — только подсистемой SocialMediaImage (D-40, A-07)"),
    (re.compile(r"v1/organizations/(?!usage_report|cost_report)"), (),
     "Admin API — только GET отчётов Usage и Cost; изменяющих вызовов нет (D-08, D-44)"),
    (re.compile(r"\b(?:org\.jsoup|org\.openqa\.selenium|com\.microsoft\.playwright|org\.htmlunit|com\.gargoylesoftware\.htmlunit)\b"
                r"|\.(?:evaluateJavascript|addJavascriptInterface)\s*\("), (),
     "без скрейпинга и автоматизации браузера — только официальные API (D-20)"),
)
CODE_EXT = (".kt", ".kts", ".java")


def check_forbidden(repo, r):
    """code.forbidden — в коде приложения нет запрещённого ТЗ, §6.5: выгрузки сайта мимо
    SocialMediaImage, изменяющих вызовов Admin API, скрейпинга и автоматизации браузера (A-06, A-07)."""
    for rel in repo.files:
        if not rel.endswith(CODE_EXT) or "/src/test/" in rel or rel.startswith("tests/") or "/tests/" in rel:
            continue
        text = repo.text(rel)
        if text is None:
            continue
        r.scanned += 1
        for i, line in enumerate(text.splitlines(), 1):
            if line.lstrip().startswith(("//", "*", "/*")):
                continue
            for rx, allowed, why in FORBIDDEN_CODE:
                if rx.search(line) and not rel.startswith(allowed):
                    r.add(f"{rel}:{i}", why)


def check_wrapper(repo, r):
    """build.wrapper — wrapper Gradle корня совпадает с wrapper подсистемы dpai/ байт в байт (D-28)."""
    for rel in WRAPPER:
        src = WRAPPER_SOURCE + rel
        if not repo.exists(src):
            r.add(src, "нет wrapper подсистемы — источника копии")
            continue
        r.scanned += 1
        if not repo.exists(rel):
            r.add(rel, f"нет копии {src} в корне — сборка из корня не запустится")
        elif repo.raw(rel) != repo.raw(src):
            r.add(rel, f"отличается от {src}: wrapper один — скопировать заново (cp {src} {rel})")


def check_subsystems(root, subsystems=None):
    """subsystem.* — команда проверок каждой подсистемы на уровне static проходит (D-28)."""
    results = []
    for name, spec in sorted((SUBSYSTEMS if subsystems is None else subsystems).items()):
        cmd, *args = (spec, "static") if isinstance(spec, str) else spec
        r = Result(f"subsystem.{name}")
        t0 = time.monotonic()
        path = os.path.join(root, cmd)
        if not os.path.isfile(path):
            r.add(cmd, "нет команды проверок подсистемы")
        else:
            p = subprocess.run(["bash", path, *args], cwd=root, capture_output=True, text=True)
            found = SUBSYSTEM_STEP_OWN.get(name, SUBSYSTEM_STEP).findall(p.stdout)
            steps = [(m[0] or m[2], m[1] or m[3]) if len(m) == 4 else m for m in found]
            r.scanned = len(steps)
            r.findings.extend(f"{cmd}: {what}" for mark, what in steps if mark == "✗")
            if p.returncode != 0 and not r.findings:
                tail = [l for l in (p.stdout + p.stderr).strip().split("\n") if l][-SHOW:]
                r.findings.extend(tail or [f"{cmd}: код {p.returncode}"])
        r.seconds = time.monotonic() - t0
        results.append(r)
    return results


# Отказ загрузки зависимостей — окружение (прокси, сеть), а не код: сбой проверки, код 3.
TRANSIENT = re.compile(r"status code (?:429|5\d\d)|Could not GET|Connection reset|Read timed out|connect timed out")
TASKS_DONE = re.compile(r"^(\d+) actionable tasks?", re.M)


def android_sdk(root):
    """Android SDK, как его ищет dpai/settings.gradle.kts: dpai/local.properties или переменные окружения."""
    local = os.path.join(root, "dpai", "local.properties")
    candidates = []
    if os.path.isfile(local):
        for line in open(local, encoding="utf-8"):
            if line.startswith("sdk.dir="):
                candidates.append(line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "/"))
    candidates += [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    return next((c for c in candidates if c and os.path.isdir(c)), None)


def gradle_run(cmd, cwd):
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=3600)
    return p.returncode, p.stdout + p.stderr


def check_gradle(root, java=None, sdk=None, run=gradle_run):
    """build.gradle — `./gradlew build` из корня: ядро, подсистема dpai/ и, при Android SDK, приложение (ТЗ, §14).

    Нет JDK — сборка не выполнена, и это **находка**, а не зелёный: без Kotlin-компиляции
    `full` не значит ничего (аудит 30 октября 2026, DPmetaApp:D-51). Нет Android SDK —
    собрано всё, кроме оболочки: это законный пропуск, он назван и в `--strict` становится
    находкой. Отказ загрузки зависимостей — сбой проверки (код 3), а не код.
    """
    r = Result("build.gradle")
    t0 = time.monotonic()
    java = shutil.which("java") if java is None else java
    if not java:
        # Сборка не проверена вовсе: не «пропуск», а провал уровня full (D-51).
        r.findings.append("нет JDK — сборка Gradle не проверена: `full` без сборки не значит ничего (установите JDK 21)")
        return r
    if not (android_sdk(root) if sdk is None else sdk):
        r.skipped.append("нет Android SDK — приложение (dpai/android) не собрано")
    rc, out = run([os.path.join(root, "gradlew"), "build", "--console=plain"], root)
    m = TASKS_DONE.search(out)
    r.scanned = int(m.group(1)) if m else 0
    if rc != 0:
        tail = [l for l in out.strip().split("\n") if l.strip()][-SHOW * 2:]
        if TRANSIENT.search(out):
            r.error = "загрузка зависимостей не удалась (сеть, прокси) — сбой окружения, не кода; повторить\n" + tail[-1]
        else:
            r.findings.extend(tail or [f"gradlew: код {rc}"])
    r.seconds = time.monotonic() - t0
    return r


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
    ("core.single", check_core_single),
    ("modules.root", check_modules),
    ("code.forbidden", check_forbidden),
    ("build.wrapper", check_wrapper),
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


def report(results, level, verbose, seconds, strict=False):
    failed = 0
    errors = 0
    for r in results:
        if r.error:
            errors += 1
            print(f"! {r.id} — сбой проверки")
            print("    " + r.error.strip().split("\n")[-1])
            continue
        if strict:
            r.findings.extend(f"пропуск в --strict — ошибка: {why}" for why in r.skipped)
        elif r.skipped and not r.findings:
            print(f"○ {r.id} — просмотрено: {r.scanned}; пропуск: {'; '.join(r.skipped)}")
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
    level, verbose, write, strict = "static", False, False, False
    for a in argv:
        if a in ("static", "full"):
            level = a
        elif a == "--strict":
            strict = True
        elif a == "--verbose":
            verbose = True
        elif a == "--write-status":
            write = True
        elif a in ("-h", "--help"):
            print(__doc__)
            return 0
        else:
            print(f"неизвестный параметр: {a} (есть static, full, --strict, --verbose, --write-status)")
            return 2
    if strict and level != "full":
        print("--strict — только с full: пропуски бывают у сборки (ТЗ, §14)")
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
        results.extend(check_subsystems(ROOT))
        results.append(check_gradle(ROOT))
    return report(results, level, verbose, time.monotonic() - t0, strict)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
