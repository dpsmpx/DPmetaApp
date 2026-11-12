package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.io.TempDir

class EditorSessionBookTest {
    @TempDir lateinit var dir: Path
    private fun docs() = DocumentStore(NioVfs(dir), "ui")
    private fun book() = EditorSessionBook(docs())
    @Test fun emptyInstallDefaultsToBothSyntaxViewsAndFullInterface() {
        assertEquals(EditorSessions(), book().load().valueOrNull())
    }
    @Test fun coldRestartRestoresSeparateRootsOrderActiveTabsAndOptions() {
        val state = EditorSessions(EditorOptions(zen = true, previewSyntax = false, editorSyntax = true), linkedMapOf(
            "root\u0001one" to EditorRootSession(EditorTabs(listOf("one.cpp", "two.py"), "two.py"), "python3 \"\$DPAI_FILE\""),
            "root\u0001two" to EditorRootSession(EditorTabs(listOf("one.cpp"), "one.cpp"))), "place\u0001folder")
        assertIs<Outcome.Success<Unit>>(book().save(state))
        assertEquals(state, book().load().valueOrNull())
    }
    @Test fun browseAndCloseRemainClosedAfterRestartWithoutAffectingOtherRoot() {
        val tabs = EditorTabs().open("one.cpp").open("two.py").close("one.cpp").browse()
        val state = EditorSessions(roots = mapOf("one" to EditorRootSession(tabs), "two" to EditorRootSession(EditorTabs().open("one.cpp"))))
        book().save(state)
        assertEquals(state, book().load().valueOrNull())
    }
    @Test fun syntaxSettingsAreIndependentAndDoNotEraseOpenPaths() {
        val state = EditorSessions(EditorOptions(editorSyntax = false), mapOf("root" to EditorRootSession(EditorTabs().open("main.kt"))))
        book().save(state)
        val next = state.copy(options = state.options.copy(previewSyntax = false, zen = true))
        book().save(next)
        assertEquals(next, book().load().valueOrNull())
    }
    @Test fun invalidSnapshotDoesNotReplacePreviouslyStoredTabs() {
        val previous = EditorSessions(roots = mapOf("root" to EditorRootSession(EditorTabs().open("main.cpp"))))
        book().save(previous)
        for (invalid in listOf(
            EditorRootSession(EditorTabs(listOf("same.cpp", "same.cpp"), "same.cpp")),
            EditorRootSession(EditorTabs(listOf("main.cpp"), "other.cpp")),
            EditorRootSession(EditorTabs((0..EditorTabs.MAX_OPEN).map { "$it.cpp" })),
            EditorRootSession(runCommand = "x".repeat(4097)),
            EditorRootSession(EditorTabs(listOf("bad\u0000.cpp")))
        )) {
            assertIs<Outcome.Failure>(book().save(EditorSessions(roots = mapOf("root" to invalid))))
            assertEquals(previous, book().load().valueOrNull())
        }
    }
    @Test fun unknownVersionReadDoesNotOverwriteStoredDocument() {
        val unknown = JsonObject.of("format" to JsonString("dpai.editor-sessions"), "version" to JsonNumber.of(3))
        docs().write("sessions", unknown)
        assertIs<Outcome.Failure>(book().load())
        assertEquals(unknown, docs().read("sessions").valueOrNull()?.content)
    }
    @Test fun tooManyRootsFailWithoutEvictingOtherSessions() {
        book().save(EditorSessions())
        val roots = (0..EditorSessionBook.MAX_ROOTS).associate { "$it" to EditorRootSession() }
        assertIs<Outcome.Failure>(book().save(EditorSessions(roots = roots)))
        assertEquals(EditorSessions(), book().load().valueOrNull())
    }
    @Test fun languageCommandsSurviveRestartWithoutMixingScriptAndJvmOrCppAndPython() {
        val root = EditorRootSession().withCommand("a.cpp", "build cpp").withCommand("a.py", "run python").withCommand("a.kt", "run jvm").withCommand("a.kts", "run script")
        val state = EditorSessions(roots = mapOf("root" to root))
        assertIs<Outcome.Success<Unit>>(book().save(state))
        val loaded = book().load().valueOrNull()!!.roots.getValue("root")
        assertEquals(root, loaded)
        assertEquals("run python", loaded.command("other.py"))
        assertEquals("run script", loaded.command("other.kts"))
        assertEquals("run jvm", loaded.command("other.kt"))
    }
    @Test fun versionOneIsReadWithoutLosingLegacyCommandOrApplyingPythonToCpp() {
        val original = EditorSessions(roots = mapOf("root" to EditorRootSession(runCommand = "python3 \"\$DPAI_FILE\"")))
        book().save(original)
        val saved = docs().read("sessions").valueOrNull()!!.content
        docs().write("sessions", JsonObject(saved.fields + ("version" to JsonNumber.of(1))))
        val restored = book().load().valueOrNull()!!
        assertEquals(original, restored)
        val root = restored.roots.getValue("root")
        assertEquals("python3 \"\$DPAI_FILE\"", root.command("a.py"))
        assertEquals(EditorTerminalRun.defaultCommand("a.cpp"), root.command("a.cpp"))
        book().save(restored)
        assertEquals(2L, (docs().read("sessions").valueOrNull()!!.content["version"] as JsonNumber).toLongExact())
    }
    @Test fun invalidLanguageMetadataDoesNotReplaceValidSnapshot() {
        book().save(EditorSessions())
        for (commands in listOf(mapOf("unknown" to "x"), mapOf("python" to "x".repeat(4097)), mapOf("kotlin" to "bad\u0000"))) {
            assertIs<Outcome.Failure>(book().save(EditorSessions(roots = mapOf("root" to EditorRootSession(commands = commands)))))
            assertEquals(EditorSessions(), book().load().valueOrNull())
        }
    }
}
