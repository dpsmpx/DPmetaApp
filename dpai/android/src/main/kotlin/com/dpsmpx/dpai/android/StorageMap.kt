package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.DisplayZone
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.linux.GuestStorage
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import com.dpsmpx.dpai.ui.AppDisk
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Одно хранилище на телефоне (`DPmetaApp:D-111`, `DPmetaApp:D-113`; D-143): разделы
 * домашней папки гостя, перенос из прежних мест — один раз на процесс, `~/tools/bin`
 * в `PATH` оболочек входа, карта `~/STORAGE.md` и строка о ней в инструкции каждого
 * слота ИИ. Каталоги приложения, которые должны жить в разделе гостя (клоны пульта),
 * отдаёт приложение через [adopt]; секреты для карты — список [SecretGrants], без значений.
 */
object StorageMap {
    private val adopted = CopyOnWriteArrayList<Pair<File, String>>()
    private val prepared = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)

    /** Каталог приложения [host] переезжает в раздел гостя [section] (`projects`); на старом месте — ссылка. */
    fun adopt(host: File, section: String) {
        require(section in GuestStorage.SECTIONS) { "раздел хранилища: $section" }
        if (adopted.none { it.first == host }) adopted += host to section
    }

    /**
     * Разделы и перенос — один раз на процесс, до первого чтения клонов и запуска гостя:
     * переносы — переименования в пределах тома, без копирования. Итог — в журнал приложения.
     */
    fun prepare(context: Context) {
        if (!prepared.compareAndSet(false, true)) return
        val app = context.applicationContext
        val root = app.filesDir.toPath()
        val home = AppDisk.LINUX_HOME
        val moves = adopted.map { (host, section) -> GuestStorage.Move(root.relativize(host.toPath()).toString(), "$home/$section") } +
            GuestStorage.legacyMoves(root, home)
        val r = GuestStorage.migrate(root, moves)
        GuestStorage.ensure(root, home)
        if (LinuxSetup.environment(app).installed() != null) LinuxSetup.environment(app).writeProfile()
        when (r) {
            is Outcome.Success -> if (r.value.moved.isNotEmpty() || r.value.skipped.isNotEmpty()) {
                AppLogs.info(app, "хранилище", "перенос: ${r.value.moved.size}, ссылок ${r.value.linked.size}, пропущено ${r.value.skipped.size}" +
                    r.value.skipped.take(3).joinToString("") { "; $it" })
            }
            is Outcome.Failure -> AppLogs.info(app, "хранилище", "перенос не закончен: ${r.errors.first().code}")
        }
    }

    /** Обновить карту и строки в инструкциях слотов — в фоне; частые вызовы сливаются. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        dirty.set(true)
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                while (dirty.getAndSet(false)) runCatching { write(app) }
            } finally {
                running.set(false)
            }
        }.start()
    }

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    private fun write(app: Context) {
        prepare(app)
        val home = GuestPaths.home(app).toPath()
        val rootfs = File(app.filesDir, LinuxSetup.environment(app).rootfs).toPath()
        val space = StorageSpace.facts(app)
        val at = Instant.ofEpochMilli(Clock.SYSTEM.now().epochMillis).atZone(DisplayZone.current).format(TIME)
        val secrets = runCatching { SecretGrants.rows(app) }.getOrDefault(emptyList()).map { GuestStorage.SecretLine(it.name, it.variable, it.toAi, it.alias) }
        val facts = GuestStorage.scan(home, rootfs, at, secrets, space.free, space.limit)
        replace(home, GuestStorage.MAP, GuestStorage.render(facts))
        val ai = home.resolve(GuestStorage.AI)
        val slots = runCatching { Files.list(ai).use { s -> s.iterator().asSequence().toList() } }.getOrDefault(emptyList())
        for (slot in slots) {
            if (Files.isSymbolicLink(slot) || !Files.isDirectory(slot, LinkOption.NOFOLLOW_LINKS)) continue
            val rel = GuestStorage.instructionFile(slot.fileName.toString()) ?: continue
            val file = slot.resolve(rel)
            // Ссылка по пути — не наш файл: гость мог направить её куда угодно.
            if (generateSequence(file) { it.parent }.takeWhile { it != slot }.any { Files.isSymbolicLink(it) }) continue
            val old = runCatching { NioVfs(slot).read(rel)?.toString(Charsets.UTF_8) }.getOrNull()
            val text = GuestStorage.withPointer(old)
            if (text != old) replace(slot, rel, text)
        }
    }

    /**
     * Записать [rel] в [dir] целиком через `Vfs` хранилища (D-24): черновик рядом и
     * переименование — ссылка на месте файла заменяется, а не её цель; ссылку на месте
     * черновика гость мог подложить — она убирается до записи.
     */
    private fun replace(dir: Path, rel: String, text: String) {
        val vfs = NioVfs(dir)
        val parent = Vfs.parentOf(rel)
        val draft = Vfs.join(parent, ".${rel.substringAfterLast('/')}.dpmetaapp")
        try {
            if (parent.isNotEmpty()) vfs.mkdirs(parent)
            vfs.delete(draft)
            vfs.write(draft, text.toByteArray())
            vfs.rename(draft, rel)
        } catch (_: IOException) {
            // Не записалось — карта обновится при следующем изменении.
        }
    }
}
