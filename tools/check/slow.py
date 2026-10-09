"""Проверки уровня `full` (D-09): тесты, сборка, дым в Chromium.

Каждая возвращает `(находки, примечание)`; `None` вместо находок — проверка
не запускалась (нет инструмента): это сбой окружения, а не кода (CLAUDE.md,
«Сбой инструмента — не сбой кода»), и итог прогона — код 3, а не зелёный.
Дым в Chromium без браузера — пропуск с пометкой: браузер есть не везде, где
идёт сессия, а сборку проверяет ещё и `build.smoke`.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SIZE_LIMIT = 1024 * 1024                  # N-09: сборка со срезом — до 1 МБ
CHROMIUM_CANDIDATES = ("chromium", "chromium-browser", "google-chrome", "chrome")
CHROMIUM_DEFAULT = Path("/opt/pw-browsers/chromium")


def _run(cmd: list[str], root: Path, timeout: int = 600) -> subprocess.CompletedProcess:
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    return subprocess.run(cmd, cwd=root, capture_output=True, text=True, timeout=timeout, env=env)


def _tail(text: str, lines: int = 15) -> str:
    return "\n".join(text.strip().splitlines()[-lines:])


def py_tests(root: Path):
    proc = _run([sys.executable, "-m", "unittest", "discover", "-s", "tests", "-t", "."], root)
    summary = re.findall(r"^Ran (\d+) tests?", proc.stderr, re.M)
    note = f"тестов {summary[-1]}" if summary else ""
    if proc.returncode != 0:
        return [f"python3 -m unittest:\n{_tail(proc.stderr)}"], note
    return [], note


def viewer_tests(root: Path):
    node = shutil.which("node")
    if node is None:
        return None, "нет Node.js — тесты просмотрщика не запускались"
    files = sorted(str(p.relative_to(root)) for p in (root / "viewer" / "test").glob("*.test.js"))
    proc = _run([node, "--test", *files], root)
    passed = re.findall(r"^# pass (\d+)", proc.stdout, re.M)
    note = f"тестов {passed[-1]}" if passed else ""
    if proc.returncode != 0:
        return [f"node --test:\n{_tail(proc.stdout + proc.stderr)}"], note
    return [], note


def _build(root: Path, *args: str) -> subprocess.CompletedProcess:
    return _run([sys.executable, "-m", "tools.dpvis", "build", *args], root, timeout=120)


def build_smoke(root: Path):
    """Сборка одного файла: без внешних адресов, до 1 МБ; комплект сайта детерминирован (F-40, D-15)."""
    problems = []
    with tempfile.TemporaryDirectory(prefix="dpvis-build-") as tmp:
        t = Path(tmp)
        for name, extra in (("full.html", []), ("public.html", ["--public"])):
            proc = _build(root, "--out", str(t / name), *extra)
            if proc.returncode != 0:
                problems.append(f"build {' '.join(extra)}: {_tail(proc.stdout + proc.stderr, 5)}")
                continue
            html = (t / name).read_text(encoding="utf-8")
            if re.search(r"""\b(?:src|href)\s*=\s*["']?https?://""", html):
                problems.append(f"build {' '.join(extra)}: в HTML внешний адрес в src/href — сборка должна работать без сети (A-13)")
            size = (t / name).stat().st_size
            if size > SIZE_LIMIT:
                problems.append(f"build {' '.join(extra)}: {size // 1024} КБ — больше 1 МБ (N-09)")
        sums = []
        for kit in ("site1", "site2"):
            proc = _build(root, "--site", str(t / kit))
            if proc.returncode != 0:
                problems.append(f"build --site: {_tail(proc.stdout + proc.stderr, 5)}")
                break
            sums.append(json.loads((t / kit / "MANIFEST.json").read_text(encoding="utf-8"))["files"])
        if len(sums) == 2 and sums[0] != sums[1]:
            problems.append("build --site: две сборки подряд дали разные файлы — сборка не детерминирована")
    return problems, ""


def chromium() -> str | None:
    env = os.environ.get("DPVIS_CHROMIUM")
    if env:
        return env
    for name in CHROMIUM_CANDIDATES:
        found = shutil.which(name)
        if found:
            return found
    return str(CHROMIUM_DEFAULT) if CHROMIUM_DEFAULT.exists() else None


def render_dom(browser: str, html: Path, width: int = 1366, height: int = 900) -> str:
    proc = subprocess.run(
        [browser, "--headless", "--no-sandbox", "--disable-gpu", "--hide-scrollbars",
         f"--window-size={width},{height}", "--virtual-time-budget=5000", "--dump-dom", html.as_uri()],
        capture_output=True, text=True, timeout=120,
    )
    return proc.stdout


def screenshot(browser: str, html: Path, out: Path, width: int, height: int) -> bool:
    proc = subprocess.run(
        [browser, "--headless", "--no-sandbox", "--disable-gpu", "--hide-scrollbars",
         f"--window-size={width},{height}", "--virtual-time-budget=5000",
         f"--screenshot={out}", html.as_uri()],
        capture_output=True, text=True, timeout=120,
    )
    return proc.returncode == 0 and out.is_file()


# Узкие ширины окно Chromium без флагов эмуляции не даёт (меньше 500 px не бывает), поэтому
# сборка открывается во фрейме нужной ширины; страница сама сообщает родителю, что нарисовала.
PROBE = """<script>
setTimeout(function () {
  var d = document.documentElement;
  parent.postMessage({ dpvisSmoke: 1, sw: d.scrollWidth, cw: d.clientWidth,
    nodes: document.querySelectorAll("g.node").length, flows: document.querySelectorAll("g.flow").length,
    error: !!document.querySelector(".dv-msg:not([hidden])") }, "*");
}, 1500);
</script></body>"""
HARNESS = """<!doctype html><html><body>
<iframe src="{src}" width="{w}" height="900"></iframe>
<script>
addEventListener("message", function (e) {{
  if (e.data && e.data.dpvisSmoke) document.body.setAttribute("data-r", JSON.stringify(e.data));
}});
</script></body></html>"""


def measure(browser: str, page: Path, width: int) -> dict | None:
    harness = page.with_name(f"harness-{width}.html")
    harness.write_text(HARNESS.format(src=page.name, w=width), encoding="utf-8")
    dom = render_dom(browser, harness, width=max(width + 40, 600))
    m = re.search(r'data-r="([^"]+)"', dom)
    return json.loads(m.group(1).replace("&quot;", '"')) if m else None


def browser_smoke(root: Path):
    """Дым сборки в Chromium (A-07, A-13, D-16): из file:// при 1366 и 390 px нарисованы все
    узлы и потоки модели, данных хватило без сети, ширина страницы не больше окна."""
    browser = chromium()
    if browser is None:
        return [], "пропущено: нет Chromium (DPVIS_CHROMIUM=путь)"
    model = json.loads((root / "data" / "model.json").read_text(encoding="utf-8"))
    want_nodes, want_flows = len(model["nodes"]), len(model["flows"])
    problems = []
    with tempfile.TemporaryDirectory(prefix="dpvis-smoke-") as tmp:
        page = Path(tmp) / "dpvisualisation.html"
        proc = _build(root, "--out", str(page))
        if proc.returncode != 0:
            return [f"build: {_tail(proc.stdout + proc.stderr, 5)}"], ""
        page.write_text(page.read_text(encoding="utf-8").replace("</body>", PROBE), encoding="utf-8")
        for width in (1366, 390):
            r = measure(browser, page, width)
            if r is None:
                problems.append(f"Chromium {width} px: страница не ответила — просмотрщик не запустился")
                continue
            if r["nodes"] != want_nodes or r["flows"] != want_flows:
                problems.append(f"Chromium {width} px: нарисовано узлов {r['nodes']} из {want_nodes}, потоков {r['flows']} из {want_flows}")
            if r["sw"] > r["cw"]:
                problems.append(f"Chromium {width} px: страница шире окна ({r['sw']} > {r['cw']}) — прокрутка вбок (D-16)")
            if r["error"]:
                problems.append(f"Chromium {width} px: просмотрщик показал ошибку")
    return problems, f"узлов {want_nodes}, потоков {want_flows}; 1366 и 390 px — без прокрутки вбок"


FULL = (
    ("py.tests", py_tests),
    ("viewer.tests", viewer_tests),
    ("build.smoke", build_smoke),
    ("browser.smoke", browser_smoke),
)
