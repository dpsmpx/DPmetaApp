package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Описания служб Python и Kotlin (D-142): языки, настройки, команда kotlin-lsp по данным сборки. */
class LanguageServersTest {
    @Test
    fun languagesByFileName() {
        assertEquals(CodeLanguage.CPP, LanguageServers.language("src/a.cpp"))
        assertEquals(CodeLanguage.PYTHON, LanguageServers.language("tools/run.py"))
        assertEquals("python", LanguageServers.languageId("stubs/m.pyi"))
        assertEquals("python", LanguageServers.languageId("gui.pyw"))
        assertEquals(CodeLanguage.KOTLIN, LanguageServers.language("app/Main.kt"))
        assertEquals("kotlin", LanguageServers.languageId("build.gradle.kts"))
        assertNull(LanguageServers.language("README.md"))
        assertNull(LanguageServers.language("py"))
    }

    @Test
    fun pythonServicesShareRuffAndUseTheRunInterpreter() {
        val venv = "/root/.local/share/dpai/projects/k/python/bin/python"
        val pylsp = PythonServers.pylsp(venv)
        assertEquals(listOf("pylsp"), pylsp.command)
        val plugins = (pylsp.settings["pylsp"] as JsonObject)["plugins"] as JsonObject
        assertEquals(json(venv), (plugins["jedi"] as JsonObject)["environment"])
        // Замечания и формат — у ruff: свои у pylsp выключены, иначе одно замечание пришло бы дважды.
        for (name in listOf("pyflakes", "pycodestyle", "yapf", "autopep8")) assertEquals(json(false), (plugins[name] as JsonObject)["enabled"], name)
        assertEquals(JsonObject(emptyMap()), (((PythonServers.pylsp(null).settings["pylsp"] as JsonObject)["plugins"] as JsonObject)["jedi"]))
        val pyright = PythonServers.pyright("/root/.local/share/dpai/pyright/${PythonServers.PYRIGHT_VERSION}", venv)
        assertEquals(listOf("node", "/root/.local/share/dpai/pyright/1.1.414/package/langserver.index.js", "--stdio"), pyright.command)
        val python = pyright.settings["python"] as JsonObject
        assertEquals(json(venv), python["pythonPath"])
        assertEquals(json("openFilesOnly"), (python["analysis"] as JsonObject)["diagnosticMode"])
        assertEquals(null, (PythonServers.pyright("/x", null).settings["python"] as JsonObject)["pythonPath"])
        assertEquals(listOf("ruff", "server"), PythonServers.ruff().command)
        assertTrue(PythonServers.PYRIGHT_URL.startsWith("https://registry.npmjs.org/pyright/-/pyright-1.1.414.tgz"))
        assertEquals(64, PythonServers.PYRIGHT_SHA256.length)
    }

    @Test
    fun kotlinBuildComesFromTheReleasePageAddress() {
        assertEquals("263.7084.0", KotlinLsp.build("https://github.com/Kotlin/kotlin-lsp/releases/tag/v263.7084.0\r"))
        assertEquals("263.7084.0", KotlinLsp.build("/Kotlin/kotlin-lsp/releases/tag/263.7084.0"))
        assertNull(KotlinLsp.build("https://github.com/Kotlin/kotlin-lsp/releases/tag/v263.7084.0;rm -rf"))
        assertNull(KotlinLsp.build("https://github.com/Kotlin/kotlin-lsp/releases"))
        assertEquals(
            "https://download.jetbrains.com/language-server/kotlin-server/263.7084.0/kotlin-server-263.7084.0-aarch64.tar.gz",
            KotlinLsp.archiveUrl("263.7084.0", "aarch64"),
        )
        assertEquals("https://download.jetbrains.com/language-server/kotlin-server/263.7084.0/kotlin-server-263.7084.0.tar.gz", KotlinLsp.archiveUrl("263.7084.0", "x86_64"))
        assertFailsWith<IllegalArgumentException> { KotlinLsp.archiveUrl("../x", "aarch64") }
        assertEquals("librocksdbjni-linux-aarch64-musl.so", KotlinLsp.rocksDbLibrary("aarch64"))
        assertTrue(KotlinLsp.expired(7, "") && KotlinLsp.expired(1, "This build of intellij-server has expired") && !KotlinLsp.expired(0, ""))
    }

    /** Синтетический образец формата `product-info.json`, не копия файла сборки. */
    private val productInfo = """
        {"name":"kotlin-server","buildNumber":"1.2.3","launch":[
          {"os":"Linux","arch":"amd64","mainClass":"demo.Main","bootClassPathJarNames":["a.jar"],"additionalJvmArguments":["-Dx=1"]},
          {"os":"Linux","arch":"aarch64","mainClass":"demo.server.Main","bootClassPathJarNames":["platform-loader.jar","util-8.jar"],
           "additionalJvmArguments":["-Xbootclasspath/a:${'$'}IDE_HOME/lib/nio-fs.jar","-Djna.boot.library.path=${'$'}IDE_HOME/lib/jna/aarch64","--add-opens=java.base/java.lang=ALL-UNNAMED"]}
        ]}
    """.trimIndent()

    @Test
    fun kotlinCommandIsBuiltFromBuildDataForAlpineJdk() {
        val vm = "# комментарий\n-Xms128m\n-Xmx2048m\n\n-XX:+UseCompactObjectHeaders\n"
        val cmd = (KotlinLsp.command(productInfo, vm, "/opt/kls/", KotlinLsp.JAVA, "/root/.cache/kls", "aarch64", heapMb = 1536) as Outcome.Success).value
        assertEquals(
            listOf(
                "/usr/lib/jvm/java-25-openjdk/bin/java", "-Xlog:disable", "-Xlog:all=warning:stderr",
                "-Xms128m", "-XX:+UseCompactObjectHeaders", "-Xmx1536m",
                "-Xbootclasspath/a:/opt/kls/lib/nio-fs.jar", "-Djna.boot.library.path=/opt/kls/lib/jna/aarch64", "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-cp", "/opt/kls/lib/platform-loader.jar:/opt/kls/lib/util-8.jar", "demo.server.Main",
                "--stdio", "--data-sharing=none", "--system-path=/root/.cache/kls",
            ),
            cmd,
        )
        val x64 = (KotlinLsp.command(productInfo, vm, "/opt/kls", KotlinLsp.JAVA, "/s", "x86_64") as Outcome.Success).value
        assertTrue("-Xmx2048m" in x64 && "demo.Main" in x64 && "--data-sharing=none" in x64)
    }

    @Test
    fun foreignBuildDataThatCouldInjectIsRefused() {
        fun refused(info: String, vm: String = "-Xmx1g") = KotlinLsp.command(info, vm, "/opt/kls", KotlinLsp.JAVA, "/s", "aarch64") is Outcome.Failure
        assertTrue(refused(productInfo.replace("\"util-8.jar\"", "\"../../bin/sh.jar\"")))
        assertTrue(refused(productInfo.replace("\"util-8.jar\"", "\"/etc/x.jar\"")))
        assertTrue(refused(productInfo.replace("demo.server.Main", "demo.Main --eula=x")))
        assertTrue(refused(productInfo.replace("\"--add-opens=java.base/java.lang=ALL-UNNAMED\"", "\"demo.Other\"")))
        assertTrue(refused(productInfo, "-Xmx1g\nevil.Main"))
        assertTrue(refused(productInfo.replace("aarch64", "riscv64")))
        assertTrue(refused("не json"))
        assertTrue(KotlinLsp.command(productInfo, "", "/opt/kls", KotlinLsp.JAVA, "/s", "mips") is Outcome.Failure)
    }

    @Test
    fun kotlinLaunchPullsDiagnosticsAndGivesGradleAJdk() {
        val launch = KotlinLsp.launch(listOf("java"), "/root/android-sdk")
        assertTrue(launch.pull)
        assertEquals("/usr/lib/jvm/java-21-openjdk", launch.environment["JAVA_HOME"])
        assertEquals("/root/android-sdk", launch.environment["ANDROID_HOME"])
        assertEquals(null, KotlinLsp.launch(listOf("java"), null).environment["ANDROID_HOME"])
    }
}
