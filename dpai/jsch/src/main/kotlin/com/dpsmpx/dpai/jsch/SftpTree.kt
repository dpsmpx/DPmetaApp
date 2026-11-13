package com.dpsmpx.dpai.jsch

import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.then
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.files.FileEntry
import com.dpsmpx.dpai.files.FileMeta
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.files.FileTree
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import java.io.IOException
import java.io.InputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Операции SFTP, нужные [SftpTree] (D-162): на деле — JSch `ChannelSftp`, в тестах —
 * двойник в памяти. Пути — как их понимает сервер; отказ — `IOException`
 * ([NoSuchFile] — нет такого файла или каталога).
 */
interface SftpOps : AutoCloseable {
    /**
     * Элемент каталога: [link] — символьная ссылка; [modifiedSec] — время изменения, секунды;
     * [mode] — права POSIX (`0644`…), `null` — сервер не сообщил.
     */
    data class Entry(val name: String, val dir: Boolean, val link: Boolean, val size: Long, val modifiedSec: Long, val mode: Int? = null)

    class NoSuchFile(path: String) : IOException("нет такого файла: $path")

    /** Сервер отказал в действии (права, «не каталог», занято) — связь цела, переподключаться незачем. */
    class Refused(message: String) : IOException(message)

    fun list(path: String): List<Entry>

    fun stat(path: String): Entry

    fun open(path: String): InputStream

    /** Записать файл [path] из [input] целиком (создать или заменить). */
    fun write(path: String, input: InputStream)

    /** Переименовать [from] в [to]; [to] существует — сервер может отказать (Windows OpenSSH). */
    fun rename(from: String, to: String)

    fun remove(path: String)

    fun mkdir(path: String)

    /** Права файла [path] — [mode] (POSIX); сервер без прав (Windows) может их не применить. */
    fun chmod(path: String, mode: Int)

    /** Абсолютный путь [path] на сервере (`.` — домашний каталог входа). */
    fun realpath(path: String): String = throw IOException("сервер SFTP не назвал путь $path")

    override fun close()
}

/**
 * Файлы дерева на ПК по SFTP (`DPmetaApp:D-127`, D-131; D-162, D-166): список
 * каталога, чтение и копирование файла кусками; с [writable] — запись (временный
 * файл и переименование), без него — отказ. Удаления нет. Пути — относительные от
 * корня дерева [root] (`FileTree`); символьные ссылки и негодные имена не
 * показываются и не проходятся. Одно подключение — закрыть [close].
 */
class SftpTree(
    private val ops: SftpOps,
    root: String,
    platform: TargetPlatform,
    private val writable: Boolean = false,
    private val random: RandomSource = RandomSource.secure(),
) : FileTree, AutoCloseable {
    /** Корень дерева так, как его видит сервер SFTP. */
    val root: String = remotePath(platform, root)

    private fun abs(rel: String): Outcome<String> = when {
        rel.isNotEmpty() && !FileNames.pathValid(rel) -> Outcome.failure(DiagCode.VALUE_INVALID, "путь недопустим", rel)
        // JSch понимает `*` и `?` в последнем сегменте как шаблон: rm и put задели бы другие файлы.
        rel.any { it in GLOB } -> Outcome.failure(DiagCode.VALUE_INVALID, "имя с «*» или «?» по SFTP не открыть: сервер понимает его как шаблон", rel)
        else -> Outcome.success(if (rel.isEmpty()) root else root.trimEnd('/') + "/" + rel)
    }

    override fun list(dir: String): Outcome<List<FileEntry>> = abs(dir).then { path ->
        attempt(dir, "список не прочитан") {
            checkDirs(dir)
            ops.list(path).filter { it.name != "." && it.name != ".." && !it.link && FileNames.segmentValid(it.name) && it.name.none { c -> c in GLOB } }
                .map { FileEntry(it.name, it.dir, if (it.dir) 0 else it.size, it.modifiedSec * 1000) }
        }
    }

    override fun read(path: String, maxBytes: Long): Outcome<ByteArray> {
        val out = java.io.ByteArrayOutputStream()
        return copy(path, maxBytes) { b, n -> out.write(b, 0, n) }.then { Outcome.success(out.toByteArray()) }
    }

    /**
     * Записать файл (D-166): недостающие каталоги создаются, байты — во временный файл
     * того же каталога со случайным именем, которого нет (`lstat`: ссылка из репозитория
     * на месте временного файла увела бы запись за корень дерева), права прежнего файла —
     * временному, затем переименование поверх. Сервер не переименовывает поверх (Windows
     * OpenSSH) — прежний файл удаляется и временный переименовывается; не встал на место —
     * временный остаётся (в нём новая версия) и назван в отказе. Прочий отказ — временный убран.
     */
    override fun write(path: String, bytes: ByteArray): Outcome<FileMeta> {
        if (!writable) return readOnly(path)
        if (path.isEmpty()) return Outcome.failure(DiagCode.VALUE_INVALID, "не файл", path)
        val target = when (val r = abs(path)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val parent = FileNames.parent(path)
        return attempt(path, "файл не записан") {
            ensureDirs(parent)
            val before = try { ops.stat(target) } catch (_: SftpOps.NoSuchFile) { null }
            if (before != null && (before.link || before.dir)) throw SftpOps.Refused("на месте файла — ссылка или каталог")
            val tmp = freshTemp(parent, FileNames.name(path))
            try {
                ops.write(tmp, bytes.inputStream())
                // Скрипт не теряет +x, файл с правами 600 не становится читаемым всем; сервер без
                // прав POSIX (Windows) может отказать — запись от этого не отменяется.
                before?.mode?.let { m -> try { ops.chmod(tmp, m) } catch (_: SftpOps.Refused) { } }
            } catch (e: IOException) {
                runCatching { ops.remove(tmp) }
                throw e
            }
            try {
                ops.rename(tmp, target)
            } catch (e: IOException) {
                if (e is SftpOps.NoSuchFile || before == null) {
                    runCatching { ops.remove(tmp) }
                    throw e
                }
                try {
                    ops.remove(target)
                } catch (r: IOException) {
                    runCatching { ops.remove(tmp) }
                    throw r
                }
                try {
                    ops.rename(tmp, target)
                } catch (r: IOException) {
                    // Прежнего файла уже нет: новая версия — только во временном, его не трогать.
                    throw SftpOps.Refused("прежний файл удалён, а новый не встал на место — он в ${FileNames.name(tmp)}: ${r.message?.take(200)}")
                }
            }
            val e = ops.stat(target)
            FileMeta(e.size, e.modifiedSec * 1000)
        }
    }

    /** Путь временного файла в каталоге [parent]: случайное имя, которого на сервере нет. */
    private fun freshTemp(parent: String, name: String): String {
        repeat(3) {
            val suffix = random.nextBytes(8).joinToString("") { b -> "%02x".format(b) }
            val p = root.trimEnd('/') + "/" + FileNames.join(parent, FileNames.TMP_PREFIX + name + "-" + suffix)
            try {
                ops.stat(p)
            } catch (_: SftpOps.NoSuchFile) {
                return p
            }
        }
        throw SftpOps.Refused("имя временного файла не нашлось: все заняты")
    }

    /** Создать недостающие каталоги [dir] (относительный путь) по одному. */
    private fun ensureDirs(dir: String) {
        if (dir.isEmpty()) return
        var cur = ""
        for (seg in dir.split('/')) {
            cur = FileNames.join(cur, seg)
            val p = root.trimEnd('/') + "/" + cur
            val e = try { ops.stat(p) } catch (_: SftpOps.NoSuchFile) { null }
            when {
                e == null -> ops.mkdir(p)
                e.link || !e.dir -> throw SftpOps.Refused("часть пути — не каталог: $cur")
            }
        }
    }

    /** Ссылка в середине пути столь же недопустима, как ссылка на сам файл. */
    private fun checkDirs(dir: String) {
        if (dir.isEmpty()) return
        var current = root.trimEnd('/')
        for (part in dir.split('/')) {
            current += "/$part"
            val entry = ops.stat(current)
            if (entry.link || !entry.dir) throw SftpOps.Refused("часть пути — ссылка или не каталог")
        }
    }

    override fun delete(path: String): Outcome<Unit> = Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "удаление файлов ПК из приложения недоступно", path)

    /** Сведения о файле [path]: размер и время изменения (мс). */
    fun meta(path: String): Outcome<FileMeta> = abs(path).then { p ->
        attempt(path, "сведения не прочитаны") {
            checkDirs(FileNames.parent(path))
            val e = ops.stat(p)
            if (e.link || e.dir) throw IOException("не файл")
            FileMeta(e.size, e.modifiedSec * 1000)
        }
    }

    /**
     * Скопировать файл [path] в [sink] кусками до [CHUNK] байт; файл больше [maxBytes] —
     * отказ до чтения; [cancelled] — прервать между кусками. Итог — число байт.
     */
    fun copy(path: String, maxBytes: Long, cancelled: () -> Boolean = { false }, sink: (ByteArray, Int) -> Unit): Outcome<Long> {
        val p = when (val r = abs(path)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (path.isEmpty()) return Outcome.failure(DiagCode.VALUE_INVALID, "не файл", path)
        return attempt(path, "файл не прочитан") {
            checkDirs(FileNames.parent(path))
            val e = ops.stat(p)
            if (e.link || e.dir) return Outcome.failure(DiagCode.VALUE_INVALID, "не файл", path)
            if (e.size > maxBytes) return Outcome.failure(DiagCode.VALUE_INVALID, "файл больше ${maxBytes shr 20} МиБ", path)
            var total = 0L
            ops.open(p).use { input ->
                val buffer = ByteArray(CHUNK)
                while (true) {
                    if (cancelled()) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "скачивание отменено", path)
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    // Файл рос во время чтения — предел держится и тогда.
                    if (total > maxBytes) return Outcome.failure(DiagCode.VALUE_INVALID, "файл больше ${maxBytes shr 20} МиБ", path)
                    sink(buffer, n)
                }
            }
            total
        }
    }

    override fun close() = ops.close()

    /** Домашний каталог входа SSH так, как его видит сервер SFTP (на Windows — `/C:/Users/…`). */
    fun home(): Outcome<String> = attempt(".", "домашний каталог не назван") { ops.realpath(".") }

    private fun <T> readOnly(path: String): Outcome<T> = Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "файлы ПК здесь только читаются", path)

    /** Ошибка связи (не «нет файла» и не отказ проверки): после неё стоит переподключиться. */
    internal fun connectionLost(r: Outcome<*>): Boolean = r is Outcome.Failure && r.diagnostics.first().code == DiagCode.SSH_CONNECTION_FAILED

    /** Действие с файлом ПК: «нет файла», отказ сервера ([SftpOps.Refused]) и обрыв связи — разными кодами. */
    private inline fun <T> attempt(path: String, what: String, block: () -> T): Outcome<T> = try {
        Outcome.success(block())
    } catch (_: SftpOps.NoSuchFile) {
        Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого файла на ПК", path)
    } catch (e: SftpOps.Refused) {
        Outcome.failure(DiagCode.STORAGE_IO_FAILED, "$what: ПК отказал — ${e.message?.take(300)}", path)
    } catch (e: IOException) {
        Outcome.failure(DiagCode.SSH_CONNECTION_FAILED, "$what: связь с ПК — ${e.message?.take(200) ?: e.javaClass.simpleName}", path)
    }

    companion object {
        const val CHUNK: Int = 1 shl 20

        /** Знаки шаблона JSch в пути (`ChannelSftp`): такие имена не открываются и не показываются. */
        private val GLOB = charArrayOf('*', '?')

        /**
         * Путь дерева для SFTP: на Windows `C:\x` и `C:/x` — `/C:/x` (так диски
         * показывает SFTP OpenSSH), обратные косые — прямые; POSIX — как есть.
         */
        fun remotePath(platform: TargetPlatform, path: String): String {
            if (platform == TargetPlatform.POSIX) return path.ifEmpty { "." }
            val p = path.replace('\\', '/')
            return if (Regex("^[A-Za-z]:(/.*)?$").matches(p)) "/" + p.ifEmpty { "." } else p.ifEmpty { "." }
        }

        /** Открыть дерево [root] на target [endpoint] по SFTP — тем же входом, что команды (D-31, T2); [writable] — с записью (D-166). */
        fun open(
            endpoint: SshEndpoint,
            platform: TargetPlatform,
            root: String,
            identities: List<Identity>,
            connectTimeout: Duration = 10.seconds,
            writable: Boolean = false,
            random: RandomSource = RandomSource.secure(),
        ): Outcome<SftpTree> {
            val session = when (val s = JschConnect.open(endpoint, identities, connectTimeout, "sftp ${endpoint.host}")) {
                is Outcome.Failure -> return s
                is Outcome.Success -> s.value
            }
            return try {
                val channel = session.openChannel("sftp") as ChannelSftp
                channel.connect(connectTimeout.inWholeMilliseconds.toInt())
                Outcome.success(SftpTree(JschSftpOps(session, channel), root, platform, writable, random))
            } catch (e: JSchException) {
                session.disconnect()
                Outcome.failure(JschProcessHost.classify(e), "SFTP не открылся: ${e.message?.take(200).orEmpty()}", endpoint.host)
            }
        }
    }
}

/** [SftpOps] на JSch `ChannelSftp`: ошибка SFTP — `IOException`, «нет файла» — [SftpOps.NoSuchFile]. */
internal class JschSftpOps(private val session: Session, private val channel: ChannelSftp) : SftpOps {
    // Один ChannelSftp не годится для двух потоков сразу (D-166).
    // Отказ сервера при живом канале — [SftpOps.Refused]; канал упал — обрыв связи.
    @Synchronized
    private fun <T> call(path: String, block: () -> T): T = try {
        block()
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) throw SftpOps.NoSuchFile(path)
        val lost = e.id == ChannelSftp.SSH_FX_NO_CONNECTION || e.id == ChannelSftp.SSH_FX_CONNECTION_LOST || !channel.isConnected || !session.isConnected
        if (lost) throw IOException(e.message ?: "отказ SFTP ${e.id}", e)
        throw SftpOps.Refused(e.message ?: "отказ SFTP ${e.id}")
    }

    override fun list(path: String): List<SftpOps.Entry> = call(path) {
        channel.ls(path).filterIsInstance<ChannelSftp.LsEntry>().map { e ->
            val a = e.attrs
            SftpOps.Entry(e.filename, a.isDir, a.isLink, a.size, a.mTime.toLong() and 0xFFFFFFFFL)
        }
    }

    override fun stat(path: String): SftpOps.Entry = call(path) {
        val a = channel.lstat(path)
        val mode = a.permissions.takeIf { a.flags and com.jcraft.jsch.SftpATTRS.SSH_FILEXFER_ATTR_PERMISSIONS != 0 }?.and(0x0FFF)
        SftpOps.Entry(path.substringAfterLast('/'), a.isDir, a.isLink, a.size, a.mTime.toLong() and 0xFFFFFFFFL, mode)
    }

    /**
     * Поток файла — без буфера в памяти (APK до 512 МБ, D-127). Читать его, пока канал не
     * занят другим вызовом: `ReopeningSftpTree` выполняет действия по одному, скачивание
     * идёт своим подключением.
     */
    override fun open(path: String): InputStream = call(path) { channel.get(path) }

    override fun write(path: String, input: InputStream) = call(path) { channel.put(input, path, ChannelSftp.OVERWRITE) }

    override fun rename(from: String, to: String) = call(from) { channel.rename(from, to) }

    override fun remove(path: String) = call(path) { channel.rm(path) }

    override fun mkdir(path: String) = call(path) { channel.mkdir(path) }

    override fun chmod(path: String, mode: Int) = call(path) { channel.chmod(mode, path) }

    override fun realpath(path: String): String = call(path) { channel.realpath(path) }

    override fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }
}

/**
 * Дерево ПК с одним подключением на всё время экрана (D-166): открывается при первом
 * обращении через [open]; обрыв связи — новое подключение и один повтор действия.
 * Закрыть [close], когда экран дерева ушёл.
 */
class ReopeningSftpTree(private val open: () -> Outcome<SftpTree>) : FileTree, AutoCloseable {
    private var current: SftpTree? = null

    @Synchronized
    private fun <T> run(action: (SftpTree) -> Outcome<T>): Outcome<T> {
        repeat(2) { attempt ->
            val tree = current ?: when (val r = open()) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value.also { current = it }
            }
            val result = action(tree)
            if (!tree.connectionLost(result) || attempt == 1) return result
            runCatching { tree.close() }
            current = null
        }
        error("недостижимо")
    }

    override fun list(dir: String) = run { it.list(dir) }

    override fun read(path: String, maxBytes: Long) = run { it.read(path, maxBytes) }

    override fun write(path: String, bytes: ByteArray) = run { it.write(path, bytes) }

    override fun delete(path: String) = run { it.delete(path) }

    @Synchronized
    override fun close() {
        current?.let { runCatching { it.close() } }
        current = null
    }
}
