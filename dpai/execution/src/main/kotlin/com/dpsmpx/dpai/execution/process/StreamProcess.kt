package com.dpsmpx.dpai.execution.process

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.linux.NativeChild
import com.dpsmpx.dpai.execution.linux.NativeProcesses
import com.dpsmpx.dpai.execution.linux.NativeSessions
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration

/**
 * Процесс-собеседник: вход и выход — потоки байтов, открытые, пока он жив
 * (языковая служба редактора, D-137). В отличие от [RunningProcess], вывод
 * не режется на строки, а вход не закрывается после первой записи. Вывод
 * ошибок не читается вызывающим: он отброшен, чтобы процесс не встал на
 * заполненном канале.
 */
interface StreamProcess {
    val stdin: OutputStream
    val stdout: InputStream
    val isAlive: Boolean

    /** Остановить всё дерево процесса: мягко, через [grace] — принудительно. */
    fun stop(grace: Duration = RunningProcess.DEFAULT_GRACE): StopReport
}

/** Место, где запускаются процессы-собеседники. [ProcessSpec.stdin] не используется: вход — поток. */
interface StreamHost {
    fun open(spec: ProcessSpec): Outcome<StreamProcess>
}

/** Процесс-собеседник на этой машине. */
class LocalStreamHost(private val clock: Clock = Clock.SYSTEM) : StreamHost {
    override fun open(spec: ProcessSpec): Outcome<StreamProcess> {
        val encodable = ArgumentEncoding.check(spec.command)
        if (encodable is Outcome.Failure) return encodable
        val builder = ProcessBuilder(spec.command).redirectError(ProcessBuilder.Redirect.DISCARD)
        spec.workDir?.let { builder.directory(File(it)) }
        spec.environment?.let { env ->
            val target = builder.environment()
            target.clear()
            target.putAll(env)
        }
        val process = try {
            builder.start()
        } catch (e: IOException) {
            return Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.PROCESS_START_FAILED, "${spec.program}: не запустился", e)))
        }
        return Outcome.success(
            object : StreamProcess {
                override val stdin: OutputStream = process.outputStream
                override val stdout: InputStream = process.inputStream
                override val isAlive: Boolean get() = process.isAlive

                override fun stop(grace: Duration): StopReport {
                    val r = ProcessTree.stop(process.toHandle(), grace, clock)
                    return StopReport(if (process.isAlive) null else process.exitValue(), r.forced, r.survivors)
                }
            },
        )
    }
}

/**
 * Процесс-собеседник через `fork`/`exec` приложения Android (D-75).
 * [wrap] превращает команду гостя в команду хоста (`ProotHost.wrap`):
 * служба работает в Linux-среде приложения, рядом с рабочим деревом.
 */
class NativeStreamHost(
    private val native: NativeProcesses,
    private val wrap: (ProcessSpec) -> ProcessSpec = { it },
    clock: Clock = Clock.SYSTEM,
) : StreamHost {
    private val sessions = NativeSessions(native, clock)

    override fun open(spec: ProcessSpec): Outcome<StreamProcess> {
        val host = wrap(spec)
        val child = when (val r = native.spawn(host.command, host.environment ?: System.getenv(), host.workDir)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return Outcome.success(Native(child))
    }

    private inner class Native(private val child: NativeChild) : StreamProcess {
        @Volatile
        private var code: Int? = null

        @Volatile
        private var exited = false

        init {
            Thread({
                code = child.waitFor()
                exited = true
            }, "dpai-stream-wait-${child.pid}").apply { isDaemon = true }.start()
            child.stderr?.let { err ->
                Thread({
                    val buf = ByteArray(4096)
                    try {
                        var n = 0
                        while (n >= 0) n = err.read(buf)
                    } catch (_: IOException) {
                        // Поток закрыт вместе с процессом.
                    }
                }, "dpai-stream-stderr-${child.pid}").apply { isDaemon = true }.start()
            }
        }

        override val stdin: OutputStream get() = child.stdin
        override val stdout: InputStream get() = child.stdout
        override val isAlive: Boolean get() = !exited

        override fun stop(grace: Duration): StopReport {
            val pid = child.pid.toLong()
            val r = if (exited && sessions.living(pid).isEmpty()) NativeSessions.Result(false, emptyList()) else sessions.stop(pid, grace)
            return StopReport(code, r.forced, r.survivors)
        }
    }
}
