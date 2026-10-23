package com.dpsmpx.dpai.android.linux

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.linux.NativeChild
import com.dpsmpx.dpai.execution.linux.NativeProcesses
import com.dpsmpx.dpai.execution.linux.WindowSize
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Системные вызовы `libdpai-native.so` (`src/main/cpp/dpai_native.c`). На
 * JVM тесты загружают ту же библиотеку, собранную для машины сборки, —
 * путь в свойстве [LIBRARY_PROPERTY].
 */
internal object NativeCalls {
    const val LIBRARY_PROPERTY = "dpai.native.library"

    init {
        val path = System.getProperty(LIBRARY_PROPERTY)
        if (path != null) System.load(path) else System.loadLibrary("dpai-native")
    }

    @JvmStatic external fun spawn(argv: Array<ByteArray>, env: Array<ByteArray>, cwd: ByteArray?, cols: Int, rows: Int, fds: IntArray): Int

    @JvmStatic external fun waitFor(pid: Int): Int

    @JvmStatic external fun kill(pid: Int, signal: Int): Int

    @JvmStatic external fun resize(fd: Int, cols: Int, rows: Int): Int

    @JvmStatic external fun read(fd: Int, buf: ByteArray, off: Int, len: Int): Int

    @JvmStatic external fun write(fd: Int, buf: ByteArray, off: Int, len: Int): Int

    @JvmStatic external fun close(fd: Int): Int

    @JvmStatic external fun describe(errno: Int): String
}

/**
 * [NativeProcesses] через JNI (D-61): `fork`, `setsid`, `execve`, каналы
 * или псевдотерминал. Программа без пути ищется по `PATH` из окружения
 * процесса — как у `ProcessBuilder`.
 */
class JniProcesses : NativeProcesses {
    override fun spawn(argv: List<String>, environment: Map<String, String>, workDir: String?, pty: WindowSize?): Outcome<NativeChild> {
        val program = resolve(argv.first(), environment["PATH"])
            ?: return Outcome.failure(DiagCode.PROCESS_START_FAILED, "${argv.first()}: нет в PATH", argv.first())
        val fds = IntArray(3)
        val pid = NativeCalls.spawn(
            (listOf(program) + argv.drop(1)).map { it.toByteArray(Charsets.UTF_8) }.toTypedArray(),
            environment.map { (k, v) -> "$k=$v".toByteArray(Charsets.UTF_8) }.toTypedArray(),
            workDir?.toByteArray(Charsets.UTF_8),
            pty?.cols ?: 0,
            pty?.rows ?: 0,
            fds,
        )
        if (pid < 0) return Outcome.failure(DiagCode.PROCESS_START_FAILED, "${argv.first()}: не запустился — ${NativeCalls.describe(-pid)}", argv.first())
        return Outcome.success(Child(pid, fds[0], fds[1], fds[2].takeIf { it >= 0 }))
    }

    override fun kill(pid: Int, signal: Int): Boolean = NativeCalls.kill(pid, signal) == 0

    private fun resolve(program: String, path: String?): String? {
        if ('/' in program) return program
        return path.orEmpty().split(':').filter { it.isNotEmpty() }.map { "$it/$program" }
            .firstOrNull { java.io.File(it).let { f -> f.isFile && f.canExecute() } }
    }

    private class Child(override val pid: Int, private val writer: Int, private val reader: Int, errors: Int?) : NativeChild {
        override val stdin: OutputStream = FdOutput(writer)
        override val stdout: InputStream = FdInput(reader)
        override val stderr: InputStream? = errors?.let { FdInput(it) }

        override fun waitFor(): Int = NativeCalls.waitFor(pid)

        override fun resize(size: WindowSize) {
            if (stderr == null) NativeCalls.resize(reader, size.cols, size.rows)
        }
    }

    /** Чтение дескриптора; закрыть — один раз. */
    private class FdInput(private val fd: Int) : InputStream() {
        private val closed = AtomicBoolean(false)

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (closed.get()) throw IOException("поток закрыт")
            val n = NativeCalls.read(fd, b, off, len)
            if (n < 0) throw IOException(NativeCalls.describe(-n))
            return if (n == 0) -1 else n
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) NativeCalls.close(fd)
        }
    }

    /** Запись в дескриптор; закрыть — один раз. */
    private class FdOutput(private val fd: Int) : OutputStream() {
        private val closed = AtomicBoolean(false)

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed.get()) throw IOException("поток закрыт")
            val n = NativeCalls.write(fd, b, off, len)
            if (n < 0) throw IOException(NativeCalls.describe(-n))
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) NativeCalls.close(fd)
        }
    }
}
