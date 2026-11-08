package com.dpsmpx.dpai.execution.ssh

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.target.TargetPlatform

/**
 * Сессия вкладки терминала в `tmux` на POSIX target (D-111): после обрыва
 * связи или перезапуска приложения вкладка входит в ту же сессию. Команда —
 * одна строка `sh -lc` (оболочка входа: `PATH` пользователя с `tmux` из
 * Termux — только в ней), кавычки — по правилам [RemoteShell].
 */
object TmuxSession {
    /** Строка в терминале, когда `tmux` на target нет: приложение его не ставит само (D-111). */
    const val NO_TMUX: String = "Нет tmux — сессия не переживёт обрыв связи. Поставить: pkg install tmux (Termux), sudo apt install tmux (Debian, Ubuntu), apk add tmux (Alpine)."

    private val NAME = Regex("[a-z0-9-]+")

    /**
     * Есть `tmux` — `tmux new -A -s <имя> [команда]` (сессия есть — вход в
     * неё, нет — новая); нет — [NO_TMUX] и прежняя команда. [command] —
     * `sh -lc "exec …"` (CLI слота) или пусто (оболочка входа).
     */
    fun wrap(command: List<String>, name: String): Outcome<List<String>> {
        require(NAME.matches(name)) { "имя сессии — латиница, цифры, -" }
        val inner = when {
            command.isEmpty() -> null
            command.size == 3 && command[0] == "sh" && command[1] == "-lc" -> command[2].removePrefix("exec ")
            else -> when (val j = RemoteShell.join(TargetPlatform.POSIX, command)) {
                is Outcome.Failure -> return j
                is Outcome.Success -> j.value
            }
        }
        val quoted = inner?.let { i ->
            when (val q = RemoteShell.join(TargetPlatform.POSIX, listOf(i))) {
                is Outcome.Failure -> return q
                is Outcome.Success -> " " + q.value
            }
        }.orEmpty()
        val fallback = inner?.let { "exec $it" } ?: "exec \"\${SHELL:-/bin/sh}\" -l"
        val script = "if command -v tmux >/dev/null 2>&1; then exec tmux new -A -s $name$quoted; fi; " +
            "printf '%s\\n' '$NO_TMUX'; $fallback"
        return Outcome.success(listOf("sh", "-lc", script))
    }
}
