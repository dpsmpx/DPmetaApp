#!/usr/bin/env python3
"""Android SDK, Google Maven и закреплённые файлы сборок — из приватного build_tools автора.

Облачной сессии закрыт dl.google.com; build_tools собирает на телефоне автора
tools/termux/build-tools.py (слово автора 31 октября 2026). Этот скрипт ставит из него то, что
сборка берёт оттуда сама. Вызывает хук начала сессии, когда dl.google.com закрыт; руками:

    python3 tools/build_tools_install.py [--clone DIR] [--sdk /opt/android-sdk]

1. Клон build_tools: есть рядом с этим репозиторием — берётся он, нет — git clone --depth 1
   --no-checkout (сессии нужен доступ к dpsmpx/build_tools): файлы читаются прямо из объектов git,
   без рабочей копии — вдвое меньше места (≈ 2,3 ГБ). Клон, сделанный самим скриптом, после
   установки удаляется.
2. Всё сверяется по MANIFEST.json — SHA-256 файлов и частей; не сошлось — остановка, ничего не
   ставится.
3. SDK ставит sdkmanager из build_tools — из локальной копии манифеста Google и архивов
   (SDK_TEST_BASE_URL на 127.0.0.1; архивы отдаются прямо из частей, без копии; --no_proxy: иначе
   запросы уходят в прокси среды) — со своей сверкой SHA-1 по манифесту. Лицензии принимаются,
   как в хуке.
4. Google Maven — в <GRADLE_USER_HOME>/build-tools/maven2, и скрипт инициализации Gradle ставит его
   первым, только для групп Google (как google { content } в dpai/settings.gradle.kts); сначала
   .module — у компонентов из сверки DPpass в build_tools нет POM.
5. Закреплённые загрузки сборок — в кеш, откуда их берёт linuxFiles dpai/android
   (<GRADLE_USER_HOME>/caches/dpai-linux).
6. Метка <GRADLE_USER_HOME>/build-tools/INSTALLED — коммит build_tools, путь SDK, пакеты. Всё на
   месте и коммит на GitHub тот же (git ls-remote) — повторный запуск ничего не делает и не
   клонирует; GitHub не ответил — остаётся прежняя установка.

Код возврата: 0 — стоит; 1 — не сошлось или sdkmanager не поставил; 2 — нет клона и не клонируется.
"""
from __future__ import annotations

import argparse
import contextlib
import functools
import hashlib
import http.server
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = "https://github.com/dpsmpx/build_tools"
GOOGLE_GROUPS = ("com\\\\.android(\\\\..*)?", "androidx\\\\..*", "com\\\\.google\\\\.(android|testing)\\\\..*")
INIT = """// Пишет tools/build_tools_install.py DPmetaApp: Google Maven из build_tools (облачной сессии закрыт
// dl.google.com). Первым — только для групп Google; найдено здесь — до google() дело не доходит.
// Сначала .module: у компонентов из сверки DPpass в build_tools нет POM. Руками не править.
def local = "{url}"
def onlyGoogle = {{ repo ->
    repo.metadataSources {{
        gradleMetadata()
        mavenPom()
    }}
    repo.content {{
{groups}
    }}
}}
beforeSettings {{ settings ->
    settings.pluginManagement.repositories {{
        onlyGoogle(maven {{ url local }})
    }}
    settings.dependencyResolutionManagement.repositories {{
        onlyGoogle(maven {{ url local }})
    }}
}}
"""


class Failed(Exception):
    """Не сошлось или не поставилось — установка остановлена."""


def say(msg: str) -> None:
    print(msg, flush=True)


class Source:
    """Файлы клона build_tools: из рабочей копии, а без неё (клон --no-checkout) — из объектов git."""

    def __init__(self, clone: Path):
        self.clone = clone

    @contextlib.contextmanager
    def open(self, rel: str):
        if (self.clone / rel).is_file():
            with open(self.clone / rel, "rb") as f:
                yield f
            return
        p = subprocess.Popen(["git", "-C", str(self.clone), "cat-file", "blob", f"HEAD:{rel}"],
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        try:
            yield p.stdout
        finally:
            p.stdout.close()
            if p.wait():
                raise Failed(f"{rel}: нет в клоне build_tools")

    def read(self, rel: str) -> bytes:
        with self.open(rel) as f:
            return f.read()


def pieces(e: dict, rel: str) -> list[str]:
    return [p["path"] for p in e["parts"]] if e.get("parts") else [rel]


def verify(src: Source, m: dict, rels) -> None:
    """SHA-256 частей и целого файла — до того, как что-либо ставить."""
    for rel in rels:
        e = m["files"][rel]
        whole, n = hashlib.sha256(), 0
        for part, piece in zip(e.get("parts") or [None], pieces(e, rel)):
            h, k = hashlib.sha256(), 0
            with src.open(piece) as f:
                while chunk := f.read(1 << 20):
                    h.update(chunk)
                    whole.update(chunk)
                    k += len(chunk)
            n += k
            if part and (h.hexdigest() != part["sha256"] or k != part["size"]):
                raise Failed(f"{part['path']}: SHA-256 не сошлась с MANIFEST.json")
        if whole.hexdigest() != e["sha256"] or n != e["size"]:
            raise Failed(f"{rel}: SHA-256 не сошлась с MANIFEST.json")


def assemble(src: Source, e: dict, rel: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".tmp")
    with open(tmp, "wb") as out:
        for piece in pieces(e, rel):
            with src.open(piece) as f:
                shutil.copyfileobj(f, out, 1 << 20)
    tmp.replace(dest)


def installed(sdk: Path, path: str) -> bool:
    return (sdk / path.replace(";", "/") / "package.xml").is_file()


class _Repo(http.server.BaseHTTPRequestHandler):
    """Локальная копия репозитория пакетов: манифест и архивы — прямо из частей build_tools."""

    files: dict = {}  # имя → (размер, [части])
    src: Source

    def do_GET(self):  # noqa: N802 — имя задаёт http.server
        entry = self.files.get(self.path.lstrip("/"))
        if entry is None:
            self.send_error(404)
            return
        size, parts = entry
        self.send_response(200)
        self.send_header("Content-Length", str(size))
        self.end_headers()
        for piece in parts:
            with self.src.open(piece) as f:
                shutil.copyfileobj(f, self.wfile, 1 << 20)

    def log_message(self, *args):
        pass


def install_sdk(src: Source, m: dict, sdk: Path) -> None:
    want = [p for p in m["sdk"] if p != "cmdline-tools"]
    sdkmanager = sdk / "cmdline-tools/latest/bin/sdkmanager"
    if sdkmanager.is_file() and all(installed(sdk, p) for p in want):
        say(f"  SDK: всё уже стоит в {sdk}")
        return
    verify(src, m, [m["sdk"][p] for p in m["sdk"]])
    if not sdkmanager.is_file():
        rel = m["sdk"]["cmdline-tools"]
        tmp = sdk / "cmdline-tools/.unpack"
        shutil.rmtree(tmp, ignore_errors=True)
        zpath = sdk / "cmdline-tools/.clt.zip"
        assemble(src, m["files"][rel], rel, zpath)
        with zipfile.ZipFile(zpath) as z:
            z.extractall(tmp)
        zpath.unlink()
        shutil.rmtree(sdk / "cmdline-tools/latest", ignore_errors=True)
        (tmp / "cmdline-tools").rename(sdk / "cmdline-tools/latest")
        tmp.rmdir()
        for b in (sdk / "cmdline-tools/latest/bin").iterdir():
            b.chmod(0o755)
    xml = src.read("sdk/repository2-3.xml")
    files = {"repository2-3.xml": (len(xml), ["sdk/repository2-3.xml"])}
    for p in want:
        rel = m["sdk"][p]
        files[Path(rel).name] = (m["files"][rel]["size"], pieces(m["files"][rel], rel))
    handler = type("Repo", (_Repo,), {"files": files, "src": src})
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    env = dict(os.environ, SDK_TEST_BASE_URL=f"http://127.0.0.1:{server.server_address[1]}/")
    run = functools.partial(subprocess.run, env=env, capture_output=True, text=True)
    base = [str(sdkmanager), f"--sdk_root={sdk}", "--no_proxy"]
    try:
        say(f"  SDK: ставлю {' '.join(want)} в {sdk} (несколько минут)")
        run(base + ["--licenses"], input="y\n" * 64)
        r = run(base + want)
    finally:
        server.shutdown()
        server.server_close()
    missing = [p for p in want if not installed(sdk, p)]
    if r.returncode or missing:
        tail = (r.stdout + r.stderr).strip().splitlines()[-5:]
        raise Failed(f"sdkmanager не поставил {', '.join(missing) or '(код ' + str(r.returncode) + ')'}: {' | '.join(tail)}")


def install_maven(src: Source, m: dict, gradle: Path) -> None:
    rels = [r for r in m["files"] if r.startswith("maven2/")]
    dest = gradle / "build-tools"
    todo = [r for r in rels if not (dest / r).is_file() or (dest / r).stat().st_size != m["files"][r]["size"]]
    verify(src, m, todo)
    for rel in todo:
        assemble(src, m["files"][rel], rel, dest / rel)
    groups = "\n".join(f'        includeGroupByRegex("{g}")' for g in GOOGLE_GROUPS)
    init = gradle / "init.d/build-tools-google-maven.gradle"
    init.parent.mkdir(parents=True, exist_ok=True)
    init.write_text(INIT.format(url=(dest / "maven2").as_uri() + "/", groups=groups), encoding="utf-8")
    say(f"  Google Maven: файлов {len(rels)} (новых {len(todo)}) в {dest / 'maven2'}; {init}")


def install_pins(src: Source, m: dict, gradle: Path) -> None:
    pins = m.get("pins") or {}
    verify(src, m, list(pins.values()))
    cache = gradle / "caches/dpai-linux"  # откуда linuxFiles dpai/android берёт загрузки
    for url, rel in pins.items():
        assemble(src, m["files"][rel], rel, cache / url.rsplit("/", 1)[-1])
    if pins:
        say(f"  файлы сборки: {len(pins)} в {cache}")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Android SDK и Google Maven из build_tools (облачная сессия)")
    ap.add_argument("--clone", help="клон build_tools (по умолчанию — рядом с этим репозиторием)")
    ap.add_argument("--sdk", default=os.environ.get("ANDROID_HOME") or "/opt/android-sdk")
    ap.add_argument("--gradle-home", default=os.environ.get("GRADLE_USER_HOME") or str(Path.home() / ".gradle"))
    ap.add_argument("--repo", default=REPO, help=argparse.SUPPRESS)
    a = ap.parse_args(argv)
    clone = Path(a.clone) if a.clone else ROOT.parent / "build_tools"
    sdk, gradle = Path(a.sdk), Path(a.gradle_home)
    cloned = False
    src = Source(clone)
    mark = gradle / "build-tools/INSTALLED"
    try:
        have = json.loads(mark.read_text()) if mark.is_file() else {}
    except ValueError:  # метка прежнего вида — поставить заново
        have = {}
    have_ok = (have.get("sdk") == str(sdk) and (gradle / "init.d/build-tools-google-maven.gradle").is_file()
               and all(installed(sdk, p) for p in have.get("packages", [])))
    if not (clone / "MANIFEST.json").is_file() and not (clone / ".git").exists():
        if have_ok:
            r = subprocess.run(["git", "ls-remote", a.repo, "HEAD"], capture_output=True, text=True, timeout=120)
            remote = r.stdout.split()[0] if not r.returncode and r.stdout.strip() else None
            if remote in (None, have.get("head")):
                say(f"  build_tools {have['head'][:12]}: уже стоит" + ("" if remote else " (GitHub не ответил — без сверки с ним)"))
                return 0
        say(f"  build_tools: клонирую {a.repo} в {clone} без рабочей копии (несколько минут)")
        for attempt in range(2):  # обрыв соединения через прокси — один повтор
            r = subprocess.run(["git", "clone", "-q", "--depth", "1", "--no-checkout", a.repo, str(clone)],
                               capture_output=True, text=True)
            if not r.returncode:
                break
            shutil.rmtree(clone, ignore_errors=True)
            if attempt == 0:
                time.sleep(10)
        else:
            say(f"  ! build_tools не клонируется ({r.stderr.strip()[-200:]}) — добавьте dpsmpx/build_tools к сессии")
            return 2
        cloned = True
    try:
        m = json.loads(src.read("MANIFEST.json").decode("utf-8"))
    except Failed as e:
        say(f"  ! build_tools: {e}")
        return 2
    head = subprocess.run(["git", "-C", str(clone), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    packages = [p for p in m["sdk"] if p != "cmdline-tools"]
    if head and have_ok and have.get("head") == head and set(have.get("packages", [])) == set(packages):
        say(f"  build_tools {head[:12]}: уже стоит")
        if cloned:
            shutil.rmtree(clone, ignore_errors=True)
        return 0
    try:
        sdk.mkdir(parents=True, exist_ok=True)
        install_sdk(src, m, sdk)
        install_maven(src, m, gradle)
        install_pins(src, m, gradle)
    except Failed as e:
        say(f"  ! build_tools: {e}")
        return 1
    mark.parent.mkdir(parents=True, exist_ok=True)
    mark.write_text(json.dumps({"head": head, "sdk": str(sdk), "packages": packages}, ensure_ascii=False) + "\n")
    if cloned:
        shutil.rmtree(clone, ignore_errors=True)
    say(f"  build_tools {head[:12]}: SDK, Google Maven и файлы сборки поставлены")
    return 0


if __name__ == "__main__":
    sys.exit(main())
