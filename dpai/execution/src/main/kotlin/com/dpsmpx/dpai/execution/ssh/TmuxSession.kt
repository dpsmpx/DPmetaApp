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

    /** `tmux` есть, но не запустился (версия, права) — вкладка работает без него. */
    const val TMUX_FAILED: String = "tmux не запустился — вкладка без него."

    /** Автопереподключение CLI слота нашло не ту сессию: заново молча не запускается (D-111). */
    const val NOT_RESUMED: String = "Сессии tmux нет — «Переподключить» запустит программу заново."

    /** Код выхода «вернуть без потерь нельзя»: экран предлагает «Переподключить» (EX_TEMPFAIL). */
    const val NOT_RESUMED_CODE = 75

    /**
     * Сколько секунд tmux может проработать и всё ещё считаться «не
     * запустившимся»: дольше — сессия была и кончилась (`kill-server`, сбой
     * сервера), и программа заново сама не запускается.
     */
    const val START_SECONDS = 5

    private val NAME = Regex("[a-z0-9-]+")

    /**
     * Команда вкладки. [command] — как у вкладки (`sh -lc "exec …"` CLI
     * слота) или пусто (оболочка входа); внутрь `tmux` она идёт целиком —
     * с оболочкой входа, даже если сервер `tmux` на target запущен без неё.
     *
     * - Обычный запуск: есть `tmux` — `tmux -u new -A -s <имя> [команда]`
     *   (сессия есть — вход в неё, нет — новая; `-u` — UTF-8 и без локали
     *   SSH); нет — [NO_TMUX] и сама команда; `tmux` не запустился —
     *   [TMUX_FAILED] и сама команда.
     * - [resumeOnly] (автопереподключение CLI слота, D-111): только вход в
     *   сессию, если она есть; иначе — [NOT_RESUMED] и код
     *   [NOT_RESUMED_CODE], программа заново не запускается.
     * - [socket] — свой сервер `tmux -L` (target «в приложении»: у каждой
     *   вкладки свой proot, общий сервер погиб бы с первой вкладкой).
     */
    fun wrap(command: List<String>, name: String, socket: String? = null, resumeOnly: Boolean = false): Outcome<List<String>> {
        require(NAME.matches(name)) { "имя сессии — латиница, цифры, -" }
        require(socket == null || NAME.matches(socket)) { "сокет — латиница, цифры, -" }
        val full = if (command.isEmpty()) {
            null
        } else {
            when (val j = RemoteShell.join(TargetPlatform.POSIX, command)) {
                is Outcome.Failure -> return j
                is Outcome.Success -> j.value
            }
        }
        val quoted = full?.let { f ->
            when (val q = RemoteShell.join(TargetPlatform.POSIX, listOf(f))) {
                is Outcome.Failure -> return q
                is Outcome.Success -> " " + q.value
            }
        }.orEmpty()
        val tmux = "tmux" + (socket?.let { " -L $it" } ?: "")
        val script = if (resumeOnly) {
            "if command -v tmux >/dev/null 2>&1 && $tmux has-session -t =$name 2>/dev/null; then $tmux -u attach -t =$name && exit 0; fi; " +
                "printf '%s\\n' '$NOT_RESUMED'; exit $NOT_RESUMED_CODE"
        } else {
            // Без exec: tmux, который не запустился, — не конец вкладки, а запасной путь. Проработал дольше
            // START_SECONDS и кончился с ошибкой — сессия была: вкладка закрывается с его кодом, программа заново не идёт.
            // Запасной путь — уже в оболочке входа: команда `sh -lc X` — сразу X, профиль не читается дважды.
            val direct = if (command.size == 3 && command[0] == "sh" && command[1] == "-lc" && command[2].startsWith("exec ")) command[2] else null
            val fallback = direct ?: full?.let { "exec $it" } ?: "exec \"\${SHELL:-/bin/sh}\" -l"
            "if command -v tmux >/dev/null 2>&1; then s=\$(date +%s 2>/dev/null || echo 0); $tmux -u new -A -s $name$quoted && exit 0; c=\$?; " +
                "[ \$((\$(date +%s 2>/dev/null || echo 0) - s)) -ge $START_SECONDS ] && exit \$c; printf '%s\\n' '$TMUX_FAILED'; " +
                "else printf '%s\\n' '$NO_TMUX'; fi; $fallback"
        }
        return Outcome.success(listOf("sh", "-lc", script))
    }

    /**
     * Завершить сессию [name] (закрытие вкладки, D-111): только точное имя
     * (`=имя`, иначе `tmux` берёт и сессию с таким началом); нет `tmux` или
     * сессии — тихо.
     */
    fun kill(name: String, socket: String? = null): List<String> {
        require(NAME.matches(name)) { "имя сессии — латиница, цифры, -" }
        require(socket == null || NAME.matches(socket)) { "сокет — латиница, цифры, -" }
        val tmux = "tmux" + (socket?.let { " -L $it" } ?: "")
        return listOf("sh", "-lc", "$tmux kill-session -t =$name 2>/dev/null; true")
    }
}
