package com.dpsmpx.dpai.execution.linux

import com.dpsmpx.dpai.core.result.Outcome
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Размер окна псевдотерминала в знаках. */
data class WindowSize(val cols: Int, val rows: Int) {
    init {
        require(cols > 0 && rows > 0) { "размер окна — положительный: $cols×$rows" }
    }
}

/**
 * Запуск процессов там, где нет `java.lang.ProcessHandle` (ART, D-61;
 * `docs/research/LINUX_IN_APP.md`, §7): реализация — JNI модуля `android`
 * (`JniProcesses`, свой код на C). Процесс запускается лидером своего
 * сеанса (`setsid`), поэтому все его потомки, в том числе ушедшие в фон,
 * узнаются по сеансу, а остановка не зависит от дерева «родитель —
 * потомок».
 */
interface NativeProcesses {
    /**
     * Запустить [argv] с окружением [environment] целиком в каталоге
     * [workDir] (`null` — каталог приложения). [pty] — на псевдотерминале
     * этого размера: stdout и stderr — один поток, [NativeChild.stderr] —
     * `null`. Отказ `fork`, `exec` или каталога — `PROCESS_START_FAILED`.
     */
    fun spawn(argv: List<String>, environment: Map<String, String>, workDir: String?, pty: WindowSize? = null): Outcome<NativeChild>

    /** `kill(2)`: сигнал процессу [pid] (отрицательный — группе); `false` — адресата нет. */
    fun kill(pid: Int, signal: Int): Boolean
}

/** Запущенный процесс [NativeProcesses]; потоки закрываются, когда их дочитали или процесс остановлен. */
interface NativeChild {
    val pid: Int
    val stdin: OutputStream
    val stdout: InputStream

    /** `null` у псевдотерминала: вывод ошибок — в [stdout]. */
    val stderr: InputStream?

    /** Ждать выхода и забрать его (`waitpid`): код или 128 + номер сигнала. Вызывается один раз. */
    fun waitFor(): Int

    /** Сменить размер окна псевдотерминала; без него — ничего. */
    fun resize(size: WindowSize)
}

/** Номера сигналов Linux (одинаковы на arm64 и x86_64). */
object Signals {
    const val HUP: Int = 1
    const val KILL: Int = 9
    const val TERM: Int = 15
}

/**
 * Чтение `/proc/<pid>/stat` без `ProcessHandle`: сеанс и состояние
 * процесса. На Android приложение видит в `/proc` свои процессы — их и
 * нужно (потомки, запущенные им самим).
 */
object ProcStat {
    /** [startTicks] — время старта в тиках от загрузки (поле 22); тик — [TICK_MILLIS]. */
    data class Entry(val pid: Long, val state: Char, val session: Long, val startTicks: Long? = null)

    /**
     * Длительность тика `/proc/<pid>/stat` (`USER_HZ` = 100 у ядра Linux на
     * всех архитектурах, Android — тоже): время старта процесса в мс от загрузки.
     */
    const val TICK_MILLIS: Long = 10

    fun of(pid: Long, proc: File = File("/proc")): Entry? = try {
        File(proc, "$pid/stat").takeIf { it.isFile }?.readText()?.let { parse(pid, it) }
    } catch (_: IOException) {
        null
    }

    /** Номер своего процесса (`/proc/self`); `ProcessHandle` в ART нет. */
    fun self(proc: File = File("/proc")): Long? = try {
        File(proc, "self").canonicalFile.name.toLongOrNull()
    } catch (_: IOException) {
        null
    }

    /** Время старта процесса в мс от загрузки — не зависит от скачков часов (A-11); нет процесса — `null`. */
    fun startedSinceBootMillis(pid: Long, proc: File = File("/proc")): Long? = of(pid, proc)?.startTicks?.times(TICK_MILLIS)

    /** Поля после имени программы: состояние, родитель, группа, сеанс, …, старт (`man 5 proc`); имя может содержать `)`. */
    internal fun parse(pid: Long, stat: String): Entry? {
        val fields = stat.substringAfterLast(')').trim().split(' ')
        val state = fields.getOrNull(0)?.firstOrNull() ?: return null
        val session = fields.getOrNull(3)?.toLongOrNull() ?: return null
        return Entry(pid, state, session, fields.getOrNull(19)?.toLongOrNull())
    }

    /** Живые процессы сеанса [session]; зомби (`Z`) и умирающие (`X`) — уже не живые. */
    fun livingInSession(session: Long, proc: File = File("/proc")): List<Long> =
        proc.listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }.orEmpty()
            .mapNotNull { of(it.name.toLong(), proc) }
            .filter { it.session == session && it.state != 'Z' && it.state != 'X' }
            .map { it.pid }
            .sorted()
}
