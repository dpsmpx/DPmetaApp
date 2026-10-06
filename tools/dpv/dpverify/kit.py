"""Process-kit и `dpv init` (ТЗ, §10; F-30…F-32).

`dpv init` кладёт недостающие файлы kit и профиль по языкам; существующий
файл не перезаписывается никогда — печатается, каких разделов шаблона в
нём нет. `dpv init --adopt` пишет только профиль — по найденному в
репозитории — и печатает план подключения: что найдено и какие проверки
сразу красные. Шаблоны — каталог `kit/` рядом с пакетом (его поставляет
tools/vendor.py). Номера решений в шаблоне — `D-NN` (V-16).
"""
import io
import json
import os
import re

from . import VERSION, profile as profile_mod, version
from .errors import UsageError
from .repo import Repo

KIT = "kit"
PROFILE = "tools/dpverify.toml"
ROOT_ALLOW = [".gitignore", "CLAUDE.md", "LICENSE", "README.md", "TODO.md"]
EXTENSIONS = [  # порядок — как в profile.LANGUAGES в справке ТЗ, §7.3
    ("kotlin", (".kt", ".kts")), ("java", (".java",)), ("cpp", (".cpp", ".cc", ".cxx", ".hpp", ".hh")),
    ("c", (".c",)), ("glsl", (".glsl", ".vert", ".frag", ".comp")), ("python", (".py",)),
    ("shell", (".sh", ".bash")), ("markdown", (".md",)),
]
# Пример команды проекта по языку — комментарием в профиле: проект подключает своё (V-04).
COMMANDS = {
    "python": ('project.tests', '["python3", "-m", "unittest", "discover", "-s", "tests"]', '["**/*.py"]'),
    "kotlin": ('project.build', '["./gradlew", "--console=plain", "build"]', '["**/*.kt", "**/*.kts"]'),
    "java": ('project.build', '["./gradlew", "--console=plain", "build"]', '["**/*.java"]'),
    "cpp": ('project.build', '["cmake", "--build", "build"]', '["**/*.cpp", "**/*.hpp", "**/CMakeLists.txt"]'),
    "c": ('project.build', '["make"]', '["**/*.c", "**/*.h", "Makefile"]'),
}
HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
DECISION_HEAD = re.compile(r"^(#{2,4})\s+([A-Za-zА-Яа-яЁё]{1,3})(-?)(\d+)\b")
TOP_ITEM = re.compile(r"^[-*]\s")
SIZE_ITEM = re.compile(r"^- `[МСТК]` ")
ROADMAPS = ("ROADMAP/ROADMAP.md", "docs/direction/ROADMAP/README.md", "ROADMAP.md", "docs/ROADMAP.md")
PROVENANCES = ("docs/PROVENANCE.md", "PROVENANCE.md")


def main(root, args, out, package_root, cwd):
    adopt, langs, name, path = False, None, None, None
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--adopt":
            adopt = True
        elif a == "--lang":
            langs = []
            while i + 1 < len(args) and not args[i + 1].startswith("--"):
                i += 1
                langs.append(args[i])
            if not langs:
                raise UsageError("init --lang: нужен хотя бы один язык")
            bad = sorted(set(langs) - profile_mod.LANGUAGES)
            if bad:
                raise UsageError(f"init --lang: неизвестный язык {bad[0]!r} (есть: "
                                 f"{', '.join(sorted(profile_mod.LANGUAGES))})")
        elif a in ("--name", "--profile") and i + 1 < len(args) and not args[i + 1].startswith("--"):
            i += 1
            if a == "--name":
                name = args[i]
            else:
                path = os.path.abspath(os.path.join(cwd, args[i]))
        else:
            raise UsageError(f"init: неизвестный аргумент {a!r} — dpv init [--adopt] [--lang Я…] [--name ИМЯ]")
        i += 1
    path = path or os.path.join(root, PROFILE)
    repo = Repo(root)
    name = name or os.path.basename(root)
    if adopt:
        if langs is not None:
            raise UsageError("init --adopt: языки находятся по файлам репозитория — без --lang")
        return _adopt(repo, path, name, out, package_root)
    return _init(repo, path, name, langs, out, package_root)


def _rel(repo, path):
    return os.path.relpath(path, repo.root).replace(os.sep, "/")


def _templates(package_root):
    kit = os.path.join(package_root, KIT)
    if not os.path.isdir(kit):
        raise UsageError(f"в копии DP Verify нет {KIT}/ — поставить копию заново (tools/vendor.py)")
    out = []
    for dirpath, dirs, files in os.walk(kit):
        dirs.sort()
        for f in sorted(files):
            full = os.path.join(dirpath, f)
            out.append((os.path.relpath(full, kit).replace(os.sep, "/"), full))
    return out


def detect_languages(files):
    found = []
    for lang, exts in EXTENSIONS:
        if any(f.lower().endswith(exts) for f in files):
            found.append(lang)
    return found


def _range():
    """Диапазон для нового профиля: от этой версии до следующей мажорной. Предварительная
    версия (1.0.0-rc1) младше 1.0 — граница тогда она сама, иначе профиль отверг бы свою копию."""
    major, minor = version.parse(VERSION)[:2]
    low = VERSION if "-" in VERSION else f"{major}.{minor}"
    return f">={low}, <{max(2, major + 1)}"


def _toml_list(items):
    return "[" + ", ".join(json.dumps(x, ensure_ascii=False) for x in items) + "]"


def _commands(langs):
    out = []
    for lang in langs:
        if lang in COMMANDS:
            cid, run, paths = COMMANDS[lang]
            out.append(f"# [[command]]\n# id = \"{cid}\"\n# run = {run}\n# level = \"full\"\n# paths = {paths}\n")
            break  # одного примера хватает: команды — у проекта свои
    return "\n".join(out) or "# [[command]]\n# id = \"project.tests\"\n# run = [\"…\"]\n# level = \"full\"\n"


def _profile_text(package_root, name, langs, root_allow):
    with open(os.path.join(package_root, KIT, PROFILE), encoding="utf-8") as f:
        text = f.read()
    for key, value in (("@@NAME@@", name.replace('"', "'")), ("@@DPV@@", _range()),
                       ("@@LANGUAGES@@", _toml_list(langs)), ("@@ROOT@@", _toml_list(root_allow)),
                       ("@@COMMANDS@@", _commands(langs).rstrip("\n"))):
        text = text.replace(key, value)
    return text


def _missing_sections(template, target):
    """Заголовки шаблона, которых нет в существующем файле (сравнение без регистра и меток)."""
    def heads(path):
        with open(path, encoding="utf-8", errors="replace") as f:
            return [m.group(2) for m in map(HEADING.match, f.read().splitlines()) if m]

    def norm(h):
        return re.sub(r"<[^>]+>|\[[ +xX]\]|`", "", h).strip().lower()
    have = {norm(h) for h in heads(target)}
    return [h for h in heads(template) if norm(h) not in have and "dpv:fill" not in h]


def _init(repo, path, name, langs, out, package_root):
    created, kept = [], []
    files = repo.files()
    if langs is None:
        langs = detect_languages(files)
    root_files = sorted({f for f in files if "/" not in f} | set(ROOT_ALLOW) | {"CLAUDE.md", "TODO.md"})
    profile_rel = _rel(repo, path)
    for rel, src in _templates(package_root):
        target = path if rel == PROFILE else repo.abspath(rel)
        shown = profile_rel if rel == PROFILE else rel
        if os.path.exists(target):
            note = ""
            if rel.endswith(".md"):
                missing = _missing_sections(src, target)
                note = f" — нет разделов шаблона: {', '.join('«' + m + '»' for m in missing)}" if missing else ""
            kept.append(f"  есть, не перезаписан: {shown}{note}")
            continue
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if rel == PROFILE:
            data = _profile_text(package_root, name, langs, root_files).encode("utf-8")
        else:
            with open(src, "rb") as f:
                data = f.read()
        with open(target, "wb") as f:
            f.write(data)
        if os.access(src, os.X_OK):
            os.chmod(target, 0o755)
        created.append(f"  создан: {shown}")
    print(f"dpv init · {name} · языки: {', '.join(langs) or 'не найдены'}", file=out)
    for line in created + kept:
        print(line, file=out)
    print("дальше: заполнить заглушки <!-- dpv:fill … --> (kit-unfilled), затем dpv run static", file=out)
    return 0


# ---- --adopt ----

def _first(repo, candidates):
    return next((c for c in candidates if os.path.isfile(repo.abspath(c))), None)


def _decisions(repo, files):
    """(путь журнала, регэксп заголовка, регэксп ссылки, обязательные поля, пример номера) или None."""
    named = sorted((f for f in files if os.path.basename(f).lower() == "decisions.md"),
                   key=lambda f: (f != "docs/direction/DECISIONS.md", len(f), f))
    if not named:
        return None
    path = named[0]
    text = repo.text(path) or ""
    heads = [m for m in map(DECISION_HEAD.match, text.splitlines()) if m]
    if not heads:
        return path, None, None, [], None
    level, prefix, sep = max({(m.group(1), m.group(2), m.group(3)) for m in heads},
                             key=lambda k: sum((m.group(1), m.group(2), m.group(3)) == k for m in heads))
    widths = {len(m.group(4)) for m in heads if (m.group(1), m.group(2), m.group(3)) == (level, prefix, sep)}
    # ширина — только у номеров с ведущими нулями (D-01): «Р1, Р12» — любой длины
    width = widths.pop() if len(widths) == 1 else 0
    digits = f"\\d{{{width}}}" if width >= 2 else "\\d+"
    heading = f"^{level} {re.escape(prefix)}{sep}({digits})\\b"
    ref = f"(?<![\\w-]){re.escape(prefix)}{sep}({digits})\\b"
    bodies = re.split(r"^#{2,4}\s+" + re.escape(prefix) + sep + r"\d+", text, flags=re.M)[1:]
    fields = ["**Проверка.**"] if bodies and all("**Проверка.**" in b for b in bodies) else []
    return path, heading, ref, fields, f"{prefix}{sep}{heads[0].group(4)}"


def _adopt(repo, path, name, out, package_root):
    from .checks import REGISTRY
    from .runner import Options, Run
    if os.path.exists(path):
        raise UsageError(f"init --adopt: профиль {_rel(repo, path)} уже есть — --adopt пишет только новый профиль")
    files = repo.files()
    langs = detect_languages(files)
    found = _decisions(repo, files)
    roadmap = _first(repo, ROADMAPS)
    provenance = _first(repo, PROVENANCES)
    todo_text = repo.text("TODO.md") if "TODO.md" in files else None
    items = [ln for ln in (todo_text or "").splitlines() if TOP_ITEM.match(ln)]
    sized = bool(items) and all(SIZE_ITEM.match(ln) for ln in items)
    root_files = sorted(f for f in files if "/" not in f)
    lines = ["# tools/dpverify.toml — профиль DP Verify, создан dpv init --adopt по найденному в репозитории.",
             "# Проверьте и поправьте: что настройкой не выразить — вопрос автору (V-13 DP Verify).",
             "schema = 1", f'dpv = "{_range()}"', "", "[project]", f"name = {json.dumps(name, ensure_ascii=False)}",
             f"languages = {_toml_list(langs)}", 'main_branch = "main"', "", "[process]"]
    if "CLAUDE.md" in files:
        lines.append('agent_rules = "CLAUDE.md"')
    lines.append('todo = "TODO.md"')
    if sized:
        lines.append("todo_item = '^- `[МСТК]` '")
    if roadmap:
        lines.append(f'roadmap = "{roadmap}"')
    if found:
        dpath, heading, ref, fields, _ = found
        lines.append(f'decisions = "{dpath}"')
        if heading:
            lines += [f"decision_heading = '{heading}'", f"decision_ref = '{ref}'"]
        if fields:
            lines.append(f"decision_required_fields = {_toml_list(fields)}")
    if provenance:
        lines.append(f'provenance = "{provenance}"')
    lines += ["", '[checks."repo.layout"]', "# файлы корня на момент подключения: лишнее — убрать или оставить с причиной",
              f"root_allow = {_toml_list(root_files)}", ""]
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"dpv init --adopt · {name} · профиль {_rel(repo, path)} создан", file=out)
    print(f"  языки: {', '.join(langs) or 'не найдены'}", file=out)
    if found:
        example = f", номера вида {found[4]}" if found[4] else ", номера не распознаны — decision_heading вручную"
        print(f"  журнал решений: {found[0]}{example}" + (", у каждого — «Проверка.»" if found[3] else ""), file=out)
    else:
        print("  журнал решений: не найден — decisions в [process] вручную или dpv init", file=out)
    for what, value in (("ROADMAP", roadmap), ("PROVENANCE", provenance)):
        print(f"  {what}: {value or 'не найден'}", file=out)
    print(f"  TODO.md: {'пункты с меткой сложности' if sized else ('есть' if todo_text is not None else 'нет')}", file=out)
    print(f"  корень: файлов {len(root_files)} — в root_allow", file=out)
    prof = profile_mod.load(path, REGISTRY)
    run = Run(Repo(repo.root, prof.exclude, prof.logs), prof, Options(level="static"), package_root)
    run.execute(io.StringIO())
    red = [r for r in run.results if r.status.value in ("fail", "error")]
    if red:
        print("сразу красные (dpv run static):", file=out)
        for r in red:
            print(f"  ✗ {r.id}: находок {len(r.errors())}", file=out)
        print("дальше: починить, исключение [[waiver]] с причиной или dpv baseline write --reason \"…\" (I-06)",
              file=out)
    else:
        print("dpv run static — ✓", file=out)
    return 0
