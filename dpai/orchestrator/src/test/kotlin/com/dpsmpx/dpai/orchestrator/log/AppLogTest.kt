package com.dpsmpx.dpai.orchestrator.log

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.io.TempDir

/** Журнал приложения (D-43): кольцо, файл, выключатель, падение, выгрузка, строки событий. */
class AppLogTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()

    private class Memory : LogSink {
        val lines = mutableListOf<String>()
        val now = mutableListOf<String>()
        override val location = "память теста"

        override fun append(line: String) {
            lines += line
        }

        override fun appendNow(line: String) {
            now += line
        }

        override fun readAll(): String? = lines.takeIf { it.isNotEmpty() }?.joinToString("") { "$it\n" }

        override fun close() = Unit
    }

    @Test
    fun linesAreRedactedFlatCappedAndFilteredByLevel() {
        val sink = Memory()
        val log = AppLog(clock, sink, capacity = 10, zone = ZoneOffset.UTC)
        log.info("сессия", "ключ sk-ant-api03-" + "B".repeat(30) + "\nвторая строка")
        log.debug("сессия", "инструмент Read")
        log.warn("ssh", "отказ соединения")
        log.error("агент", "нет ответа")
        val all = log.lines()
        assertEquals(4, all.size)
        assertFalse("sk-ant-api03-BBBB" in all[0].text, all[0].text)
        assertFalse('\n' in all[0].text)
        // В файл — от INFO: отладка не вытесняет сводки.
        assertEquals(3, sink.lines.size)
        assertEquals(listOf(LogLevel.ERROR), log.lines(filter = LogFilter.ERROR).map { it.level })
        assertEquals(listOf(LogLevel.DEBUG), log.lines(filter = LogFilter.DEBUG).map { it.level })
        // Кольцо держит последние строки; номера растут дальше.
        repeat(20) { log.info("сводка", "строка $it") }
        assertEquals(10, log.lines().size)
        assertEquals(24, log.nextSeq)
        assertEquals("строка 19", log.lines().last().text)
        // Длинная строка — обрезана.
        log.info("x", "я".repeat(AppLog.MAX_LINE + 50))
        assertEquals(AppLog.MAX_LINE + 1, log.lines().last().text.length)
    }

    @Test
    fun renderedLineHasWallTimeUptimeLevelThreadAndTag() {
        val log = AppLog(clock, zone = ZoneOffset.UTC)
        clock.advance(3_723_004.milliseconds)
        log.info("служба", "старт")
        val r = log.lines().single().render(ZoneOffset.UTC)
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \+1:02:03\.004 I T0 \[служба] старт$""").matches(r), r)
    }

    @Test
    fun disabledLogWritesNothingButFatalAndStepIsRemembered() {
        val sink = Memory()
        val log = AppLog(clock, sink)
        log.step("служба поднята")
        log.enabled = false
        log.info("x", "не пишется")
        assertTrue(log.lines().isEmpty(), "выключено — кольцо пусто")
        assertTrue(sink.lines.last().endsWith("журнал выключен"))
        log.fatal("падение", "IllegalStateException: x")
        // Падение — сразу, в потоке вызова, с последним этапом запуска.
        assertTrue(sink.now.single().contains("F T0 [падение] IllegalStateException: x; последний этап запуска: служба поднята"), sink.now.single())
        log.enabled = true
        assertTrue(sink.lines.last().endsWith("журнал включён"))
    }

    @Test
    fun exportKeepsHeadAndTailOfALongLog() {
        val sink = Memory()
        val log = AppLog(clock, sink)
        repeat(2_000) { log.info("сводка", "строка номер $it") }
        val out = log.export(maxChars = 20_000, headChars = 5_000)
        assertTrue(out.length <= 20_100, "${out.length}")
        assertTrue("строка номер 0" in out && "строка номер 1999" in out)
        assertTrue("… пропущено знаков:" in out)
        // Без файла — кольцо.
        val bare = AppLog(clock)
        bare.info("a", "b")
        assertTrue(bare.export().endsWith("[a] b"))
    }

    @Test
    fun fileRotatesWithHeaderAndReadsBothFilesInOrder() {
        val vfs = NioVfs(dir)
        val sink = FileLogSink(vfs, maxBytes = 4_096, header = { listOf("=== dpai test-build ===") })
        val log = AppLog(clock, sink)
        repeat(200) { log.info("сводка", "строка номер $it " + "x".repeat(40)) }
        sink.close()
        assertTrue(vfs.exists("logs/dpai.log.1"))
        val all = sink.readAll()!!
        assertTrue(all.startsWith("=== dpai test-build ==="), all.take(100))
        assertTrue("строка номер 199" in all)
        assertTrue(all.indexOf("строка номер 150") < all.indexOf("строка номер 199"))
        assertTrue(vfs.read("logs/dpai.log")!!.size <= 4_096 + 200)
        assertEquals(0, sink.dropped)
    }

    /**
     * `DPmetaApp:D-39`: в файл журнала отладки секрет не попадает, даже если строку
     * написал пульт оболочки (`AppLogs.info`); образец собран из частей.
     */
    @Test
    fun secretNeverReachesTheLogFile() {
        val vfs = NioVfs(dir)
        val sink = FileLogSink(vfs, maxBytes = 4_096, header = { emptyList() })
        val log = AppLog(clock, sink)
        val token = "gh" + "p_" + "Q7w8E9r0".repeat(5)
        log.info("пульт", "синхронизация: токен $token")
        log.warn("действия", "comment dpsmpx/DPmetaApp ур.1: Authorization: Bearer $token")
        sink.close()
        val all = sink.readAll()!!
        assertTrue("синхронизация" in all && "действия" in all, all)
        assertTrue(token !in all, "токен в файле журнала")
    }

    @Test
    fun diskFailureDropsLinesInsteadOfCrashing() {
        val broken = object : Vfs by NioVfs(dir) {
            override fun append(path: String, bytes: ByteArray) = throw java.io.IOException("нет места")
            override fun write(path: String, bytes: ByteArray) = throw java.io.IOException("нет места")
        }
        val sink = FileLogSink(broken)
        val log = AppLog(clock, sink)
        log.info("x", "y")
        log.fatal("падение", "z")
        sink.close()
        assertEquals(2, sink.dropped)
    }

    private val ids = IdGenerator(clock, RandomSource.seeded(43))

    private fun env(type: EventType, payload: JsonObject) =
        EventEnvelope(ids.next(IdKinds.EVENT), type, StreamRef.SYSTEM, 1, Timestamp(0), Timestamp(0), payload = payload)

    /** Длинный путь в строке события — без обрезка id, который защита журнала спрятала бы (Redmi Note 13, 9 октября 2026). */
    @Test
    fun longPathKeepsWholeTailSegmentsAndSurvivesRedaction() {
        val path = "/data/data/com.termux/files/home/dpai-sandbox/trees/tsk_01M4FP745VR6TQ0JJ9VQJ0H9TP"
        val line = EventLog.line(env(EventType.WORKTREE_CREATED, JsonObject.of("path" to JsonString(path))))!!
        assertTrue(line.text.endsWith("path=…/data/com.termux/files/home/dpai-sandbox/trees/tsk_01M4FP745VR6TQ0JJ9VQJ0H9TP"), line.text)
        assertEquals(line.text, com.dpsmpx.dpai.core.diagnostics.Redactor.STANDARD.redact(line.text), "нечего прятать")
        // Текст — по границе слова: 13 целых «слово» (78 знаков) и «…», без обрывка «сл».
        assertEquals(List(13) { "слово" }.joinToString(" ") + "…", EventLog.shorten("слово ".repeat(20)))
    }

    @Test
    fun eventsBecomeFactLinesWithoutTextsOfModelOrUser() {
        assertNull(EventLog.line(env(EventType.SESSION_OUTPUT, JsonObject.of("text" to JsonString("ответ модели")))))
        assertNull(EventLog.line(env(EventType.SESSION_HEARTBEAT, JsonObject.of("session" to JsonString("ses_1")))))
        val created = EventLog.line(env(EventType.TASK_CREATED, JsonObject.of("task" to JsonString("tsk_1"), "title" to JsonString("Ответь одним словом"), "objective" to JsonString("секретная цель"))))!!
        assertEquals("задача", created.tag)
        assertTrue("task=tsk_1" in created.text && "title=«Ответь одним словом»" in created.text, created.text)
        assertFalse("секретная цель" in created.text)
        val failed = EventLog.line(env(EventType.SESSION_STATE_CHANGED, JsonObject.of("from" to JsonString("RUNNING"), "to" to JsonString("FAILED"), "reason" to JsonString("SSH_CONNECTION_FAILED"))))!!
        assertEquals(LogLevel.WARN, failed.level)
        val usage = EventLog.line(
            env(
                EventType.SESSION_TURN_USAGE,
                JsonObject.of(
                    "session" to JsonString("ses_1"), "turn" to JsonNumber.of(2),
                    "usage" to JsonObject.of("inputTokens" to JsonNumber.of(10), "contextTokens" to JsonNumber.of(30_000), "models" to JsonArray(listOf(JsonObject.of("model" to JsonString("claude-x"))))),
                ),
            ),
        )!!
        assertTrue("inputTokens=10" in usage.text && "contextTokens=30000" in usage.text && "models=claude-x" in usage.text && "turn=2" in usage.text, usage.text)
        val tools = EventLog.line(
            env(
                EventType.SESSION_TURN_TOOLS,
                JsonObject.of(
                    "turn" to JsonNumber.of(1),
                    "tools" to JsonObject.of(
                        "byTool" to JsonArray(listOf(JsonObject.of("tool" to JsonString("Read"), "calls" to JsonNumber.of(3), "resultBytes" to JsonNumber.of(900), "errors" to JsonNumber.of(0), "duplicates" to JsonNumber.of(1)))),
                        "filesRead" to JsonNumber.of(2),
                    ),
                ),
            ),
        )!!
        assertTrue("toolCalls=3" in tools.text && "duplicates=1" in tools.text && "filesRead=2" in tools.text, tools.text)
        assertEquals(LogLevel.DEBUG, EventLog.line(env(EventType.SESSION_TOOL_STARTED, JsonObject.of("tool" to JsonString("Read"))))!!.level)
        // Ответ на запрос (D-44): вариант и итог — да, текст ответа человека — нет.
        val answered = EventLog.line(
            env(
                EventType.USER_REQUEST_ANSWERED,
                JsonObject.of("request" to JsonString("req_1"), "answer" to JsonObject.of("option" to JsonString("reply"), "text" to JsonString("пароль 123"), "effect" to JsonString("задача снова в очереди"))),
            ),
        )!!
        assertEquals("запрос", answered.tag)
        assertTrue("option=reply" in answered.text && "request=req_1" in answered.text, answered.text)
        assertFalse("пароль" in answered.text, answered.text)
    }

    @Test
    fun tickMeterSeesSleepAsAGapBetweenTicks() {
        val m = TickMeter(clock)
        m.begin(); clock.advance(10.milliseconds); m.end()
        clock.advance(2_990.milliseconds)
        m.begin(); clock.advance(30.milliseconds); m.end(ok = false)
        // Устройство спало 5 минут.
        clock.advance(300_000.milliseconds)
        m.begin(); clock.advance(20.milliseconds); m.end()
        val s = m.drain()
        assertEquals(TickStats(3, 20, 30, 300_030, 1), s)
        assertEquals(TickStats(0, 0, 0, 0, 0), m.drain())
    }

    /** Части такта (D-158): у медленного такта видно, что ждало; в сводке — часть самого долгого. */
    @Test
    fun tickMeterNamesTheSlowestPart() {
        val m = TickMeter(clock)
        m.begin()
        val a = clock.monotonicNanos(); clock.advance(5.milliseconds); m.part("шаг контура", a)
        val b = clock.monotonicNanos(); clock.advance(20_000.milliseconds); m.part("лимиты", b)
        val c = clock.monotonicNanos(); clock.advance(1.milliseconds); m.part("шаг контура", c)
        val slow = m.end()!!
        assertEquals(20_006, slow.ms)
        assertEquals(mapOf("шаг контура" to 6L, "лимиты" to 20_000L), slow.parts)
        assertEquals("лимиты" to 20_000L, slow.slowest)
        m.begin(); clock.advance(3.milliseconds); assertEquals(Tick(3, emptyMap()), m.end())
        m.part("вне такта", clock.monotonicNanos())
        assertEquals(TickStats(2, 10_004, 20_006, 20_006, 0, "лимиты" to 20_000L), m.drain())
        assertEquals(null, m.end(), "такт не начат")
    }
}
