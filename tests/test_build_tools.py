"""Самотест tools/termux/build-tools.py на синтетических репозиториях (file://): манифест SDK с архивами,
Google Maven с POM и Gradle module metadata (BOM, available-at, ограничения, свойства).
Настоящий dl.google.com облачной сессии закрыт — здесь проверяется логика, а не сеть."""
import hashlib
import importlib.util
import io
import json
import os
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location("build_tools", ROOT / "tools" / "termux" / "build-tools.py")
bt = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bt)
_ispec = importlib.util.spec_from_file_location("build_tools_install", ROOT / "tools" / "build_tools_install.py")
inst = importlib.util.module_from_spec(_ispec)
_ispec.loader.exec_module(inst)

# Поддельный sdkmanager: пишет вызовы и адрес репозитория, «ставит» пакеты и скачивает архив NDK с
# локального сервера установщика — сумма в журнале проверяет, что архив отдан из частей целым.
FAKE_SDKMANAGER = """#!/bin/sh
root=""
for a in "$@"; do case "$a" in --sdk_root=*) root="${a#--sdk_root=}";; esac; done
echo "$SDK_TEST_BASE_URL $*" >> "$root.log"
for a in "$@"; do case "$a" in --*) ;; *) d="$root/$(printf %s "$a" | tr ';' '/')"; mkdir -p "$d"; echo x > "$d/package.xml";; esac; done
case "$*" in *ndk*) python3 -c "import sys, urllib.request, hashlib; print('ndk', hashlib.sha256(urllib.request.urlopen(sys.argv[1]).read()).hexdigest())" "${SDK_TEST_BASE_URL}ndk-28.2.1.zip" >> "$root.log";; esac
"""


def zipped(name: str, size: int) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
        z.writestr(name, os.urandom(size))
    return buf.getvalue()


def put(base: Path, rel: str, data) -> bytes:
    p = base / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    data = data.encode() if isinstance(data, str) else data
    p.write_bytes(data)
    return data


def pom(g, a, v, deps=(), packaging="jar", props="", managed=(), parent=None):
    d = "".join(f"<dependency><groupId>{dg}</groupId><artifactId>{da}</artifactId>"
                + (f"<version>{dv}</version>" if dv else "") + (f"<scope>{sc}</scope>" if sc else "")
                + "</dependency>" for dg, da, dv, sc in deps)
    m = "".join(f"<dependency><groupId>{dg}</groupId><artifactId>{da}</artifactId><version>{dv}</version></dependency>"
                for dg, da, dv in managed)
    par = f"<parent><groupId>{parent[0]}</groupId><artifactId>{parent[1]}</artifactId><version>{parent[2]}</version></parent>" if parent else ""
    return (f'<project xmlns="http://maven.apache.org/POM/4.0.0">{par}<groupId>{g}</groupId><artifactId>{a}</artifactId>'
            f"<version>{v}</version><packaging>{packaging}</packaging><properties>{props}</properties>"
            f"<dependencyManagement><dependencies>{m}</dependencies></dependencyManagement>"
            f"<dependencies>{d}</dependencies></project>")


class BuildTools(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="bt-"))
        self.root, self.sdk, self.maven, self.central = (self.tmp / n for n in ("root", "sdk", "maven", "central"))
        self.out = self.tmp / "build_tools"
        # клон DPmetaApp: версии — из файлов сборки
        put(self.root, "dpai/android/build.gradle.kts",
            'android {\n    compileSdk = 37\n    ndkVersion = "28.2.1"\n    externalNativeBuild {\n        cmake {\n            version = "3.22.1"\n        }\n    }\n}\n')
        self.rootfs = put(self.tmp / "cdn", "alpine-minirootfs.tar.gz", b"rootfs" * 50)
        put(self.root, "dpai/android/linux.gradle.kts",
            f'val minirootfs = "{(self.tmp / "cdn" / "alpine-minirootfs.tar.gz").as_uri()}" to\n'
            f'    "{hashlib.sha256(self.rootfs).hexdigest()}"\n')
        put(self.root, "dpai/gradle/libs.versions.toml", 'agp = "9.4.1"\ncompose-bom = "2026.09.00"\nactivity-compose = "1.13.0"\n')
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("cmdline-tools/bin/sdkmanager", FAKE_SDKMANAGER)
        clt = buf.getvalue()
        put(self.sdk, "clt.zip", clt)
        put(self.root, ".claude/hooks/session-start.sh",
            f'        CLT_ZIP="clt.zip"\n        CLT_SHA256="{hashlib.sha256(clt).hexdigest()}"\n')
        # манифест SDK: у NDK 28 — два архива (Linux и macOS), берётся Linux
        pkgs = {"platforms;android-37.0": 2000, "platforms;android-30": 900, "build-tools;37.0.0": 700,
                "platform-tools": 500, "ndk;28.2.1": 5000, "cmake;3.22.1": 400, "platforms;android-36": 800,
                "build-tools;36.0.0": 600, "ndk;30.0.1": 300, "emulator": 100}
        xml = []
        for path, n in pkgs.items():
            name = path.replace(";", "-") + ".zip"
            data = put(self.sdk, name, zipped("x", n))
            arch = (f"<archive><complete><size>{len(data)}</size><checksum type=\"sha1\">{hashlib.sha1(data).hexdigest()}</checksum>"
                    f"<url>{name}</url></complete><host-os>linux</host-os></archive>")
            if path == "ndk;28.2.1":
                arch = ("<archive><complete><size>1</size><checksum type=\"sha1\">00</checksum><url>mac.zip</url></complete>"
                        "<host-os>macosx</host-os></archive>") + arch
            xml.append(f'<remotePackage path="{path}"><archives>{arch}</archives></remotePackage>')
        put(self.sdk, "repository2-3.xml",
            '<sdk:sdk-repository xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/03">' + "".join(xml) + "</sdk:sdk-repository>")
        # Google Maven
        M = self.maven
        self.gradle_jar = put(M, "com/android/tools/build/gradle/9.4.1/gradle-9.4.1.jar", b"agp")
        put(M, "com/android/tools/build/gradle/9.4.1/gradle-9.4.1.module", json.dumps({"variants": [
            {"name": "runtimeElements", "attributes": {"org.gradle.usage": "java-runtime"},
             "files": [{"url": "gradle-9.4.1.jar", "sha256": hashlib.sha256(self.gradle_jar).hexdigest()}]}]}))
        put(M, "com/android/application/com.android.application.gradle.plugin/9.4.1/com.android.application.gradle.plugin-9.4.1.pom",
            pom("com.android.application", "com.android.application.gradle.plugin", "9.4.1",
                deps=[("com.android.tools.build", "gradle", "9.4.1", None)], packaging="pom"))
        put(M, "androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom",
            pom("androidx.compose", "compose-bom", "2026.09.00", packaging="pom",
                managed=[("androidx.compose.material3", "material3", "1.5.0"), ("androidx.compose.ui", "ui", "1.10.0"),
                         ("androidx.compose.ui", "ui-test", "1.10.0")]))
        put(M, "androidx/compose/material3/material3/1.5.0/material3-1.5.0.module", json.dumps({"variants": [
            {"name": "metadataApiElements", "attributes": {"org.jetbrains.kotlin.platform.type": "common", "org.gradle.usage": "kotlin-metadata"},
             "files": [{"url": "material3-metadata-1.5.0.jar"}]},
            {"name": "androidRelease", "attributes": {"org.jetbrains.kotlin.platform.type": "androidJvm"},
             "available-at": {"url": "../../material3-android/1.5.0/material3-android-1.5.0.module",
                              "group": "androidx.compose.material3", "module": "material3-android", "version": "1.5.0"}}]}))
        aar = b"m3-android-aar"
        put(M, "androidx/compose/material3/material3-android/1.5.0/material3-android-1.5.0.aar", aar)
        put(M, "androidx/compose/material3/material3-android/1.5.0/material3-android-1.5.0.module", json.dumps({"variants": [
            {"name": "releaseRuntimeElements", "attributes": {"org.jetbrains.kotlin.platform.type": "androidJvm"},
             "files": [{"url": "material3-android-1.5.0.aar", "sha256": hashlib.sha256(aar).hexdigest()}],
             "dependencies": [{"group": "androidx.compose.ui", "module": "ui", "version": {"requires": "1.9.0"}},
                              {"group": "org.jetbrains.kotlin", "module": "kotlin-stdlib", "version": {"requires": "2.0"}},
                              {"group": "androidx.annotation", "module": "annotation", "version": {"requires": "[1.0,2.0)"}}],
             "dependencyConstraints": [{"group": "androidx.compose.material3", "module": "material3-window", "version": {"requires": "1.5.0"}}]},
            {"name": "sources", "attributes": {"org.gradle.category": "documentation"}, "files": [{"url": "sources.jar"}]}]}))
        for v in ("1.9.0", "1.10.0"):  # 1.9.0 — по зависимости, 1.10.0 — версия BOM
            put(M, f"androidx/compose/ui/ui/{v}/ui-{v}.pom", pom("androidx.compose.ui", "ui", v, packaging="aar"))
            put(M, f"androidx/compose/ui/ui/{v}/ui-{v}.aar", b"ui" + v.encode())
        put(M, "androidx/activity/activity-compose/1.13.0/activity-compose-1.13.0.pom",
            pom("androidx.activity", "activity-compose", "1.13.0", packaging="aar", props="<core.version>1.17.0</core.version>",
                parent=("androidx.activity", "activity-parent", "1"),
                deps=[("androidx.core", "core", "${core.version}", "compile"), ("androidx.test", "runner", "1.0", "test"),
                      ("org.jetbrains.kotlin", "kotlin-stdlib", "2.0", None)]))
        put(M, "androidx/activity/activity-compose/1.13.0/activity-compose-1.13.0.aar", b"activity")
        put(M, "androidx/activity/activity-parent/1/activity-parent-1.pom", pom("androidx.activity", "activity-parent", "1", packaging="pom"))
        put(M, "androidx/core/core/1.17.0/core-1.17.0.pom", pom("androidx.core", "core", "1.17.0", packaging="aar",
                                                             deps=[("androidx.arch.core", "core-common", "[2.1.0]", None)]))
        put(M, "androidx/arch/core/core-common/2.1.0/core-common-2.1.0.pom", pom("androidx.arch.core", "core-common", "2.1.0"))
        put(M, "androidx/arch/core/core-common/2.1.0/core-common-2.1.0.jar", b"cc")
        # во время сборки плагин Android берёт lint (мажор + 23) и aapt2 под Linux — не по графу
        put(M, "com/android/tools/build/aapt2/maven-metadata.xml",
            "<metadata><versioning><versions><version>9.4.1-100</version><version>9.4.1-111</version>"
            "<version>9.5.0-200</version></versions></versioning></metadata>")
        put(M, "com/android/tools/build/aapt2/9.4.1-111/aapt2-9.4.1-111.pom", pom("com.android.tools.build", "aapt2", "9.4.1-111"))
        put(M, "com/android/tools/build/aapt2/9.4.1-111/aapt2-9.4.1-111-linux.jar", b"aapt2-linux")
        put(M, "com/android/tools/lint/lint-gradle/32.4.1/lint-gradle-32.4.1.pom",
            pom("com.android.tools.lint", "lint-gradle", "32.4.1", deps=[("com.android.tools.lint", "lint-api", "32.4.1", None)]))
        put(M, "com/android/tools/lint/lint-gradle/32.4.1/lint-gradle-32.4.1.jar", b"lg")
        put(M, "com/android/tools/lint/lint-api/32.4.1/lint-api-32.4.1.pom", pom("com.android.tools.lint", "lint-api", "32.4.1"))
        put(M, "com/android/tools/lint/lint-api/32.4.1/lint-api-32.4.1.jar", b"la")
        put(M, "androidx/core/core/1.17.0/core-1.17.0.aar", b"core" * 100)

    def run_tool(self, *extra):
        url = lambda p: p.as_uri() + "/"  # noqa: E731
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = bt.main([*extra, "--root", str(self.root), "--sdk-repo", url(self.sdk),
                          "--google-maven", url(self.maven), "--central", url(self.central)])
        return rc, buf.getvalue()

    def test_collects_sdk_and_google_maven_with_checks(self):
        old = bt.PART
        bt.PART = 2048  # маленькая часть — чтобы проверить разрезание
        try:
            rc, log = self.run_tool(str(self.out))
        finally:
            bt.PART = old
        self.assertEqual(rc, 0, log)
        m = json.loads((self.out / "MANIFEST.json").read_text())
        self.assertEqual(m["schema"], "build-tools/1")
        # SDK: пакеты сборки dpai/android, без лишнего (emulator, пакеты чужих проектов), Linux-архив NDK;
        # большой — частями
        self.assertEqual(sorted(m["sdk"]), sorted(["cmdline-tools", "platforms;android-37.0", "platforms;android-30",
                                                   "build-tools;37.0.0", "platform-tools", "ndk;28.2.1", "cmake;3.22.1"]))
        ndk = m["files"]["sdk/ndk-28.2.1.zip"]
        self.assertTrue(ndk["parts"] and not (self.out / "sdk/ndk-28.2.1.zip").exists())
        whole = b"".join((self.out / p["path"]).read_bytes() for p in ndk["parts"])
        self.assertEqual(hashlib.sha256(whole).hexdigest(), ndk["sha256"])
        self.assertTrue((self.out / "sdk/repository2-3.xml").is_file())
        self.assertIn("SHA-256 из хука", m["files"]["sdk/clt.zip"]["verified"])
        # Maven: граф Compose и плагина; чужие группы, документация, metadata и тесты — нет
        files = set(m["files"])
        for rel in ("com/android/tools/build/gradle/9.4.1/gradle-9.4.1.jar",
                    "com/android/application/com.android.application.gradle.plugin/9.4.1/com.android.application.gradle.plugin-9.4.1.pom",
                    "androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom",
                    "androidx/compose/material3/material3-android/1.5.0/material3-android-1.5.0.aar",
                    "androidx/compose/ui/ui/1.9.0/ui-1.9.0.aar", "androidx/compose/ui/ui/1.10.0/ui-1.10.0.aar",
                    "androidx/activity/activity-compose/1.13.0/activity-compose-1.13.0.aar",
                    "androidx/activity/activity-parent/1/activity-parent-1.pom",
                    "androidx/core/core/1.17.0/core-1.17.0.aar", "androidx/arch/core/core-common/2.1.0/core-common-2.1.0.jar",
                    "com/android/tools/build/aapt2/9.4.1-111/aapt2-9.4.1-111-linux.jar",
                    "com/android/tools/lint/lint-gradle/32.4.1/lint-gradle-32.4.1.jar",
                    "com/android/tools/lint/lint-api/32.4.1/lint-api-32.4.1.jar"):
            self.assertIn("maven2/" + rel, files)
        self.assertFalse(any("kotlin-stdlib" in f or "material3-metadata" in f or "sources.jar" in f
                             or "ui-test" in f or "runner" in f or "material3-window" in f for f in files), files)
        self.assertIn(".module", m["files"]["maven2/com/android/tools/build/gradle/9.4.1/gradle-9.4.1.jar"]["verified"])
        # закреплённая загрузка сборки — по закреплённой сумме
        self.assertEqual(list(m["pins"].values()), ["files/alpine-minirootfs.tar.gz"])
        self.assertEqual((self.out / "files/alpine-minirootfs.tar.gz").read_bytes(), self.rootfs)
        self.assertIn("закреплённый", m["files"]["files/alpine-minirootfs.tar.gz"]["verified"])
        self.assertIn(".module", m["files"]["maven2/androidx/compose/material3/material3-android/1.5.0/material3-android-1.5.0.aar"]["verified"])
        self.assertTrue(any("androidx.annotation:annotation:[1.0,2.0)" in s for s in m["maven_skipped"]))
        self.assertFalse(any("core-common" in s for s in m["maven_skipped"]), m["maven_skipped"])  # [2.1.0] — точная
        self.assertFalse(any("aapt2/9.4.1-100" in f or "aapt2/9.5.0" in f for f in files))  # только последняя сборка 9.4.1
        # повторный запуск ничего не качает заново
        rc, log = self.run_tool(str(self.out))
        self.assertEqual(rc, 0)
        self.assertNotIn("SDK:", log)
        self.assertIn("новых файлов 0", log)

    def test_wrong_checksum_stops_and_removes_file(self):
        put(self.sdk, "platform-tools.zip", zipped("y", 500))  # не та, что в манифесте
        with self.assertRaises(SystemExit) as e:
            self.run_tool(str(self.out), "--only", "sdk")
        self.assertIn("не сошлось", str(e.exception))
        self.assertFalse((self.out / "sdk/platform-tools.zip").exists())
        self.assertFalse((self.out / "sdk/platform-tools.zip.download").exists())

    def test_push_commits_each_package(self):
        import subprocess
        origin = self.tmp / "origin.git"
        subprocess.run(["git", "init", "-q", "--bare", str(origin)], check=True)
        subprocess.run(["git", "clone", "-q", str(origin), str(self.out)], check=True, capture_output=True)
        for k, v in (("user.name", "t"), ("user.email", "t@example.invalid"), ("commit.gpgsign", "false")):
            subprocess.run(["git", "-C", str(self.out), "config", k, v], check=True)
        rc, log = self.run_tool(str(self.out), "--push")
        self.assertEqual(rc, 0, log)
        msgs = subprocess.run(["git", "--git-dir", str(origin), "log", "--format=%s", "--all"],
                              capture_output=True, text=True, check=True).stdout.splitlines()
        self.assertIn("SDK: ndk;28.2.1", msgs)
        self.assertIn("Maven: граф dpai/android (Compose, плагин Android)", msgs)
        self.assertEqual(len(msgs), 9)  # 7 пакетов SDK, файлы сборки, граф dpai/android
        tracked = subprocess.run(["git", "-C", str(self.out), "ls-files"], capture_output=True, text=True).stdout
        self.assertNotIn(".download", tracked)

    def test_wrong_pinned_file_stops(self):
        put(self.tmp / "cdn", "alpine-minirootfs.tar.gz", b"other")
        with self.assertRaises(SystemExit) as e:
            self.run_tool(str(self.out), "--only", "files")
        self.assertIn("не сошлось", str(e.exception))
        self.assertFalse((self.out / "sdk").exists(), "--only files не трогает SDK")

    def built(self):
        old = bt.PART
        bt.PART = 2048
        try:
            rc, log = self.run_tool(str(self.out))
        finally:
            bt.PART = old
        self.assertEqual(rc, 0, log)
        return json.loads((self.out / "MANIFEST.json").read_text())

    def install(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = inst.main(["--clone", str(self.out), "--sdk", str(self.tmp / "android-sdk"),
                            "--gradle-home", str(self.tmp / "gradle")])
        return rc, buf.getvalue()

    def test_install_from_build_tools(self):
        m = self.built()
        rc, log = self.install()
        self.assertEqual(rc, 0, log)
        sdk, gradle = self.tmp / "android-sdk", self.tmp / "gradle"
        calls = (self.tmp / "android-sdk.log").read_text()
        self.assertIn("http://127.0.0.1:", calls)
        self.assertIn("--no_proxy --licenses", calls)
        self.assertIn(f"ndk {m['files']['sdk/ndk-28.2.1.zip']['sha256']}", calls)  # архив из частей — целый
        for path in m["sdk"]:
            if path != "cmdline-tools":
                self.assertTrue((sdk / path.replace(";", "/") / "package.xml").is_file(), path)
        self.assertEqual((gradle / "build-tools/maven2/androidx/core/core/1.17.0/core-1.17.0.aar").read_bytes(), b"core" * 100)
        init = (gradle / "init.d/build-tools-google-maven.gradle").read_text()
        self.assertIn((gradle / "build-tools/maven2").as_uri() + "/", init)
        self.assertIn('includeGroupByRegex("com\\\\.android(\\\\..*)?")', init)
        self.assertIn("gradleMetadata()", init)
        self.assertEqual((gradle / "caches/dpai-linux/alpine-minirootfs.tar.gz").read_bytes(), self.rootfs)
        # второй запуск: SDK не ставится заново
        rc, log = self.install()
        self.assertEqual(rc, 0, log)
        self.assertIn("всё уже стоит", log)

    def test_install_from_clone_without_checkout(self):
        """Клон --no-checkout (так клонирует сам установщик): файлы — из объектов git, вдвое меньше места."""
        import subprocess
        m = self.built()
        for args in (["init", "-q"], ["config", "user.name", "t"], ["config", "user.email", "t@example.invalid"],
                     ["config", "commit.gpgsign", "false"], ["add", "-A"], ["commit", "-q", "-m", "x"]):
            subprocess.run(["git", "-C", str(self.out), *args], check=True, capture_output=True)
        bare = self.tmp / "nocheckout"
        subprocess.run(["git", "clone", "-q", "--no-checkout", str(self.out), str(bare)], check=True, capture_output=True)
        self.assertFalse((bare / "MANIFEST.json").exists())
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = inst.main(["--clone", str(bare), "--sdk", str(self.tmp / "android-sdk"), "--gradle-home", str(self.tmp / "gradle")])
        self.assertEqual(rc, 0, buf.getvalue())
        calls = (self.tmp / "android-sdk.log").read_text()
        self.assertIn(f"ndk {m['files']['sdk/ndk-28.2.1.zip']['sha256']}", calls)
        self.assertEqual((self.tmp / "gradle/caches/dpai-linux/alpine-minirootfs.tar.gz").read_bytes(), self.rootfs)

    def test_installer_clones_itself_then_skips_without_clone(self):
        """Клона нет — установщик клонирует сам (--no-checkout), ставит и удаляет клон; второй запуск
        с той же меткой и тем же коммитом на «GitHub» (здесь — локальный репозиторий) не клонирует."""
        import subprocess
        self.built()
        for args in (["init", "-q"], ["config", "user.name", "t"], ["config", "user.email", "t@example.invalid"],
                     ["config", "commit.gpgsign", "false"], ["add", "-A"], ["commit", "-q", "-m", "x"]):
            subprocess.run(["git", "-C", str(self.out), *args], check=True, capture_output=True)
        clone = self.tmp / "bt-clone"
        argv = ["--clone", str(clone), "--repo", str(self.out), "--sdk", str(self.tmp / "android-sdk"),
                "--gradle-home", str(self.tmp / "gradle")]
        for expect in ("клонирую", "уже стоит"):
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = inst.main(argv)
            self.assertEqual(rc, 0, buf.getvalue())
            self.assertIn(expect, buf.getvalue())
            self.assertFalse(clone.exists(), "клон установщика удалён")
        self.assertNotIn("клонирую", buf.getvalue())

    def test_install_stops_on_tampered_part(self):
        m = self.built()
        part = self.out / m["files"]["sdk/ndk-28.2.1.zip"]["parts"][0]["path"]
        part.write_bytes(b"x" + part.read_bytes()[1:])
        rc, log = self.install()
        self.assertEqual(rc, 1, log)
        self.assertIn("не сошлась", log)
        self.assertFalse((self.tmp / "android-sdk.log").exists(), "sdkmanager не запускался")
        self.assertFalse((self.tmp / "gradle/build-tools").exists())

    def test_list_names_packages_without_download(self):
        rc, log = self.run_tool("--list")
        self.assertEqual(rc, 0, log)
        self.assertIn("ndk;28.2.1", log)
        self.assertNotIn("ndk;30.0.1", log)  # пакеты чужих проектов сборщик не знает (D-174)
        self.assertFalse(self.out.exists())


if __name__ == "__main__":
    unittest.main()
