package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalStreamHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * kotlin-lsp против настоящей сборки (D-142, DPmetaApp:D-110): команда из
 * её `product-info.json` ([KotlinLsp.command]), Gradle-проект из двух файлов,
 * диагностика по запросу. Нужны `-Ddpai.test.kotlinlsp=<каталог сборки>`,
 * `-Ddpai.test.kotlinjava=<java 25>` и `-Ddpai.test.gradlejava=<JDK для
 * Gradle>`; импорт Gradle качает плагин Kotlin — нужна сеть. Без свойств
 * тест пропускается.
 */
class KotlinLspLiveTest {
    private fun hostEnvironment(): Map<String, String> = System.getenv().filterKeys { it in setOf("PATH", "HOME", "TMPDIR", "GRADLE_USER_HOME", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy", "JAVA_TOOL_OPTIONS") }

    private fun <T> ok(r: Outcome<T>): T = (r as? Outcome.Success)?.value ?: error("отказ: $r")

    private val main = """
        package demo

        data class Shape(val width: Int, val height: Int) {
            fun area(): Int = width * height
        }

        fun twice(value: Int): Int = value * 2

        fun main() {
            val shape = Shape(2, 3)
            val total = shape.area() + 1
            println(twice(total))
        }

    """.trimIndent()

    @Test
    fun officialServerOnGradleProject(@TempDir dir: File) {
        val home = System.getProperty("dpai.test.kotlinlsp")
        val java = System.getProperty("dpai.test.kotlinjava")
        val gradleJava = System.getProperty("dpai.test.gradlejava")
        assumeTrue(home != null && java != null && gradleJava != null, "dpai.test.kotlinlsp/kotlinjava/gradlejava не заданы")
        val kotlinVersion = checkNotNull(System.getProperty("dpai.test.kotlin.version"))
        val src = File(dir, "src/main/kotlin/demo").apply { mkdirs() }
        File(src, "Main.kt").writeText(main)
        File(src, "Helper.kt").writeText("package demo\n\nfun helper(x: Int): Int = twice(x) + 1\n")
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"demo\"\n")
        File(dir, "build.gradle.kts").writeText("plugins {\n    kotlin(\"jvm\") version \"$kotlinVersion\"\n}\n\nrepositories {\n    mavenCentral()\n}\n")
        File(dir, "gradle/wrapper").mkdirs()
        File(dir, "gradle/wrapper/gradle-wrapper.properties").writeText("distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.0-bin.zip\n")
        val arch = if (System.getProperty("os.arch") == "aarch64") "aarch64" else "x86_64"
        val command = ok(KotlinLsp.command(File(home!!, "product-info.json").readText(), File(home, "bin/intellij-server.vmoptions").readText(), home, java!!, File(dir, ".kls").path, arch, heapMb = 1536))
        assertTrue("--data-sharing=none" in command)
        val launch = KotlinLsp.launch(command, null)
        val process = ok(LocalStreamHost().open(ProcessSpec(launch.command, workDir = dir.path, environment = hostEnvironment() + launch.environment + ("JAVA_HOME" to gradleJava!!))))
        val client = LspClient(
            process.stdout, process.stdin, DocumentUri.ofPath(dir.path),
            object : LspClient.Listener {
                override fun diagnostics(uri: String, version: Int?, items: List<LspDiagnostic>) = Unit
                override fun stopped(reason: Diagnostic) = Unit
            }, initializationOptions = launch.initializationOptions,
        )
        val session = LanguageSession(client, pull = setOf(client))
        try {
            val info = ok(client.start(2.minutes))
            assertTrue(KotlinLsp.acceptsIndexDirectory(launch.initializationOptions, info.indexDirectory))
            assertTrue(info.capabilities.pullDiagnostics && info.capabilities.rename && info.capabilities.signatureHelp, info.toString())
            val uri = DocumentUri.ofPath(File(src, "Main.kt").path)
            val broken = main.replace("println(twice(total))", "println(twice(totl))")
            ok(session.open(uri, "kotlin", broken))
            // Импорт Gradle идёт в фоне; пока он не закончен, файл «вне проекта» и ошибки не видно.
            var errors = emptyList<LspDiagnostic>()
            repeat(60) {
                if (errors.isEmpty()) {
                    errors = session.pullDiagnostics(uri).values.mapNotNull { (it as? Outcome.Success)?.value }.flatten().filter { "totl" in it.message }
                    if (errors.isEmpty()) CountDownLatch(1).await(5_000, TimeUnit.MILLISECONDS)
                }
            }
            assertEquals(11, errors.firstOrNull()?.range?.start?.line, "ошибки нет после импорта: $errors")
            ok(session.open(uri, "kotlin", main))
            val index = LineIndex(main)
            val areaAt = index.position(main.indexOf("area() +") + 1)
            assertTrue(assertNotNull(ok(session.hover(uri, areaAt))).text.contains("area"))
            val dot = index.position(main.indexOf("shape.area") + "shape.".length)
            assertTrue(ok(session.completion(uri, dot, '.')).items.any { it.label.startsWith("width") })
            val line = assertNotNull(Assist.signature(ok(session.signatureHelp(uri, index.position(main.indexOf("twice(total") + "twice(".length)))))
            assertEquals("value: Int", line.label.substring(assertNotNull(line.parameter)))
            val twiceAt = index.position(main.indexOf("twice(total") + 1)
            val refs = ok(session.references(uri, twiceAt))
            assertTrue(refs.any { it.uri.endsWith("/Helper.kt") }, refs.toString())
            // Локальная — переименование в этом файле; функция с вызовом из Helper.kt — правка двух файлов: отказ.
            val totalAt = index.position(main.indexOf("total"))
            assertTrue(Assist.renameScope(uri, ok(session.declaration(uri, totalAt)) + ok(session.definition(uri, totalAt))) is Outcome.Success)
            assertEquals(main.replace("total", "sum"), ok(Assist.applyEdits(main, 0, ok(Assist.editsForFile(ok(session.rename(uri, totalAt, "sum")), uri)))).text)
            val wide = Assist.editsForFile(ok(session.rename(uri, twiceAt, "doubled")), uri)
            assertTrue(wide is Outcome.Failure && "Helper.kt" in wide.errors.single().message, wide.toString())
            assertEquals(emptyList(), ok(session.format(uri, 4, true)), "текст уже в стиле службы")
        } finally {
            session.stop()
            process.stop(5.seconds)
        }
    }
}
