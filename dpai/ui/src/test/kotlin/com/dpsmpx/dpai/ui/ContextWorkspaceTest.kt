package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.contexts.ContextBinding
import com.dpsmpx.dpai.contexts.ContextMode
import com.dpsmpx.dpai.contexts.ContextProfile
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.LocalTree
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContextWorkspaceTest {
    @TempDir lateinit var dir: Path
    private val project = IdKinds.PROJECT.parse("prj_01J9ZQ3K8M7N6P5R4S3T2V1W0X").valueOrNull()!!
    private val tree get() = LocalTree(dir.resolve("source").also { it.toFile().mkdirs() })
    private fun file(path: String, text: String) { tree.write(path, text.toByteArray()).also { assertIs<Outcome.Success<*>>(it) } }
    private fun workspace(p: ContextProfile = ContextProfile(), source: FileTree = tree) =
        ContextWorkspace(source, p, ContextBinding(project, "target:root", "chat:human", p.revision))

    @Test fun selectionKeepsAncestorRulesButNeverReadsNeighbourSecretsOrUnrelatedCode() {
        file("AGENTS.md", "Правила корня")
        file("src/CLAUDE.md", "Правила модуля")
        file("src/A.kt", "class A")
        file("src/B.kt", "must not read")
        file(".env", "secret")
        val reads = arrayListOf<String>()
        val source = object : FileTree by tree { override fun read(path: String, maxBytes: Long) = tree.read(path, maxBytes).also { reads += path } }
        val w = workspace(ContextProfile(ContextMode.MINIMAL, listOf("src/A.kt")), source)
        val packet = w.packet("исправить A").valueOrNull()!!
        assertTrue(reads.containsAll(listOf("AGENTS.md", "src/CLAUDE.md", "src/A.kt")))
        assertFalse("src/B.kt" in reads); assertFalse(".env" in reads)
        assertIs<Outcome.Failure>(w.tree.read("src/B.kt", 1000)); assertIs<Outcome.Failure>(w.tree.write("src/B.kt", byteArrayOf()))
        assertIs<Outcome.Failure>(w.tree.write("AGENTS.md", byteArrayOf()))
        assertTrue(packet.stamps.containsKey("CLAUDE.md")); assertEquals(null, packet.stamps["CLAUDE.md"])
    }

    @Test fun fullAccessDoesNotDumpRepositoryAndExpansionCannotWidenHumanScope() {
        repeat(10) { file("unrelated/File$it.kt", "unrelated body $it") }
        val full = workspace(ContextProfile(ContextMode.FULL_ACCESS)).packet("unknown objective").valueOrNull()!!
        assertTrue(full.selection.included.none { it.key.startsWith("file:unrelated/") })
        val p = ContextProfile(ContextMode.SCOPED, listOf("src"), allowExpansion = false)
        file("src/A.kt", "class A")
        assertIs<Outcome.Failure>(workspace(p).packet("A", listOf("src/A.kt"), reason = "нужно определение"))
        assertIs<Outcome.Failure>(workspace(p.copy(allowExpansion = true)).packet("A", listOf("unrelated/File1.kt"), reason = "нужно"))
    }

    @Test fun unchangedMtimeCannotMakeChangedBytesFreshAndUnreadRulesFailClosed() {
        file("src/A.kt", "first")
        val w = workspace()
        val before = w.packet("A", listOf("src/A.kt")).valueOrNull()!!
        val timestamp = dir.resolve("source/src/A.kt").toFile().lastModified()
        file("src/A.kt", "other")
        dir.resolve("source/src/A.kt").toFile().setLastModified(timestamp)
        val after = w.packet("A", listOf("src/A.kt")).valueOrNull()!!
        assertNotEquals(before.fingerprint, after.fingerprint)
        assertFalse(before.fresh(before.binding, w.hashes(before.stamps.keys).valueOrNull()!!, null))
        file("CLAUDE.md", "x".repeat(300_000))
        assertIs<Outcome.Failure>(w.packet("A"))
    }

    @Test fun freshnessKeepsExistingTwoMiBFilePagingCapability() {
        val text = "class LargeFile {}\n".repeat(20_000)
        file("src/LargeFile.kt", text)
        val w = workspace()
        val page = ApiTextPage.read(w.tree, "src/LargeFile.kt", 0).valueOrNull()!!
        assertEquals(json(false), page["complete"])
        val expected = Hashing.sha256(text).hex
        assertEquals(json(expected), page["sha256"])
        assertEquals(expected, w.hashes(listOf("src/LargeFile.kt")).valueOrNull()!!["src/LargeFile.kt"],
            "предел автовыборки 256 КиБ не блокирует прежнее чтение больших файлов")
    }

    @Test fun reportsKeepExistingBuildOutputCapabilityWithoutOpeningSecrets() {
        file("module/build/results.xml", "<testsuite/>")
        file("module/build/.env", "secret")
        val profile = ContextProfile(ContextMode.SCOPED, listOf("module"))
        val reports = ContextTree(tree, profile, reports = true)
        assertIs<Outcome.Success<*>>(reports.read("module/build/results.xml", 1000))
        assertIs<Outcome.Failure>(reports.read("module/build/.env", 1000))
        assertIs<Outcome.Failure>(reports.read("other/build/results.xml", 1000))
        assertIs<Outcome.Failure>(reports.write("module/build/results.xml", byteArrayOf()))
    }

    @Test fun settingsCorpusAcceptsV1AndRejectsUnknownVersionWithoutWidening() {
        val vfs = NioVfs(dir.resolve("private"))
        val documents = com.dpsmpx.dpai.storage.document.DocumentStore(vfs, "settings")
        val store = ContextStore(vfs, "target:root")
        for (name in listOf("project-v1", "invalid-version")) {
            val raw = javaClass.getResource("/context-settings/$name.json")!!.readText()
            val obj = com.dpsmpx.dpai.core.serialization.JsonParser().parse(raw).valueOrNull() as JsonObject
            assertTrue(documents.write("profile", obj).isSuccess)
            assertEquals(name == "project-v1", store.load().isSuccess, name)
        }
    }

    @Test fun settingsUseCasBindingAndNeverRecoverAnOlderPermissionImplicitly() {
        val vfs = NioVfs(dir.resolve("private"))
        val store = ContextStore(vfs, "target:root")
        assertFalse(store.load().valueOrNull()!!.explicit)
        val scoped = ContextProfile(ContextMode.SCOPED, listOf("src"))
        assertTrue(store.save(scoped, null).isSuccess)
        val revision = store.load().valueOrNull()!!.revision
        assertIs<Outcome.Failure>(store.save(ContextProfile(), null))
        assertTrue(store.save(scoped.copy(maxTokens = 20000), revision).isSuccess)
        assertIs<Outcome.Failure>(ContextStore(vfs, "another-target").load())
        vfs.write("settings/profile", "corrupt".toByteArray())
        assertIs<Outcome.Failure>(store.load(), "last known good не выдаёт старые разрешения")
        assertTrue(store.reset().isSuccess); assertEquals(ContextProfile(), store.load().valueOrNull()!!.profile)
    }

    @Test fun toolSchemasRejectMalformedExpansionAndCannotEditProfile() {
        assertEquals(7, ContextTools.tools().size)
        assertIs<Outcome.Failure>(ContextTools.request("expand_context", JsonObject.of("objective" to json("A"), "paths" to json("src"), "reason" to json("need"))))
        assertIs<Outcome.Failure>(ContextTools.request("get_task_context", JsonObject.of("objective" to json("A"), "mode" to json("FULL_ACCESS"))))
        val request = ContextTools.request("expand_context", JsonObject.of("objective" to json("A"), "paths" to JsonArray(listOf(json("src/A.kt"))), "reason" to json("need"))).valueOrNull()!!
        assertEquals(listOf("src/A.kt"), ContextTools.paths(request.second).valueOrNull())
    }
    @Test fun restrictedProfileCannotBypassFileScopeThroughReadLevelNativeToolsOrWorkflows() {
        val p = ContextProfile(ContextMode.SCOPED, listOf("src"))
        for (id in listOf("manual.check", "manual.build", "environment.command", "environment.inspect", "verification.full", "development.symbol",
            "development.diff", "development.history", "context.history", "context.evidence", "context.symbol", "context.interfaces", "unregistered.tool"))
            assertFalse(ContextAccess.tool(p, id), id)
        for (id in listOf("context.task", "context.constraints", "context.expand", "development.tests", "development.structure")) assertTrue(ContextAccess.tool(p, id), id)
        assertTrue(ContextAccess.tool(ContextProfile(), "manual.check"))
    }

    @Test fun legacyWorkflowRevisionsSurviveUntilExplicitContextConfigurationAndAllAdaptersAgree() {
        val definitions = DevelopmentTools.describe("/project", com.dpsmpx.dpai.execution.target.TargetPlatform.POSIX, true)
        val legacy = ContextSettings(ContextProfile(), null, false)
        assertEquals(definitions, ContextAccess.bind(definitions, legacy), "прежние сценарии не требуют пересоздания после обновления")
        val configured = ContextSettings(ContextProfile(), "stored", true)
        val editor = ContextAccess.bind(definitions, configured)
        val nativeGate = ContextAccess.bind(definitions, configured)
        assertEquals(editor.map { it.revision }, nativeGate.map { it.revision })
        assertNotEquals(definitions.first().revision, editor.first().revision, "явная смена политики отзывает прежний снимок")
        val scoped = ContextAccess.bind(definitions, configured.copy(profile = ContextProfile(ContextMode.SCOPED, listOf("src"))))
        assertFalse(scoped.first { it.id == "development.symbol" }.available)
    }

}
