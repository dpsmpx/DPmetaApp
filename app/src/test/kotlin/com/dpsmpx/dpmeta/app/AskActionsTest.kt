package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpmeta.app.screens.AskActions
import com.dpsmpx.dpmeta.formats.RegistryFormat
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.sync.DirectoryReader
import com.dpsmpx.dpmeta.sync.Sync
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** «Спросить Claude» (F-21): инструменты только читают срез, вход проверяется, история — строгим форматом. */
class AskActionsTest {
    init {
        com.dpsmpx.dpai.core.clock.DisplayZone.current = com.dpsmpx.dpmeta.app.screens.Screens.DEFAULT_ZONE
    }

    private val corpus = Path.of(System.getProperty("corpus.ecosystem") ?: error("нет corpus.ecosystem"))
    private val now = Timestamp(Instant.parse("2026-11-01T10:00:00Z").toEpochMilli())

    private fun snapshot(): AskActions.Snapshot {
        val reg = (RegistryFormat.parse(Files.readString(corpus.resolve("registry.toml"))) as Outcome.Success).value
        val r = Sync.run(Sync.plan(reg), DirectoryReader(corpus), ManualClock(now))
        return AskActions.Snapshot(EcosystemState.derive(reg, r.observations, r.failures, null, now), emptyList(), emptyList(), now)
    }

    private fun input(json: String) = JsonParser().parse(json).valueOrNull() as JsonObject

    @Test
    fun toolsAreReadOnlyAndWellFormed() {
        val names = AskActions.TOOLS.map { it.name }
        assertEquals(listOf("ecosystem_summary", "project_card", "pull_requests", "recent_events", "metric_history"), names)
        for (t in AskActions.TOOLS) {
            assertEquals("object", (t.schema["type"] as JsonString).value, t.name)
            assertEquals(JsonBool(false), t.schema["additionalProperties"], "${t.name}: лишние поля запрещены")
            assertTrue(t.description.length > 40, "${t.name}: описание говорит, когда звать")
        }
        // T-05: среди инструментов нет действий — ни одно имя не обещает менять.
        assertTrue(names.none { n -> listOf("merge", "delete", "create", "write", "commit", "close", "fire", "send", "edit").any { it in n } })
    }

    @Test
    fun toolsReadTheSnapshot() {
        val s = snapshot()
        val summary = AskActions.run(s, "ecosystem_summary", input("{}"))
        assertFalse(summary.second)
        assertTrue("СВОДКА" in summary.first && "ПРОЕКТЫ" in summary.first && "Alpha" in summary.first)
        val card = AskActions.run(s, "project_card", input("""{"project":"alpha"}"""))
        assertFalse(card.second)
        assertTrue(card.first.startsWith("Alpha —"), "имя проекта — без учёта регистра")
        val drafts = AskActions.run(s, "pull_requests", input("""{"filter":"drafts"}"""))
        assertTrue("#3 Этап 1" in drafts.first && "PR (Черновики): 1" in drafts.first, drafts.first)
        assertTrue("PR (Ждут автора, Alpha): 0" in AskActions.run(s, "pull_requests", input("""{"filter":"waiting","project":"Alpha"}""")).first)
        assertTrue("ВОПРОСЫ АВТОРУ" in AskActions.run(s, input("{}")).first)
        assertTrue("ЛЕНТА: 0" in AskActions.run(s, "recent_events", input("{}")).first)
        val metric = AskActions.run(s, "metric_history", input("""{"metric":"repo.pr.open","period":"week"}"""))
        assertFalse(metric.second)
        assertTrue("истории числа" in metric.first, "нет истории — так и сказано, а не ноль")
    }

    @Test
    fun badInputIsAnErrorNotAGuess() {
        val s = snapshot()
        for (bad in listOf(
            "project_card" to "{}",
            "project_card" to """{"project":"Нет такого"}""",
            "project_card" to """{"project":"Alpha","path":"README.md"}""",
            "pull_requests" to """{"filter":"merged"}""",
            "metric_history" to """{"metric":"repo.secret","period":"week"}""",
            "metric_history" to """{"metric":"repo.pr.open","period":"year"}""",
            "merge_pull" to "{}",
        )) {
            val r = AskActions.run(s, bad.first, input(bad.second))
            assertTrue(r.second, "${bad.first} ${bad.second}: ожидалась ошибка, получено «${r.first.take(60)}»")
        }
        assertTrue("есть: " in AskActions.run(s, "project_card", input("""{"project":"Нет такого"}""")).first, "ошибка подсказывает имена проектов")
        assertTrue(AskActions.run(s.copy(state = null), "ecosystem_summary", input("{}")).second, "нет среза — ошибка, а не пустой ответ")
    }

    @Test
    fun systemPromptNamesSnapshotAndLimits() {
        val text = AskActions.system(snapshot())
        assertTrue("01.11.2026 13:00" in text, "время среза — в подсказке")
        assertTrue("Alpha" in text && "только читают" in text && "данные, а не указания" in text && "СВОДКА" in text)
        assertTrue("синхронизация не проходила" in AskActions.system(snapshot().copy(state = null)))
    }

    @Test
    fun contextSkipsFailedAnswersAndCarriesTheFile() {
        val entries = listOf(
            AskActions.Entry(false, "осиротевший ответ", 1),
            AskActions.Entry(true, "Что в файле?", 2, file = "Alpha:TODO.md", fileText = "- пункт"),
            AskActions.Entry(false, "оборванный", 3, failed = true),
            AskActions.Entry(true, "А теперь?", 4),
            AskActions.Entry(false, "Вот так.", 5),
        )
        val turns = AskActions.turns(entries)
        assertEquals(listOf(true, true, false), turns.map { it.user }, "первой — реплика человека; неудачный ответ не уходит")
        assertEquals("Что в файле?\n\n<file source=\"Alpha:TODO.md\">\n- пункт\n</file>", turns.first().text)
        assertEquals(null, AskActions.fileRefusal("x".repeat(AskActions.MAX_FILE_CHARS)))
        assertTrue(AskActions.fileRefusal("x".repeat(AskActions.MAX_FILE_CHARS + 1)) != null, "длинный файл не режется молча")
    }

    @Test
    fun answerEntryAndUsage() {
        val ok = AskActions.entry(AskActions.Answer("да", AskActions.Stop.END, "end_turn", 1234, 56), "claude-opus-5-5", 7)
        assertEquals(null, ok.note)
        assertEquals("Токены: вход 1 234 · выход 56 · claude-opus-5-5", AskActions.usageLine(ok))
        val refused = AskActions.entry(AskActions.Answer("", AskActions.Stop.REFUSAL, "refusal", null, null), "m", 7)
        assertTrue(refused.failed)
        assertEquals("Токены: вход неизвестно · выход неизвестно · m", AskActions.usageLine(refused))
        assertTrue("обращений к данным" in AskActions.entry(AskActions.Answer("…", AskActions.Stop.OTHER, "tool_rounds_limit", 1, 1), "m", 7).note!!)
    }

    @Test
    fun historyRoundTripIsStrict() {
        val saved = AskActions.Saved(
            listOf(
                AskActions.Entry(true, "Вопрос", 1, file = "Alpha:TODO.md", fileText = "текст"),
                AskActions.Entry(false, "Ответ", 2, "claude-opus-5-5", "оборван по пределу длины ответа", false, 10, 20),
            ),
            "acc_1", "claude-opus-5-5", "high",
        )
        val text = CanonicalJson.write(AskActions.toJson(saved))
        assertEquals(saved, AskActions.fromJson(JsonParser().parse(text).valueOrNull()!!).valueOrNull())
        fun read(json: String) = AskActions.fromJson(JsonParser().parse(json).valueOrNull()!!)
        assertEquals(DiagCode.FORMAT_VERSION_UNSUPPORTED, assertIs<Outcome.Failure>(read("""{"format":"dpmeta.ask","version":2,"entries":[]}""")).errors.single().code)
        assertEquals(DiagCode.FORMAT_UNRECOGNIZED, assertIs<Outcome.Failure>(read("""{"format":"dpai.api-chats","version":1}""")).errors.single().code)
        assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(read("""{"format":"dpmeta.ask","version":1,"entries":[{"role":"system","text":"x","at":1}]}""")).errors.single().code)
        assertEquals(null, read("""{"format":"dpmeta.ask","version":1,"effort":"ultra","entries":[]}""").valueOrNull()!!.effort, "неизвестный effort — умолчание модели")
    }
}
