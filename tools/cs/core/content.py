"""Канон и варианты площадок объекта контента v2 (Д22, docs/content/CANONICAL_MODEL.md).

    content/items/<id>-<slug>/
    ├── item.yaml               content_item/v2: canonical.versions[], targets[], brand, category…
    ├── canonical/v1.md         каноническая версия: заголовок canonical/v1 + текст в разметке канона
    └── variants/telegram.ru.md вариант площадки: заголовок variant/v1 + текст

Версия канона фиксируется (`seal`): в item.yaml пишутся sha256 файла и
зависимости. Зафиксированная версия не меняется — правка = новая версия.
Вариант фиксирует source_version, отпечатки зависимостей и хэши медиа;
одобрение автора хранит sha256 содержимого варианта и недействительно,
если содержимое изменилось (Д12, Д28).
"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path

import yaml

from ..common import sha256_file
from . import textdoc
from .inject import Resolved, changed, fact_fp, resolve
from .repo import Repo

POST_CATEGORIES = {"news", "devlog", "article", "release"}
VARIANT_ID = re.compile(r"^(?P<platform>[a-z]+)(?:-(?P<suffix>[a-z0-9]+))?\.(?P<lang>ru|en)$")


@dataclass
class CanonVersion:
    version: int
    path: Path
    doc: textdoc.TextDoc
    record: dict

    @property
    def sealed(self) -> bool:
        return bool(self.record.get("sha256"))


@dataclass
class Variant:
    item: "CItem"
    id: str
    path: Path
    doc: textdoc.TextDoc

    def m(self, key, default=None):
        return self.doc.meta.get(key, default)

    @property
    def platform(self) -> str:
        return self.m("platform") or VARIANT_ID.match(self.id).group("platform")

    @property
    def lang(self) -> str:
        return self.m("lang") or VARIANT_ID.match(self.id).group("lang")

    @property
    def rel(self) -> str:
        return f"{self.item.dir.name}/variants/{self.path.name}"


class CItem:
    def __init__(self, repo: Repo, d: Path, data: dict):
        self.repo, self.dir, self.data = repo, d, data

    @property
    def id(self) -> str:
        return self.data.get("id", self.dir.name)

    @property
    def schema_version(self) -> int:
        return int(str(self.data.get("schema", "content_item/v1")).rsplit("v", 1)[-1])

    @property
    def category(self) -> str:
        return self.data.get("category") or "news"

    @property
    def brands(self) -> list[str]:
        return list((self.data.get("brand") or {}).get("brands") or [])

    # ------------------------------------------------------- канон
    def versions(self) -> list[CanonVersion]:
        out = []
        for rec in (self.data.get("canonical") or {}).get("versions", []):
            p = self.dir / rec["file"]
            doc = textdoc.load(p) if p.is_file() else textdoc.TextDoc({}, "")
            out.append(CanonVersion(int(rec["version"]), p, doc, rec))
        return out

    def canon(self, version: int | None = None) -> CanonVersion | None:
        vs = self.versions()
        if not vs:
            return None
        want = version or (self.data.get("canonical") or {}).get("current") or vs[-1].version
        return next((v for v in vs if v.version == want), None)

    @property
    def canonical_lang(self) -> str:
        return (self.data.get("canonical") or {}).get("lang", "ru")

    # ------------------------------------------------------- варианты
    def variants(self) -> dict[str, Variant]:
        out = {}
        vdir = self.dir / "variants"
        if vdir.is_dir():
            for p in sorted(vdir.glob("*.md")):
                out[p.stem] = Variant(self, p.stem, p, textdoc.load(p))
        return out

    def url_path(self, lang: str) -> str:
        """Путь на сайте: страница — website.path, запись — /news/<slug>/; английский — /en/…"""
        web = self.data.get("website") or {}
        base = web.get("path") or f"/news/{self.data.get('slug')}/"
        return ("/en" + base) if lang == "en" else base


def load_items(repo: Repo) -> list[CItem]:
    out = []
    d = repo.path("content", "items")
    if not d.is_dir():
        return out
    for sub in sorted(d.iterdir()):
        f = sub / "item.yaml"
        if sub.is_dir() and f.is_file():
            out.append(CItem(repo, sub, yaml.safe_load(f.read_text(encoding="utf-8")) or {}))
    return out


def find(repo: Repo, item_id: str) -> CItem:
    for it in load_items(repo):
        if it.id == item_id or it.dir.name == item_id:
            return it
    raise KeyError(f"объект {item_id} не найден в content/items/")


def save_item(item: CItem) -> None:
    path = item.dir / "item.yaml"
    head = "".join(l for l in path.read_text(encoding="utf-8").splitlines(True) if l.startswith("#")) if path.is_file() else ""
    with open(path, "w", encoding="utf-8") as f:
        f.write(head.split("\n\n")[0] if head else "")
        yaml.safe_dump(item.data, f, allow_unicode=True, sort_keys=False, width=100)


# ----------------------------------------------------------- медиа и хэши

def media_files(v: Variant) -> list[tuple[str, Path]]:
    """Медиа варианта: пути от каталога объекта или assets/…; (как записано, путь)."""
    out = []
    for m in v.m("media") or []:
        p = v.item.repo.path(m) if m.startswith(("assets/", "content/")) else v.item.dir / m
        out.append((m, p))
    return out


def link_url(v: Variant, base: str) -> str | None:
    link = v.m("link")
    if not link:
        return None
    if link == "canonical":
        return base.rstrip("/") + v.item.url_path(v.lang) if base else v.item.url_path(v.lang)
    return link


def resolved_body(v: Variant, base: str) -> Resolved:
    return resolve(v.doc.body, v.lang, v.item.repo, base)


def variant_sha(v: Variant) -> str:
    """Хэш содержимого варианта — то, что одобряет автор (Д12).

    Входят: площадка, язык, формат, версия канона, ссылка, CTA, медиа с
    хэшами файлов и текст после подстановки фактов с каноническим адресом.
    Не входят: статус, доверие, одобрение, зависимости, номер версии.
    """
    repo = v.item.repo
    base = repo.canonical_base()
    media = []
    for m, p in media_files(v):
        media.append([m, sha256_file(p) if p.is_file() else None])
    payload = {
        "item": v.item.id, "variant": v.id, "platform": v.platform, "lang": v.lang,
        "format": v.m("format"), "source_version": v.m("source_version"),
        "link": link_url(v, base), "cta": v.m("cta"), "title": v.m("title"),
        "media": media, "body": resolved_body(v, base).text,
    }
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def approval_state(v: Variant) -> str:
    """none — не одобрен; valid — одобрен и не менялся; invalid — изменился после одобрения."""
    a = v.m("approval") or {}
    if not a:
        return "none"
    if a.get("by") != "author":
        return "invalid"
    return "valid" if a.get("sha256") == variant_sha(v) else "invalid"


def canon_deps(item: CItem, cv: CanonVersion) -> dict:
    return resolve(cv.doc.body, cv.doc.meta.get("lang") or item.canonical_lang, item.repo).deps


def seal_canon(item: CItem, cv: CanonVersion) -> None:
    cv.record["sha256"] = sha256_file(cv.path)
    cv.record["deps"] = canon_deps(item, cv)


def seal_variant(v: Variant) -> None:
    """Записать зависимости варианта: версия канона с хэшем, факты/ссылки, хэши медиа."""
    item = v.item
    deps = resolved_body(v, item.repo.canonical_base()).deps
    facts = item.repo.facts()
    for fid in v.m("claims") or []:  # факты, написанные в тексте словами, а не подстановкой
        if fid in facts:
            deps["facts"].setdefault(fid, fact_fp(facts[fid]))
    sv = v.m("source_version")
    cv = item.canon(sv) if sv else None
    if cv is not None:
        deps["canonical"] = {"version": cv.version, "sha256": cv.record.get("sha256")}
    deps["media"] = {m: (sha256_file(p) if p.is_file() else None) for m, p in media_files(v)}
    v.doc.meta["deps"] = deps


def stale_reasons(v: Variant) -> list[str]:
    """Почему вариант устарел (Д28): новая версия канона, изменившиеся факты, ссылки, медиа."""
    item, deps = v.item, v.m("deps") or {}
    out = []
    sv = v.m("source_version")
    cur = (item.data.get("canonical") or {}).get("current")
    if sv and cur and int(sv) < int(cur):
        out.append(f"канон v{cur} новее источника варианта v{sv}")
    rec_c = deps.get("canonical") or {}
    if rec_c.get("version"):
        cv = item.canon(rec_c["version"])
        now = sha256_file(cv.path) if cv is not None and cv.path.is_file() else None
        if rec_c.get("sha256") and now != rec_c.get("sha256"):
            out.append(f"канон v{rec_c['version']} изменён после фиксации")
    out += changed(deps, item.repo)
    for m, sha in (deps.get("media") or {}).items():
        p = dict(media_files(v)).get(m)
        cur_sha = sha256_file(p) if p is not None and p.is_file() else None
        if cur_sha != sha:
            out.append(f"медиа {m}")
    return out


def canon_stale(item: CItem, cv: CanonVersion) -> list[str]:
    return changed(cv.record.get("deps") or {}, item.repo) if cv.sealed else []
