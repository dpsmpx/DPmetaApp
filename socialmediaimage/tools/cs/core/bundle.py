"""Выгрузка всей базы в переносимом виде (Д30, PLATFORM_VISION §65, §110).

Один архив .tar.gz с манифестом: контент (объекты, каноны, варианты),
медиа (ассеты и экспорт), происхождение, журнал публикаций, решения,
данные и код инструментов (архив самодостаточен для проверки). Формат — обычные файлы YAML/JSON/Markdown/PNG/JPEG: читаются без
этой системы. Это и резервная копия «вне GitHub» (локальная и внешняя
копии — docs/operations/BACKUP.md).
"""
from __future__ import annotations

import datetime as _dt
import hashlib
import io
import json
import subprocess
import tarfile
from pathlib import Path

from .repo import Repo

PARTS = {
    "content": ["content/"],
    "media": ["assets/"],
    "provenance": ["data/provenance/", "docs/PROVENANCE.md"],
    "publications": ["data/publications/"],
    "data": ["data/facts/", "data/identity/", "data/platforms/", "data/projects/", "data/policy/",
             "data/website/", "data/releases/", "data/campaigns/", "data/metrics/", "data/experiments/",
             "data/progress/"],
    "decisions": ["docs/", "ROADMAP/", "README.md", "CLAUDE.md", "TODO.md"],
    "schemas": ["schemas/"],
    "code": ["tools/", "tests/", "website/", "templates/", "prompts/", ".gitignore", ".htaccess", ".claude/"],
}


def tracked(repo: Repo) -> list[str]:
    r = subprocess.run(["git", "ls-files"], cwd=repo.root, capture_output=True, text=True)
    return [l for l in r.stdout.splitlines() if l] if r.returncode == 0 else []


def bundle(repo: Repo, out: Path) -> dict:
    files = tracked(repo)
    stamp = _dt.datetime.now(_dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out.mkdir(parents=True, exist_ok=True)
    path = out / f"dpsmpx-content-{stamp}.tar.gz"
    manifest = {"schema": "bundle/v1", "created": stamp, "parts": {}, "files": {}}
    with tarfile.open(path, "w:gz") as tar:
        for part, prefixes in PARTS.items():
            chosen = [f for f in files if any(f == p or f.startswith(p) for p in prefixes)]
            manifest["parts"][part] = len(chosen)
            for f in chosen:
                if f in manifest["files"]:
                    continue
                data = (repo.root / f).read_bytes()
                manifest["files"][f] = hashlib.sha256(data).hexdigest()
                info = tarfile.TarInfo(f)
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        raw = json.dumps(manifest, ensure_ascii=False, indent=1).encode("utf-8")
        info = tarfile.TarInfo("MANIFEST.json")
        info.size = len(raw)
        tar.addfile(info, io.BytesIO(raw))
    return {"path": str(path), "files": len(manifest["files"]), "parts": manifest["parts"]}


def verify(path: Path) -> list[str]:
    """Сверить архив с его манифестом (восстановление, docs/operations/DISASTER_RECOVERY.md)."""
    errors = []
    with tarfile.open(path, "r:gz") as tar:
        man = json.loads(tar.extractfile("MANIFEST.json").read())
        for f, sha in man["files"].items():
            m = tar.extractfile(f)
            if m is None or hashlib.sha256(m.read()).hexdigest() != sha:
                errors.append(f"{f}: хэш не совпадает")
    return errors
