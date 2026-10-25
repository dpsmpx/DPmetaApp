package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.accounts.SlotEnvironment
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.ssh.RemoteShell
import com.dpsmpx.dpai.execution.target.TargetPlatform

/**
 * Что запускает свой терминал на target (D-71): пустая команда —
 * оболочка входа; «Claude» — интерактивный `claude` в каталоге слота
 * (`CLAUDE_CONFIG_DIR`, D-29): вход, `/usage`, ручная работа.
 */
object TerminalLaunch {
    /**
     * `claude` слота — через оболочку входа (`sh -l`): `PATH` пользователя
     * (`~/.local/bin`, `$PREFIX/bin` Termux) есть только в ней, а канал
     * `exec` её не читает. Профиль может выставить ключ API или Bedrock —
     * их `env -u` снимает уже после профиля (D-29, ревью 17 октября 2026):
     * иначе интерактивный Claude слота шёл бы не на подписку слота.
     * Windows и target без каталога слота — отказ.
     */
    fun claude(t: TargetEntry): Outcome<List<String>> {
        val dir = t.configDir ?: return Outcome.failure(DiagCode.UNSUPPORTED, "у target нет каталога Claude слота", t.label)
        if (t.platform != TargetPlatform.POSIX) {
            return Outcome.failure(DiagCode.UNSUPPORTED, "Claude в терминале — на POSIX target; в Windows — из оболочки", t.label)
        }
        val unset = SlotEnvironment.OVERRIDING.flatMap { listOf("-u", it) }
        return when (val inner = RemoteShell.join(TargetPlatform.POSIX, listOf("env") + unset + listOf("${SlotEnvironment.CONFIG_DIR}=$dir", "claude"))) {
            is Outcome.Failure -> inner
            is Outcome.Success -> Outcome.success(listOf("sh", "-lc", "exec ${inner.value}"))
        }
    }
}
