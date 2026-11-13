"""Раздел «Экосистема» (Д43): интерактивная модель из комплекта DPvisualisation (его D-15).

Комплект — `website/ecosystem/` (путь — `ecosystem.kit` в site.yaml):
`dpvis.js`, `dpvis.css`, `dpvis-data.json` (публичная выжимка `dpvis-public/1`)
и `MANIFEST.json` с их sha256. Сборка сайта сверяет хэши — правка руками или
неполное копирование дают ошибку сборки. На странице — один внешний скрипт
`dpvis.js`; CSP этого каталога разрешает только свои скрипты (`script-src
'self'`), весь остальной сайт — без скриптов (`script-src 'none'`). Без
JavaScript страница показывает статичный список системы по слоям — тот же
текст, что в данных.
"""
from __future__ import annotations

import hashlib
import html
import json
import shutil
from pathlib import Path

from ..core.repo import Repo

KIT_FILES = ("dpvis.js", "dpvis.css", "dpvis-data.json")
MANIFEST = "MANIFEST.json"
PUBLIC_SCHEMA = "dpvis-public/1"
E = html.escape


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def kit_problems(kit: Path) -> list[str]:
    """Что не так с комплектом: файлы, хэши по MANIFEST.json, формат данных."""
    out = []
    man_p = kit / MANIFEST
    if not man_p.is_file():
        return [f"{kit}: нет {MANIFEST}"]
    try:
        man = json.loads(man_p.read_text(encoding="utf-8"))
    except ValueError as e:
        return [f"{MANIFEST}: не читается — {e}"]
    files = man.get("files") or {}
    for name in KIT_FILES:
        p = kit / name
        if not p.is_file():
            out.append(f"комплект экосистемы: нет {name}")
        elif files.get(name) != _sha(p.read_bytes()):
            out.append(f"комплект экосистемы: {name} не совпадает с {MANIFEST} — файлы правят только сборкой DPvisualisation")
    if not out:
        try:
            data = json.loads((kit / "dpvis-data.json").read_text(encoding="utf-8"))
        except ValueError as e:
            return [f"dpvis-data.json не читается — {e}"]
        if data.get("schema") != PUBLIC_SCHEMA:
            out.append(f"dpvis-data.json: schema {data.get('schema')!r}, ждали {PUBLIC_SCHEMA} — на сайт только публичная выжимка")
    return out


def import_kit(repo: Repo, src: Path, kit_rel: str = "website/ecosystem") -> list[str]:
    """Скопировать комплект из сборки DPvisualisation (build --site); проверить до и после."""
    bad = kit_problems(src)
    if bad:
        return bad
    dst = repo.path(*kit_rel.split("/"))
    dst.mkdir(parents=True, exist_ok=True)
    for name in (*KIT_FILES, MANIFEST):
        shutil.copyfile(src / name, dst / name)
    return kit_problems(dst)


def load(kit: Path) -> dict:
    return json.loads((kit / "dpvis-data.json").read_text(encoding="utf-8"))


def apply_facts(data: dict, roles: dict, stages: dict, fact) -> tuple[dict, list[str]]:
    """Роли и стадии репозиториев — из реестра фактов (Д6, Д43): fact(id) → формулировка или None.

    У репозитория без факта роль и стадия не выводятся — на сайте нет утверждений
    о проектах мимо реестра. Остальные тексты (участники, сервисы, площадки,
    описания потоков) остаются из модели и проходят проверку запретов (check_texts).
    Возвращает (новые данные, предупреждения).
    """
    out = json.loads(json.dumps(data))
    warn = []
    for n in out["model"]["nodes"]:
        if n["kind"] != "repo":
            continue
        for key, mapping in (("role", roles), ("stage", stages)):
            fid = mapping.get(n["id"])
            text = fact(fid) if fid else None
            if text:
                n[key] = text
            else:
                n.pop(key, None)
                if key == "role":
                    warn.append(f"экосистема: у {n['id']} нет факта роли — роль не выводится")
    return out, warn


def texts(data: dict) -> list[str]:
    m = data["model"]
    out = [m.get("title", "")]
    for n in m["nodes"]:
        out += [n.get(k, "") for k in ("name", "role", "stage")]
    for f in m["flows"]:
        out.append(f.get("what", ""))
    return [x for x in out if x]


def static_summary(data: dict) -> str:
    """Система по слоям без JavaScript: имя, роль, стадия — из тех же данных."""
    m = data["model"]
    parts = []
    for layer in m.get("layers", []):
        nodes = [n for n in m["nodes"] if n["layer"] == layer["id"] and n["kind"] != "actor"]
        if not nodes:
            continue
        items = []
        for n in nodes:
            role = f" — {E(n['role'])}" if n.get("role") else ""
            stage = f' <span class="muted">({E(n["stage"])})</span>' if n.get("stage") else ""
            items.append(f"<li><strong>{E(n['name'])}</strong>{role}{stage}</li>")
        parts.append(f"<h3>{E(layer['name'])}</h3><ul>{''.join(items)}</ul>")
    hidden = (m.get("hidden") or {}).get("undisclosed")
    if hidden:
        parts.append(f'<p class="muted">Ещё {hidden} проект без публичного имени на схеме не показан.</p>')
    return "".join(parts)


def page_main(data: dict, base_path: str, as_of_text: str, path_href: str | None = None) -> str:
    """Статичный список системы по слоям (Д51): без скрипта, стилей и данных схемы.
    path_href — раздел «Путь» (Д44): откуда система выросла и к какой цели идёт."""
    see_path = (f'<p>Откуда эта система выросла и к какой цели идёт — <a href="{E(path_href)}">Путь</a>.</p>'
                if path_href else "")
    return (
        '<article class="page page-ecosystem">'
        "<h1>Экосистема</h1>"
        '<p class="lead">Система, которую я строю: проекты по слоям — что каждый делает и на какой он стадии.</p>'
        f'<section class="eco-static" aria-label="Система по слоям">{static_summary(data)}'
        f'<p class="muted">Срез на {E(as_of_text)}. Старые открытые проекты, личные устройства и подробности '
        "рабочих веток в этот список не входят.</p></section>"
        f"{see_path}</article>"
    )
