package com.dpsmpx.dpai.jsch

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.ssh.RemoteShell
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Интерактивная оболочка на target с PTY для своего терминала (D-71):
 * связь — по тем же правилам, что команды агента (ключ приложения,
 * строгий ключ хоста, D-31, D-35). Терминал `xterm-256color`. Пустая
 * команда — оболочка входа; иначе — команда в PTY (например, `claude`
 * слота).
 *
 * [open] ходит в сеть — звать не из потока интерфейса. Ввод и смена
 * размера уходят своим потоком: запись в сокет из потока интерфейса
 * Android запрещена.
 */
class JschShell(
    private val endpoint: SshEndpoint,
    private val platform: TargetPlatform,
    private val connectTimeout: Duration = 10.seconds,
    private val identities: List<Identity> = emptyList(),
) {
    private val subject = "ssh ${endpoint.user}@${endpoint.host}:${endpoint.port}"

    /**
     * Открыть канал размером [cols]×[rows]. [onOutput] — куски вывода из
     * потока чтения (буфер переиспользуется: данные — до возврата);
     * [onClosed] — один раз, с кодом выхода, если сервер его прислал.
     */
    fun open(
        cols: Int,
        rows: Int,
        command: List<String> = emptyList(),
        onOutput: (ByteArray, Int) -> Unit,
        onClosed: (Int?) -> Unit,
    ): Outcome<ShellChannel> {
        val line = if (command.isEmpty()) null else when (val l = RemoteShell.join(platform, command)) {
            is Outcome.Failure -> return l
            is Outcome.Success -> l.value
        }
        val session = when (val s = JschConnect.open(endpoint, identities, connectTimeout, subject)) {
            is Outcome.Failure -> return s
            is Outcome.Success -> s.value
        }
        return try {
            // Общий предок ChannelShell и ChannelExec в JSch закрыт: размер окна — через свою функцию.
            val opened: Pair<Channel, (Int, Int) -> Unit> = if (line == null) {
                val c = session.openChannel("shell") as ChannelShell
                c.setPtyType(TERM, cols, rows, 0, 0)
                c to { x: Int, y: Int -> c.setPtySize(x, y, 0, 0) }
            } else {
                val c = session.openChannel("exec") as ChannelExec
                c.setCommand(line)
                c.setPty(true)
                c.setPtyType(TERM, cols, rows, 0, 0)
                c to { x: Int, y: Int -> c.setPtySize(x, y, 0, 0) }
            }
            val (channel, ptySize) = opened
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(connectTimeout.inWholeMilliseconds.toInt())
            Outcome.success(ShellChannel(session, channel, ptySize, input, output, onOutput, onClosed))
        } catch (e: JSchException) {
            session.disconnect()
            Outcome.failure(JschProcessHost.classify(e), "не открылся терминал: ${e.message}", subject)
        } catch (e: IOException) {
            session.disconnect()
            Outcome.failure(DiagCode.SSH_CONNECTION_FAILED, "не открылся терминал: ${e.message}", subject)
        }
    }

    companion object {
        const val TERM = "xterm-256color"
    }
}

/** Открытый канал терминала: ввод, размер окна, закрытие (D-71). */
class ShellChannel internal constructor(
    private val session: Session,
    private val channel: Channel,
    private val ptySize: (Int, Int) -> Unit,
    input: java.io.InputStream,
    private val output: java.io.OutputStream,
    onOutput: (ByteArray, Int) -> Unit,
    onClosed: (Int?) -> Unit,
) {
    /** Действия записи по порядку; [STOP] — конец потока записи. */
    private val queue = LinkedBlockingQueue<() -> Unit>()
    private val done = CountDownLatch(1)

    init {
        Thread({
            val buf = ByteArray(8_192)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) onOutput(buf, n)
                }
            } catch (_: IOException) {
                // Канал закрыт: читать больше нечего.
            } finally {
                // Код выхода сервер присылает рядом с концом вывода.
                waitClosed(channel)
                val code = channel.exitStatus.takeIf { channel.isClosed && it >= 0 }
                queue.put(STOP)
                session.disconnect()
                // Сначала итог вызывающему, потом «закрыт»: awaitClosed возвращается уже с кодом выхода.
                try {
                    onClosed(code)
                } finally {
                    done.countDown()
                }
            }
        }, "dpai-term-read").apply { isDaemon = true }.start()
        Thread({
            while (true) {
                val action = queue.take()
                if (action === STOP) break
                try {
                    action()
                } catch (_: IOException) {
                    // Обрыв: поток чтения увидит конец и закроет канал.
                } catch (_: JSchException) {
                    // То же для смены размера.
                }
            }
        }, "dpai-term-write").apply { isDaemon = true }.start()
    }

    val isOpen: Boolean get() = done.count > 0

    fun write(bytes: ByteArray) {
        if (!isOpen) return
        val copy = bytes.copyOf()
        queue.put {
            output.write(copy)
            output.flush()
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (!isOpen) return
        queue.put { ptySize(cols, rows) }
    }

    fun close() {
        queue.put {
            channel.disconnect()
            session.disconnect()
        }
    }

    /** Дождаться закрытия (тесты, выход из приложения). */
    fun awaitClosed(timeout: Duration): Boolean = done.await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    private companion object {
        val STOP: () -> Unit = {}

        fun waitClosed(channel: Channel) {
            val tick = CountDownLatch(1)
            var left = 50
            while (!channel.isClosed && left-- > 0) tick.await(10, TimeUnit.MILLISECONDS)
        }
    }
}
