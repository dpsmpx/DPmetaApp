package com.dpsmpx.dpai.orchestrator.log

import com.dpsmpx.dpai.storage.vfs.Vfs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Файл журнала приложения (D-43): `logs/dpai.log` через `Vfs` (D-24),
 * запись в своём фоновом потоке — такт и экран не ждут диска. Больше
 * [maxBytes] — файл становится `dpai.log.1` (прежний `.1` удаляется), и
 * журнал начинается заново с заголовка ([header]): на диске не больше
 * двух файлов, а выгрузка берёт оба. Строка пишется без fsync: убитый
 * системой процесс теряет только то, что ещё в очереди, а запись ОС
 * переживает (как у журнала Galdhold).
 *
 * Отказ записи — не отказ приложения: строка теряется, счётчик
 * [dropped] растёт и попадает в следующую сводку.
 */
class FileLogSink(
    private val vfs: Vfs,
    private val path: String = DEFAULT_PATH,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val header: () -> List<String> = { emptyList() },
    /** Где лежит файл для человека (абсолютный путь каталога приложения). */
    override val location: String = path,
) : LogSink {
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "dpai-log").apply { isDaemon = true } }
    private var size: Long = -1

    @Volatile
    var dropped: Long = 0
        private set

    init {
        require(maxBytes >= 4_096) { "maxBytes — от 4096" }
        vfs.mkdirs(Vfs.parentOf(path))
    }

    override fun append(line: String) {
        try {
            writer.execute { write(line) }
        } catch (_: RejectedExecutionException) {
            dropped++
        }
    }

    @Synchronized
    override fun appendNow(line: String) = write(line)

    @Synchronized
    override fun readAll(): String? {
        val old = vfs.read("$path.1")?.toString(Charsets.UTF_8)
        val cur = vfs.read(path)?.toString(Charsets.UTF_8)
        if (old == null && cur == null) return null
        return listOfNotNull(old, cur).joinToString("")
    }

    /** Дождаться записи очереди (до [timeoutMs]) и остановить поток. */
    override fun close() {
        writer.shutdown()
        writer.awaitTermination(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun write(line: String) {
        try {
            // Размер — без чтения файла (аудит стабильности, A-8): после сбоя записи каждая строка
            // читала до 1 МиБ ради одного числа.
            if (size < 0) size = vfs.size(path) ?: 0
            if (size == 0L || size >= maxBytes) {
                if (size >= maxBytes) {
                    vfs.delete("$path.1")
                    vfs.rename(path, "$path.1")
                }
                val head = (header() + "--- файл журнала начат; предыдущий — ${Vfs.parentOf(path).ifEmpty { "." }}/${path.substringAfterLast('/')}.1 ---")
                    .joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8)
                vfs.write(path, head)
                size = head.size.toLong()
            }
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            vfs.append(path, bytes)
            size += bytes.size
        } catch (_: Exception) {
            // Диск полон или каталог пропал: строка теряется, журнал не роняет приложение.
            dropped++
            size = -1
        }
    }

    companion object {
        const val DEFAULT_PATH = "logs/dpai.log"

        /** Файл — до 1 МиБ: ~8 000 строк, при сводке раз в минуту — сутки работы. */
        const val DEFAULT_MAX_BYTES = 1_048_576L

        private const val TIMEOUT_MS = 2_000L
    }
}
