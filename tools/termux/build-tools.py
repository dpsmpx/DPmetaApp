#!/usr/bin/env python3
"""Инструменты сборки для облачных сессий — через приватный репозиторий автора build_tools.

Облачной сессии закрыт dl.google.com (политика сети её среды): ни Android SDK, ни Google Maven
(maven.google.com переадресует туда же). Этот скрипт запускается в Termux на телефоне автора, где
адрес открыт: скачивает всё, что нужно сборкам и проверкам DPmetaApp, сверяет и кладёт в клон
build_tools; с --push коммитит и пушит по частям. Сессия забирает репозиторий с GitHub (слово
автора 31 октября 2026).

    pkg install python git
    git clone https://github.com/dpsmpx/build_tools ~/build_tools
    cd ~/DPmetaApp && git pull
    python3 tools/termux/build-tools.py --list               # что и сколько, без загрузки
    python3 tools/termux/build-tools.py ~/build_tools --push

Репозиторий build_tools — только приватный: лицензия Android SDK не разрешает его раздавать.

Что и откуда (версии — из файлов сборки этого клона DPmetaApp, не из скрипта):
  sdk/      пакеты Android SDK для dpai/android и dppass/ — zip с dl.google.com/android/repository;
            размер и SHA-1 — по манифесту Google repository2-3.xml (он лежит рядом: по нему ставит
            sdkmanager); command-line tools — по SHA-256 из хука начала сессии;
  maven2/   Google Maven — группы, которые сборки берут из google() (как google { content } в
            dpai/settings.gradle.kts): весь список dppass/gradle/verification-metadata.xml — по
            SHA-256 оттуда; для dpai/android — обход графа плагина Android, Compose BOM, material3 и
            activity-compose по POM и Gradle module metadata (суммы из .module, где они есть);
  files/    закреплённые загрузки самих сборок — пары «адрес to SHA-256» в *.gradle.kts dpai/ (как
            linuxFiles dpai/android: Alpine minirootfs и proot-static с dl-cdn.alpinelinux.org) — по
            закреплённой сумме;
  MANIFEST.json  размер и SHA-256 каждого файла и чем он сверен; файл больше 45 МБ лежит частями
            name.000, name.001… (GitHub не берёт файлы больше 100 МБ) — MANIFEST знает части.
Повторный запуск докачивает недостающее (большие файлы — с места обрыва), сверенное не трогает.
Места нужно около 2,5 ГБ под файлы и столько же под .git.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SDK_REPO = "https://dl.google.com/android/repository/"
GOOGLE_MAVEN = "https://dl.google.com/dl/android/maven2/"
CENTRAL = "https://repo.maven.apache.org/maven2/"
# Группы Google Maven — как google { content } в dpai/settings.gradle.kts.
GOOGLE_GROUP = re.compile(r"com\.android(\..*)?|androidx\..*|com\.google\.(android|testing)\..*")
PART = 45 * 1024 * 1024
SCHEMA = "build-tools/1"
UA = "dpmetaapp-build-tools"
VERIFY_NS = "{https://schema.gradle.org/dependency-verification}"


class Mismatch(Exception):
    """Скачанное не сошлось с размером или суммой — файл удалён, запуск остановлен."""


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def child(el, name):
    return next((c for c in el if local(c.tag) == name), None)


def text(el, name):
    c = child(el, name) if el is not None else None
    return c.text.strip() if c is not None and c.text else None


def gav_dir(g: str, a: str, v: str) -> str:
    return f"{g.replace('.', '/')}/{a}/{v}/"


# ---------------------------------------------------------------- версии из файлов сборки
def sdk_wanted(root: Path) -> list[list[str]]:
    """Пакеты SDK: у каждого — варианты пути (первый, что есть в манифесте). Как хуки начала сессии."""
    app = (root / "dpai/android/build.gradle.kts").read_text(encoding="utf-8")
    api = re.search(r"^\s*compileSdk\s*=\s*(\d+)", app, re.M).group(1)
    ndk = re.search(r'^\s*ndkVersion\s*=\s*"([^"]+)"', app, re.M).group(1)
    cmake = re.search(r'^\s*version\s*=\s*"(3\.[0-9.]+)"', app, re.M).group(1)
    cat = (root / "dppass/gradle/libs.versions.toml").read_text(encoding="utf-8")
    p_api = re.search(r'^android-compileSdk\s*=\s*"(\d+)"', cat, re.M).group(1)
    p_ndk = re.search(r'^android-ndk\s*=\s*"([^"]+)"', cat, re.M).group(1)
    p_hook = (root / "dppass/.claude/hooks/session-start.sh").read_text(encoding="utf-8")
    p_bt = re.search(r'^BUILD_TOOLS="([^"]+)"', p_hook, re.M).group(1)
    want = [
        [f"platforms;android-{api}.0", f"platforms;android-{api}"],
        ["platforms;android-30"],  # minSdk dpai/android — для check_android_api.py (как хук)
        [f"build-tools;{api}.0.0"],
        ["platform-tools"],
        [f"ndk;{ndk}"],
        [f"cmake;{cmake}"],
        [f"platforms;android-{p_api}", f"platforms;android-{p_api}.0"],
        [f"build-tools;{p_bt}"],
        [f"ndk;{p_ndk}"],
    ]
    out, seen = [], set()
    for alts in want:
        if alts[0] not in seen:
            seen.add(alts[0])
            out.append(alts)
    return out


def command_line_tools(root: Path) -> tuple[str, str]:
    hook = (root / ".claude/hooks/session-start.sh").read_text(encoding="utf-8")
    return (re.search(r'CLT_ZIP="([^"]+)"', hook).group(1), re.search(r'CLT_SHA256="([0-9a-f]{64})"', hook).group(1))


def catalog_version(root: Path, key: str) -> str:
    cat = (root / "dpai/gradle/libs.versions.toml").read_text(encoding="utf-8")
    return re.search(rf'^{re.escape(key)}\s*=\s*"([^"]+)"', cat, re.M).group(1)


def remote_packages(xml: bytes) -> dict[str, tuple[str, int, str]]:
    """Путь пакета → (имя архива, размер, SHA-1) для Linux x86_64 из манифеста repository2-3.xml."""
    out = {}
    for pkg in ET.fromstring(xml).iter():
        if local(pkg.tag) != "remotePackage" or pkg.get("path") in out:
            continue
        for arch in pkg.iter():
            if local(arch.tag) != "archive":
                continue
            if text(arch, "host-os") not in (None, "linux") or text(arch, "host-arch") not in (None, "x64", "x86_64"):
                continue
            comp = child(arch, "complete")
            if comp is None:
                continue
            out[pkg.get("path")] = (text(comp, "url"), int(text(comp, "size")), text(comp, "checksum"))
            break
    return out


PIN = re.compile(r'"([a-z]+://[^"\s]+)"\s+to\s+"([0-9a-f]{64})"')


def pinned(root: Path) -> list[tuple[str, str]]:
    """Закреплённые загрузки сборок: (адрес, SHA-256) из *.gradle.kts dpai/ — сборка качает их сама."""
    out = []
    for f in sorted((root / "dpai").rglob("*.gradle.kts")):
        if "/build/" in f.as_posix():
            continue
        out += [(u, h) for u, h in PIN.findall(f.read_text(encoding="utf-8")) if (u, h) not in out]
    return out


def dppass_artifacts(root: Path):
    """Артефакты Google Maven из сверки DPpass: (группа, имя, версия, файл, SHA-256)."""
    tree = ET.parse(root / "dppass/gradle/verification-metadata.xml").getroot()
    for c in tree.iter(VERIFY_NS + "component"):
        g, a, v = c.get("group"), c.get("name"), c.get("version")
        if not GOOGLE_GROUP.fullmatch(g):
            continue
        for art in c.findall(VERIFY_NS + "artifact"):
            sha = art.find(VERIFY_NS + "sha256")
            yield g, a, v, art.get("name"), sha.get("value") if sha is not None else None


# ---------------------------------------------------------------- загрузка и хранилище
class Store:
    """Каталог build_tools: файлы, части больших файлов и MANIFEST.json."""

    def __init__(self, out: Path, root: Path):
        self.out = out
        self.path = out / "MANIFEST.json"
        self.m = json.loads(self.path.read_text(encoding="utf-8")) if self.path.is_file() else {}
        self.m.setdefault("schema", SCHEMA)
        self.m.setdefault("files", {})
        self.m.setdefault("sdk", {})
        self.m["dpmetaapp_commit"] = git_head(root)

    def have(self, rel: str) -> bool:
        e = self.m["files"].get(rel)
        if not e:
            return False
        parts = e.get("parts")
        if parts:
            return all((self.out / p["path"]).is_file() and (self.out / p["path"]).stat().st_size == p["size"] for p in parts)
        return (self.out / rel).is_file() and (self.out / rel).stat().st_size == e["size"]

    def read(self, rel: str) -> bytes:
        return (self.out / rel).read_bytes()

    def add(self, rel: str, sha256: str, size: int, how: str) -> None:
        f = self.out / rel
        entry = {"sha256": sha256, "size": size, "verified": how}
        if size > PART:
            parts = []
            with open(f, "rb") as src:
                i = 0
                while chunk := src.read(PART):
                    p = f"{rel}.{i:03d}"
                    (self.out / p).write_bytes(chunk)
                    parts.append({"path": p, "size": len(chunk), "sha256": hashlib.sha256(chunk).hexdigest()})
                    i += 1
            f.unlink()  # в репозитории — только части
            entry["parts"] = parts
        self.m["files"][rel] = entry

    def save(self) -> None:
        self.m["updated"] = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.m, ensure_ascii=False, indent=1, sort_keys=True) + "\n", encoding="utf-8")
        tmp.replace(self.path)


def git_head(root: Path) -> str | None:
    try:
        r = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True, text=True, timeout=10)
        return r.stdout.strip() or None
    except (OSError, subprocess.SubprocessError):
        return None


def get(url: str, tries: int = 5) -> bytes | None:
    """Небольшой файл целиком; нет его (404) — None."""
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if attempt == tries - 1:
                raise
        except urllib.error.URLError as e:
            if url.startswith("file:") and isinstance(e.reason, FileNotFoundError):
                return None
            if attempt == tries - 1:
                raise
        except (TimeoutError, ConnectionError):
            if attempt == tries - 1:
                raise
        time.sleep(2 ** attempt)
    return None


def fetch(url: str, dest: Path, *, size: int | None = None, sha1: str | None = None,
          sha256: str | None = None, tries: int = 8) -> str | None:
    """Скачать в dest с докачкой обрыва; сверить размер и суммы. Нет файла (404) — None; иначе SHA-256."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".download")
    for attempt in range(tries):
        have = tmp.stat().st_size if tmp.exists() else 0
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        if have and url.startswith("http"):
            req.add_header("Range", f"bytes={have}-")
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                mode = "ab" if have and getattr(r, "status", 200) == 206 else "wb"
                with open(tmp, mode) as f:
                    while chunk := r.read(1 << 20):
                        f.write(chunk)
            break
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code == 416:  # уже целиком
                break
            if attempt == tries - 1:
                raise
        except urllib.error.URLError as e:
            if url.startswith("file:") and isinstance(e.reason, FileNotFoundError):
                return None
            if attempt == tries - 1:
                raise
        except (TimeoutError, ConnectionError):
            if attempt == tries - 1:
                raise
        time.sleep(min(60, 2 ** attempt))
    h1, h2, n = hashlib.sha1(), hashlib.sha256(), 0
    with open(tmp, "rb") as f:
        while chunk := f.read(1 << 20):
            h1.update(chunk)
            h2.update(chunk)
            n += len(chunk)
    bad = ((size is not None and n != size) and f"размер {n} ≠ {size}"
           or (sha1 and h1.hexdigest() != sha1.lower()) and "SHA-1"
           or (sha256 and h2.hexdigest() != sha256.lower()) and "SHA-256")
    if bad:
        tmp.unlink()
        raise Mismatch(f"{url}: не сошлось — {bad}")
    tmp.replace(dest)
    return h2.hexdigest()


# ---------------------------------------------------------------- Google Maven: обход графа
class Crawler:
    """Замыкание зависимостей в группах Google Maven по POM и Gradle module metadata.

    Берётся больше, чем выберет Gradle (все запрошенные версии и версии ограничений), — лишнее
    безвредно, недостающее ломает сборку. Чужие группы (Maven Central) — листья: их сессия берёт
    сама. Неточные версии (диапазоны, «+») не скачиваются — пишутся в skipped."""

    def __init__(self, store: Store, maven: str, central: str):
        self.store, self.maven, self.central = store, maven, central
        self.seen: set = set()
        self.queue: list = []
        self.forced: dict = {}  # (группа, имя) → версии из BOM и ограничений
        self.skipped: list[str] = []
        self.files = 0
        self._central: dict = {}

    def force(self, g, a, v):
        if not GOOGLE_GROUP.fullmatch(g) or not v:
            return
        vs = self.forced.setdefault((g, a), set())
        if v not in vs:
            vs.add(v)
            if any(k[:2] == (g, a) for k in self.seen):
                self.want(g, a, v, "ограничение")

    def want(self, g, a, v, why):
        if not g or not a or not GOOGLE_GROUP.fullmatch(g):
            return
        m = re.fullmatch(r"\[([^\[\](),]+)\]", v or "")
        if m:  # «[2.1.0]» в Maven — точная версия, не диапазон
            v = m.group(1)
        if not v or any(ch in v for ch in "[]()+,$"):
            self.skipped.append(f"{g}:{a}:{v} — версия не точная ({why})")
            return
        if (g, a, v) in self.seen:
            return
        self.seen.add((g, a, v))
        self.queue.append((g, a, v))
        for fv in sorted(self.forced.get((g, a), ())):
            self.want(g, a, fv, "ограничение")

    def run(self):
        while self.queue:
            self.visit(*self.queue.pop())

    def meta(self, g, a, v, name) -> bytes | None:
        rel = "maven2/" + gav_dir(g, a, v) + name
        if self.store.have(rel):
            return self.store.read(rel)
        sha = fetch(self.maven + gav_dir(g, a, v) + name, self.store.out / rel)
        if sha is None:
            return None
        data = self.store.read(rel)
        self.store.add(rel, sha, len(data), "tls")
        self.files += 1
        return data

    def file(self, g, a, v, name, sha256=None) -> bool:
        rel = "maven2/" + gav_dir(g, a, v) + name
        if self.store.have(rel):
            return True
        sha = fetch(self.maven + gav_dir(g, a, v) + name, self.store.out / rel, sha256=sha256)
        if sha is None:
            return False
        self.store.add(rel, sha, (self.store.out / rel).stat().st_size, "sha256 из .module" if sha256 else "tls")
        self.files += 1
        return True

    def visit(self, g, a, v):
        pom = self.meta(g, a, v, f"{a}-{v}.pom")
        module = self.meta(g, a, v, f"{a}-{v}.module")
        if module:
            self.from_module(g, a, v, json.loads(module))
        elif pom:
            self.from_pom(g, a, v, pom)
        else:
            self.skipped.append(f"{g}:{a}:{v} — нет ни POM, ни .module в Google Maven")

    def from_module(self, g, a, v, mod):
        variants = mod.get("variants", [])
        plats = {x.get("attributes", {}).get("org.jetbrains.kotlin.platform.type") for x in variants}
        keep = "androidJvm" if "androidJvm" in plats else "jvm"
        for var in variants:
            attrs = var.get("attributes", {})
            if attrs.get("org.gradle.category") == "documentation":
                continue
            if attrs.get("org.jetbrains.kotlin.platform.type") not in (None, keep):
                continue
            at = var.get("available-at")
            if at:
                self.want(at.get("group"), at.get("module"), at.get("version"), f"{a}: available-at")
                continue
            for f in var.get("files", []):
                self.file(g, a, v, f["url"], f.get("sha256"))
            for d in var.get("dependencyConstraints", []):
                ver = d.get("version", {})
                self.force(d.get("group"), d.get("module"), ver.get("strictly") or ver.get("requires") or ver.get("prefers"))
            for d in var.get("dependencies", []):
                ver = d.get("version", {})
                self.want(d.get("group"), d.get("module"), ver.get("strictly") or ver.get("requires") or ver.get("prefers"), a)

    def pom(self, g, a, v) -> bytes | None:
        if GOOGLE_GROUP.fullmatch(g):
            return self.meta(g, a, v, f"{a}-{v}.pom")
        key = (g, a, v)
        if key not in self._central:  # чужой родитель или BOM — только прочитать
            self._central[key] = get(self.central + gav_dir(g, a, v) + f"{a}-{v}.pom")
        return self._central[key]

    def model(self, data: bytes, depth: int = 0) -> tuple[dict, dict, list, str]:
        """(свойства, управляемые версии, зависимости, упаковка) POM с учётом родителя и импортов BOM."""
        root = ET.fromstring(data)
        props, managed = {}, {}
        parent = child(root, "parent")
        pg = pv = None
        if parent is not None and depth < 10:
            pg, pa, pv = text(parent, "groupId"), text(parent, "artifactId"), text(parent, "version")
            if GOOGLE_GROUP.fullmatch(pg or ""):
                self.want(pg, pa, pv, "родитель")
            pdata = self.pom(pg, pa, pv)
            if pdata:
                pprops, pmanaged, _, _ = self.model(pdata, depth + 1)
                props.update(pprops)
                managed.update(pmanaged)
        gid, ver = text(root, "groupId") or pg, text(root, "version") or pv
        props.update({"project.groupId": gid, "project.version": ver, "pom.version": ver, "version": ver,
                      "project.artifactId": text(root, "artifactId"), "project.parent.version": pv})
        for p in child(root, "properties") if child(root, "properties") is not None else []:
            props[local(p.tag)] = (p.text or "").strip()

        def interp(s):
            for _ in range(10):
                if not s or "${" not in s:
                    break
                s = re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m.group(1)) or m.group(0), s)
            return s

        dm = child(child(root, "dependencyManagement"), "dependencies") if child(root, "dependencyManagement") is not None else None
        for d in dm if dm is not None else []:
            dg, da, dv = interp(text(d, "groupId")), interp(text(d, "artifactId")), interp(text(d, "version"))
            if text(d, "scope") == "import" and (text(d, "type") or "") == "pom":
                bdata = self.pom(dg, da, dv)
                if GOOGLE_GROUP.fullmatch(dg or ""):
                    self.want(dg, da, dv, "импорт BOM")
                if bdata:
                    for k, val in self.model(bdata, depth + 1)[1].items():
                        managed.setdefault(k, val)
            else:
                managed[(dg, da)] = dv
        deps = []
        dl = child(root, "dependencies")
        for d in dl if dl is not None else []:
            dg, da = interp(text(d, "groupId")), interp(text(d, "artifactId"))
            deps.append((dg, da, interp(text(d, "version")) or managed.get((dg, da)),
                         text(d, "scope"), text(d, "optional") == "true", text(d, "type")))
        return props, managed, deps, interp(text(root, "packaging")) or "jar"

    def from_pom(self, g, a, v, data):
        _, managed, deps, packaging = self.model(data)
        for (dg, da), dv in managed.items():
            self.force(dg, da, dv)
        for dg, da, dv, scope, optional, dtype in deps:
            if scope in (None, "compile", "runtime") and not optional and dtype != "test-jar":
                self.want(dg, da, dv, a)
        ext = {"pom": None, "aar": "aar", "jar": "jar", "bundle": "jar", "maven-plugin": "jar"}.get(packaging, "jar")
        if ext and not self.file(g, a, v, f"{a}-{v}.{ext}"):
            self.skipped.append(f"{g}:{a}:{v} — нет {a}-{v}.{ext}")

    def agp_runtime(self, agp: str) -> None:
        """Что плагин Android докачивает во время сборки, а не по графу зависимостей: lint (версия —
        мажор плагина + 23: 9.4.1 → 32.4.1) и aapt2 под Linux (сборка aapt2 — из maven-metadata.xml)."""
        self.want("com.android.tools.lint", "lint-gradle", re.sub(r"^\d+", lambda m: str(int(m.group(0)) + 23), agp), "lint плагина")
        meta = get(self.maven + "com/android/tools/build/aapt2/maven-metadata.xml")
        vs = re.findall(r"<version>(" + re.escape(agp) + r"-\d+)</version>", meta.decode("utf-8")) if meta else []
        if not vs:
            self.skipped.append(f"com.android.tools.build:aapt2:{agp}-* — нет в maven-metadata.xml")
            return
        v = max(vs, key=lambda x: int(x.rsplit("-", 1)[1]))
        self.want("com.android.tools.build", "aapt2", v, "aapt2 плагина")
        if not self.file("com.android.tools.build", "aapt2", v, f"aapt2-{v}-linux.jar"):
            self.skipped.append(f"com.android.tools.build:aapt2:{v}:linux — нет")

    def extra(self, coord: str) -> None:
        """Координаты из --extra: группа:имя:версия[:классификатор]."""
        g, a, v, *cls = coord.split(":")
        self.want(g, a, v, "--extra")
        if cls and not self.file(g, a, v, f"{a}-{v}-{cls[0]}.jar"):
            self.skipped.append(f"{coord} — нет")

    def bom(self, g, a, v):
        """BOM (platform): его версии — ограничения, а не зависимости: тянуть всё из него не нужно."""
        self.seen.add((g, a, v))
        module = self.meta(g, a, v, f"{a}-{v}.module")
        pom = self.meta(g, a, v, f"{a}-{v}.pom")
        if module:
            for var in json.loads(module).get("variants", []):
                for d in var.get("dependencyConstraints", []):
                    ver = d.get("version", {})
                    self.force(d.get("group"), d.get("module"), ver.get("strictly") or ver.get("requires"))
        elif pom:
            for (dg, da), dv in self.model(pom)[1].items():
                self.force(dg, da, dv)


# ---------------------------------------------------------------- git
def git(out: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(out), *args], capture_output=True, text=True)


def publish(out: Path, store: Store, message: str, push: bool) -> None:
    store.save()
    if not push:
        return
    git(out, "add", "-A")
    if not git(out, "diff", "--cached", "--quiet").returncode:
        return
    r = git(out, "commit", "-q", "-m", message)
    if r.returncode:
        raise SystemExit(f"✗ git commit: {r.stderr.strip()}")
    for attempt in range(5):
        r = git(out, "push", "-q", "origin", "HEAD")
        if not r.returncode:
            print(f"  ↑ {message}")
            return
        time.sleep(2 ** (attempt + 1))
    raise SystemExit(f"✗ git push: {r.stderr.strip()} — повторите запуск: сделанное не скачивается заново")


README = """# build_tools

Инструменты сборки для облачных сессий Claude Code проектов dpsmpx: в их среде закрыт dl.google.com.
Собирает и обновляет `DPmetaApp:tools/termux/build-tools.py` на телефоне автора; руками не править.

Репозиторий — только приватный: лицензия Android SDK не разрешает его раздавать.

- `sdk/` — пакеты Android SDK (zip) и манифест Google `repository2-3.xml`;
- `maven2/` — артефакты Google Maven в раскладке репозитория Maven;
- `MANIFEST.json` — размер, SHA-256 и способ сверки каждого файла; большие — частями `name.000`…
"""


# ---------------------------------------------------------------- запуск
def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Инструменты сборки DPmetaApp — в репозиторий build_tools (Termux)")
    ap.add_argument("out", nargs="?", help="клон build_tools")
    ap.add_argument("--push", action="store_true", help="коммитить и пушить после каждого пакета")
    ap.add_argument("--list", action="store_true", help="только показать пакеты SDK и размеры")
    ap.add_argument("--only", choices=["sdk", "maven", "files"], help="только эту часть")
    ap.add_argument("--extra", action="append", default=[], metavar="ГРУППА:ИМЯ:ВЕРСИЯ[:КЛАССИФИКАТОР]",
                    help="ещё артефакт Google Maven с его графом (если сборке чего-то не хватило)")
    ap.add_argument("--root", default=str(ROOT), help=argparse.SUPPRESS)
    ap.add_argument("--sdk-repo", default=SDK_REPO, help=argparse.SUPPRESS)
    ap.add_argument("--google-maven", default=GOOGLE_MAVEN, help=argparse.SUPPRESS)
    ap.add_argument("--central", default=CENTRAL, help=argparse.SUPPRESS)
    a = ap.parse_args(argv)
    root = Path(a.root)

    sdk = a.only in (None, "sdk")
    manifest_xml = get(a.sdk_repo + "repository2-3.xml") if sdk else None
    pkgs = remote_packages(manifest_xml) if manifest_xml else {}
    chosen, missing = [], []
    for alts in sdk_wanted(root) if sdk else []:
        path = next((p for p in alts if p in pkgs), None)
        (chosen.append(path) if path else missing.append(" или ".join(alts)))
    if a.list:
        clt, _ = command_line_tools(root)
        total = 0
        print(f"command-line tools: {clt}")
        for p in chosen:
            name, size, _ = pkgs[p]
            total += size
            print(f"  {p:32} {name:48} {size / 2**20:8.1f} МБ")
        print(f"  всего SDK (без command-line tools): {total / 2**30:.2f} ГБ; Maven — ещё около 0,3–0,5 ГБ")
        for m in missing:
            print(f"  ! нет в манифесте: {m}")
        return 1 if missing else 0
    if not a.out:
        ap.error("укажите клон build_tools (или --list)")
    out = Path(a.out).expanduser()
    if a.push and not (out / ".git").exists():
        raise SystemExit(f"✗ {out} — не клон git: git clone https://github.com/dpsmpx/build_tools {out}")
    if missing:
        raise SystemExit("✗ нет в манифесте Google: " + "; ".join(missing))
    out.mkdir(parents=True, exist_ok=True)
    (out / "README.md").write_text(README, encoding="utf-8")
    (out / ".gitattributes").write_text("* -text\n", encoding="utf-8")
    store = Store(out, root)

    try:
        if sdk:
            (out / "sdk").mkdir(exist_ok=True)
            (out / "sdk/repository2-3.xml").write_bytes(manifest_xml)
            clt, clt_sha = command_line_tools(root)
            jobs = [("cmdline-tools", clt, None, None, clt_sha)] + [(p, *pkgs[p], None) for p in chosen]
            for path, name, size, sha1, sha256 in jobs:
                rel = "sdk/" + name
                if not store.have(rel):
                    print(f"SDK: {path} — {name}" + (f" ({size / 2**20:.0f} МБ)" if size else ""), flush=True)
                    got = fetch(a.sdk_repo + name, out / rel, size=size, sha1=sha1, sha256=sha256)
                    if got is None:
                        raise SystemExit(f"✗ нет {a.sdk_repo + name}")
                    store.add(rel, got, (out / rel).stat().st_size, "SHA-256 из хука" if sha256 else "SHA-1 из repository2-3.xml")
                store.m["sdk"][path] = rel
                publish(out, store, f"SDK: {path}", a.push)

        if a.only in (None, "files"):
            store.m.setdefault("pins", {})
            for url, sha in pinned(root):
                rel = "files/" + url.rsplit("/", 1)[-1]
                if not store.have(rel):
                    print(f"Файл сборки: {url}", flush=True)
                    got = fetch(url, out / rel, sha256=sha)
                    if got is None:
                        raise SystemExit(f"✗ нет {url}")
                    store.add(rel, got, (out / rel).stat().st_size, "SHA-256, закреплённый в сборке")
                store.m["pins"][url] = rel
            publish(out, store, "Файлы сборки: закреплённые загрузки dpai/", a.push)

        if a.only in (None, "maven"):
            crawler = Crawler(store, a.google_maven, a.central)
            n = 0
            for g, art, v, name, sha256 in dppass_artifacts(root):
                crawler.seen.add((g, art, v))  # граф этих — уже в сверке DPpass
                rel = "maven2/" + gav_dir(g, art, v) + name
                if not store.have(rel):
                    got = fetch(a.google_maven + gav_dir(g, art, v) + name, out / rel, sha256=sha256)
                    if got is None:
                        raise SystemExit(f"✗ нет в Google Maven: {g}:{art}:{v} {name}")
                    store.add(rel, got, (out / rel).stat().st_size, "SHA-256 из dppass/gradle/verification-metadata.xml")
                    n += 1
            print(f"Maven: сверка DPpass — новых файлов {n}", flush=True)
            publish(out, store, "Maven: Google Maven из сверки DPpass", a.push)

            agp, bom, act = catalog_version(root, "agp"), catalog_version(root, "compose-bom"), catalog_version(root, "activity-compose")
            crawler.bom("androidx.compose", "compose-bom", bom)
            m3 = sorted(crawler.forced.get(("androidx.compose.material3", "material3"), ()))
            if not m3:
                raise SystemExit(f"✗ в compose-bom {bom} нет material3")
            crawler.want("com.android.application", "com.android.application.gradle.plugin", agp, "плагин")
            crawler.want("androidx.compose.material3", "material3", m3[-1], "dpai/android")
            crawler.want("androidx.activity", "activity-compose", act, "dpai/android")
            crawler.agp_runtime(agp)
            for coord in a.extra:
                crawler.extra(coord)
            crawler.run()
            store.m["maven_skipped"] = sorted(set(crawler.skipped))
            print(f"Maven: граф dpai/android — новых файлов {crawler.files}; пропущено {len(store.m['maven_skipped'])}", flush=True)
            publish(out, store, "Maven: граф dpai/android (Compose, плагин Android)", a.push)
    except Mismatch as e:
        store.save()
        raise SystemExit(f"✗ {e} — файл удалён; повторите запуск") from None
    files = store.m["files"]
    print(f"✓ готово: файлов {len(files)}, {sum(e['size'] for e in files.values()) / 2**30:.2f} ГБ"
          + ("; запушено" if a.push else f"; каталог {out}"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
