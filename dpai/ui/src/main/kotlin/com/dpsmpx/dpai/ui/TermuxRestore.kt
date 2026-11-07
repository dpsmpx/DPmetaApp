package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.target.TargetPlatform
import java.security.MessageDigest
import java.util.Base64

/**
 * Восстановление после переустановки приложения (D-54). Удаление стирает
 * настройки и ключ приложения в Keystore, но Termux цел: `sshd`, агент,
 * входы Claude в каталогах слотов, копия настроек `~/.dpai/app-settings.json`
 * (приложение кладёт её туда вместе с копией в «Загрузки»), ключ связки с ПК
 * в proot (D-53).
 *
 * Человек вставляет в Termux одну команду ([command]): она заменяет строку
 * прежнего ключа приложения в `authorized_keys` новой и кладёт одноразовый
 * код. Приложение входит по SSH и доверяет ключу хоста Termux, только если
 * сервер вернул этот код ([codeMatches]): код знает лишь настоящий Termux,
 * куда его вставил человек, — подставной сервер на 127.0.0.1:8022 его не
 * знает (D-31 без сверки отпечатка вручную). Дальше — разбор ответа
 * ([parse]), настройки из копии и targets по слотам ([merge]), ключ
 * приложения на ПК — через ключ связки ([pcReauthorize]).
 */
object TermuxRestore {
    const val HOST = "127.0.0.1"
    const val PORT = 8022
    private const val CODE = "\$HOME/.dpai/restore-code"
    const val SETTINGS = "\$HOME/.dpai/app-settings.json"
    private val NONCE = Regex("^[0-9a-f]{32}$")
    private val APP_KEY = Regex("^[a-z0-9-]+ [A-Za-z0-9+/]+={0,2} dpai-android$")

    /** Команда для Termux: ключ приложения вместо прежнего, код, `sshd`; `null` — ключ или код не те. */
    fun command(appKey: String, nonce: String): String? {
        if (!APP_KEY.matches(appKey.trim()) || !NONCE.matches(nonce)) return null
        return "mkdir -p ~/.ssh ~/.dpai && chmod 700 ~/.ssh ~/.dpai && " +
            "{ sed -i '/ dpai-android\$/d' ~/.ssh/authorized_keys 2>/dev/null; true; } && " +
            "echo 'from=\"127.0.0.1,::1\" ${appKey.trim()}' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && " +
            "printf %s $nonce > ~/.dpai/restore-code && chmod 600 ~/.dpai/restore-code && " +
            "{ pgrep -x sshd >/dev/null || sshd -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no; } && " +
            "echo 'Готово: вернитесь в приложение'"
    }

    /** Что есть в Termux: код, пользователь, агент, каталоги слотов (`~/.claude-slot-*`, D-29; вход в них проверяет проба слота приложения), ключ связки, копия настроек. */
    fun discover(): List<String> = listOf(
        "sh", "-c",
        "echo \"code=\$(cat \"$CODE\" 2>/dev/null)\"; echo \"user=\$(whoami)\"; " +
            "[ -x \"\$HOME/dpai/bin/dpai\" ] && echo \"agent=\$HOME/dpai/bin/dpai\"; " +
            "for d in \"\$HOME\"/.claude-slot-*; do [ -d \"\$d\" ] && echo \"slot=\$d\"; done; " +
            "proot-distro login alpine -- test -f ${WorkspaceLink.KEY} 2>/dev/null && echo workspace=yes; " +
            "if [ -f \"$SETTINGS\" ]; then echo settings-begin; cat \"$SETTINGS\"; echo; echo settings-end; fi; true",
    )

    /** Код использован — убрать. */
    fun cleanup(): List<String> = listOf("sh", "-c", "rm -f \"$CODE\"")

    /** Копия настроек — в Termux (со входа), атомарно. */
    fun mirror(): List<String> = listOf(
        "sh", "-c",
        "mkdir -p \"\$HOME/.dpai\" && chmod 700 \"\$HOME/.dpai\" && cat > \"$SETTINGS.tmp\" && chmod 600 \"$SETTINGS.tmp\" && mv \"$SETTINGS.tmp\" \"$SETTINGS\"",
    )

    data class Found(
        val code: String,
        val user: String,
        val agent: String?,
        val slots: List<String>,
        val workspace: Boolean,
        val settings: String?,
    )

    fun parse(stdout: List<String>): Found {
        val begin = stdout.indexOf("settings-begin")
        val end = stdout.lastIndexOf("settings-end")
        val settings = if (begin >= 0 && end > begin) stdout.subList(begin + 1, end).joinToString("\n").trim().takeIf { it.isNotEmpty() } else null
        val head = if (begin >= 0) stdout.subList(0, begin) else stdout
        fun value(k: String) = head.firstOrNull { it.startsWith("$k=") }?.substringAfter('=')?.trim()
        return Found(
            code = value("code").orEmpty(),
            user = value("user").orEmpty(),
            agent = value("agent")?.takeIf { it.isNotEmpty() },
            slots = head.filter { it.startsWith("slot=") }.map { it.substringAfter('=').trim() }.filter { it.isNotEmpty() },
            workspace = head.any { it.trim() == "workspace=yes" },
            settings = settings,
        )
    }

    /** Код совпал — сравнение за постоянное время; пустой код не совпадает ни с чем. */
    fun codeMatches(found: Found, nonce: String): Boolean =
        NONCE.matches(nonce) && MessageDigest.isEqual(found.code.toByteArray(), nonce.toByteArray())

    /**
     * Настройки после восстановления: нынешние, а если их нет — из копии в
     * Termux; у targets Termux — пользователь из ответа; на каждый каталог
     * слота без target — новый target (вход проверит проба слота); ключ хоста Termux — [hostLine]
     * вместо прежних строк этого имени. Итог и строки отчёта.
     */
    fun merge(current: SettingsSnapshot, found: Found, hostLine: String, ids: IdGenerator): Pair<SettingsSnapshot, List<String>> {
        val report = mutableListOf<String>()
        val fromCopy = if (current.targets.isEmpty() && found.settings != null) {
            when (val r = SettingsBackup.read(found.settings)) {
                is Outcome.Success -> r.value.also { report += "настройки — из копии в Termux: targets ${it.targets.size}, проект — ${if (it.project != null) "есть" else "нет"}" }
                is Outcome.Failure -> null.also { report += "копия в Termux не прочитана: ${r.diagnostics.first().message}" }
            }
        } else {
            null
        }
        val base = fromCopy ?: current
        val local = { t: TargetEntry -> (t.host == HOST || t.host == "localhost") && t.port == PORT }
        val agent = found.agent ?: SetupGuide.AGENT
        var targets = base.targets.map { t -> if (local(t) && found.user.isNotEmpty() && t.user != found.user) t.copy(user = found.user) else t }
        for (slot in found.slots) {
            if (targets.any { local(it) && it.configDir == slot }) continue
            val label = "Termux (" + slot.substringAfterLast('/').trimStart('.') + ")"
            TargetForm.validate(ids.next(IdKinds.TARGET), label, HOST, PORT.toString(), found.user, false, agent, slot).valueOrNull()?.let {
                targets = targets + it
                report += "новый target: $label"
            }
        }
        if (targets.none(local)) {
            TargetForm.validate(ids.next(IdKinds.TARGET), "Termux", HOST, PORT.toString(), found.user, false, agent, null).valueOrNull()?.let {
                targets = targets + it
                report += "новый target: Termux (входа Claude в Termux не найдено)"
            }
        }
        val name = hostLine.trim().substringBefore(' ')
        val known = base.knownHosts.lines().map(String::trim).filter { it.isNotEmpty() && it.substringBefore(' ') != name } + hostLine.trim()
        return SettingsSnapshot(targets, base.project, known.joinToString("\n", postfix = "\n")) to report
    }

    /**
     * Ключ приложения на ПК (или Linux) [pc] — с Termux ключом связки D-53:
     * строка прежнего ключа приложения заменяется новой. Ключ хоста ПК в
     * known_hosts proot — с прошлой связки; нет его — вход отвергается.
     */
    fun pcReauthorize(pc: TargetEntry, appKey: String): List<String> {
        require(APP_KEY.matches(appKey.trim())) { "не ключ приложения" }
        val key = appKey.trim()
        val remote = when (pc.platform) {
            TargetPlatform.WINDOWS -> {
                val script = "\$ak = \"\$env:ProgramData\\ssh\\administrators_authorized_keys\"; " +
                    "\$keep = @(if (Test-Path \$ak) { Get-Content \$ak | Where-Object { \$_ -notmatch ' dpai-android\$' } }); " +
                    "Set-Content \$ak (\$keep + 'from=\"${PcSetup.PRIVATE_NETS}\" $key') -Encoding ascii; " +
                    "icacls \$ak /inheritance:r /grant Administrators:F /grant SYSTEM:F | Out-Null; 'dpai-reauthorized'"
                "powershell -NoProfile -NonInteractive -EncodedCommand " + Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
            }
            TargetPlatform.POSIX -> {
                val script = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && { sed -i '/ dpai-android\$/d' ~/.ssh/authorized_keys 2>/dev/null; true; } && " +
                    // Только из частных сетей, как при настройке (D-84): восстановление не снимает это ограничение.
                    "echo 'from=\"${PcSetup.PRIVATE_NETS}\" $key' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && echo dpai-reauthorized"
                "echo " + Base64.getEncoder().encodeToString(script.toByteArray()) + " | base64 -d | sh"
            }
        }
        return listOf(
            "proot-distro", "login", "alpine", "--", "sh", "-c",
            "exec ssh -T -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=${WorkspaceLink.KNOWN_HOSTS} -o ConnectTimeout=15 " +
                "-i ${WorkspaceLink.KEY} -p \"\$1\" \"\$2\" \"\$3\"",
            "sh", pc.port.toString(), "${pc.user}@${pc.host}", remote,
        )
    }

    /** Ключ на ПК заменён: удалённая сторона отчиталась. */
    fun reauthorized(stdout: List<String>): Boolean = stdout.any { it.trim() == "dpai-reauthorized" }
}
