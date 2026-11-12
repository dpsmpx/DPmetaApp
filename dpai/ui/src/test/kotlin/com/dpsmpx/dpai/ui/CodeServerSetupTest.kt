package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.lsp.KotlinLsp
import com.dpsmpx.dpai.lsp.PythonServers
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Команды установки и поиска языковых служб (D-142) — настоящим `sh` на машине теста. */
class CodeServerSetupTest {
    private data class Run(val code: Int, val out: String)

    private fun run(command: List<String>, path: String? = null): Run {
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        if (path != null) pb.environment()["PATH"] = path + ":" + pb.environment()["PATH"]
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        return Run(p.exitValue(), out)
    }

    @Test
    fun scriptsParse() {
        val scripts = listOf(
            CodeServerSetup.pyrightInstall(),
            (CodeServerSetup.kotlinInstall("263.7084.0", "aarch64") as Outcome.Success).value,
            CodeServerSetup.kotlinFilesCommand(),
            CodeServerSetup.locate("/p", "/p/a.kt"),
        )
        for (s in scripts) assertEquals(0, run(listOf("/bin/sh", "-n", "-c", s[2])).code, s[2])
    }

    /** Подменённый `curl` кладёт заранее собранный архив туда, куда просит сценарий. */
    private fun fakeCurl(dir: File, payload: File): String {
        val bin = File(dir, "bin").apply { mkdirs() }
        File(bin, "curl").apply {
            writeText("#!/bin/sh\nwhile [ \$# -gt 0 ]; do if [ \"\$1\" = -o ]; then cp '${payload.path}' \"\$2\"; exit 0; fi; shift; done\nexit 22\n")
            setExecutable(true)
        }
        return bin.path
    }

    @Test
    fun pyrightIsCheckedBeforeItIsUnpacked(@TempDir dir: File) {
        val tree = File(dir, "tree/package").apply { mkdirs() }
        File(tree, "langserver.index.js").writeText("// сервер\n")
        val archive = File(dir, "pyright.tgz")
        assertEquals(0, run(listOf("tar", "-czf", archive.path, "-C", File(dir, "tree").path, "package")).code)
        val sha = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
        val path = fakeCurl(dir, archive)
        val dest = File(dir, "studio/pyright/${PythonServers.PYRIGHT_VERSION}")
        val command = CodeServerSetup.pyrightInstall().toMutableList()
        assertEquals(listOf(CodeServerSetup.PYRIGHT_HOME, PythonServers.PYRIGHT_URL, PythonServers.PYRIGHT_SHA256), command.drop(4))
        // Чужая сумма: ничего не распаковано, причина названа.
        command[4] = dest.path
        command[6] = "0".repeat(64)
        val refused = run(command, path)
        assertTrue(refused.code != 0 && "DPAI_PYRIGHT_CHECKSUM" in refused.out, refused.out)
        assertFalse(dest.exists())
        command[6] = sha
        val done = run(command, path)
        assertTrue(done.code == 0 && "DPAI_PYRIGHT_OK" in done.out, done.out)
        assertTrue(File(dest, "package/langserver.index.js").isFile)
        assertEquals(listOf(PythonServers.PYRIGHT_VERSION), dest.parentFile.list()!!.toList(), "временный каталог убран")
    }

    @Test
    fun kotlinBuildAndCommandsAreChecked() {
        assertEquals("263.7084.0", CodeServerSetup.latestBuild(listOf("HTTP/2 302", "Location: https://github.com/Kotlin/kotlin-lsp/releases/tag/v263.7084.0", "")))
        assertNull(CodeServerSetup.latestBuild(listOf("HTTP/2 200")))
        assertNull(CodeServerSetup.latestBuild(listOf("location: https://github.com/Kotlin/kotlin-lsp/releases/tag/v1;reboot")))
        assertTrue(CodeServerSetup.kotlinInstall("1.2;rm", "aarch64") is Outcome.Failure)
        assertTrue(CodeServerSetup.kotlinInstall("263.7084.0", "mips") is Outcome.Failure)
        val install = (CodeServerSetup.kotlinInstall("263.7084.0", "aarch64") as Outcome.Success).value
        assertEquals(listOf("/root/.local/share/dpai/kotlin-lsp", "263.7084.0", KotlinLsp.archiveUrl("263.7084.0", "aarch64"), KotlinLsp.ROCKSDB_URL, KotlinLsp.ROCKSDB_SHA256, "librocksdbjni-linux-aarch64-musl.so"), install.drop(4))
        val (info, vm) = assertNotNull(CodeServerSetup.kotlinFiles(listOf("{\"launch\":[]}", "", "--DPAI-VMOPTIONS--", "-Xmx2048m")))
        assertEquals("{\"launch\":[]}\n", info)
        assertEquals("-Xmx2048m", vm)
        assertNull(CodeServerSetup.kotlinFiles(listOf("{}")))
    }

    @Test
    fun locateFindsProjectInterpreterAndAndroidBuild(@TempDir dir: File) {
        val project = File(dir, "app").apply { File(this, "app/src").mkdirs() }
        File(project, "settings.gradle.kts").writeText("rootProject.name = \"app\"\n")
        File(project, "app/build.gradle.kts").writeText("plugins {\n    id(\"com.android.application\")\n}\nandroid {\n    compileSdk = 36\n}\n")
        File(project, "build/build.gradle.kts").apply { parentFile.mkdirs() }.writeText("android { compileSdk = 99 }\n")
        val out = run(CodeServerSetup.locate(dir.path, File(project, "app/src/Main.kt").path))
        assertEquals(0, out.code, out.out)
        val found = assertNotNull(CodeServerSetup.parseLocated(out.out.lines()), out.out)
        assertEquals(project.path, found.root)
        assertTrue(found.androidProject)
        assertEquals(36, found.compileSdk, "каталог build/ не считается")
        assertTrue(found.arch!!.isNotEmpty())
        val plain = File(dir, "py").apply { mkdirs() }
        File(plain, "requirements.txt").writeText("requests\n")
        val py = assertNotNull(CodeServerSetup.parseLocated(run(CodeServerSetup.locate(dir.path, File(plain, "main.py").path)).out.lines()))
        assertEquals(plain.path, py.root)
        assertFalse(py.androidProject)
        assertNull(py.compileSdk)
        assertNull(CodeServerSetup.parseLocated(listOf("root=relative")))
    }

    /** Синтетический образец формата манифеста repository2-3, не копия файла Google. */
    private val manifest = """
        <?xml version="1.0" encoding="UTF-8"?>
        <sdk:sdk-repository xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/03" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <license id="android-sdk-license" type="text">Условия образца.</license>
          <license id="android-sdk-preview-license" type="text">Предварительные условия.</license>
          <channel id="channel-0">stable</channel>
          <channel id="channel-1">beta</channel>
          <remotePackage path="platforms;android-36">
            <revision><major>2</major></revision>
            <uses-license ref="android-sdk-license"/>
            <channelRef ref="channel-0"/>
            <archives><archive><complete><size>100</size><checksum type="sha1">${"a".repeat(40)}</checksum><url>platform-36_r02.zip</url></complete></archive></archives>
          </remotePackage>
          <remotePackage path="platforms;android-37.0">
            <uses-license ref="android-sdk-license"/>
            <channelRef ref="channel-0"/>
            <archives><archive><complete><size>200</size><checksum type="sha1">${"b".repeat(40)}</checksum><url>platform-37.0_r02.zip</url></complete></archive></archives>
          </remotePackage>
          <remotePackage path="platforms;android-38">
            <uses-license ref="android-sdk-preview-license"/>
            <channelRef ref="channel-1"/>
            <archives><archive><complete><size>300</size><checksum type="sha1">${"c".repeat(40)}</checksum><url>platform-38.zip</url></complete></archive></archives>
          </remotePackage>
          <remotePackage path="platforms;android-35">
            <uses-license ref="android-sdk-license"/>
            <channelRef ref="channel-0"/>
            <archives><archive><complete><size>1</size><checksum type="sha1">${"d".repeat(40)}</checksum><url>../../evil.zip</url></complete></archive></archives>
          </remotePackage>
        </sdk:sdk-repository>
    """.trimIndent()

    @Test
    fun androidPlatformComesFromTheStableChannelWithItsLicense() {
        val p36 = assertNotNull(CodeServerSetup.androidPlatform(manifest, 36))
        assertEquals(CodeServerSetup.AndroidPlatform("platforms;android-36", "android-36", "https://dl.google.com/android/repository/platform-36_r02.zip", "a".repeat(40), 100, "android-sdk-license", "Условия образца."), p36)
        assertEquals("android-37.0", assertNotNull(CodeServerSetup.androidPlatform(manifest, 37)).directory)
        assertNull(CodeServerSetup.androidPlatform(manifest, 38), "бета-канал не ставится")
        assertNull(CodeServerSetup.androidPlatform(manifest, 35), "архив с путём — отказ")
        assertNull(CodeServerSetup.androidPlatform("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>", 36))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", CodeServerSetup.licenseHash("abc"))
        val install = CodeServerSetup.androidInstall(p36)
        assertEquals(listOf(CodeServerSetup.ANDROID_SDK, p36.url, p36.sha1, "android-36", "android-sdk-license", CodeServerSetup.licenseHash("Условия образца.")), install.drop(4))
        assertEquals(0, run(listOf("/bin/sh", "-n", "-c", install[2])).code)
    }
}
