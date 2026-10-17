package com.dpsmpx.dpai.jsch

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.OutputStream
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.process.StopReport
import com.dpsmpx.dpai.execution.ssh.RemoteShell
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Процессы на target через SSH-клиент на JVM — JSch (D-35): там, где нет
 * системного OpenSSH (приложение Android). Те же правила, что у
 * `SshProcessHost` (D-31, T2):
 *
 * - вход только ключом (`PreferredAuthentications=publickey`);
 * - `StrictHostKeyChecking=yes` — неизвестный или сменившийся ключ хоста —
 *   `SSH_HOST_KEY_REJECTED`, а не молчаливое добавление;
 * - `ServerAlive` — обрыв обнаруживается.
 *
 * Окружение и рабочий каталог задаёт агент на target — здесь такой запрос
 * `UNSUPPORTED`. Сюда — только короткие вызовы агента: остановка канала
 * не обязана остановить удалённую команду. Отказ SSH приходит из
 * [start], а не кодом 255, как у клиента OpenSSH.
 */
class JschProcessHost(
    private val endpoint: SshEndpoint,
    private val platform: TargetPlatform,
    private val clock: Clock = Clock.SYSTEM,
    private val connectTimeout: Duration = 10.seconds,
    /** Ключи входа без файла — в приложении ключ Android Keystore ([EcdsaSignerIdentity]); пусто — файлы. */
    private val identities: List<Identity> = emptyList(),
) : ProcessHost {
    private val subject = "ssh ${endpoint.user}@${endpoint.host}:${endpoint.port}"

    override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
        if (spec.workDir != null || spec.environment != null) {
            return Outcome.failure(DiagCode.UNSUPPORTED, "по SSH окружение и рабочий каталог задаёт агент на target, а не контроллер")
        }
        val line = when (val l = RemoteShell.join(platform, spec.command)) {
            is Outcome.Failure -> return l
            is Outcome.Success -> l.value
        }
        val session = when (val s = connect()) {
            is Outcome.Failure -> return s
            is Outcome.Success -> s.value
        }
        return try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(line)
            channel.setInputStream(spec.stdin?.let { ByteArrayInputStream(it.toByteArray(Charsets.UTF_8)) })
            val out = channel.inputStream
            val err = channel.extInputStream
            channel.connect(connectTimeout.inWholeMilliseconds.toInt())
            Outcome.success(JschProcess(session, channel, clock, listOf(OutputStream.STDOUT to out, OutputStream.STDERR to err), onOutput))
        } catch (e: JSchException) {
            session.disconnect()
            Outcome.failure(classify(e), "не открылся канал: ${e.message}", subject)
        } catch (e: java.io.IOException) {
            // Потоки канала (ревью A-78): отказ библиотеки — Outcome, а не исключение из такта (D-05).
            session.disconnect()
            Outcome.failure(DiagCode.SSH_CONNECTION_FAILED, "не открылся канал: ${e.message}", subject)
        }
    }

    private fun connect(): Outcome<Session> = JschConnect.open(endpoint, identities, connectTimeout, subject)

    companion object {
        /** Отказ JSch → код: ключ хоста, вход, связь; неопознанное — отказ связи (D-05). */
        fun classify(e: JSchException): DiagCode {
            val text = e.message.orEmpty()
            return when {
                e is JSchUnknownHostKeyException || e is JSchChangedHostKeyException ||
                    "HostKey" in text || "reject HostKey" in text -> DiagCode.SSH_HOST_KEY_REJECTED
                "Auth fail" in text || "Auth cancel" in text || "USERAUTH fail" in text -> DiagCode.SSH_AUTH_FAILED
                else -> DiagCode.SSH_CONNECTION_FAILED
            }
        }
    }
}

/** Команда на target в канале `exec`; закрытие канала — выход. */
private class JschProcess(
    private val session: Session,
    private val channel: ChannelExec,
    private val clock: Clock,
    streams: List<Pair<OutputStream, InputStream>>,
    onOutput: (OutputLine) -> Unit,
) : RunningProcess {
    /** Оба потока дочитаны: после этого вывод полон и канал закрывается. */
    private val drained = CountDownLatch(streams.size)

    init {
        for ((kind, input) in streams) {
            Thread({
                try {
                    input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { l ->
                            val cut = l.length > LocalProcessHost.MAX_LINE
                            onOutput(OutputLine(kind, if (cut) l.take(LocalProcessHost.MAX_LINE) else l, cut))
                        }
                    }
                } catch (_: java.io.IOException) {
                    // Канал закрыт остановкой: читать больше нечего.
                } finally {
                    drained.countDown()
                }
            }, "dpai-jsch-${kind.name.lowercase()}").apply { isDaemon = true }.start()
        }
    }

    override val pid: Long? = null

    override val isAlive: Boolean get() = !channel.isClosed

    override fun awaitExit(timeout: Duration): Int? {
        val deadline = clock.monotonicNanos() + timeout.inWholeNanoseconds
        fun left() = (deadline - clock.monotonicNanos()).nanoseconds
        if (!drained.await(left().inWholeNanoseconds.coerceAtLeast(0), TimeUnit.NANOSECONDS)) return null
        // Статус выхода сервер присылает рядом с концом вывода: подождать закрытия канала.
        val tick = CountDownLatch(1)
        while (!channel.isClosed && left() > Duration.ZERO) tick.await(10.milliseconds.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!channel.isClosed) return null
        val code = channel.exitStatus
        session.disconnect()
        return code
    }

    override fun stop(grace: Duration): StopReport {
        val code = awaitExit(Duration.ZERO)
        if (code != null) return StopReport(code, forced = false, survivors = emptyList())
        channel.disconnect()
        session.disconnect()
        return StopReport(null, forced = true, survivors = emptyList())
    }
}
