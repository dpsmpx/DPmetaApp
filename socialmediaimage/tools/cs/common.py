"""Общие помощники инструментов системы контента.

Все пути — от корня репозитория. Ошибки проверок копятся в Report и
печатаются одним итогом: проверка не падает на первой находке, чтобы
сессия видела всё сразу.
"""
from __future__ import annotations

import datetime as _dt
import hashlib
import json
import os
import struct
from dataclasses import dataclass, field
from pathlib import Path

import subprocess

import yaml

ROOT = Path(__file__).resolve().parents[2]
ITEMS_DIR = ROOT / "content" / "items"


def rel(path: Path | str) -> str:
    p = Path(path)
    try:
        return str(p.resolve().relative_to(ROOT))
    except ValueError:
        return str(p)


def load_yaml(path: Path | str):
    with open(ROOT / path if not Path(path).is_absolute() else path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def dump_yaml(data, path: Path | str, header: str = "") -> None:
    p = ROOT / path if not Path(path).is_absolute() else Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        f.write(header)
        yaml.safe_dump(data, f, allow_unicode=True, sort_keys=False, width=100)


def load_json(path: Path | str):
    with open(ROOT / path if not Path(path).is_absolute() else path, encoding="utf-8") as f:
        return json.load(f)


def sha256_file(path: Path | str) -> str:
    h = hashlib.sha256()
    with open(ROOT / path if not Path(path).is_absolute() else path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def today() -> _dt.date:
    """Дата для проверок сроков. CS_TODAY=YYYY-MM-DD — для тестов."""
    forced = os.environ.get("CS_TODAY")
    return _dt.date.fromisoformat(forced) if forced else _dt.date.today()


def as_date(value) -> _dt.date | None:
    if value is None:
        return None
    if isinstance(value, _dt.datetime):
        return value.date()
    if isinstance(value, _dt.date):
        return value
    return _dt.date.fromisoformat(str(value))


def image_size(path: Path | str) -> tuple[int, int, str]:
    """(ширина, высота, тип) PNG или JPEG — по заголовку, без библиотек."""
    p = ROOT / path if not Path(path).is_absolute() else Path(path)
    data = p.read_bytes()
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        w, h = struct.unpack(">II", data[16:24])
        return w, h, "png"
    if data[:2] == b"\xff\xd8":
        i = 2
        while i < len(data):
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
                i += 2
                continue
            length = struct.unpack(">H", data[i + 2:i + 4])[0]
            if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                h, w = struct.unpack(">HH", data[i + 5:i + 9])
                return w, h, "jpg"
            i += 2 + length
        raise ValueError(f"{rel(p)}: JPEG без кадра SOF")
    raise ValueError(f"{rel(p)}: не PNG и не JPEG")


def jpeg_metadata_segments(path: Path | str) -> list[str]:
    """Сегменты JPEG с метаданными, которым не место в экспорте: EXIF/XMP (APP1), COM."""
    p = ROOT / path if not Path(path).is_absolute() else Path(path)
    data = p.read_bytes()
    found = []
    if data[:2] != b"\xff\xd8":
        return found
    i = 2
    while i + 4 <= len(data) and data[i] == 0xFF:
        marker = data[i + 1]
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        length = struct.unpack(">H", data[i + 2:i + 4])[0]
        if marker == 0xE1:
            found.append("APP1 (EXIF/XMP)")
        elif marker == 0xFE:
            found.append("COM")
        if marker == 0xDA:
            break
        i += 2 + length
    return found


@dataclass(frozen=True)
class Finding:
    """Находка проверки объекта: level — error | warn | note."""

    level: str
    msg: str


@dataclass
class Report:
    """Итог одной проверки: ошибки валят check.sh, предупреждения — нет."""

    name: str
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)

    def error(self, msg: str) -> None:
        self.errors.append(msg)

    def warn(self, msg: str) -> None:
        self.warnings.append(msg)

    def note(self, msg: str) -> None:
        self.notes.append(msg)

    def add(self, findings: list[Finding], prefix: str = "") -> None:
        for f in findings:
            {"error": self.errors, "warn": self.warnings, "note": self.notes}[f.level].append(prefix + f.msg)

    @property
    def ok(self) -> bool:
        return not self.errors

    def print(self) -> None:
        mark = "✓" if self.ok else "✗"
        tail = f" ({len(self.warnings)} предупр.)" if self.warnings else ""
        print(f"{mark} {self.name}{tail}")
        for n in self.notes:
            print(f"    · {n}")
        for w in self.warnings:
            print(f"    ! {w}")
        for e in self.errors:
            print(f"    ✗ {e}")


def repo_files() -> list[Path]:
    """Файлы репозитория: отслеживаемые и новые, кроме игнорируемых (.gitignore)."""
    res = subprocess.run(
        ["git", "ls-files", "-co", "--exclude-standard", "-z"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    )
    return sorted(ROOT / f for f in res.stdout.split("\0") if f and (ROOT / f).is_file())


def is_text(path: Path) -> bool:
    try:
        chunk = path.read_bytes()[:4096]
    except OSError:
        return False
    if b"\0" in chunk:
        return False
    try:
        chunk.decode("utf-8")
    except UnicodeDecodeError as e:
        return e.start > len(chunk) - 4  # обрезанный многобайтовый символ на границе
    return True


@dataclass
class Item:
    """Объект контента: каталог content/items/<id>-<slug>/ и его item.yaml."""

    dir: Path
    data: dict

    @property
    def id(self) -> str:
        return self.data.get("id", self.dir.name)

    def path(self, p: str) -> Path:
        """Путь из item.yaml: от корня, если начинается с каталога верхнего уровня (docs/…), иначе — от объекта (spec/…)."""
        q = Path(p)
        return ROOT / q if q.parts and (ROOT / q.parts[0]).is_dir() else self.dir / q

    def variant(self, vid: str) -> dict | None:
        return next((v for v in self.data.get("variants", []) if v.get("id") == vid), None)


def load_items() -> list[Item]:
    out = []
    if not ITEMS_DIR.is_dir():
        return out
    for d in sorted(ITEMS_DIR.iterdir()):
        f = d / "item.yaml"
        if d.is_dir() and f.is_file():
            out.append(Item(d, load_yaml(f) or {}))
    return out


def find_item(item_id: str) -> Item:
    for it in load_items():
        if it.id == item_id or it.dir.name == item_id:
            return it
    raise SystemExit(f"объект {item_id} не найден в content/items/")
