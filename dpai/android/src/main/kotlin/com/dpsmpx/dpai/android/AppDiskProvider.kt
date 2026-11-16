package com.dpsmpx.dpai.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.AppDisk
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import android.app.Notification as AndroidNotification

/**
 * Диск приложения (D-96): данные приложения — корнем «DPmetaApp» системного
 * выбора файлов (SAF); его открывают Total Commander и другие менеджеры
 * файлов. Что видно и что можно писать — `AppDisk` (ui, с тестами); здесь —
 * курсоры, дескрипторы и отметка «открыт».
 *
 * Закрытый диск корня не даёт и на любой вызов отвечает «не найдено».
 * Файл открывается без следования ссылкам (`O_NOFOLLOW`), а настоящий путь
 * открытого дескриптора сверяется ещё раз: подмена ссылкой между проверкой
 * и открытием не выводит к секрету (угроза T15).
 */
class AppDiskProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    private val ctx: Context get() = checkNotNull(context)

    private val base: Path get() = ctx.filesDir.toPath()

    private val vfs: NioVfs get() = NioVfs(base)

    private fun guard() {
        if (!AppDisk.isOpen(ctx)) throw FileNotFoundException("диск приложения закрыт")
    }

    private fun rel(documentId: String): String {
        val r = documentId.removePrefix(DOC_PREFIX)
        if (!documentId.startsWith(DOC_PREFIX) || !AppDisk.valid(r)) throw FileNotFoundException("нет такого документа")
        return r
    }

    private fun path(documentId: String): Path = AppDisk.resolve(base, rel(documentId)) ?: throw FileNotFoundException("нет такого документа")

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_COLUMNS)
        if (!AppDisk.isOpen(ctx)) return c
        val home = Files.isDirectory(base.resolve(AppDisk.LINUX_HOME), LinkOption.NOFOLLOW_LINKS)
        c.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, DOC_PREFIX)
            add(Root.COLUMN_TITLE, "DP_IDE")
            add(Root.COLUMN_SUMMARY, "данные приложения: чтение; запись — Linux /root")
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD or if (home) Root.FLAG_SUPPORTS_CREATE else 0)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        guard()
        return MatrixCursor(projection ?: DOC_COLUMNS).also { row(it, rel(documentId), path(documentId)) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        guard()
        val parent = rel(parentDocumentId)
        val dir = path(parentDocumentId)
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        for (name in AppDisk.children(base, parent)) row(c, if (parent.isEmpty()) name else "$parent/$name", dir.resolve(name))
        return c
    }

    private fun row(c: MatrixCursor, rel: String, p: Path) {
        val dir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
        var flags = 0
        if (dir && AppDisk.canCreateIn(rel)) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        if (AppDisk.writable(rel)) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            if (!dir) flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, DOC_PREFIX + rel)
            add(Document.COLUMN_DISPLAY_NAME, if (rel.isEmpty()) "DP_IDE" else rel.substringAfterLast('/'))
            add(Document.COLUMN_MIME_TYPE, if (dir) Document.MIME_TYPE_DIR else mime(rel))
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (dir) null else runCatching { Files.size(p) }.getOrNull())
            add(Document.COLUMN_LAST_MODIFIED, runCatching { Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis() }.getOrNull())
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        guard()
        val parent = rel(parentDocumentId)
        val child = rel(documentId)
        return parent.isEmpty() || child.startsWith("$parent/")
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        guard()
        val r = rel(documentId)
        val p = path(documentId)
        val write = mode != "r"
        if (write && !AppDisk.writable(r)) throw SecurityException("диск приложения: запись — только в папке Linux /root")
        val flags = OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or when (mode) {
            "r" -> OsConstants.O_RDONLY
            // A-03: openDocument открывает существующий ID; создание — createDocument.
            // Усечение выполняется только после проверки настоящего пути дескриптора.
            "w", "wt" -> OsConstants.O_WRONLY
            "wa" -> OsConstants.O_WRONLY or OsConstants.O_APPEND
            "rw", "rwt" -> OsConstants.O_RDWR
            else -> throw IllegalArgumentException("неизвестный режим: $mode")
        }
        val fd = try {
            Os.open(p.toString(), flags, 0b110_000_000)
        } catch (e: android.system.ErrnoException) {
            throw FileNotFoundException("не открыт: ${e.message}")
        }
        try {
            val pfd = ParcelFileDescriptor.dup(fd)
            // Настоящий путь открытого — ещё раз (гонка «проверил — подменили — открыл», T15).
            val real = runCatching { Os.readlink("/proc/self/fd/${pfd.fd}") }.getOrNull()
            val refused = if (real == null) "настоящий путь открытого файла не прочитан" else AppDisk.refuseOpened(base, real)
            if (refused != null) {
                pfd.close()
                throw FileNotFoundException(refused)
            }
            if (mode == "w" || mode == "wt" || mode == "rwt") {
                try {
                    Os.ftruncate(fd, 0)
                } catch (error: android.system.ErrnoException) {
                    pfd.close()
                    throw FileNotFoundException("файл не усечён после проверки")
                }
            }
            return pfd
        } finally {
            Os.close(fd)
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        guard()
        val parent = rel(parentDocumentId)
        if (!AppDisk.canCreateIn(parent) || !AppDisk.validName(displayName)) throw SecurityException("диск приложения: создать можно только в папке Linux /root")
        val dir = path(parentDocumentId)
        if (Files.exists(dir.resolve(displayName), LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("«$displayName» уже есть")
        // Запись — через Vfs (D-24), пути — относительные от корня данных.
        val created = "$parent/$displayName"
        if (mimeType == Document.MIME_TYPE_DIR) vfs.mkdirs(created) else vfs.write(created, ByteArray(0))
        return DOC_PREFIX + created
    }

    override fun deleteDocument(documentId: String) {
        guard()
        val r = rel(documentId)
        if (!AppDisk.writable(r)) throw SecurityException("диск приложения: удалить можно только в папке Linux /root")
        val p = path(documentId)
        // Каталог — вглубь без следования ссылкам: ссылка удаляется сама, а не то, на что она указывает.
        val all = if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) Files.walk(p).use { s -> s.iterator().asSequence().toList() } else listOf(p)
        // Скрытое внутри (`.ssh`, файл входа) диск не трогает и удалением каталога: секреты — только в терминале Linux.
        if (all.any { AppDisk.hidden(base.relativize(it).toString()) }) throw SecurityException("в каталоге есть скрытое (ключи, вход Claude): удалите его в терминале Linux")
        for (each in all.sortedDescending()) vfs.delete(base.relativize(each).toString())
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        guard()
        val r = rel(documentId)
        if (!AppDisk.writable(r) || !AppDisk.validName(displayName)) throw SecurityException("диск приложения: переименовать можно только в папке Linux /root")
        val p = path(documentId)
        if (Files.exists(p.resolveSibling(displayName), LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("«$displayName» уже есть")
        val parent = r.substringBeforeLast('/', "")
        val renamed = if (parent.isEmpty()) displayName else "$parent/$displayName"
        vfs.rename(r, renamed)
        return DOC_PREFIX + renamed
    }

    private fun mime(rel: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(rel.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    companion object {
        private const val ROOT_ID = "dpmetaapp"
        private const val DOC_PREFIX = "app:"

        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON)
        private val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )

        fun authority(context: Context) = "${context.packageName}.disk"
    }
}

/**
 * Открыть и закрыть диск приложения (D-96): отметка в данных приложения,
 * уведомление «Диск приложения открыт» с «Закрыть» и сигнал системе, что
 * корни сменились.
 */
fun AppDisk.isOpen(context: Context): Boolean = File(context.filesDir, AppDisk.OPEN_FLAG).isFile

fun AppDisk.setOpen(context: Context, open: Boolean) {
    val vfs = NioVfs(context.filesDir.toPath())
    runCatching { if (open) vfs.write(AppDisk.OPEN_FLAG, "открыт человеком\n".toByteArray()) else vfs.delete(AppDisk.OPEN_FLAG) }
    context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(AppDiskProvider.authority(context)), null)
    AppDiskNotice.sync(context)
    AppDiskNotice.changed(AppDisk.isOpen(context))
}

/** Уведомление, пока диск открыт (D-96): в шторке видно, что файлы приложения открыты, и закрыть — одним нажатием. */
object AppDiskNotice {
    private const val CHANNEL = "dpai-disk"
    private const val ID = 0x0D15C

    /**
     * Кто показывает переключатель: «Закрыть» в шторке меняет отметку на диске, и открытый
     * экран должен это увидеть сразу — шторка не уводит экран в паузу (аудит инструкции
     * 2 ноября 2026, A-26). Слушатели — на главном потоке: приёмник и переключатель — там же.
     */
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(Boolean) -> Unit>()

    fun listen(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    fun unlisten(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    internal fun changed(open: Boolean) = listeners.forEach { it(open) }

    fun sync(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!AppDisk.isOpen(context)) {
            manager.cancel(ID)
            return
        }
        NotifyGroups.ensure(manager)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Диск приложения", NotificationManager.IMPORTANCE_LOW).apply {
                group = NotifyGroups.SERVICE
                description = "Пока открыт: файлы видны менеджерам файлов"
            },
        )
        val close = PendingIntent.getBroadcast(
            context, ID, Intent(context, AppDiskCloseReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = AndroidNotification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify_ai)
            .setContentTitle("Диск приложения открыт")
            .setContentText("Файлы DP_IDE видны менеджерам файлов (без секретов)")
            .setOngoing(true)
            .addAction(AndroidNotification.Action.Builder(null as android.graphics.drawable.Icon?, "Закрыть", close).build())
            .build()
        manager.notify(ID, n)
    }
}

/** «Закрыть» из уведомления: приёмник не экспортируется. */
class AppDiskCloseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppDisk.setOpen(context.applicationContext, false)
    }
}
