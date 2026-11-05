package com.dpsmpx.dpai.sync

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.IOException

/**
 * Восстановление из облачной копии (D-107): файлы копии раскладываются
 * рядом с данными контура ([stage]), а применяются при следующем запуске,
 * до открытия журнала ([applyPending]). Прежние корни не удаляются, а
 * переименовываются в `<корень>.replaced-<время>` (как D-25). Отметка
 * `cloud-restore.ready` пишется последней: без неё разложенное не
 * применяется. Применение повторяемо — прерванное довершается тем же
 * вызовом при следующем запуске.
 *
 * [vfs] — каталог контура приложения (там `live`, `docs`, `known_hosts`).
 */
class CloudRestore(private val vfs: Vfs, private val clock: Clock) {
    /** Разложить файлы копии; итог — каталог раскладки. Прежняя неприменённая раскладка заменяется отметкой. */
    fun stage(entries: List<CopyEntry>): Outcome<String> = io("копия не разложена") {
        val dir = "$STAGE_PREFIX${clock.now().epochMillis}"
        val dirs = sortedSetOf(dir)
        for (e in entries) {
            CloudArchive.pathProblem(e.path)?.let { return@io Outcome.failure(DiagCode.BACKUP_INVALID, it, e.path) }
            val path = Vfs.join(dir, e.path)
            vfs.mkdirs(Vfs.parentOf(path))
            vfs.write(path, e.bytes)
            vfs.fsync(path)
            dirs += Vfs.parentOf(path)
        }
        // Каталог, которого в копии нет, всё равно заменяется (пустым): восстановленный контур — только из копии.
        for (root in CloudArchive.ROOTS) if (root != KNOWN_HOSTS) vfs.mkdirs(Vfs.join(dir, root)).also { dirs += Vfs.join(dir, root) }
        // Записи каталогов — на носитель до отметки (D-24): иначе после сбоя питания отметка есть, а файлов нет.
        for (d in dirs) vfs.fsyncDir(d)
        vfs.write(MARKER_TMP, "$dir\n".toByteArray(Charsets.UTF_8))
        vfs.fsync(MARKER_TMP)
        vfs.rename(MARKER_TMP, MARKER)
        vfs.fsyncDir("")
        Outcome.success(dir)
    }

    /** Ждёт ли раскладка применения при следующем запуске. */
    fun pending(): Boolean = vfs.exists(MARKER)

    /** Отменить разложенное: отметка снимается, при запуске ничего не заменится; раскладка остаётся на диске. */
    fun cancel(): Outcome<Unit> = io("отметка не снята") {
        vfs.delete(MARKER)
        Outcome.success(Unit)
    }

    /**
     * Применить разложенную копию, если она есть: до открытия журнала
     * контура. Итог — применено ли. Отметка снимается последней.
     */
    fun applyPending(): Outcome<Boolean> = io("копия не применена") {
        val dir = vfs.read(MARKER)?.toString(Charsets.UTF_8)?.trim() ?: return@io Outcome.success(false)
        if (!dir.startsWith(STAGE_PREFIX) || !Vfs.isSafeRelative(dir) || '/' in dir) {
            return@io Outcome.failure(DiagCode.BACKUP_INVALID, "отметка восстановления повреждена", MARKER)
        }
        val stamp = dir.removePrefix(STAGE_PREFIX)
        for (root in CloudArchive.ROOTS) {
            val staged = Vfs.join(dir, root)
            if (!present(staged)) continue // уже перенесён прерванным применением
            if (present(root)) vfs.rename(root, free("$root$REPLACED$stamp"))
            vfs.rename(staged, root)
        }
        vfs.fsyncDir("")
        vfs.delete(MARKER)
        Outcome.success(true)
    }

    private fun present(path: String): Boolean = vfs.exists(path) || vfs.isDirectory(path)

    /**
     * Свободное имя для прежнего корня: прерванное применение могло уже
     * занять [base], а контур — создать на месте корня новый пустой; оба
     * сохраняются, ничего не затирается.
     */
    private fun free(base: String): String = generateSequence(0) { it + 1 }.map { if (it == 0) base else "$base-$it" }.first { !present(it) }

    private inline fun <T> io(message: String, block: () -> Outcome<T>): Outcome<T> = try {
        block()
    } catch (e: IOException) {
        Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, message, e)))
    } catch (e: IllegalArgumentException) {
        // Путь, который файловая система не принимает (`InvalidPathException`), — отказ, а не падение запуска.
        Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.BACKUP_INVALID, message, e)))
    }

    companion object {
        const val MARKER = "cloud-restore.ready"
        private const val MARKER_TMP = "cloud-restore.ready.tmp"
        const val STAGE_PREFIX = "cloud-restore-"
        const val REPLACED = ".replaced-"
        private const val KNOWN_HOSTS = "known_hosts"
    }
}
