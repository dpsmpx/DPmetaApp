package com.dpsmpx.dpai.execution.process

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * Запуск процесса, который переживает своего родителя и сессию SSH (D-26,
 * `docs/EXECUTION_MODEL.md`, §5): так агент target запускает супервизор
 * запуска, и обрыв SSH не убивает Claude.
 *
 * - POSIX — `setsid` (новая сессия: SIGHUP закрытой сессии SSH не
 *   приходит), без него — `nohup`; вход — `/dev/null`, вывод — в [log].
 * - Windows — через WMI (`Win32_Process.Create`): процесс, запущенный
 *   обычным способом из сессии Win32-OpenSSH, входит в её job object и
 *   умирает вместе с ней.
 *
 * Возвращает идентификатор запущенного процесса.
 */
class Detacher(private val windows: Boolean = isWindows(), private val shellHost: ProcessHost = LocalProcessHost()) {
    fun spawn(argv: List<String>, workDir: String, log: String): Outcome<Long> =
        if (windows) spawnWindows(argv, workDir) else spawnPosix(argv, workDir, log)

    private fun spawnPosix(argv: List<String>, workDir: String, log: String): Outcome<Long> {
        val setsid = listOf("/usr/bin/setsid", "/bin/setsid", (System.getenv("PREFIX") ?: "") + "/bin/setsid").firstOrNull { File(it).canExecute() }
        val wrapped = if (setsid != null) listOf(setsid) + argv else listOf("nohup") + argv
        val encodable = ArgumentEncoding.check(wrapped)
        if (encodable is Outcome.Failure) return encodable
        return try {
            val p = ProcessBuilder(wrapped)
                .directory(File(workDir))
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.appendTo(File(log)))
                .redirectErrorStream(true)
                .start()
            Outcome.success(p.pid())
        } catch (e: IOException) {
            Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.PROCESS_START_FAILED, "${argv.first()}: не запустился отдельно", e)))
        }
    }

    private fun spawnWindows(argv: List<String>, workDir: String): Outcome<Long> {
        val line = windowsCommandLine(argv)
        val script = "\$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments " +
            "@{CommandLine=${psQuote(line)}; CurrentDirectory=${psQuote(workDir)}}; " +
            "if (\$r.ReturnValue -ne 0) { Write-Error (\"Win32_Process.Create: \" + \$r.ReturnValue); exit 1 }; \$r.ProcessId"
        val spec = ProcessSpec(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script))
        return when (val r = shellHost.run(spec, 60.seconds)) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                val pid = r.value.stdout.lastOrNull { it.isNotBlank() }?.trim()?.toLongOrNull()
                if (r.value.ok && pid != null) {
                    Outcome.success(pid)
                } else {
                    val why = r.value.stderr.lastOrNull { it.isNotBlank() } ?: "код ${r.value.exitCode}"
                    Outcome.failure(DiagCode.PROCESS_START_FAILED, "${argv.first()}: не запустился через WMI: $why")
                }
            }
        }
    }

    companion object {
        fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

        private fun psQuote(s: String): String = "'" + s.replace("'", "''") + "'"

        /**
         * Строка команды Windows по правилам разбора `CommandLineToArgvW`:
         * аргумент с пробелом или кавычкой — в кавычках ([quoteWindows]).
         */
        fun windowsCommandLine(argv: List<String>): String =
            argv.joinToString(" ") { a -> if (a.isNotEmpty() && a.none { it == ' ' || it == '\t' || it == '"' }) a else quoteWindows(a) }

        /** Аргумент в кавычках: обратные косые перед кавычкой и в конце удваиваются. */
        fun quoteWindows(a: String): String = buildString {
            append('"')
            var slashes = 0
            for (c in a) {
                when (c) {
                    '\\' -> slashes++
                    '"' -> {
                        append("\\".repeat(slashes * 2 + 1)).append('"')
                        slashes = 0
                    }
                    else -> {
                        append("\\".repeat(slashes)).append(c)
                        slashes = 0
                    }
                }
            }
            append("\\".repeat(slashes * 2)).append('"')
        }
    }
}
