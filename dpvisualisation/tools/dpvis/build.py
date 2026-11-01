"""Сборка просмотрщика (F-40, D-15).

- `build_bundle` — один HTML-файл: стили, скрипт, модель и срез внутри; без
  внешних запросов; открывается из file://, в браузере телефона и в WebView
  (DPmetaApp).
- `build_site` — комплект для сайта автора (D-15): `dpvis.js`, `dpvis.css`,
  `dpvis-data.json` — отдельными файлами, без встроенных скриптов и стилей
  (страница сайта живёт под CSP `script-src 'self'`); данные — публичная
  выжимка (`public.py`).

Модули `viewer/src/*.js` — ES-модули для разработки и тестов `node --test`.
Склейка: строки `import … from "./x.js";` убираются, `export` у объявлений
снимается, модули идут в порядке ORDER внутри одной функции-обёртки.
Поэтому импорт пишется в одну строку, а имена верхнего уровня в модулях не
повторяются — иначе сборка откажет.
"""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

from . import public

REPO = Path(__file__).resolve().parents[2]
VIEWER = REPO / "viewer"
ORDER = ["format", "data", "filters", "layout", "dom", "graph", "panel", "tables", "main"]

IMPORT_LINE = re.compile(r'^import\s+\{[^}\n]*\}\s+from\s+"\./[\w-]+\.js";[ \t]*\n', re.M)
EXPORT_DECL = re.compile(r"^export\s+(?=(?:async\s+)?function\b|const\b|let\b|class\b)", re.M)
TOP_DECL = re.compile(r"^(?:async\s+)?(?:function\*?|const|let|class)\s+([A-Za-z_$][\w$]*)", re.M)


class BuildError(Exception):
    pass


def bundle_js(viewer: Path = VIEWER) -> str:
    seen: dict[str, str] = {}
    parts = []
    for name in ORDER:
        src = (viewer / "src" / f"{name}.js").read_text(encoding="utf-8")
        src = IMPORT_LINE.sub("", src)
        if re.search(r"^\s*import\s", src, re.M):
            raise BuildError(f"{name}.js: импорт не в одну строку или не из ./*.js — склейка его не поймёт")
        src = EXPORT_DECL.sub("", src)
        if re.search(r"^\s*export\s", src, re.M):
            raise BuildError(f"{name}.js: export, который склейка не снимает (export {{…}} или export default)")
        for m in TOP_DECL.finditer(src):
            if m.group(1) in seen:
                raise BuildError(f"имя {m.group(1)} объявлено и в {seen[m.group(1)]}.js, и в {name}.js")
            seen[m.group(1)] = name
        parts.append(f"// ---- {name}.js\n{src}")
    return "/* DPvisualisation — просмотрщик (сборка). Исходники: viewer/src/. */\n(function () {\n\"use strict\";\n" + \
        "\n".join(parts) + "\n})();\n"


def css(viewer: Path = VIEWER, page: bool = False) -> str:
    out = (viewer / "style.css").read_text(encoding="utf-8")
    if page:
        out = (viewer / "page.css").read_text(encoding="utf-8") + "\n" + out
    return out


def _json_for_script(data) -> str:
    # внутри <script> не должно встретиться «</»: закрыло бы тег
    return json.dumps(data, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")


def _load(path: Path | None):
    if path is None or not Path(path).is_file():
        return None
    return json.loads(Path(path).read_text(encoding="utf-8"))


def build_bundle(model_path: Path, state_path: Path | None, out: Path, public_only: bool = False) -> Path:
    model = _load(model_path)
    if model is None:
        raise BuildError(f"нет модели {model_path} — сначала model build")
    state = _load(state_path)
    data = public.public_bundle(model, state) if public_only else {"model": model, "state": state}
    html = f"""<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{model.get("title", "DPvisualisation")} — DPvisualisation</title>
<style>
{css(page=True)}
</style>
</head>
<body>
<main><div data-dpvis></div></main>
<script type="application/json" id="dpvis-data">{_json_for_script(data)}</script>
<script>
{bundle_js()}
</script>
</body>
</html>
"""
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(html, encoding="utf-8")
    return out


def build_site(model_path: Path, state_path: Path | None, outdir: Path) -> dict[str, str]:
    """Комплект для сайта: только публичная выжимка (D-15). Возвращает {файл: sha256}."""
    model = _load(model_path)
    if model is None:
        raise BuildError(f"нет модели {model_path} — сначала model build")
    data = public.public_bundle(model, _load(state_path))
    outdir.mkdir(parents=True, exist_ok=True)
    files = {
        "dpvis.js": bundle_js(),
        "dpvis.css": css(),
        "dpvis-data.json": json.dumps(data, ensure_ascii=False, indent=1) + "\n",
    }
    sums = {}
    for name, text in files.items():
        (outdir / name).write_text(text, encoding="utf-8")
        sums[name] = hashlib.sha256(text.encode("utf-8")).hexdigest()
    manifest = {
        "schema": "dpvis-site-kit/1",
        "data_as_of": (data.get("state") or {}).get("as_of"),
        "model_as_of": data["model"].get("as_of"),
        "files": sums,
    }
    (outdir / "MANIFEST.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return sums
