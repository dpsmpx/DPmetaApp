"""Общее для проверок tools/verify: корень репозитория, список файлов, отчёт.

Проверки — только стандартная библиотека Python 3.11+, без сети; пишут
только в build/verify/ (docs/PROCESS.md, «Проверки»).
"""
import os
import re
import subprocess
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

TEXT_EXT = (".md", ".kt", ".kts", ".py", ".sh", ".toml", ".properties", ".json",
            ".txt", ".yml", ".yaml", ".xml", ".gitignore", ".bat")

TZ = "docs/direction/MASTER_TECHNICAL_TASK.md"
DECISIONS = "docs/direction/DECISIONS.md"
ROADMAP_DIR = "docs/direction/ROADMAP"
ROADMAP = ROADMAP_DIR + "/README.md"
ARCHITECTURE = "docs/ARCHITECTURE.md"
SECURITY = "docs/SECURITY_MODEL.md"


def root():
    """Корень проверяемого дерева: переменная DPAI_VERIFY_ROOT (самотесты) или репозиторий."""
    return os.environ.get("DPAI_VERIFY_ROOT", PROJ)


def files(base=None):
    """Файлы дерева: отслеживаемые git и новые неигнорируемые; без git — обход каталога."""
    base = base or root()
    try:
        out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                             cwd=base, capture_output=True, check=True).stdout.decode("utf-8")
        names = [p for p in out.split("\0") if p]
    except (subprocess.CalledProcessError, FileNotFoundError):
        names = []
        for d, dirs, fs in os.walk(base):
            dirs[:] = [x for x in dirs if x not in (".git", "build", ".gradle", ".kotlin")]
            for f in fs:
                names.append(os.path.relpath(os.path.join(d, f), base))
    return sorted(p.replace(os.sep, "/") for p in names if os.path.isfile(os.path.join(base, p)))


def is_text(rel):
    name = os.path.basename(rel)
    return rel.endswith(TEXT_EXT) or name in ("gradlew", "LICENSE", ".gitignore")


def read(rel, base=None):
    with open(os.path.join(base or root(), rel), encoding="utf-8", errors="replace") as f:
        return f.read()


def exists(rel, base=None):
    return os.path.exists(os.path.join(base or root(), rel))


def kotlin_sources(base=None):
    return [p for p in files(base) if p.endswith(".kt")]


def module_of(rel):
    """Модуль Gradle файла по пути: `core/src/...` → `core`, `tests/integration/...` → `tests/integration`."""
    parts = rel.split("/")
    if parts[0] == "tests" and len(parts) > 1:
        return "tests/" + parts[1]
    return parts[0]


def strip_kotlin_comments_and_strings(text):
    """Код без комментариев и строковых литералов (длина строк сохраняется для номеров строк)."""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(re.sub(r"[^\n]", " ", text[i:j]))
            i = j
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(re.sub(r"[^\n]", " ", text[i:j]))
            i = j
        elif c == '"':
            j = i + 1
            while j < n and text[j] != '"' and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(" " * (j - i))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def line_of(text, pos):
    return text.count("\n", 0, pos) + 1


class Findings:
    """Сбор находок проверки и единый вывод: `✗ путь:строка: сообщение`, итог `✓`."""

    def __init__(self, name):
        self.name = name
        self.items = []

    def add(self, where, message):
        self.items.append((where, message))

    def finish(self, ok_message):
        if self.items:
            for where, message in sorted(self.items):
                print(f"✗ {where}: {message}")
            print(f"{self.name}: находок — {len(self.items)}")
            return 1
        print(f"✓ {self.name}: {ok_message}")
        return 0


def main(fn):
    sys.exit(fn())
