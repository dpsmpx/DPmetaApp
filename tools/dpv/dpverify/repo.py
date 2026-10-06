"""Репозиторий: корень, файлы, текст и двоичный, коммит и состояние дерева (ТЗ, §8.4)."""
import os
import subprocess

from .errors import UsageError
from .globs import MaskSet

# Копию tools/dpv/ сверяет dpv.integrity по манифесту, а её код проверяет прогон
# самого DP-Verify: в проверках проекта она — не его файлы (§8.4).
ALWAYS_EXCLUDED = MaskSet(["build/**", "tools/dpv/**"])
BINARY_PROBE = 8192


def git(root, *args, check=True):
    try:
        r = subprocess.run(["git", "-C", root, *args], capture_output=True, stdin=subprocess.DEVNULL)
    except FileNotFoundError:
        raise UsageError("нет git: DP Verify работает только в git-репозитории (F-13)") from None
    if check and r.returncode != 0:
        raise UsageError(f"git {' '.join(args)}: {r.stderr.decode('utf-8', 'replace').strip()}")
    return r


def find_root(cwd):
    r = git(cwd, "rev-parse", "--show-toplevel", check=False)
    if r.returncode != 0:
        raise UsageError(f"{cwd} — не git-репозиторий: DP Verify работает только в git-репозитории")
    return os.path.realpath(r.stdout.decode("utf-8").strip())


class Repo:
    def __init__(self, root, exclude=None, logs="build/dpv"):
        self.root = root
        self.logs = logs
        self._exclude = exclude
        self._files = None
        self._bytes = {}

    def files(self):
        """Файлы, которые попадут в коммит: отслеживаемые и новые неигнорируемые,
        без build/, копии tools/dpv/ и [project].exclude; порядок — по байтам пути (N-04)."""
        if self._files is None:
            out = git(self.root, "ls-files", "-z", "--cached", "--others", "--exclude-standard").stdout
            paths = set()
            for raw in out.split(b"\0"):
                if not raw:
                    continue
                path = raw.decode("utf-8", "surrogateescape")
                if ALWAYS_EXCLUDED.match(path) or (self._exclude and self._exclude.match(path)):
                    continue
                if os.path.isfile(self.abspath(path)):
                    paths.add(path)
            self._files = sorted(paths, key=lambda p: p.encode("utf-8", "surrogateescape"))
        return self._files

    def all_paths(self):
        """Все файлы без учёта [project].exclude — для сверки масок профиля."""
        return Repo(self.root, None, self.logs).files()

    def abspath(self, path):
        return os.path.join(self.root, path)

    def read_bytes(self, path):
        """Содержимое файла; читается один раз за прогон — его смотрят несколько проверок."""
        if path not in self._bytes:
            with open(self.abspath(path), "rb") as f:
                self._bytes[path] = f.read()
        return self._bytes[path]

    def is_binary(self, path):
        return b"\0" in self.read_bytes(path)[:BINARY_PROBE]

    def text(self, path):
        """Текст файла (неверный UTF-8 — заменой) или None для двоичного."""
        if self.is_binary(path):
            return None
        return self.read_bytes(path).decode("utf-8", "replace")

    def head(self):
        r = git(self.root, "rev-parse", "HEAD", check=False)
        return r.stdout.decode().strip() if r.returncode == 0 else None

    def dirty(self):
        """Есть ли незакоммиченное (кроме каталога журналов DP Verify)."""
        r = git(self.root, "status", "--porcelain", "--untracked-files=normal", "--",
                ".", f":(exclude){self.logs}")
        return bool(r.stdout.strip())
