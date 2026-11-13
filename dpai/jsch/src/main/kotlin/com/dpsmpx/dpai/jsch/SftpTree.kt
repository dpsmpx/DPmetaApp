package com.dpsmpx.dpai.jsch

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
    /** Элемент каталога: [link] — символьная ссылка; [modifiedSec] — время изменения, секунды. */
    data class Entry(val name: String, val dir: Boolean, val link: Boolean, val size: Long, val modifiedSec: Long)

    class NoSuchFile(path: String) : IOException("нет такого файла: $path")

    fun list(path: String): List<Entry>

    fun stat(path: String): Entry

    fun open(path: String): InputStream

    override fun close()
}

/**
 * Файлы дерева на ПК по SFTP (`DPmetaApp:D-127`; D-162) — только чтение: список
 * каталога, чтение и копирование файла кусками; запись и удаление — отказ. Пути —
 * относительные от корня дерева [root] (`FileTree`); символьные ссылки и негодные
 * имена не показываются и не проходятся. Одно подключение на обход — закрыть [close].
 */
class SftpTree(private val ops: SftpOps, root: String, platform: TargetPlatform) : FileTree, AutoCloseable {
    /** Корень дерева так, как его видит сервер SFTP. */
    val root: String = remotePath(platform, root)

    private fun abs(rel: String): Outcome<String> =
        if (rel.isNotEmpty() && !FileNames.pathValid(rel)) Outcome.failure(DiagCode.VALUE_INVALID, "путь недопустим", rel)
        else Outcome.success(if (rel.isEmpty()) root else root.trimEnd('/') + "/" + rel)

    override fun list(dir: String): Outcome<List<FileEntry>> = abs(dir).then { path ->
        attempt(dir) {
            ops.list(path).filter { it.name != "." && it.name != ".." && !it.link && FileNames.segmentValid(it.name) }
                .map { FileEntry(it.name, it.dir, if (it.dir) 0 else it.size, it.modifiedSec * 1000) }
        }
    }

    override fun read(path: String, maxBytes: Long): Outcome<ByteArray> {
        val out = java.io.ByteArrayOutputStream()
        return copy(path, maxBytes) { b, n -> out.write(b, 0, n) }.then { Outcome.success(out.toByteArray()) }
    }

    override fun write(path: String, bytes: ByteArray): Outcome<FileMeta> = readOnly(path)

    override fun delete(path: String): Outcome<Unit> = readOnly(path)

    /** Сведения о файле [path]: размер и время изменения (мс). */
    fun meta(path: String): Outcome<FileMeta> = abs(path).then { p ->
        attempt(path) {
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
        return attempt(path) {
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

    private fun <T> readOnly(path: String): Outcome<T> = Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "файлы ПК здесь только читаются", path)

    private inline fun <T> attempt(path: String, block: () -> T): Outcome<T> = try {
        Outcome.success(block())
    } catch (_: SftpOps.NoSuchFile) {
        Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого файла на ПК", path)
    } catch (e: IOException) {
        Outcome.failure(DiagCode.SSH_CONNECTION_FAILED, "файл ПК не прочитан: ${e.message?.take(200) ?: e.javaClass.simpleName}", path)
    }

    companion object {
        const val CHUNK: Int = 1 shl 20

        /**
         * Путь дерева для SFTP: на Windows `C:\x` и `C:/x` — `/C:/x` (так диски
         * показывает SFTP OpenSSH), обратные косые — прямые; POSIX — как есть.
         */
        fun remotePath(platform: TargetPlatform, path: String): String {
            if (platform == TargetPlatform.POSIX) return path.ifEmpty { "." }
            val p = path.replace('\\', '/')
            return if (Regex("^[A-Za-z]:(/.*)?$").matches(p)) "/" + p.ifEmpty { "." } else p.ifEmpty { "." }
        }

        /** Открыть дерево [root] на target [endpoint] по SFTP — тем же входом, что команды (D-31, T2). */
        fun open(endpoint: SshEndpoint, platform: TargetPlatform, root: String, identities: List<Identity>, connectTimeout: Duration = 10.seconds): Outcome<SftpTree> {
            val session = when (val s = JschConnect.open(endpoint, identities, connectTimeout, "sftp ${endpoint.host}")) {
                is Outcome.Failure -> return s
                is Outcome.Success -> s.value
            }
            return try {
                val channel = session.openChannel("sftp") as ChannelSftp
                channel.connect(connectTimeout.inWholeMilliseconds.toInt())
                Outcome.success(SftpTree(JschSftpOps(session, channel), root, platform))
            } catch (e: JSchException) {
                session.disconnect()
                Outcome.failure(JschProcessHost.classify(e), "SFTP не открылся: ${e.message?.take(200).orEmpty()}", endpoint.host)
            }
        }
    }
}

/** [SftpOps] на JSch `ChannelSftp`: ошибка SFTP — `IOException`, «нет файла» — [SftpOps.NoSuchFile]. */
internal class JschSftpOps(private val session: Session, private val channel: ChannelSftp) : SftpOps {
    private fun <T> call(path: String, block: () -> T): T = try {
        block()
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) throw SftpOps.NoSuchFile(path)
        throw IOException(e.message ?: "отказ SFTP ${e.id}", e)
    }

    override fun list(path: String): List<SftpOps.Entry> = call(path) {
        channel.ls(path).filterIsInstance<ChannelSftp.LsEntry>().map { e ->
            val a = e.attrs
            SftpOps.Entry(e.filename, a.isDir, a.isLink, a.size, a.mTime.toLong() and 0xFFFFFFFFL)
        }
    }

    override fun stat(path: String): SftpOps.Entry = call(path) {
        val a = channel.lstat(path)
        SftpOps.Entry(path.substringAfterLast('/'), a.isDir, a.isLink, a.size, a.mTime.toLong() and 0xFFFFFFFFL)
    }

    override fun open(path: String): InputStream = call(path) { channel.get(path) }

    override fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }
}
