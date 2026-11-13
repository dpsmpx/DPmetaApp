package com.dpsmpx.dpai.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.edit
import androidx.core.net.toUri
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.files.FileEntry
import com.dpsmpx.dpai.files.FileLink
import com.dpsmpx.dpai.files.FileLinkBook
import com.dpsmpx.dpai.files.FileMeta
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.FileTrees
import com.dpsmpx.dpai.files.FolderSync
import com.dpsmpx.dpai.files.LocalTree
import com.dpsmpx.dpai.files.Side
import com.dpsmpx.dpai.files.SyncItem
import com.dpsmpx.dpai.files.SyncOptions
import com.dpsmpx.dpai.files.SyncReport
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.AppDisk
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * «Доступ ко всем файлам» (D-99): переключатель приложения и системное
 * разрешение `MANAGE_EXTERNAL_STORAGE`. Хранилище привязано к Linux, только
 * если включено и то и другое ([active]).
 */
object AllFiles {
    private const val PREFS = "dpai.files"
    private const val KEY = "allFiles"

    fun granted(): Boolean = Environment.isExternalStorageManager()

    fun wanted(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun active(context: Context): Boolean = wanted(context) && granted()

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY, on) }
    }

    /** Системное окно разрешения для этого приложения. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())

    fun root(): File = Environment.getExternalStorageDirectory()
}

/** Где лежат файлы гостя Linux на стороне приложения и как они называются в госте. */
object GuestPaths {
    /** Домашняя папка гостя (`/root`) в данных приложения. */
    fun home(context: Context): File = File(context.filesDir, AppDisk.LINUX_HOME)

    /** Каталог копий связанных папок: `~/files`. */
    fun copies(context: Context): File = File(home(context), "files")

    fun guestCopy(dir: String): String = "/root/files/$dir"

    fun guestHome(rel: String): String = if (rel.isEmpty()) "/root" else "/root/$rel"

    fun guestShared(rel: String): String = if (rel.isEmpty()) com.dpsmpx.dpai.android.linux.LinuxSetup.GUEST_SHARED else "${com.dpsmpx.dpai.android.linux.LinuxSetup.GUEST_SHARED}/$rel"

    /** Домашняя папка Linux для обзора: секреты — как на диске приложения (D-96), не видны. */
    fun homeTree(context: Context): FileTree = LocalTree(home(context).toPath()) { rel -> AppDisk.hidden("${AppDisk.LINUX_HOME}/$rel") }
}

/**
 * Папка системного окна Android (SAF) как дерево файлов (D-99). Путь —
 * имена от корня папки; идентификаторы документов ищутся по именам.
 * Ожидаемые отказы провайдера (нет прав, папка пропала) — `Outcome`.
 */
class SafTree(private val context: Context, private val tree: Uri) : FileTree {
    private val resolver get() = context.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    private data class Doc(val id: String, val name: String, val mime: String, val size: Long, val modified: Long) {
        val dir: Boolean get() = mime == Document.MIME_TYPE_DIR
    }

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    /**
     * Списки каталогов на время жизни дерева (один план или один проход
     * синхронизации): путь ищется по именам от корня, и без кеша каждый файл
     * заново читал бы все каталоги над ним. Запись и удаление сбрасывают
     * список своего каталога.
     */
    private val cache = HashMap<String, List<Doc>>()

    private fun children(parentId: String): List<Doc> = synchronized(cache) { cache[parentId] } ?: fetch(parentId).also { synchronized(cache) { cache[parentId] = it } }

    private fun forget(parentId: String) = synchronized(cache) { cache.remove(parentId) }

    private fun fetch(parentId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        return resolver.query(uri, cols, null, null, null)?.use { c -> rows(c) } ?: throw IOException("провайдер не ответил")
    }

    private fun rows(c: Cursor): List<Doc> = buildList {
        while (c.moveToNext()) {
            val id = c.getString(0) ?: continue
            add(Doc(id, c.getString(1) ?: continue, c.getString(2) ?: "", if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4)))
        }
    }

    /** Документ по пути; `null` — нет такого. */
    private fun find(path: String): Doc? {
        var cur = Doc(rootId, "", Document.MIME_TYPE_DIR, 0, 0)
        if (path.isEmpty()) return cur
        for (part in path.split('/')) {
            if (!cur.dir) return null
            cur = children(cur.id).firstOrNull { it.name == part } ?: return null
        }
        return cur
    }

    private inline fun <T> guard(path: String, what: String, block: () -> Outcome<T>): Outcome<T> = try {
        block()
    } catch (e: SecurityException) {
        Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "нет доступа к папке — свяжите её заново: ${e.javaClass.simpleName}", path)
    } catch (e: IOException) {
        Outcome.failure(DiagCode.STORAGE_IO_FAILED, "$what: ${e.javaClass.simpleName}", path)
    } catch (e: IllegalArgumentException) {
        Outcome.failure(DiagCode.STORAGE_UNAVAILABLE, "$what: ${e.javaClass.simpleName}", path)
    } catch (e: UnsupportedOperationException) {
        Outcome.failure(DiagCode.UNSUPPORTED, "$what: провайдер не умеет", path)
    }

    override fun list(dir: String): Outcome<List<FileEntry>> = guard(dir, "папка не прочитана") {
        val d = find(dir) ?: return@guard Outcome.failure(DiagCode.STORAGE_UNAVAILABLE, "нет такой папки", dir)
        Outcome.success(children(d.id).map { FileEntry(it.name, it.dir, it.size, it.modified) }.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() })))
    }

    override fun read(path: String, maxBytes: Long): Outcome<ByteArray> = guard(path, "файл не прочитан") {
        val d = find(path) ?: return@guard Outcome.failure(DiagCode.STORAGE_UNAVAILABLE, "нет такого файла", path)
        if (d.dir) return@guard Outcome.failure(DiagCode.VALUE_INVALID, "это папка", path)
        if (d.size > maxBytes) return@guard Outcome.failure(DiagCode.VALUE_INVALID, "файл больше ${maxBytes / 1024} КиБ", path)
        val input = resolver.openInputStream(docUri(d.id)) ?: throw IOException("поток не открылся")
        // Размер у провайдера бывает неточным — предел держит и чтение.
        input.use { readLimited(it, maxBytes) }?.let { Outcome.success(it) }
            ?: Outcome.failure(DiagCode.VALUE_INVALID, "файл больше ${maxBytes / 1024} КиБ", path)
    }

    override fun write(path: String, bytes: ByteArray): Outcome<FileMeta> = guard(path, "файл не записан") {
        if (!FileNames.pathValid(path)) return@guard Outcome.failure(DiagCode.VALUE_INVALID, "путь недопустим", path)
        var parent = find("") ?: throw IOException("нет корня папки")
        val parts = path.split('/')
        for (part in parts.dropLast(1)) {
            val next = children(parent.id).firstOrNull { it.name == part }
            parent = when {
                next == null -> {
                    forget(parent.id)
                    val uri = DocumentsContract.createDocument(resolver, docUri(parent.id), Document.MIME_TYPE_DIR, part) ?: throw IOException("папка не создана")
                    Doc(DocumentsContract.getDocumentId(uri), part, Document.MIME_TYPE_DIR, 0, 0)
                }
                next.dir -> next
                else -> return@guard Outcome.failure(DiagCode.VALUE_INVALID, "часть пути — файл", path)
            }
        }
        val name = parts.last()
        val existing = children(parent.id).firstOrNull { it.name == name }
        if (existing?.dir == true) return@guard Outcome.failure(DiagCode.VALUE_INVALID, "на этом месте папка", path)
        val id = existing?.id ?: run {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
            val uri = DocumentsContract.createDocument(resolver, docUri(parent.id), mime, name) ?: throw IOException("файл не создан")
            DocumentsContract.getDocumentId(uri)
        }
        // «wt» — с усечением: «w» у части провайдеров оставляет хвост прежнего файла.
        resolver.openOutputStream(docUri(id), "wt")?.use { it.write(bytes) } ?: throw IOException("поток записи не открылся")
        forget(parent.id)
        val written = children(parent.id).firstOrNull { it.id == id }
        if (written == null || written.name != name) {
            return@guard Outcome.failure(DiagCode.STORAGE_IO_FAILED, "провайдер сохранил файл под другим именем: ${written?.name}", path)
        }
        Outcome.success(FileMeta(written.size, written.modified))
    }

    override fun delete(path: String): Outcome<Unit> = guard(path, "файл не удалён") {
        val d = find(path) ?: return@guard Outcome.failure(DiagCode.STORAGE_UNAVAILABLE, "нет такого файла", path)
        if (d.dir) return@guard Outcome.failure(DiagCode.VALUE_INVALID, "удаляются только файлы", path)
        if (!DocumentsContract.deleteDocument(resolver, docUri(d.id))) throw IOException("провайдер не удалил")
        forget(parentIdOf(path))
        Outcome.success(Unit)
    }

    private fun parentIdOf(path: String): String = find(FileNames.parent(path))?.id ?: rootId

    /** Имя папки для экрана. */
    fun title(): String = guard("", "имя") { Outcome.success(resolverName().orEmpty()) }.valueOrNull().orEmpty().ifBlank { "папка" }

    private fun resolverName(): String? =
        resolver.query(docUri(rootId), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    companion object {
        /** Поток целиком, не больше [max]; длиннее — `null`. */
        fun readLimited(input: java.io.InputStream, max: Long): ByteArray? {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) return out.toByteArray()
                out.write(buf, 0, n)
                if (out.size() > max) return null
            }
        }
    }
}

/**
 * Связи папок с копиями в Linux (D-99): документ `dpai.file-links` в
 * данных приложения (`app_files/`, вне диска приложения и копий);
 * постоянные права на папки — у системы, отзываются при «Отвязать».
 */
// init сохраняет только context.applicationContext; ссылки на Activity здесь нет.
@SuppressLint("StaticFieldLeak")
object FileLinks {
    private const val DOC = "file-links"

    private lateinit var app: Context
    private var docs: DocumentStore? = null
    private val ids = IdGenerator(Clock.SYSTEM, RandomSource.secure())
    private val flow = MutableStateFlow<List<FileLink>>(emptyList())
    private val problemFlow = MutableStateFlow<String?>(null)

    val links: StateFlow<List<FileLink>> = flow
    val problem: StateFlow<String?> = problemFlow

    /** С фонового потока, один раз на процесс. */
    @Synchronized
    fun init(context: Context) {
        if (docs != null) return
        app = context.applicationContext
        val d = DocumentStore(NioVfs(app.getDir("files", Context.MODE_PRIVATE).toPath()), "docs")
        docs = d
        flow.value = when (val r = d.read(DOC)) {
            is Outcome.Failure -> emptyList<FileLink>().also { problemFlow.value = "связи папок не прочитаны: ${r.errors.joinToString("; ") { it.message }}" }
            is Outcome.Success -> r.value?.let { stored ->
                when (val p = FileLinkBook.fromJson(stored.content)) {
                    is Outcome.Success -> p.value
                    is Outcome.Failure -> null.also { problemFlow.value = "связи папок не прочитаны: ${p.errors.joinToString("; ") { "${it.message} (${it.subject})" }}" }
                }
            }.orEmpty()
        }
    }

    @Synchronized
    private fun save(next: List<FileLink>): Outcome<Unit> {
        val d = docs ?: return Outcome.failure(DiagCode.STORAGE_UNAVAILABLE, "хранилище не готово")
        return when (val w = d.write(DOC, FileLinkBook.toJson(next))) {
            is Outcome.Failure -> w
            is Outcome.Success -> Outcome.success(Unit).also { flow.value = next }
        }
    }

    fun copyRoot(link: FileLink): File = File(GuestPaths.copies(app), link.dir)

    fun local(link: FileLink): LocalTree = LocalTree(copyRoot(link).toPath())

    fun remote(link: FileLink): SafTree = SafTree(app, link.tree.toUri())

    /** Связать папку [tree]: права — постоянными, копия — новый каталог `~/files/<имя>`. */
    @Synchronized
    fun add(tree: Uri): Outcome<FileLink> {
        if (com.dpsmpx.dpai.android.linux.LinuxSetup.environment(app).installed() == null) {
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "сначала установите Linux (Targets → Linux в приложении)")
        }
        if (flow.value.size >= FileLinkBook.MAX_LINKS) return Outcome.failure(DiagCode.VALUE_INVALID, "связей больше ${FileLinkBook.MAX_LINKS}")
        if (flow.value.any { it.tree == tree.toString() }) return Outcome.failure(DiagCode.VALUE_INVALID, "эта папка уже связана")
        try {
            app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "система не дала постоянный доступ к папке: ${e.javaClass.simpleName}")
        }
        val title = SafTree(app, tree).title()
        val taken = flow.value.map { it.dir }.toSet() + (GuestPaths.copies(app).list()?.toSet() ?: emptySet())
        val link = FileLink(ids.next(IdKinds.CORRELATION), title, tree.toString(), FileNames.localDir(title, taken), null, emptyMap())
        if (!copyRoot(link).mkdirs()) return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "каталог копии не создан", link.dir)
        return save(flow.value + link).let { if (it is Outcome.Failure) it else Outcome.success(link) }
    }

    /** Отвязать: права на папку отозваны, копия в Linux остаётся. */
    @Synchronized
    fun remove(link: FileLink): Outcome<Unit> {
        runCatching { app.contentResolver.releasePersistableUriPermission(link.tree.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        return save(flow.value.filter { it.id != link.id })
    }

    /** План синхронизации и пропущенные имена; блокирующий — с фонового потока. */
    fun plan(link: FileLink): Outcome<Pair<List<SyncItem>, List<String>>> {
        val r = remote(link)
        val l = local(link)
        val rs = when (val s = FileTrees.scan(r)) {
            is Outcome.Failure -> return s
            is Outcome.Success -> s.value
        }
        val ls = when (val s = FileTrees.scan(l)) {
            is Outcome.Failure -> return s
            is Outcome.Success -> s.value
        }
        val plan = FolderSync.plan(rs.files, ls.files, link.base) { side, p -> FolderSync.hashOf(if (side == Side.REMOTE) r else l, p) }
        return Outcome.success(plan to (rs.skipped + ls.skipped))
    }

    /** Выполнить план и записать базу; блокирующий — с фонового потока. */
    @Synchronized
    fun apply(link: FileLink, plan: List<SyncItem>, options: SyncOptions): Outcome<SyncReport> {
        val current = flow.value.firstOrNull { it.id == link.id } ?: return Outcome.failure(DiagCode.STORAGE_UNAVAILABLE, "связь удалена")
        val report = FolderSync.apply(plan, remote(current), local(current), current.base, options)
        val updated = current.copy(base = report.base, lastSync = Clock.SYSTEM.now().epochMillis)
        return when (val s = save(flow.value.map { if (it.id == current.id) updated else it })) {
            is Outcome.Failure -> s
            is Outcome.Success -> Outcome.success(report)
        }
    }
}
