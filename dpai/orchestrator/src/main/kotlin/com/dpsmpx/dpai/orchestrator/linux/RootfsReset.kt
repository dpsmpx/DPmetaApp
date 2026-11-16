package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Сброс Alpine целиком (D-196; `DPmetaApp:D-171`, шаг 6): корневая система — заново из APK,
 * дом гостя — сохраняется: `~/projects` и `~/ai` всегда, остальное — по выбору человека; кеш
 * файлов пакетов (D-194) — тоже, иначе откат к снимку «перед сбросом» не вернул бы версии.
 *
 * Порядок держит данные при обрыве на любом шаге: сначала снимается признак готовности
 * (оборванный сброс — «Linux не установлен», и установка его доделает), сохраняемое
 * переносится переименованием в [KEPT] рядом с корневой системой (вне гостя, на том же
 * диске), старый корень удаляется через [Vfs] без перехода по ссылкам; после распаковки
 * [restore] возвращает сохранённое на место — и при следующей установке, если сброс оборвался
 * раньше. [host] — каталог данных приложения, тот же, что корень [vfs]: обход только читает.
 */
class RootfsReset(private val vfs: Vfs, private val host: Path, dir: String = LinuxEnvironment.DIR) {
    private val ready = Vfs.join(dir, LinuxEnvironment.READY)
    private val rootfs = Vfs.join(dir, "rootfs")
    private val home = Vfs.join(rootfs, "root")
    private val kept = Vfs.join(dir, KEPT)
    private val cache = Vfs.join(rootfs, LinuxEnvironment.CACHE_DIR.removePrefix("/"))

    /** Сброс начат и сохранённое ещё не возвращено. */
    fun pending(): Boolean = vfs.isDirectory(kept)

    /** Что сохранится: [wholeHome] — весь `~`, иначе — [ALWAYS]. */
    fun keeps(wholeHome: Boolean): List<String> = if (wholeHome) listOf("~") else ALWAYS.map { "~/$it" }

    /**
     * Шаг 1: снять готовность, отложить дом, удалить корень. Итог — сколько удалено файлов.
     * Повторный вызов после обрыва — безопасен: отложенное не перезаписывается.
     */
    fun begin(wholeHome: Boolean): Outcome<Int> {
        return try {
            vfs.delete(ready)
            vfs.mkdirs(kept)
            if (wholeHome) {
                if (vfs.isDirectory(home) && !vfs.isDirectory(Vfs.join(kept, "root"))) vfs.rename(home, Vfs.join(kept, "root"))
            } else {
                vfs.mkdirs(Vfs.join(kept, "parts"))
                for (name in ALWAYS) {
                    val from = Vfs.join(home, name)
                    val to = Vfs.join(Vfs.join(kept, "parts"), name)
                    if ((vfs.isDirectory(from) || vfs.isSymlink(from)) && !vfs.isDirectory(to)) vfs.rename(from, to)
                }
            }
            // Кеш apk (D-194) — единственный источник старых версий для отката к снимку «перед сбросом».
            val cacheParents = listOf(Vfs.join(rootfs, "var"), Vfs.join(Vfs.join(rootfs, "var"), "cache"))
            if (vfs.isDirectory(cache) && cacheParents.none(vfs::isSymlink) && !vfs.isDirectory(Vfs.join(kept, "apk-cache"))) vfs.rename(cache, Vfs.join(kept, "apk-cache"))
            Outcome.success(deleteTree(rootfs))
        } catch (e: IOException) {
            Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "сброс Alpine остановлен — сохранённое цело в $kept", e, kept)))
        }
    }

    /**
     * Шаг 2 (после распаковки): вернуть отложенное. Весь дом — вместо свежего `/root`;
     * части — на их места; занятое место не перезаписывается — отложенное ложится рядом с
     * приставкой `restored-`. В конце [KEPT] удаляется.
     */
    fun restore(): Outcome<List<String>> {
        if (!pending()) return Outcome.success(emptyList())
        val back = mutableListOf<String>()
        return try {
            val whole = Vfs.join(kept, "root")
            if (vfs.isDirectory(whole) || vfs.isSymlink(whole)) {
                if (vfs.isDirectory(home)) deleteTree(home)
                vfs.rename(whole, home)
                back += "~"
            }
            val parts = Vfs.join(kept, "parts")
            if (vfs.isDirectory(parts)) {
                vfs.mkdirs(home)
                for (name in ALWAYS) {
                    val from = Vfs.join(parts, name)
                    if (!vfs.isDirectory(from) && !vfs.isSymlink(from)) continue
                    val to = Vfs.join(home, name)
                    val free = !vfs.isDirectory(to) && !vfs.isSymlink(to) && !vfs.exists(to)
                    vfs.rename(from, if (free) to else Vfs.join(home, "restored-$name"))
                    back += "~/$name"
                }
            }
            val keptCache = Vfs.join(kept, "apk-cache")
            if (vfs.isDirectory(keptCache) && !vfs.isSymlink(Vfs.join(rootfs, "var")) && !vfs.isSymlink(Vfs.join(Vfs.join(rootfs, "var"), "cache"))) {
                vfs.mkdirs(Vfs.join(Vfs.join(rootfs, "var"), "cache"))
                if (vfs.isDirectory(cache)) deleteTree(cache)
                vfs.rename(keptCache, cache)
            }
            deleteTree(kept)
            Outcome.success(back)
        } catch (e: IOException) {
            Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "сохранённое возвращено не всё — остальное в $kept", e, kept)))
        }
    }

    /** Удалить дерево [rel]: обход — чтением без перехода по ссылкам, удаление — через [vfs]. */
    private fun deleteTree(rel: String): Int {
        val at = host.resolve(rel)
        if (!Files.exists(at, LinkOption.NOFOLLOW_LINKS)) return 0
        if (Files.isSymbolicLink(at) || !Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS)) {
            vfs.delete(rel)
            return 1
        }
        val order = mutableListOf<String>()
        Files.walkFileTree(
            at,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    order += host.relativize(file).toString().replace('\\', '/')
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    order += host.relativize(dir).toString().replace('\\', '/')
                    return FileVisitResult.CONTINUE
                }
            },
        )
        for (p in order) vfs.delete(p)
        return order.size
    }

    companion object {
        /** Каталог отложенного дома рядом с корневой системой. */
        const val KEPT = "reset-kept"

        /** Что сохраняется всегда. */
        val ALWAYS: List<String> = listOf(GuestStorage.PROJECTS, GuestStorage.AI)

        /** Имя, которое человек вводит для подтверждения (D-37). */
        const val CONFIRM = "Alpine"
    }
}
