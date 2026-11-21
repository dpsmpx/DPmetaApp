package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.LocalStreamHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.files.LocalTree
import com.dpsmpx.dpai.files.TextFile
import com.dpsmpx.dpai.lsp.DocumentUri
import com.dpsmpx.dpai.lsp.KotlinLsp
import com.dpsmpx.dpai.lsp.LanguageSession
import com.dpsmpx.dpai.lsp.LineIndex
import com.dpsmpx.dpai.lsp.LspClient
import com.dpsmpx.dpai.lsp.LspDiagnostic
import com.dpsmpx.dpai.lsp.LspSeverity
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.File
import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Настоящий Android-шаблон мастера: SDK → семантика → правка нескольких файлов → debug APK. */
class KotlinAndroidLiveTest {
    private fun <T> ok(result: Outcome<T>): T = when (result) {
        is Outcome.Success -> result.value
        is Outcome.Failure -> error(result.toString())
    }

    private fun hostEnvironment(): Map<String, String> = System.getenv().filterKeys {
        it in setOf("PATH", "HOME", "TMPDIR", "GRADLE_USER_HOME", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy", "JAVA_TOOL_OPTIONS")
    }

    private fun cipher(): DraftCipher = object : DraftCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun seal(record: String, bytes: ByteArray): Outcome<ByteArray> {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(record.toByteArray()) }
            return Outcome.success(c.iv + c.doFinal(bytes))
        }
        override fun open(record: String, bytes: ByteArray): Outcome<ByteArray> = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12)); updateAAD(record.toByteArray()) }
            Outcome.success(c.doFinal(bytes, 12, bytes.size - 12))
        } catch (e: GeneralSecurityException) { error("копия тестовой правки не открылась: $e") }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun generatedAndroidProjectHasSdkSemanticsAndStillBuildsAfterWorkspaceRename(@TempDir dir: File) {
        assumeTrue(System.getProperty("dpai.test.kotlinAndroid") == "true", "живой Android-сценарий не включён")
        val home = System.getProperty("dpai.test.kotlinlsp")
        val java = System.getProperty("dpai.test.kotlinjava")
        val gradleJava = System.getProperty("dpai.test.gradlejava")
        val sdk = System.getenv("ANDROID_HOME")
        assertNotNull(home); assertNotNull(java); assertNotNull(gradleJava); assertNotNull(sdk)
        val platformPresent = File(sdk, "platforms").listFiles().orEmpty().any { folder ->
            val properties = File(folder, "source.properties")
            if (!File(folder, "android.jar").isFile || !properties.isFile) false else {
                val metadata = Properties().apply { properties.inputStream().use(::load) }
                metadata.getProperty("AndroidVersion.ApiLevel") in setOf("${NewProject.COMPILE_SDK}", "${NewProject.COMPILE_SDK}.0")
            }
        }
        assertTrue(platformPresent, "SDK API ${NewProject.COMPILE_SDK} не найден в $sdk")
        val repo = File(checkNotNull(System.getProperty("dpai.repoRoot")))
        val wrapper = NewProject.GradleWrapper(File(repo, "gradlew").readBytes(), File(repo, "gradlew.bat").readBytes(),
            File(repo, "gradle/wrapper/gradle-wrapper.jar").readBytes(), File(repo, "gradle/wrapper/gradle-wrapper.properties").readBytes())
        val spec = NewProject.Spec(NewProject.Template.KOTLIN_ANDROID, "android-live", "demo.ide")
        for (file in ok(NewProject.files(spec, wrapper))) {
            File(dir, file.path).apply { parentFile.mkdirs(); writeBytes(file.bytes); if (file.executable) assertTrue(setExecutable(true)) }
        }
        File(dir, "local.properties").writeText("sdk.dir=${sdk.replace("\\", "\\\\")}\n")
        val source = "app/src/main/kotlin/demo/ide/Probe.kt"
        val helper = "app/src/main/kotlin/demo/ide/Helpers.kt"
        var text = "package demo.ide\n\nimport android.content.Context\n\nfun caption(context: Context): String = decorate(context.packageName)\n"
        File(dir, source).writeText(text)
        File(dir, helper).writeText("package demo.ide\n\nfun decorate(value: String): String = value.uppercase()\n")
        val arch = if (System.getProperty("os.arch") == "aarch64") "aarch64" else "x86_64"
        val command = ok(KotlinLsp.command(File(home, "product-info.json").readText(), File(home, "bin/intellij-server.vmoptions").readText(),
            home, java, File(dir, ".kls").path, arch, heapMb = 1536))
        val environment = hostEnvironment() + KotlinLsp.environment(sdk) + ("JAVA_HOME" to gradleJava)
        val process = ok(LocalStreamHost().open(ProcessSpec(command, workDir = dir.path, environment = environment)))
        val client = LspClient(process.stdout, process.stdin, DocumentUri.ofPath(dir.path), object : LspClient.Listener {
            override fun diagnostics(uri: String, version: Int?, items: List<LspDiagnostic>) = Unit
            override fun stopped(reason: Diagnostic) = Unit
        })
        val session = LanguageSession(client, pull = setOf(client))
        val tree = LocalTree(dir.toPath())
        val uri = DocumentUri.ofPath(File(dir, source).path)
        try {
            ok(client.start(2.minutes))
            ok(session.open(uri, "kotlin", text.replace("packageName", "missingStudioMember")))
            var errors = emptyList<LspDiagnostic>()
            repeat(90) {
                if (errors.none { "missingStudioMember" in it.message }) {
                    errors = ok(client.pullDiagnostics(uri)).orEmpty()
                    if (errors.none { "missingStudioMember" in it.message }) CountDownLatch(1).await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(errors.any { "missingStudioMember" in it.message }, "Gradle/Android импорт не дал ожидаемой диагностики: $errors")
            ok(session.open(uri, "kotlin", text))
            val valid = ok(client.pullDiagnostics(uri)).orEmpty()
            assertFalse(valid.any { it.severity == LspSeverity.ERROR }, valid.toString())
            // Реальное отложенное дополнение класса SDK/JDK вместе с импортом.
            val unfinished = text + "\nfun today(): LocalDa = java.time.LocalDate.now()\n"
            ok(session.open(uri, "kotlin", unfinished))
            val offset = unfinished.indexOf("LocalDa =") + "LocalDa".length
            val offered = ok(session.completion(uri, LineIndex(unfinished).position(offset))).items
            val imported = com.dpsmpx.dpai.lsp.Assist.filter(offered, "LocalDa", 24).firstOrNull { it.label.startsWith("LocalDate") }
            assertNotNull(imported, "служба не предложила LocalDate вне импортов")
            val resolved = ok(session.resolveCompletion(imported))
            assertFalse(resolved.unsupported, "служба требует неподдерживаемую команду/snippet: $resolved")
            assertEquals(text, File(dir, source).readText(), "команда службы не должна записывать исходный файл")
            text = ok(com.dpsmpx.dpai.lsp.Assist.complete(unfinished, offset, resolved)).text
            assertTrue(text.contains("import java.time.LocalDate") || text.contains("fun today(): java.time.LocalDate"), text)
            File(dir, source).writeText(text)
            ok(session.open(uri, "kotlin", text))
            val index = LineIndex(text)
            val at = index.position(text.indexOf("context.packageName") + "context.".length)
            assertTrue(ok(session.completion(uri, at, '.')).items.any { it.label.startsWith("packageName") || it.label.startsWith("getPackageName") })
            val contextAt = index.position(text.indexOf("Context):") + 1)
            assertTrue(assertNotNull(ok(session.hover(uri, contextAt))).text.contains("Context"))
            val nameAt = index.position(text.indexOf("decorate(") + 1)
            assertTrue(ok(session.definition(uri, nameAt)).any { it.uri == DocumentUri.ofPath(File(dir, helper).path) })
            val plan = ok(WorkspaceRename.prepare(session, dir.path, { path ->
                tree.read(path, TextFile.MAX_EDIT_BYTES).let { result ->
                    when (result) {
                        is Outcome.Failure -> result
                        is Outcome.Success -> TextFile.decode(result.value)
                    }
                }
            }) { session.rename(uri, nameAt, "renderName") })
            assertEquals(setOf(source, helper), plan.files.map { it.path }.toSet())
            val store = WorkspaceChangeStore(NioVfs(File(dir, ".copies").toPath()), cipher())
            ok(WorkspaceChanges(store).apply("android-live", dir.path, tree, plan))
            assertFalse(store.blocked("android-live"))
            assertTrue(File(dir, source).readText().contains("renderName(context.packageName)"))
            assertTrue(File(dir, helper).readText().contains("fun renderName("))
            ok(session.filesChanged(plan.files.associate { it.uri to it.after }))
        } finally {
            session.stop()
            process.stop(5.seconds)
        }
        // Сборка после закрытия backend: не нагружаем одновременно LSP и компилятор.
        val built = ok(LocalProcessHost().run(ProcessSpec(listOf(File(dir, "gradlew").path, ":app:assembleDebug", "--no-daemon", "--no-parallel", "--max-workers=1"),
            workDir = dir.path, environment = environment), 10.minutes))
        val report = File(repo, "ui/build/reports/kotlin-android-live").apply { mkdirs() }
        File(report, "build.log").writeText((built.stdout + built.stderr).joinToString("\n"))
        assertEquals(0, built.exitCode, (built.stdout + built.stderr).takeLast(60).joinToString("\n"))
        val apk = File(dir, "app/build/outputs/apk/debug/app-debug.apk")
        assertTrue(apk.isFile && apk.length() > 0)
        apk.copyTo(File(report, "fixture-debug.apk"), overwrite = true)
    }
}
