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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UserAlertRulesTest {
    @TempDir lateinit var dir: Path
    private val catalog = EventCatalogFormat.parse("""
        schema = 1
        [[event]]
        type = "checks.red"
        title = "Проверка упала"
        source = ["app"]
        channel = "dev"
        fields = ["message"]
        numbers = ["count"]
        [[event]]
        type = "site.down"
        title = "Сайт"
        source = ["app"]
        channel = "site"
    """.trimIndent()).valueOrNull()!!
    private val text = """
        # Личный комментарий сохраняется
        schema = 1
        [[rule]]
        id = "checks.red"
        title = "Ошибка \"сборки\""
        on = "checks.red"
        action = "notify"
        channel = "dev"
        importance = "high"
        enabled = false
        project = "my-project"
        contains = "a\\b"
        regex = "error.*"
        field = "count"
        at_least = 1
        at_most = 8
        days = ["wed", "mon"]
        limit = 2
        window = 60
        quiet = false
        [[rule]]
        id = "fallback"
        title = "Остальное"
        on = "checks.*"
        action = "feed"
    """.trimIndent()

    @Test
    fun invalidSaveKeepsExactPreviousTextAndResetRemovesOverlay() {
        val store = UserAlertRules(DocumentStore(NioVfs(dir), "settings"))
        assertNull((store.read() as Outcome.Success).value)
        assertIs<Outcome.Success<*>>(store.save(text, catalog))
        assertEquals(text, (store.read() as Outcome.Success).value)
        assertIs<Outcome.Failure>(store.save(text.replace("checks.*", "nonexistent.*"), catalog))
        assertEquals(text, (store.read() as Outcome.Success).value)
        // «В сводку» по расписанию (D-152) — больше не отказ.
        val digest = text.replace("enabled = false", "enabled = true").replace("action = \"notify\"", "action = \"digest\"")
        assertIs<Outcome.Success<*>>(store.save(digest, catalog))
        assertEquals(digest, (store.read() as Outcome.Success).value)
        assertIs<Outcome.Success<*>>(store.reset())
        assertNull((store.read() as Outcome.Success).value)
    }

    @Test
    fun v1GetsAiDefaultsWithoutOverwritingTextAndV2CanExplicitlyOmitThem() {
        val documents = DocumentStore(NioVfs(dir), "settings")
        val store = UserAlertRules(documents)
        val ai = catalog.types.first().copy(type = "ai.info", title = "ИИ")
        val combined = catalog.copy(types = catalog.types + ai)
        val defaults = (AlertFormat.parse("""
            schema = 1
            [[rule]]
            id = "checks.red"
            title = "ИИ"
            on = "ai.*"
            action = "notify"
        """.trimIndent(), combined) as Outcome.Success).value
        assertIs<Outcome.Success<*>>(documents.write("alert-rules", JsonObject.of(*FormatHeader.write(UserAlertRules.FORMAT, 1), "text" to JsonString(text))))
        val effective = (store.effective(defaults, combined) as Outcome.Success).value
        assertEquals(3, effective.rules.size)
        assertEquals(3, effective.rules.map { it.id }.toSet().size, "совпавший id не затирает личное правило")
        val editor = (store.editorText(defaults) as Outcome.Success).value
        assertEquals(effective.rules.map { it.id }, (AlertFormat.parse(editor, combined) as Outcome.Success).value.rules.map { it.id })
        assertEquals(text, (store.read() as Outcome.Success).value, "фоновая миграция не пишет текст")
        assertIs<Outcome.Success<*>>(store.save(text, combined))
        assertEquals(2, (store.effective(defaults, combined) as Outcome.Success).value.rules.size, "v2 сохраняет явный выбор без ai.*")
    }

    @Test
    fun futureVersionDoesNotSilentlyEnableDefaultsOrLastGood() {
        val documents = DocumentStore(NioVfs(dir), "settings")
        val store = UserAlertRules(documents)
        assertIs<Outcome.Success<*>>(store.save(text, catalog))
        assertIs<Outcome.Success<*>>(documents.write("alert-rules", JsonObject.of(*FormatHeader.write(UserAlertRules.FORMAT, UserAlertRules.VERSION + 1), "text" to JsonString(text))))
        assertIs<Outcome.Failure>(store.read())
    }

    @Test
    fun canonicalTextRetainsEveryConditionAndRuleOrder() {
        val rules = (AlertFormat.parse(text, catalog) as Outcome.Success).value
        val encoded = (AlertFormat.write(rules) as Outcome.Success).value
        val parsed = AlertFormat.parse(encoded, catalog)
        val back = assertIs<Outcome.Success<com.dpsmpx.dpmeta.model.AlertRules>>(parsed, parsed.toString()).value
        assertEquals(rules.rules.map { it.copy(regex = null) }, back.rules.map { it.copy(regex = null) })
        assertEquals(rules.rules.map { it.regex?.pattern }, back.rules.map { it.regex?.pattern })
        assertEquals(encoded, (AlertFormat.write(back) as Outcome.Success).value)
    }

    @Test
    fun integerThresholdsStayReadableAtInt64BoundaryAndFractionsAreRefused() {
        val rules = (AlertFormat.parse(text.replace("at_most = 8", "at_most = 9223372036854775807"), catalog) as Outcome.Success).value
        val encoded = (AlertFormat.write(rules) as Outcome.Success).value
        val back = (AlertFormat.parse(encoded, catalog) as Outcome.Success).value
        assertEquals(rules.rules.first().number, back.rules.first().number)
        val first = rules.rules.first()
        val fraction = first.copy(number = first.number!!.copy(atLeast = 1.5))
        assertIs<Outcome.Failure>(AlertFormat.write(rules.copy(rules = listOf(fraction))))
    }

    @Test
    fun studioDoesNotExposeContentEventsAndClockBoundariesAreValidated() {
        val studio = AlertActions.studioCatalog(catalog)
        assertEquals(listOf("checks.red"), studio.types.map { it.type })
        assertEquals(2, catalog.types.size)
        assertIs<Outcome.Failure>(UserAlertRules(DocumentStore(NioVfs(dir), "settings")).save(text.replace("checks.red", "site.down"), studio))
        val days = setOf(DayOfWeek.MONDAY)
        val night = (AlertActions.quiet("22:30", "06:00", days) as Outcome.Success).value
        assertEquals(1350, night.fromMinute)
        assertEquals(360, night.toMinute)
        assertEquals(days, night.days)
        for (bad in listOf("24:00", "23:60", "1:00", "-1:00")) assertIs<Outcome.Failure>(AlertActions.quiet(bad, "08:00", emptySet()))
        assertIs<Outcome.Failure>(AlertActions.quiet("08:00", "08:00", emptySet()))
    }

    @Test
    fun importStopsAtLimitWithoutReadingTheRestOfTheFile() {
        var seen = 0
        val reader = object : java.io.Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                buffer.fill('x', offset, offset + length)
                seen += length
                return length
            }
            override fun close() = Unit
        }
        assertIs<Outcome.Failure>(UserAlertRules.importText(reader))
        assertEquals(UserAlertRules.MAX_TEXT + 1, seen)
    }
}
