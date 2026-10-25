"""Фильтр чувствительных данных на выходе сайта (Д39; data/policy/sensitive.yaml).

Второй слой защиты. Первый — белые списки: на сайт идут только публичные
поля (публичная выжимка набора прогресса, Д38; факты с `public: true`).
Этот фильтр проверяет готовый выход — каждый файл сборки — и ловит то, что
просочилось мимо белых списков:

    правила политики   почта, телефон, IP, локальные пути, технический адрес
                       хостинга, сессии ИИ, имена веток (data/policy/sensitive.yaml)
    секреты            те же шаблоны, что проверка `secrets` (tools/cs/secrets.py)
    слова из данных    имена закрытых проектов без публичного имени и личных
                       репозиториев (data/progress/scope.yaml); коммиты закрытых
                       репозиториев (наборы прогресса и вехи, Д17); ручной список terms
    метаданные         EXIF, XMP, IPTC и текстовые блоки PNG в изображениях

Filter(repo).scan_tree(каталог) → список Hit. Найденный секрет в отчёт не
печатается (show: false). Фильтр ничего не меняет в файлах: исправлять надо
источник (данные, текст варианта, изображение), а не выход.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

from ..secrets import norm as secrets_norm
from .repo import Repo

TEXT_SUFFIXES = {".html", ".xml", ".json", ".txt", ".css", ".svg", ".js", ".webmanifest", ".htaccess", ""}
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".gif"}
HEX_REF = re.compile(r"^[0-9a-f]{7,40}$")
TAGS = re.compile(r"<[^>]*>")


@dataclass(frozen=True)
class Hit:
    rule: str
    where: str
    label: str
    basis: str
    found: str | None  # None — не печатать

    @property
    def msg(self) -> str:
        what = f" ({self.found})" if self.found else ""
        return f"{self.where}: {self.label}{what} — {self.basis}"


@dataclass(frozen=True)
class Rule:
    id: str
    label: str
    basis: str
    where: str
    show: bool
    pattern: re.Pattern


def image_metadata(data: bytes, suffix: str) -> list[str]:
    """Блоки метаданных изображения: EXIF, XMP, IPTC, текст PNG. Пусто — чисто."""
    out = []
    if suffix in (".jpg", ".jpeg") and data[:2] == b"\xff\xd8":
        i = 2
        while i + 4 <= len(data) and data[i] == 0xFF:
            marker, size = data[i + 1], int.from_bytes(data[i + 2:i + 4], "big")
            seg = data[i + 4:i + 2 + size]
            if marker == 0xDA:  # начало данных изображения — дальше метаданных нет
                break
            if marker == 0xE1 and seg.startswith(b"Exif\0"):
                out.append("EXIF")
            elif marker == 0xE1 and b"ns.adobe.com/xap" in seg[:64]:
                out.append("XMP")
            elif marker == 0xED:
                out.append("IPTC")
            elif marker == 0xFE:
                out.append("комментарий JPEG")
            i += 2 + size
    elif suffix == ".png" and data[:8] == b"\x89PNG\r\n\x1a\n":
        i = 8
        while i + 8 <= len(data):
            size, kind = int.from_bytes(data[i:i + 4], "big"), data[i + 4:i + 8]
            if kind in (b"tEXt", b"zTXt", b"iTXt"):
                out.append(f"текст PNG ({kind.decode()})")
            elif kind == b"eXIf":
                out.append("EXIF")
            elif kind == b"IEND":
                break
            i += 12 + size
    elif suffix == ".webp" and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        i = 12
        while i + 8 <= len(data):
            kind, size = data[i:i + 4], int.from_bytes(data[i + 4:i + 8], "little")
            if kind in (b"EXIF", b"XMP "):
                out.append(kind.decode().strip())
            i += 8 + size + (size & 1)
    return sorted(set(out))


class Filter:
    def __init__(self, repo: Repo):
        self.repo = repo
        policy = repo.yaml("data/policy/sensitive.yaml") or {}
        from .. import secrets
        self.rules = [Rule(r["id"], r["label"], r["basis"], r["where"], r["show"], re.compile(r["pattern"], re.I))
                      for r in policy.get("rules", [])]
        self.rules += [Rule("SD-SECRET", f"секрет: {name}", "секретов на сайте нет (docs/SECURITY.md)", "raw", False, pat)
                       for name, pat in secrets.PATTERNS]
        self.allow = {(a["rule"], a["value"]) for a in policy.get("allow", [])}
        # (правило, слово, подпись, основание, печатать найденное)
        self.terms: list[tuple[str, str, str, str, bool]] = []
        scope = repo.yaml("data/progress/scope.yaml") or {}
        for r in scope.get("repos", []):
            if r.get("visibility") == "undisclosed":
                for w in {r["slug"].split("/")[-1], r.get("key", "")} - {"", "undisclosed"}:
                    self.terms.append(("SD-UNDISCLOSED", w, "имя закрытого проекта, не названного публично",
                                       "Д38: только числа в общих суммах", True))
        for x in scope.get("excluded", []):
            if x.get("kind") == "personal":
                self.terms.append(("SD-PERSONAL", x["slug"].split("/")[-1], "личный репозиторий", "Д2, ОР-8", True))
        for t in policy.get("terms", []):
            self.terms.append(("SD-TERM", t["term"], t["label"], t["basis"], True))
        self.refs = self._private_refs(scope)

    def _private_refs(self, scope: dict) -> set[str]:
        """Коммиты закрытых репозиториев: head из наборов прогресса и ref вех — полные и первые 7 знаков."""
        closed = {r["key"] for r in scope.get("repos", []) if r.get("visibility") in ("private", "undisclosed")}
        refs: set[str] = set()
        for f in sorted(self.repo.path("data", "progress", "datasets").glob("*.json")):
            data = json.loads(f.read_text(encoding="utf-8"))
            for key, r in (data.get("repos") or {}).items():
                if key in closed and r.get("head"):
                    refs.add(r["head"].lower())
        for m in (self.repo.yaml("data/progress/milestones.yaml") or {}).get("milestones", []):
            if set(m.get("repos") or []) & closed:
                for ev in m.get("evidence") or []:
                    for ref in re.split(r"[\s,]+", str(ev.get("ref") or "")):
                        if HEX_REF.match(ref.lower()):
                            refs.add(ref.lower())
        return refs | {r[:7] for r in refs}

    # ------------------------------------------------------------ проверка
    def scan_text(self, text: str, where: str) -> list[Hit]:
        hits = []
        visible = TAGS.sub(" ", text)
        for rule in self.rules:
            for m in rule.pattern.finditer(text if rule.where == "raw" else visible):
                if (rule.id, m.group(0)) in self.allow:
                    continue
                hits.append(Hit(rule.id, where, rule.label, rule.basis, m.group(0) if rule.show else None))
        hits += self._terms(text, where)
        low = text.lower()
        for ref in sorted(self.refs):
            if re.search(rf"(?<![0-9a-f]){ref}(?![0-9a-f])", low):
                hits.append(Hit("SD-REF", where, "коммит закрытого репозитория", "Д17: только факты и вехи", ref[:7]))
        return hits

    def _terms(self, text: str, where: str) -> list[Hit]:
        low = secrets_norm(text)
        out = []
        for rule, term, label, basis, show in self.terms:
            if secrets_norm(term) in low and (rule, term) not in self.allow:
                out.append(Hit(rule, where, label, basis, term if show else None))
        return out

    def scan_file(self, path: Path, where: str) -> list[Hit]:
        data = path.read_bytes()
        suffix = path.suffix.lower()
        if suffix in TEXT_SUFFIXES or path.name.startswith("."):
            return self.scan_text(data.decode("utf-8", "replace"), where)
        hits = []
        if suffix in IMAGE_SUFFIXES:
            for kind in image_metadata(data, suffix):
                hits.append(Hit("SD-META", where, "метаданные изображения", "фото и картинки — без метаданных "
                                "(docs/SECURITY.md)", kind))
        # двоичный файл: слова из данных могут сидеть в метаданных или вшитом тексте
        hits += self._terms(data.decode("utf-8", "ignore"), where)
        return hits

    def scan_tree(self, root: Path) -> list[Hit]:
        hits = []
        for p in sorted(root.rglob("*")):
            if p.is_file():
                hits += self.scan_file(p, str(p.relative_to(root)))
        return hits


def scan(repo: Repo, root: Path) -> list[Hit]:
    return Filter(repo).scan_tree(root)
