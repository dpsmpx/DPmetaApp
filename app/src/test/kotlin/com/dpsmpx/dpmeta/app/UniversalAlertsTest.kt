package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.app.data.UserAlertRules
import com.dpsmpx.dpmeta.app.screens.AlertActions
import com.dpsmpx.dpmeta.formats.AlertFormat
import com.dpsmpx.dpmeta.formats.EventCatalogFormat
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Удалённые источники читаются для совместимости, но не предлагаются и не исполняются. */
class UniversalAlertsTest {
    @TempDir lateinit var root: Path
    private val repo = File(System.getProperty("corpus.repo") ?: error("нет corpus.repo"))
    private val all = EventCatalogFormat.parse(File(repo, "config/events.toml").readText()).valueOrNull()!!
    private val active = AlertActions.studioCatalog(all)
    private val defaults = AlertFormat.parse(File(repo, "config/alerts.toml").readText(), all).valueOrNull()!!
    private val retired = listOf("dpmeta.report.new", "decision.new", "todo.author.new", "question.new",
        "site.down", "social.comment.new", "termux.command.done", "app.notification", "claude.turn.done")

    @Test
    fun defaultsContainOnlyLiveStudioEvents() {
        assertTrue(active.types.none { it.type in retired })
        assertEquals(defaults, AlertActions.studioRules(defaults, active))
        assertTrue(active.types.any { it.type == "pr.opened" })
        assertTrue(defaults.rules.any { it.on == "ai.failure" })
    }

    @Test
    fun oldOverlaysRemainReadableAndExportableWithoutActivatingRetiredRules() {
        val text = "schema = 1\n" + (retired + "ai.failure").mapIndexed { i, type ->
            """
                [[rule]]
                id = "old.$i"
                title = "Моё правило $i"
                on = "$type"
                action = "notify"
            """.trimIndent()
        }.joinToString("\n", "\n")
        for (version in 1..UserAlertRules.VERSION) {
            val documents = DocumentStore(NioVfs(root.resolve("v$version")), "settings")
            assertIs<Outcome.Success<*>>(documents.write("alert-rules", JsonObject.of(
                *FormatHeader.write(UserAlertRules.FORMAT, version), "text" to JsonString(text),
            )))
            val store = UserAlertRules(documents)
            val effective = assertIs<Outcome.Success<com.dpsmpx.dpmeta.model.AlertRules>>(store.effective(defaults, all)).value
            val live = AlertActions.studioRules(effective, active)
            assertTrue(live.rules.isNotEmpty())
            assertTrue(live.rules.all { it.on.startsWith("ai.") })
            assertEquals(text, assertIs<Outcome.Success<String?>>(store.read()).value)
            val editor = assertIs<Outcome.Success<String>>(store.editorText(defaults)).value
            assertTrue(retired.all { it in editor })
            assertIs<Outcome.Success<*>>(store.save(editor, all))
            assertEquals(editor, assertIs<Outcome.Success<String?>>(store.read()).value)
        }
    }
}
