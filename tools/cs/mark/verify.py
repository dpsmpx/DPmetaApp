"""`mark verify <файл|адрес>` — чей это файл и когда он вышел (Д41; исследование §8).

Порядок:
1. sha256 → запись манифеста выпуска: точная копия выпущенного файла или
   его исходник до метки;
2. перцептивный хэш изображения → ближайшие записи (пересжатая,
   уменьшенная копия), не дальше DHASH_NEAR бит;
3. с ключом — метка: изображение сверяется с исходником из репозитория
   (по `source_sha256` записи), SVG и фигуры страниц читаются без
   исходника; совпадение — по порогу, с вероятностью ложной находки;
4. для найденных выпусков — метки времени манифеста (RFC 3161, OTS).

Ничего не нашлось — «не найдено», без догадок. Адрес скачивается один раз,
без входа и без обхода других страниц.
"""
from __future__ import annotations

import hashlib
import io
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

from . import release, stamp, svg

DHASH_NEAR = 10  # бит из 64: дальше — другая картинка
ORIGINALS = ("content", "assets", "website", "data")  # где в репозитории искать исходники изображений
MAX_BYTES = 30_000_000


@dataclass
class Result:
    lines: list[str] = field(default_factory=list)
    releases: set = field(default_factory=set)  # id выпусков, с которыми связан файл
    found: bool = False  # точная копия, исходник или метка
    p: float | None = None  # лучшая вероятность ложной находки метки

    def add(self, s: str) -> None:
        self.lines.append(s)


def fetch(url: str, opener=urllib.request.urlopen, timeout: float = 30) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "dpsmpx-cs-mark-verify"})
    with opener(req, timeout=timeout) as r:
        return r.read(MAX_BYTES)


def originals(repo_root: Path) -> dict[str, Path]:
    """sha256 → файл изображения в репозитории (исходники до метки)."""
    out = {}
    for top in ORIGINALS:
        for p in (repo_root / top).rglob("*"):
            if p.is_file() and p.suffix.lower() in release.IMAGES:
                out.setdefault(hashlib.sha256(p.read_bytes()).hexdigest(), p)
    return out


def _is_svg(name: str, data: bytes) -> bool:
    return name.lower().endswith(".svg") or data.lstrip()[:5] == b"<svg "


def _is_html(name: str, data: bytes) -> bool:
    head = data.lstrip()[:200].lower()
    return name.lower().endswith((".html", "/")) or head.startswith((b"<!doctype html", b"<html"))


def check(repo_root: Path, data: bytes, name: str, key: bytes | None) -> Result:
    res = Result()
    sha = hashlib.sha256(data).hexdigest()
    mans = release.load_all(repo_root)
    res.add(f"файл {name}: sha256 {sha[:16]}…, {len(data)} байт; выпусков в реестре: {len(mans)}")
    for mp, m in mans:
        for f in m["files"]:
            if f["sha256"] == sha:
                res.found = True
                res.releases.add(m["id"])
                res.add(f"  ✓ точная копия: выпуск {m['id']}, {f['path']}" + (f" (метка {f['method']})" if f.get("method") else ""))
            elif f.get("source_sha256") == sha:
                res.found = True
                res.releases.add(m["id"])
                res.add(f"  ✓ исходник до метки: выпуск {m['id']}, {f['path']}")
    image_entries = [(m, f) for _, m in mans for f in m["files"] if f.get("kind") == "image"]
    img = None
    if not _is_svg(name, data) and not _is_html(name, data):
        try:
            from PIL import Image
            img = Image.open(io.BytesIO(data))
            img.load()
        except Exception:  # не изображение — дальше только sha256
            img = None
    near = []
    if img is not None:
        from . import image
        dh = image.dhash(img)
        near = sorted(((image.hamming(dh, f["dhash"]), m, f) for m, f in image_entries if f.get("dhash")),
                      key=lambda x: x[0])
        near = [x for x in near if x[0] <= DHASH_NEAR]
        for d, m, f in near[:5]:
            res.releases.add(m["id"])
            res.add(f"  ~ похоже по перцептивному хэшу ({d} бит из 64): выпуск {m['id']}, {f['path']}")
    if key is None:
        res.add("  · метка не проверялась: нет ключа (mark init — только на машине автора, Д41)")
    elif img is not None:
        _image_mark(repo_root, res, img, key, near or [(None, m, f) for m, f in image_entries])
    elif _is_svg(name, data) or _is_html(name, data):
        text = data.decode("utf-8", "replace")
        ms = [svg.read(text, key)] if _is_svg(name, data) else svg.read_inline(text, key)
        if not ms:
            res.add("  · фигур SVG нет — метку нести нечему")
        for i, mt in enumerate(ms, 1):
            res.p = mt.p if res.p is None else min(res.p, mt.p)
            res.found |= mt.found
            res.add(f"  {'✓' if mt.found else '·'} метка SVG, фигура {i}: сдвигов {mt.moved} из {mt.total}, "
                    f"по ключу — {mt.agree}; вероятность случайно — {mt.p:.1e}")
    for mp, m in mans:
        if m["id"] not in res.releases:
            continue
        for kind, (ok, why) in (("RFC 3161", stamp.verify_rfc3161(mp)), ("OpenTimestamps", stamp.verify_ots(mp))):
            res.add(f"  {'✓' if ok else '✗' if ok is False else '·'} выпуск {m['id']}, метка времени {kind}: {why}")
    if not res.found and not res.releases:
        res.add("  не найдено: ни в реестре выпусков, ни по метке")
    return res


def _image_mark(repo_root: Path, res: Result, img, key: bytes, cands: list) -> None:
    from . import image
    orig = originals(repo_root)
    tried = 0
    for _, m, f in cands:
        src = orig.get(f.get("source_sha256") or "")
        if src is None:
            continue
        tried += 1
        mt = image.match(img, src.read_bytes(), key)
        res.p = mt.p if res.p is None else min(res.p, mt.p)
        if mt.found:
            res.found = True
            res.releases.add(m["id"])
        res.add(f"  {'✓' if mt.found else '·'} метка изображения против {f['path']} (выпуск {m['id']}): "
                f"мест {mt.agree} из {mt.cells}, масштаб {mt.scale:.3g}, вероятность случайно — {mt.p:.1e}")
    if not tried:
        res.add("  · исходников изображений в репозитории не нашлось — метку сверить не с чем")
