package com.dpsmpx.dpai.execution.ssh

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.execution.process.Detacher
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Строка команды для оболочки, которой SSH-сервер target исполняет
 * команду (D-31). Сервер получает одну строку, а не список аргументов:
 * кавычки ставит контроллер, и неподдержанное — отказ, а не «похожее»
 * (D-05).
 */
object RemoteShell {
    private val WINDOWS_SAFE = Regex("[A-Za-z0-9_./:=@,+\\\\-]+")

    fun join(platform: TargetPlatform, args: List<String>): Outcome<String> {
        require(args.isNotEmpty()) { "пустая команда" }
        return when (platform) {
            TargetPlatform.POSIX -> {
                if (args.any { '\u0000' in it }) {
                    Outcome.failure(DiagCode.UNSUPPORTED, "аргумент с нулевым символом не передаётся по SSH")
                } else {
                    Outcome.success(args.joinToString(" ") { posix(it) })
                }
            }
            TargetPlatform.WINDOWS -> {
                // cmd.exe раскрывает % и ^ даже в кавычках; кавычка внутри аргумента
                // по-разному разбирается программами. Такие аргументы не передаются.
                val bad = args.firstOrNull { a -> a.isEmpty() || a.any { it == '"' || it == '%' || it == '^' || it == '!' || it.isISOControl() } }
                if (bad != null) {
                    Outcome.failure(DiagCode.UNSUPPORTED, "аргумент с \", %, ^, ! или управляющими знаками не передаётся на Windows по SSH")
                } else {
                    Outcome.success(args.joinToString(" ") { if (WINDOWS_SAFE.matches(it)) it else Detacher.quoteWindows(it) })
                }
            }
        }
    }

    private fun posix(a: String): String =
        if (a.isNotEmpty() && a.all { it.isLetterOrDigit() && it.code < 128 || it in "_./:=@,+-" }) a else "'" + a.replace("'", "'\\''") + "'"
}

/**
 * Процессы на target через системный клиент OpenSSH (D-26, D-31).
 *
 * - `BatchMode=yes` — без вопросов о пароле: вход только ключом;
 * - `StrictHostKeyChecking=yes` — неизвестный или сменившийся ключ хоста —
 *   отказ `SSH_HOST_KEY_REJECTED`, а не молчаливое добавление (T2);
 * - `ServerAlive*` — обрыв связи обнаруживается, а не висит вечно.
 *
 * Окружение и рабочий каталог по SSH не передаются: на target их задаёт
 * агент (`dpai agent start`); здесь такой запрос — `UNSUPPORTED`. Долгие
 * процессы запускает агент, а не этот хост: остановка локального `ssh` не
 * обязана остановить удалённую команду, поэтому сюда — только вызовы
 * агента: короткие и поток `follow` (D-76), который кончается сам —
 * агент замечает закрытый канал на следующей строке.
 */
class SshProcessHost(
    private val endpoint: SshEndpoint,
    private val platform: TargetPlatform,
    private val local: ProcessHost,
    private val sshProgram: String = "ssh",
    private val connectTimeout: Duration = 10.seconds,
) : ProcessHost {
    fun argv(remote: List<String>): Outcome<List<String>> = command(endpoint, platform, remote, sshProgram, connectTimeout)

    override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
        if (spec.workDir != null || spec.environment != null) {
            return Outcome.failure(DiagCode.UNSUPPORTED, "по SSH окружение и рабочий каталог задаёт агент на target, а не контроллер")
        }
        val argv = when (val a = argv(spec.command)) {
            is Outcome.Failure -> return a
            is Outcome.Success -> a.value
        }
        return local.start(ProcessSpec(argv, stdin = spec.stdin), onOutput)
    }

    companion object {
        /**
         * Путь в значении `-o` OpenSSH (ревью A-19): значение разбирается как
         * строка конфигурации — пробел делит его на несколько файлов, поэтому
         * путь с пробелом — в кавычках; `%` — начало токена, сам знак — `%%`.
         */
        internal fun configPath(path: String): String = path.replace("%", "%%").let { if (it.any(Char::isWhitespace)) "\"$it\"" else it }

        /**
         * Команда клиента OpenSSH для [remote] на [endpoint]: те же параметры,
         * что у вызовов агента, — и для MCP-сервера рабочего дерева, который
         * Claude Code запускает сам (D-50).
         */
        fun command(
            endpoint: SshEndpoint,
            platform: TargetPlatform,
            remote: List<String>,
            sshProgram: String = "ssh",
            connectTimeout: Duration = 10.seconds,
        ): Outcome<List<String>> =
            RemoteShell.join(platform, remote).map { line ->
                buildList {
                    add(sshProgram)
                    add("-T")
                    for (o in listOf(
                        "BatchMode=yes",
                        "StrictHostKeyChecking=yes",
                        "ConnectTimeout=${connectTimeout.inWholeSeconds.coerceAtLeast(1)}",
                        "ServerAliveInterval=15",
                        "ServerAliveCountMax=3",
                        "LogLevel=ERROR",
                    )) {
                        add("-o")
                        add(o)
                    }
                    endpoint.knownHostsFile?.let {
                        add("-o")
                        add("UserKnownHostsFile=${configPath(it)}")
                    }
                    endpoint.identityFile?.let {
                        add("-i")
                        add(it.replace("%", "%%"))
                        add("-o")
                        add("IdentitiesOnly=yes")
                    }
                    add("-p")
                    add(endpoint.port.toString())
                    add("-l")
                    add(endpoint.user)
                    add("--")
                    add(endpoint.host)
                    add(line)
                }
            }

        /** Код выхода клиента `ssh` при ошибке самого SSH, а не удалённой команды. */
        const val SSH_ERROR_EXIT: Int = 255

        /**
         * Отказ SSH по коду и тексту ошибки клиента: ключ хоста, вход,
         * связь. Текст — сообщения OpenSSH; неопознанное — общий отказ
         * связи с текстом причиной (D-05: не догадка).
         */
        fun classify(result: Completed, subject: String): Diagnostic? {
            if (result.exitCode != SSH_ERROR_EXIT) return null
            val text = result.stderr.joinToString("\n")
            val code = when {
                "Host key verification failed" in text || "REMOTE HOST IDENTIFICATION HAS CHANGED" in text ||
                    "No matching host key" in text -> DiagCode.SSH_HOST_KEY_REJECTED
                "Permission denied" in text || "Too many authentication failures" in text -> DiagCode.SSH_AUTH_FAILED
                else -> DiagCode.SSH_CONNECTION_FAILED
            }
            return Diagnostic.error(code, text.lineSequence().lastOrNull { it.isNotBlank() }?.take(300) ?: "ssh: код $SSH_ERROR_EXIT", subject)
        }
    }
}
