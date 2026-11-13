package com.dpsmpx.dpai.jsch

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.StopReport
import com.dpsmpx.dpai.execution.process.StreamHost
import com.dpsmpx.dpai.execution.process.StreamProcess
import com.dpsmpx.dpai.execution.ssh.RemoteShell
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSchException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Процесс-собеседник на ПК-target по SSH (DP_AI_Orcestrator:D-164): адаптер
 * отладки (`gdb -i=dap`, `python -m debugpy.adapter`), говорящий по своим
 * stdin и stdout. Канал `exec` без PTY: байты протокола идут как есть,
 * без эха и замены переводов строк. Вход — тот же, что у команд и
 * терминала (ключ приложения, строгий ключ хоста, D-31, D-35).
 *
 * Без PTY сервер не шлёт удалённому процессу SIGHUP при обрыве, поэтому
 * остановка — сначала закрытие stdin (адаптеры DAP по концу ввода
 * выходят сами), затем закрытие канала и сеанса.
 */
class JschStreamHost(
    private val endpoint: SshEndpoint,
    private val platform: TargetPlatform,
    private val clock: Clock = Clock.SYSTEM,
    private val connectTimeout: Duration = 10.seconds,
    private val identities: List<Identity> = emptyList(),
) : StreamHost {
    private val subject = "ssh ${endpoint.user}@${endpoint.host}:${endpoint.port}"

    override fun open(spec: ProcessSpec): Outcome<StreamProcess> {
        if (spec.workDir != null || spec.environment != null) {
            return Outcome.failure(DiagCode.UNSUPPORTED, "по SSH рабочий каталог и окружение адаптера не передаются: их задаёт запрос launch")
        }
        val line = when (val l = RemoteShell.join(platform, spec.command)) {
            is Outcome.Failure -> return l
            is Outcome.Success -> l.value
        }
        val session = when (val s = JschConnect.open(endpoint, identities, connectTimeout, subject)) {
            is Outcome.Failure -> return s
            is Outcome.Success -> s.value
        }
        return try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(line)
            channel.setPty(false)
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(connectTimeout.inWholeMilliseconds.toInt())
            Outcome.success(Stream(channel, session, input, output, clock))
        } catch (e: JSchException) {
            session.disconnect()
            Outcome.failure(JschProcessHost.classify(e), e.message?.take(300) ?: "отказ SSH", subject)
        }
    }

    private class Stream(
        private val channel: ChannelExec,
        private val session: com.jcraft.jsch.Session,
        override val stdout: InputStream,
        override val stdin: OutputStream,
        private val clock: Clock,
    ) : StreamProcess {
        override val isAlive: Boolean get() = channel.isConnected && !channel.isClosed

        override fun stop(grace: Duration): StopReport {
            runCatching { stdin.close() }
            // Адаптер по концу ввода выходит сам; ждём не дольше grace, затем рвём канал.
            val deadline = clock.monotonicNanos() + grace.inWholeNanoseconds
            val tick = CountDownLatch(1)
            while (!channel.isClosed && (deadline - clock.monotonicNanos()).nanoseconds > Duration.ZERO) tick.await(10.milliseconds.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            val exited = channel.isClosed
            val code = if (exited) channel.exitStatus.takeIf { it >= 0 } else null
            channel.disconnect()
            session.disconnect()
            return StopReport(code, forced = !exited, survivors = emptyList())
        }
    }
}
