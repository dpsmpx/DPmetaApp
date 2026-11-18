package com.dpsmpx.dpai.execution.target

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Вид execution target (D-26; `docs/EXECUTION_MODEL.md`, §1). `LOCAL` —
 * машина самого контроллера (`cli` на Linux или Windows), без SSH; `MOCK` —
 * для тестов ядра без процессов; `IN_APP` — Linux-среда в самом
 * приложении, агент — в его процессе, без SSH (D-75; `docs/EXECUTION_MODEL.md`, §8).
 */
enum class TargetType { LOCAL, LINUX_SSH, WINDOWS_REMOTE, MOCK, IN_APP }

/** Оболочка, которой SSH-сервер target исполняет команду (`RemoteShell`). */
enum class TargetPlatform { POSIX, WINDOWS }

/**
 * Адрес SSH (D-26, D-31). Проверка ключа хоста обязательна (T2): файл
 * известных хостов — [knownHostsFile] или стандартный файл пользователя;
 * ключ входа — [identityFile] (путь, а не секрет: сам ключ лежит на диске
 * контроллера, D-14).
 */
data class SshEndpoint(
    val host: String,
    val user: String,
    val port: Int = 22,
    val identityFile: String? = null,
    val knownHostsFile: String? = null,
) {
    companion object {
        private val HOST = Regex("[A-Za-z0-9._:\\[\\]-]{1,253}")
        private val USER = Regex("[A-Za-z0-9._][A-Za-z0-9._-]{0,63}")

        /**
         * Строгая проверка: имя хоста, пользователь и пути не начинаются с `-`
         * и не содержат пробелов и управляющих знаков — иначе их принял бы за
         * параметр сам `ssh`.
         */
        fun of(
            host: String,
            user: String,
            port: Int = 22,
            identityFile: String? = null,
            knownHostsFile: String? = null,
        ): Outcome<SshEndpoint> {
            if (!HOST.matches(host) || host.startsWith("-")) {
                return Outcome.failure(DiagCode.VALUE_INVALID, "имя хоста SSH недопустимо", "host")
            }
            if (!USER.matches(user)) return Outcome.failure(DiagCode.VALUE_INVALID, "имя пользователя SSH недопустимо", "user")
            if (port !in 1..65535) return Outcome.failure(DiagCode.VALUE_INVALID, "порт SSH — 1…65535", "port")
            for ((name, path) in listOf("identityFile" to identityFile, "knownHostsFile" to knownHostsFile)) {
                if (path != null && (path.isBlank() || path.any { it.isISOControl() } || path.startsWith("-"))) {
                    return Outcome.failure(DiagCode.VALUE_INVALID, "путь недопустим", name)
                }
            }
            return Outcome.success(SshEndpoint(host, user, port, identityFile, knownHostsFile))
        }
    }
}

/**
 * Execution target (ТЗ, §7, §11; `docs/DATA_MODEL.md`, §3): где работает
 * агент `dpai agent` и Claude. [agentCommand] — как вызвать агента на
 * target (`["dpai"]` или путь к сценарию установки); [agentHome] — каталог
 * агента на target с журналами запусков (`null` — по умолчанию агента).
 */
data class ExecutionTarget(
    val id: ExecutionTargetId,
    val type: TargetType,
    val label: String,
    val platform: TargetPlatform,
    val ssh: SshEndpoint?,
    val agentCommand: List<String>,
    val agentHome: String? = null,
    val workspaceRoot: String? = null,
) {
    init {
        require(label.isNotBlank()) { "у target нет метки" }
        require(agentCommand.isNotEmpty()) { "у target нет команды агента" }
        require(type.usesSsh == (ssh != null)) { "SSH у target $type ${if (type.usesSsh) "обязателен" else "не задаётся"}" }
    }
}

/** Ходит ли контроллер к target по SSH (D-26, D-202); loopback — обычный SSH-адрес. */
val TargetType.usesSsh: Boolean
    get() = this == TargetType.LINUX_SSH || this == TargetType.WINDOWS_REMOTE
