package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * Установка и снятие службы агента на ПК (D-109) — `dpai agent service
 * install|remove`, без прав администратора:
 *
 * - Linux — служба пользователя systemd `dpai-agent.service`
 *   (`~/.config/systemd/user`): `Restart=on-failure`, `KillMode=process` —
 *   остановка и перезапуск службы не трогают идущие ходы; linger
 *   (`loginctl enable-linger`) — служба идёт и без входа в систему. Не
 *   включился — предупреждение: служба уйдёт с последним выходом.
 * - Windows — задача планировщика `dpai-agent` при входе пользователя
 *   (`Register-ScheduledTask`, обычные права, без предела времени), окна
 *   нет (`javaw.exe`); команда — файлом аргументов Java `service.args` в
 *   каталоге агента: путь классов длиннее предела строки задачи.
 * - Termux на телефоне — отказ: там target — запуск по SSH (D-46).
 *
 * Ничего, кроме этих файлов и вызовов `systemctl`, `loginctl` и PowerShell,
 * установка не делает.
 */
class AgentServiceSetup(
    private val home: String,
    private val selfCommand: List<String>,
    private val host: ProcessHost,
    private val windows: Boolean = File.separatorChar == '\\',
    private val env: Map<String, String> = System.getenv(),
    private val userHome: String = System.getProperty("user.home"),
) {
    /** Что сделано (строки для человека) и предупреждения. */
    data class Done(val steps: List<String>, val warnings: List<Diagnostic>)

    private fun termux(): Boolean = env["PREFIX"]?.contains("com.termux") == true

    private fun unitDir(): String = (env["XDG_CONFIG_HOME"]?.takeIf { it.startsWith("/") } ?: "$userHome/.config") + "/systemd/user"

    fun install(): Outcome<Done> {
        if (termux()) return Outcome.failure(DiagCode.UNSUPPORTED, "служба агента — для ПК; в Termux target запускается по SSH", "service")
        return if (windows) installWindows() else installLinux()
    }

    fun remove(): Outcome<Done> {
        if (termux()) return Outcome.failure(DiagCode.UNSUPPORTED, "служба агента — для ПК", "service")
        return if (windows) removeWindows() else removeLinux()
    }

    private fun installLinux(): Outcome<Done> {
        val steps = mutableListOf<String>()
        val warnings = mutableListOf<Diagnostic>()
        try {
            val dir = NioVfs(File(unitDir()).toPath().also { it.toFile().mkdirs() })
            dir.write(UNIT, unit(selfCommand, home).toByteArray(Charsets.UTF_8))
            dir.fsync(UNIT)
        } catch (e: IOException) {
            return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "файл службы не записан", e, "${unitDir()}/$UNIT"))
        }
        steps += "записан ${unitDir()}/$UNIT"
        step(listOf("systemctl", "--user", "daemon-reload"))?.let { return it }
        step(listOf("systemctl", "--user", "enable", "--now", UNIT))?.let { return it }
        steps += "служба включена и запущена: systemctl --user status $UNIT"
        val user = env["USER"] ?: env["LOGNAME"]
        val linger = if (user == null) null else host.run(ProcessSpec(listOf("loginctl", "enable-linger", user)), TIMEOUT).valueOrNull()
        if (linger?.ok == true) {
            steps += "linger включён: служба идёт и без входа в систему"
        } else {
            warnings += Diagnostic.warning(
                DiagCode.OPERATION_NOT_ALLOWED,
                "linger не включён — служба уйдёт с последним выходом из системы; включить: sudo loginctl enable-linger ${user ?: "<имя>"}",
                "service",
            )
        }
        return Outcome.success(Done(steps, warnings))
    }

    private fun removeLinux(): Outcome<Done> {
        val steps = mutableListOf<String>()
        // Нет службы — `disable` отвечает отказом: это не ошибка снятия.
        host.run(ProcessSpec(listOf("systemctl", "--user", "disable", "--now", UNIT)), TIMEOUT)
        try {
            NioVfs(File(unitDir()).toPath()).delete(UNIT)
        } catch (e: IOException) {
            return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "файл службы не удалён", e, "${unitDir()}/$UNIT"))
        }
        step(listOf("systemctl", "--user", "daemon-reload"))?.let { return it }
        steps += "служба остановлена и снята; идущие ходы не тронуты; linger — как был (loginctl disable-linger — вручную)"
        return Outcome.success(Done(steps, emptyList()))
    }

    private fun installWindows(): Outcome<Done> {
        val java = selfCommand.firstOrNull() ?: return Outcome.failure(DiagCode.INTERNAL_ERROR, "нет команды агента", "service")
        val javaw = File(File(java).parentFile, "javaw.exe").path
        try {
            val vfs = NioVfs(File(home).toPath())
            vfs.write(ARGS, argFile(selfCommand.drop(1) + listOf("agent", "serve", "--home", home)).toByteArray(Charsets.UTF_8))
            vfs.fsync(ARGS)
        } catch (e: IOException) {
            return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "файл аргументов не записан", e, ARGS))
        }
        val script = registerScript(javaw, File(home, ARGS).path, home)
        step(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script))?.let { return it }
        return Outcome.success(Done(listOf("задача «$TASK» — при входе пользователя, запущена сейчас; окна нет (javaw)"), emptyList()))
    }

    private fun removeWindows(): Outcome<Done> {
        val script = "Stop-ScheduledTask -TaskName ${ps(TASK)} -ErrorAction SilentlyContinue; " +
            "Unregister-ScheduledTask -TaskName ${ps(TASK)} -Confirm:\$false -ErrorAction SilentlyContinue"
        step(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script))?.let { return it }
        runCatching { NioVfs(File(home).toPath()).delete(ARGS) }
        return Outcome.success(Done(listOf("задача «$TASK» снята; идущие ходы не тронуты"), emptyList()))
    }

    /** Шаг установки: отказ — с последней строкой вывода; успех — `null`. */
    private fun step(argv: List<String>): Outcome.Failure? = when (val r = host.run(ProcessSpec(argv), TIMEOUT)) {
        is Outcome.Failure -> r
        is Outcome.Success -> if (r.value.ok) {
            null
        } else {
            val why = (r.value.stderr + r.value.stdout).lastOrNull { it.isNotBlank() } ?: "код ${r.value.exitCode}"
            Outcome.failure(DiagCode.PROCESS_EXIT_NONZERO, "${argv.take(3).joinToString(" ")}: $why", "service")
        }
    }

    companion object {
        const val UNIT = "dpai-agent.service"
        const val TASK = "dpai-agent"
        const val ARGS = "service.args"
        private val TIMEOUT = 60.seconds

        /** Юнит службы пользователя systemd: ходы — потомки службы, и `KillMode=process` их не трогает. */
        fun unit(selfCommand: List<String>, home: String): String = """
            |# Служба агента dpai (DP_AI_Orcestrator:D-109): ходы Claude живут со службой, а не с сеансом SSH.
            |# Ставит и снимает: dpai agent service install|remove.
            |[Unit]
            |Description=dpai agent (DP AI Orcestrator)
            |
            |[Service]
            |Type=simple
            |ExecStart=${(selfCommand + listOf("agent", "serve", "--home", home)).joinToString(" ") { systemdArg(it) }}
            |WorkingDirectory=${home.replace("%", "%%")}
            |Restart=on-failure
            |RestartSec=5
            |KillMode=process
            |
            |[Install]
            |WantedBy=default.target
            |
        """.trimMargin()

        /**
         * Аргумент `ExecStart`: без пробелов и особых знаков — как есть; иначе — в
         * двойных кавычках с `\\` и `\"`. `%` (спецификаторы) — `%%`, `$` (подстановка
         * переменных) — `$$`.
         */
        fun systemdArg(a: String): String {
            val plain = a.replace("%", "%%").replace("$", "$$")
            return if (a.isNotEmpty() && a.none { it.isWhitespace() || it == '"' || it == '\'' || it == '\\' || it == ';' }) {
                plain
            } else {
                "\"" + plain.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            }
        }

        /**
         * Файл аргументов Java (`@файл`): по аргументу в строке, в двойных кавычках;
         * обратная косая — прямой (в файле аргументов `\` — экранирование, а Java на
         * Windows понимает `/` в путях).
         */
        fun argFile(args: List<String>): String =
            args.joinToString("\r\n", postfix = "\r\n") { "\"" + it.replace('\\', '/').replace("\"", "") + "\"" }

        /** PowerShell: задача при входе пользователя, без предела времени, перезапуск при сбое. */
        fun registerScript(javaw: String, argFile: String, workDir: String): String =
            "\$a = New-ScheduledTaskAction -Execute ${ps(javaw)} -Argument ${ps("\"@" + argFile.replace('\\', '/') + "\"")} -WorkingDirectory ${ps(workDir)}; " +
                "\$t = New-ScheduledTaskTrigger -AtLogOn -User ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name); " +
                "\$s = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries " +
                "-ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1); " +
                "Register-ScheduledTask -TaskName ${ps(TASK)} -Action \$a -Trigger \$t -Settings \$s -Force | Out-Null; " +
                "Start-ScheduledTask -TaskName ${ps(TASK)}"

        /** Строка PowerShell в одинарных кавычках: `'` удваивается. */
        private fun ps(s: String): String = "'" + s.replace("'", "''") + "'"
    }
}
