package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.lsp.KotlinLsp
import com.dpsmpx.dpai.lsp.PythonServers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/** Компоненты приложения (D-192): синтетическая корневая система гостя. */
class AppComponentsTest {
    @TempDir
    lateinit var dir: Path

    private fun file(rel: String, text: String = "x") = dir.resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

    private fun rootfs(): Path {
        file("root/android-sdk/cmdline-tools/latest/source.properties", "Pkg.Desc=Android SDK Command-line Tools\nPkg.Revision=23.0\n")
        file("root/android-sdk/platforms/android-36/source.properties", "Pkg.Revision=2\n")
        file("root/android-sdk/build-tools/36.1.0/source.properties", "Pkg.Revision=36.1.0\n")
        file("root/android-sdk/build-tools/36.1.0/aapt2", "a".repeat(1000))
        file("root/tools/ide/kotlin-lsp/${KotlinLsp.PINNED_BUILD}/product-info.json", "{}")
        Files.createSymbolicLink(dir.resolve("root/tools/ide/kotlin-lsp/current"), dir.resolve("root/tools/ide/kotlin-lsp/${KotlinLsp.PINNED_BUILD}"))
        file("root/tools/ide/kotlin-lsp-system/index", "i".repeat(500))
        file("root/tools/ide/pyright/1.1.300/package/langserver.index.js", "js")
        file("root/tools/ide/kotlin/${KotlinRuntime.VERSION}/kotlinc/bin/kotlinc", "sh")
        file("root/tools/ide/projects/abc123/python/bin/python", "py")
        file("usr/local/lib/node_modules/@anthropic-ai/claude-code/package.json", """{"name":"@anthropic-ai/claude-code","version":"2.1.300"}""")
        file("usr/local/lib/node_modules/.package-lock.json", """{"packages":{"node_modules/@anthropic-ai/claude-code":{"version":"2.1.300","integrity":"sha512-AAAA"}}}""")
        file("root/tools/models/tiny.gguf", "g".repeat(2048))
        file("secret.txt", "токен")
        return dir
    }

    @Test
    fun scanFindsVersionsSumsAndSizes() {
        val list = AppComponents.scan(rootfs(), "aarch64").associateBy { it.id }
        val sdk = list["android-sdk"]!!
        assertEquals("23.0", sdk.version)
        assertEquals(AndroidSdk.CMDLINE_SHA256, sdk.checksum)
        assertTrue("platforms;android-36 (2)" in sdk.parts && "build-tools;36.1.0 (36.1.0)" in sdk.parts, sdk.parts.toString())
        assertTrue(sdk.size!! >= 1000)
        val lsp = list["kotlin-lsp"]!!
        assertEquals(KotlinLsp.PINNED_BUILD, lsp.version)
        assertFalse(lsp.outdated)
        assertEquals(KotlinLsp.archiveSha256("aarch64"), lsp.checksum)
        assertTrue("кеш и индексы" in lsp.parts)
        val pyright = list["pyright"]!!
        assertEquals("1.1.300", pyright.version)
        assertTrue(pyright.outdated, "стоит не та версия, что ставит эта сборка (${PythonServers.PYRIGHT_VERSION})")
        assertEquals(KotlinRuntime.SHA256, list["kotlinc"]!!.checksum)
        assertEquals(listOf("проектов: 1"), list["project-envs"]!!.parts)
        assertNull(list["project-envs"]!!.checksum, "суммы pip и Maven приложение не хранит — и не выдумывает")
        val claude = list["npm:@anthropic-ai/claude-code"]!!
        assertEquals("2.1.300", claude.version)
        assertEquals("sha512-AAAA", claude.checksum)
        assertIs<AppComponents.Remove.Npm>(claude.remove)
        assertNull(list["npm:@openai/codex"], "не поставлено — нет в списке")
        val model = list["model:tiny.gguf"]!!
        assertEquals(2048L, model.size)
        assertNull(model.checksum)
    }

    @Test
    fun symlinkInModelsIsNotFollowed() {
        val root = rootfs()
        val link = runCatching { Files.createSymbolicLink(root.resolve("root/tools/models/leak.gguf"), root.resolve("secret.txt")) }.isSuccess
        assumeTrue(link)
        assertNull(AppComponents.scan(root, "aarch64").firstOrNull { it.id == "model:leak.gguf" }, "ссылка — не модель")
    }

    @Test
    fun deleteRemovesTreeButNotLinkTargets() {
        val root = rootfs()
        Files.createSymbolicLink(root.resolve("root/tools/ide/kotlin-lsp/escape"), root.resolve("secret.txt"))
        val lsp = AppComponents.scan(root, "aarch64").first { it.id == "kotlin-lsp" }
        val freed = AppComponents.delete(root, lsp.remove as AppComponents.Remove.Tree)
        assertIs<Outcome.Success<Long>>(freed)
        assertTrue(freed.value >= 500)
        assertFalse(Files.exists(root.resolve("root/tools/ide/kotlin-lsp")))
        assertFalse(Files.exists(root.resolve("root/tools/ide/kotlin-lsp-system")))
        assertEquals("токен", Files.readString(root.resolve("secret.txt")), "цель ссылки не тронута")
        assertTrue(Files.exists(root.resolve("root/tools/ide/pyright")), "соседнее не тронуто")
    }

    @Test fun orphanedLegacyKotlinIndexIsVisibleAndDeletionKeepsOtherLanguagesAndProjects() {
        val id = "a".repeat(32)
        val base = "root/.cache/JetBrains/IntelliJServer/workspaces/$id/index"
        file("$base/kotlin-server/rocks/v241/data.sst", "i".repeat(200))
        file("$base/java-server/keep", "other language")
        file("root/projects/keep.kt", "source")
        val component = AppComponents.scan(dir, "aarch64").single { it.id == "kotlin-lsp" }
        assertEquals(200L, component.size)
        assertEquals(listOf("старые индексы Kotlin"), component.parts)
        assertIs<Outcome.Success<Long>>(AppComponents.delete(dir, component.remove as AppComponents.Remove.Tree))
        assertFalse(Files.exists(dir.resolve("$base/kotlin-server")))
        assertEquals("other language", Files.readString(dir.resolve("$base/java-server/keep")))
        assertEquals("source", Files.readString(dir.resolve("root/projects/keep.kt")))
        for (bad in listOf("/root/.cache/JetBrains/IntelliJServer/workspaces", "/$base", "/$base/java-server", "/$base/kotlin-server/../java-server")) {
            assertIs<Outcome.Failure>(AppComponents.delete(dir, AppComponents.Remove.Tree(listOf(bad))))
        }
    }

    @Test
    fun deleteRefusesOutsideComponentPlaces() {
        val root = rootfs()
        for (bad in listOf("/root", "/root/projects", "/root/tools/ide", "/root/tools/ide/", "/root/tools/ide/../../secret.txt", "/etc", "root/tools/ide/x", "/root/tools/ide//x")) {
            val r = AppComponents.delete(root, AppComponents.Remove.Tree(listOf(bad)))
            assertIs<Outcome.Failure>(r, bad)
            assertEquals(DiagCode.OPERATION_NOT_ALLOWED, r.errors.single().code, bad)
        }
        assertTrue(Files.exists(root.resolve("root/tools/ide/pyright")))
    }

    @Test
    fun summaryAndDetails() {
        val list = AppComponents.scan(rootfs(), "aarch64")
        val s = AppComponents.summary(list)
        assertTrue(s.startsWith("Компонентов: ${list.size} · занято"), s)
        assertTrue("не та версия, что ставит DP_IDE: 2" in s, "pyright и Claude Code: $s")
        val d = AppComponents.details(list.first { it.id == "pyright" }).toMap()
        assertEquals(PythonServers.PYRIGHT_VERSION, d["Ставит эта сборка DP_IDE"])
        assertEquals("неизвестна", AppComponents.details(list.first { it.id == "model:tiny.gguf" }).toMap()["Сумма"])
        assertTrue(AppComponents.summary(emptyList()).startsWith("Компонентов приложения пока нет"))
    }

    @Test
    fun symlinkedParentIsRefused() {
        val root = rootfs()
        // Гость подменил `~/tools/ide` ссылкой на каталог вне дома: ни список, ни удаление туда не идут.
        val outside = root.resolve("app-private/kotlin-lsp").also { Files.createDirectories(it) }
        Files.writeString(outside.resolve("key"), "ключ приложения")
        Files.move(root.resolve("root/tools/ide"), root.resolve("ide-old"))
        assumeTrue(runCatching { Files.createSymbolicLink(root.resolve("root/tools/ide"), root.resolve("app-private")) }.isSuccess)
        assertNull(AppComponents.scan(root, "aarch64").firstOrNull { it.id == "kotlin-lsp" })
        val r = AppComponents.delete(root, AppComponents.Remove.Tree(listOf("/root/tools/ide/kotlin-lsp")))
        assertIs<Outcome.Failure>(r)
        assertEquals("ключ приложения", Files.readString(outside.resolve("key")))
    }

    @Test
    fun npmUninstallOnlyForKnownPackages() {
        assertEquals(listOf("/bin/sh", "-lc", "npm uninstall -g \"\$1\"", "dpai-npm-uninstall", "@openai/codex"), AppComponents.npmUninstall("@openai/codex").valueOrNull())
        assertIs<Outcome.Failure>(AppComponents.npmUninstall("left-pad; rm -rf /"))
    }
}
