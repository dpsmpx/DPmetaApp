"""Визуальная регрессия сайта (П2-17): страницы в настоящем браузере на ширине телефона и компьютера.

Как рендер изображений (Д11): Chromium снимает каждую страницу staging и
её раскладку (`tools/render/site_shots.js`), а правила здесь превращают
замеры в находки проверки `siteshots`:

  ошибка — прокрутка вбок; элемент за краем экрана; сломанное изображение;
           нарушение CSP (сервер отдаёт заголовки сайта, П2-12); ошибка в
           консоли; несработавший запрос; не загрузился IBM Plex Sans;
           цель касания меньше 24 px на телефоне; не один `h1`
  предупреждение — текст мельче 12 px
  заметка — снимки в каталоге сборки для глаз

Снимки — в build/site-shots/<окружение>/ (не в Git). Без Node и
Playwright проверка пропускается с предупреждением; `CS_SKIP_RENDER=1` —
пропуск (tools/check.sh --fast).
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import threading
from pathlib import Path

from ..common import ROOT, Finding, Report
from ..core.repo import Repo
from .build import SiteBuild
from .serve import server

WIDTHS = (390, 1280)
MOBILE_MAX = 480
SCRIPT = ROOT / "tools" / "render" / "site_shots.js"


def available() -> str | None:
    """None — можно снимать; иначе — почему нельзя."""
    if os.environ.get("CS_SKIP_RENDER") == "1":
        return "пропущено (CS_SKIP_RENDER=1)"
    if not shutil.which("node"):
        return "нет Node.js — снимки сайта недоступны"
    return None


def shoot(root: Path, pages: list[str], out: Path, headers: dict | None = None,
          widths: tuple = WIDTHS, path_headers: dict | None = None) -> list[dict]:
    """Снять страницы каталога root (корень сайта) через локальный сервер с заголовками headers
    (и заголовками по пути — как .htaccess каталога)."""
    srv = server(root, 0, headers, quiet=True, path_headers=path_headers)
    th = threading.Thread(target=srv.serve_forever, daemon=True)
    th.start()
    try:
        job = {"base": f"http://127.0.0.1:{srv.server_address[1]}", "pages": pages, "widths": list(widths),
               "height": 900, "out": str(out)}
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump(job, f)
        env = dict(os.environ)
        env.setdefault("NODE_PATH", "/opt/node22/lib/node_modules")
        r = subprocess.run(["node", str(SCRIPT), f.name], capture_output=True, text=True, env=env, timeout=600)
        os.unlink(f.name)
        if r.returncode != 0:
            raise RuntimeError(f"site_shots.js: код {r.returncode}: {r.stderr.strip()[:300]}")
        return json.loads(r.stdout)
    finally:
        srv.shutdown()
        srv.server_close()


def findings(results: list[dict], expect_404: set[str] = frozenset({"/404.html", "/en/404.html"})) -> list[Finding]:
    out: list[Finding] = []
    for m in results:
        where = f"{m['path']} @{m['width']}"
        E = lambda s: out.append(Finding("error", f"{where}: {s}"))  # noqa: E731
        if m["status"] >= 400 and m["path"] not in expect_404:
            E(f"страница отвечает HTTP {m['status']}")
        if m["scrollWidth"] > m["viewport"] + 1:
            E(f"прокрутка вбок: ширина страницы {m['scrollWidth']} > {m['viewport']} px")
        for o in m["overflow"]:
            E(f"за краем экрана: {o}")
        for img in m["images"]:
            if not img["ok"]:
                E(f"изображение не загрузилось: {img['src']}")
        for c in m["csp"]:
            E(f"нарушение CSP: {c}")
        for c in m["console"]:
            E(f"ошибка в консоли: {c}")
        for f in m["failed"]:
            E(f"запрос не удался: {f}")
        if not m["plex"]:
            E(f"шрифт IBM Plex Sans не загружен (font-family: {m['fontFamily']})")
        if m["h1"] != 1:
            E(f"заголовков h1: {m['h1']} (нужен один)")
        if m["width"] <= MOBILE_MAX:
            for tgt in m["smallTargets"]:
                E(f"цель касания меньше 24 px: {tgt}")
        for s in m["smallText"]:
            out.append(Finding("warn", f"{where}: текст мельче 12 px — {s}"))
    return out


def check(repo: Repo | None = None, out: Path | None = None) -> Report:
    repo = repo or Repo()
    r = Report("siteshots — страницы сайта в браузере: 390 и 1280 px (П2-17)")
    why = available()
    if why:
        (r.note if "CS_SKIP_RENDER" in why else r.warn)(why)
        return r
    out = out or repo.path("build", "site-shots", "staging")
    with tempfile.TemporaryDirectory() as tmp:
        sb = SiteBuild(repo, "staging")
        rep = sb.build(Path(tmp) / "site")
        pages = sorted(set(rep.pages)) + [p for p in ("/404.html", "/en/404.html") if (Path(tmp) / "site" / p[1:]).is_file()]
        if out.exists():
            shutil.rmtree(out)
        try:
            results = shoot(Path(tmp) / "site", pages, out, sb._headers(), path_headers=sb.path_headers())
        except (RuntimeError, subprocess.TimeoutExpired) as e:
            r.warn(f"снимки не сняты: {e}")
            return r
    r.add(findings(results))
    r.note(f"страниц {len(pages)} × ширины {', '.join(map(str, WIDTHS))}; снимки — {out.relative_to(repo.root) if out.is_relative_to(repo.root) else out}")
    return r
