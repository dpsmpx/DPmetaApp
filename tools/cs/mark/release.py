"""Метка сборки и манифест выпуска (Д41; docs/research/watermark/WATERMARK.md, §2, §7).

`mark_build(каталог, ключ)` ставит метку в production-сборке на месте —
изображения PNG/JPEG (`image`), файлы SVG и фигуры SVG внутри страниц
(`svg`) — и возвращает записи файлов: путь, sha256 до и после метки,
метод, перцептивный хэш изображений. Повторяемо: тот же вход и ключ дают
те же байты, так что пробный прогон выгрузки видит те же изменения, что и
настоящий.

`write_manifest` пишет манифест выпуска — продолжение реестра
происхождения (`docs/PROVENANCE.md`), а не второй реестр:
`data/provenance/site-releases/<id>/manifest.json`. В нём — коммит,
адрес сайта, отпечаток ключа (не ключ) и записи всех файлов сборки. На
манифест ставятся метки времени (`stamp`); манифест, `.tsr` и `.ots`
автор коммитит после выгрузки. Секретов и личных данных в манифесте нет.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import io
import json
import subprocess
from pathlib import Path

from . import svg
from .key import fingerprint

SCHEMA = "site-release/v1"
RELEASES = "data/provenance/site-releases"
IMAGES = (".png", ".jpg", ".jpeg")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def mark_file(rel: str, data: bytes, key: bytes) -> tuple[bytes, dict]:
    """(байты с меткой, поля записи) для одного файла; метку несут изображения и SVG."""
    ext = Path(rel).suffix.lower()
    if ext in IMAGES:
        from PIL import Image  # numpy и Pillow — только там, где есть изображения (Д41: на телефоне — pkg)
        from . import image
        out = image.embed(data, key)
        return out, {"kind": "image", "method": image.METHOD, "dhash": image.dhash(Image.open(io.BytesIO(data)))}
    if ext == ".svg":
        text = data.decode("utf-8")
        out = svg.embed(text, key).encode("utf-8")
        return out, ({"kind": "svg", "method": svg.METHOD} if out != data else {})
    if ext == ".html":
        text, n = svg.embed_inline(data.decode("utf-8"), key)
        return text.encode("utf-8"), ({"kind": "html-svg", "method": svg.METHOD, "figures": n} if n else {})
    return data, {}


def mark_build(root: Path, key: bytes) -> list[dict]:
    """Поставить метку в сборке root на месте; записи всех файлов по порядку путей."""
    files = []
    for p in sorted(x for x in root.rglob("*") if x.is_file()):
        rel = p.relative_to(root).as_posix()
        data = p.read_bytes()
        out, extra = mark_file(rel, data, key)
        if out != data:
            p.write_bytes(out)
        entry = {"path": rel, "sha256": sha256(out)}
        if out != data:
            entry["source_sha256"] = sha256(data)
        entry.update(extra)
        files.append(entry)
    return files


def release_id(now: dt.datetime | None = None) -> str:
    t = (now or dt.datetime.now(dt.timezone.utc)).astimezone(dt.timezone.utc)
    return t.strftime("%Y-%m-%dT%H%M%SZ")


def git_head(root: Path) -> str | None:
    try:
        r = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return None
    return r.stdout.strip() if r.returncode == 0 else None


def manifest(files: list[dict], key: bytes, site: str, commit: str | None, rid: str) -> dict:
    return {"schema": SCHEMA, "id": rid, "site": site, "commit": commit, "key_id": fingerprint(key),
            "methods": sorted({f["method"] for f in files if f.get("method")}), "files": files}


def manifest_bytes(m: dict) -> bytes:
    """Канонический вид: ключи по порядку, UTF-8, перевод строки в конце — хэш не зависит от машины."""
    return (json.dumps(m, ensure_ascii=False, indent=1, sort_keys=True) + "\n").encode("utf-8")


def write_manifest(repo_root: Path, m: dict) -> Path:
    """Записать манифест; выпуск с тем же id уже есть (две выгрузки за секунду) — id с суффиксом -2, -3…"""
    base, n = m["id"], 1
    while True:
        d = repo_root / RELEASES / m["id"]
        try:
            d.mkdir(parents=True, exist_ok=False)
            break
        except FileExistsError:
            n += 1
            m["id"] = f"{base}-{n}"
    p = d / "manifest.json"
    p.write_bytes(manifest_bytes(m))
    return p


def load_all(repo_root: Path) -> list[tuple[Path, dict]]:
    """Все манифесты выпусков: [(путь, манифест)], старые — первыми."""
    out = []
    for p in sorted((repo_root / RELEASES).glob("*/manifest.json")):
        out.append((p, json.loads(p.read_text(encoding="utf-8"))))
    return out


def check():
    """Проверка `releases`: манифесты выпусков целы, метки времени на месте (Д41)."""
    from ..common import ROOT, Report
    r = Report("releases — реестр выпусков сайта и метки времени (Д41)")
    mans = load_all(ROOT)
    for p, m in mans:
        where = p.relative_to(ROOT).as_posix()
        if m.get("schema") != SCHEMA:
            r.error(f"{where}: схема {m.get('schema')} — нужно {SCHEMA}")
        if m.get("id") != p.parent.name:
            r.error(f"{where}: id {m.get('id')} ≠ каталог {p.parent.name}")
        if p.read_bytes() != manifest_bytes(m):
            r.error(f"{where}: манифест изменён после записи — метки времени на него недействительны")
        for f in m.get("files") or []:
            if not f.get("path") or len(f.get("sha256") or "") != 64:
                r.error(f"{where}: запись без пути или sha256")
        for ext, how in ((".tsr", "RFC 3161"), (".ots", "OpenTimestamps")):
            if not p.with_name(p.name + ext).is_file():
                r.warn(f"{where}: метки времени {how} нет — python3 -m tools.cs mark stamp {m.get('id')}")
    r.note(f"выпусков {len(mans)}")
    return r
