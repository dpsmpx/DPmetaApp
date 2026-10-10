package com.dpsmpx.dpai.orchestrator.log

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.Redactor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Уровень строки журнала. `STEP` — пройденный этап запуска: последняя
 * отметка в файле — место, где запуск оборвался; `FATAL` — падение
 * (пишется всегда, даже при выключенном журнале).
 */
enum class LogLevel(val mark: Char, val label: String) {
    DEBUG('D', "DEBUG"), INFO('I', "INFO"), STEP('S', "STEP"), WARN('W', "WARN"), ERROR('E', "ERROR"), FATAL('F', "FATAL");
}

/** Фильтр просмотра: все строки или один уровень; «Ошибки» — с падением, «Сведения» — с этапами запуска. */
enum class LogFilter(val title: String) {
    ALL("Все"), DEBUG("Отладка"), INFO("Сведения"), WARN("Предупреждения"), ERROR("Ошибки");

    fun passes(level: LogLevel): Boolean = when (this) {
        ALL -> true
        DEBUG -> level == LogLevel.DEBUG
        INFO -> level == LogLevel.INFO || level == LogLevel.STEP
        WARN -> level == LogLevel.WARN
        ERROR -> level == LogLevel.ERROR || level == LogLevel.FATAL
    }
}

/**
 * Строка журнала: номер с запуска, время, время от запуска, уровень,
 * поток (0 — первый записавший, дальше по порядку появления), тег
 * подсистемы и текст.
 */
data class LogLine(
    val seq: Long,
    val at: Timestamp,
    val uptimeMs: Long,
    val level: LogLevel,
    val thread: Int,
    val tag: String,
    val text: String,
) {
    /** «2026-10-08 14:03:12.345 +0:12:34.567 I T1 [сессия] текст» — так и в файле, и на экране. */
    fun render(zone: ZoneId = ZoneId.systemDefault()): String {
        val wall = FORMAT.withZone(zone).format(Instant.ofEpochMilli(at.epochMillis))
        return "$wall +${uptime(uptimeMs)} ${level.mark} T$thread [$tag] $text"
    }

    companion object {
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        /** Время от запуска: часы:минуты:секунды.мс. */
        fun uptime(ms: Long): String {
            val s = ms / 1000
            return "%d:%02d:%02d.%03d".format(s / 3600, s / 60 % 60, s % 60, ms % 1000)
        }
    }
}

/**
 * Куда уходят строки сверх памяти: файл ([FileLogSink]). Запись — в фоне:
 * строка журнала не задерживает ни такт, ни экран. [appendNow] — сразу,
 * в потоке вызова: для падения, после которого процесса не будет.
 */
interface LogSink {
    fun append(line: String)

    fun appendNow(line: String)

    /** Текст файлов журнала, от старого к новому; файла нет — `null`. */
    fun readAll(): String?

    /** Куда пишется журнал — для заголовка и экрана. */
    val location: String

    fun close()
}

/**
 * Журнал приложения (D-43): кольцо последних строк в
 * памяти — для экрана «Журнал» — и тот же текст в файле ([LogSink]),
 * который пишется в фоне и переживает процесс. Образец — журнал игры
 * Galdhold: свой файл в каталоге приложения (системный журнал телефона
 * без отладчика недоступен), выгрузка наружу, отметки этапов запуска,
 * сводка раз в минуту, посмертная запись при падении.
 *
 * Только факты для проверки на устройстве: состояния, коды, счётчики,
 * длительности, id. Не пишутся prompt, текст модели, содержимое файлов,
 * команды и пути внутри хода, email, ключи — свободный текст проходит
 * `Redactor` (D-14). Выключенный журнал не пишет ничего, кроме падения.
 */
class AppLog(
    private val clock: Clock,
    private val sink: LogSink? = null,
    capacity: Int = DEFAULT_CAPACITY,
    /** Строки ниже этого уровня — только в памяти, не в файл: отладка не вытесняет из файла сводки. */
    @Volatile var fileLevel: LogLevel = LogLevel.INFO,
    private val redactor: Redactor = Redactor.STANDARD,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val started = clock.monotonicNanos()
    private val startedAt = clock.now()
    private val ring = LogRing(capacity)
    private val threads = HashMap<Long, Int>()

    /** Выключено — не пишется ничего, кроме [fatal]; кольцо очищается (выключено значит выключено). */
    @Volatile
    var enabled: Boolean = true
        set(value) {
            if (field == value) return
            if (!value) {
                write(LogLevel.INFO, "журнал", "журнал выключен", force = true)
                field = false
                ring.clear()
            } else {
                field = true
                write(LogLevel.INFO, "журнал", "журнал включён")
            }
        }

    /** Последняя отметка этапа запуска — её печатает и запись о падении. */
    @Volatile
    var lastStep: String = "—"
        private set

    val location: String get() = sink?.location ?: "только память"

    fun debug(tag: String, text: String) = write(LogLevel.DEBUG, tag, text)

    fun info(tag: String, text: String) = write(LogLevel.INFO, tag, text)

    fun warn(tag: String, text: String) = write(LogLevel.WARN, tag, text)

    fun error(tag: String, text: String) = write(LogLevel.ERROR, tag, text)

    /** Пройден этап запуска (`STEP`) с длительностью от запуска журнала. */
    fun step(name: String) {
        lastStep = name
        write(LogLevel.STEP, "запуск", "$name (${uptimeMs()} мс от запуска)")
    }

    /**
     * Падение: пишется всегда и сразу, в потоке вызова — после него
     * процесса не будет, и фоновая запись не успела бы.
     */
    fun fatal(tag: String, text: String) {
        val line = make(LogLevel.FATAL, tag, "$text; последний этап запуска: $lastStep")
        ring.push(line)
        sink?.appendNow(line.render(zone))
    }

    fun uptimeMs(): Long = (clock.monotonicNanos() - started) / 1_000_000

    /** Строки с номером не меньше [from] через [filter], не больше [max] последних. */
    fun lines(from: Long = 0, filter: LogFilter = LogFilter.ALL, max: Int = Int.MAX_VALUE): List<LogLine> =
        ring.since(from).filter { filter.passes(it.level) }.takeLast(max)

    /** Номер следующей строки: сколько строк записано с запуска. */
    val nextSeq: Long get() = ring.nextSeq

    /**
     * Текст для выгрузки (буфер обмена, файл в «Загрузках»): файлы журнала,
     * а без файла — кольцо. Длиннее [maxChars] — начало и хвост с пометкой
     * о пропуске: начало — заголовок и запуск, хвост — то, что было перед
     * выгрузкой.
     */
    fun export(maxChars: Int = 400_000, headChars: Int = 60_000): String {
        val text = (if (enabled) sink?.readAll() else null) ?: ring.since(0).joinToString("\n") { it.render(zone) }
        if (text.length <= maxChars) return text
        val head = text.take(headChars).substringBeforeLast('\n')
        val tail = text.takeLast(maxChars - headChars).substringAfter('\n')
        return "$head\n… пропущено знаков: ${text.length - head.length - tail.length} …\n$tail"
    }

    fun close() = sink?.close()

    /** Начало журнала этого запуска. */
    val since: Timestamp get() = startedAt

    private fun write(level: LogLevel, tag: String, text: String, force: Boolean = false) {
        if (!enabled && !force) return
        val line = make(level, tag, text)
        ring.push(line)
        if (level.ordinal >= fileLevel.ordinal) sink?.append(line.render(zone))
    }

    private fun make(level: LogLevel, tag: String, text: String): LogLine {
        val clean = redactor.redact(text).replace('\n', ' ').let { if (it.length > MAX_LINE) it.take(MAX_LINE) + "…" else it }
        return LogLine(ring.reserve(), clock.now(), uptimeMs(), level, thread(), tag, clean)
    }

    @Synchronized
    private fun thread(): Int {
        @Suppress("DEPRECATION") // Thread.threadId() — только с Java 19; байт-код — Java 17, Android 30.
        val id = Thread.currentThread().id
        return threads.getOrPut(id) { threads.size.coerceAtMost(MAX_THREADS) }
    }

    companion object {
        /** Сводка — раз в минуту, остальное реже: две тысячи строк — часы работы. */
        const val DEFAULT_CAPACITY = 2000

        /** Длина строки — не больше: журнал — факты, а не тексты. */
        const val MAX_LINE = 2000

        private const val MAX_THREADS = 99
    }
}

/** Кольцо последних строк: пишут все потоки, читает экран. */
internal class LogRing(private val capacity: Int) {
    private val buf = ArrayDeque<LogLine>()
    private var next = 0L

    init {
        require(capacity in 10..1_000_000) { "ёмкость кольца — 10…1000000" }
    }

    val nextSeq: Long @Synchronized get() = next

    @Synchronized
    fun reserve(): Long = next++

    @Synchronized
    fun push(line: LogLine) {
        buf.addLast(line)
        while (buf.size > capacity) buf.removeFirst()
    }

    @Synchronized
    fun since(from: Long): List<LogLine> = buf.filter { it.seq >= from }

    /** Номера не сбрасываются: читатель с номером не получит старых строк заново. */
    @Synchronized
    fun clear() = buf.clear()
}
