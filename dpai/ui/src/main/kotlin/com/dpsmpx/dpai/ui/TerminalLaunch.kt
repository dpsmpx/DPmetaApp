package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.accounts.SlotEnvironment
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.ssh.RemoteShell
import com.dpsmpx.dpai.execution.target.TargetPlatform

/**
 * Что запускает свой терминал на target (D-71): пустая команда —
 * оболочка входа; CLI слота — интерактивный `claude`, `codex` или `gemini`
 * в каталоге слота (D-29, D-100): вход, `/usage`, ручная работа.
 */
object TerminalLaunch {
    /**
     * CLI слота — через оболочку входа (`sh -l`): `PATH` пользователя
     * (`~/.local/bin`) есть только в ней, а канал
     * `exec` её не читает. Профиль может выставить ключ API или Bedrock —
     * их `env -u` снимает уже после профиля (D-29, ревью 17 октября 2026):
     * иначе CLI слота шёл бы не на подписку слота. Windows и target без
     * каталога слота — отказ.
     */
    fun claude(t: TargetEntry): Outcome<List<String>> = slot(t, login = false)

    /**
     * Вход слота (D-100): Claude — интерактивный `claude` (вход — `/login`),
     * Codex — `codex login --device-auth` (ссылка и код для браузера
     * телефона), Gemini — `gemini` без браузера на этом устройстве
     * (`NO_BROWSER=true`) со способом входа «аккаунт Google» в
     * `settings.json`, если способа ещё нет (`docs/research/GEMINI_CLI.md`).
     */
    fun login(t: TargetEntry): Outcome<List<String>> = slot(t, login = true)

    private fun slot(t: TargetEntry, login: Boolean): Outcome<List<String>> {
        val dir = t.configDir ?: return Outcome.failure(DiagCode.UNSUPPORTED, "у target нет каталога слота", t.label)
        if (t.platform != TargetPlatform.POSIX) {
            return Outcome.failure(DiagCode.UNSUPPORTED, "${t.executor.title} в терминале — на POSIX target; в Windows — из оболочки", t.label)
        }
        val unset = SlotEnvironment.overriding(t.executor).flatMap { listOf("-u", it) }
        // 24-битный цвет (терминал:A-23): библиотеки цвета CLI смотрят на COLORTERM, без него — 256 цветов.
        val env = listOf("env") + unset + listOf("COLORTERM=$COLORTERM", "${SlotEnvironment.configVar(t.executor)}=$dir")
        val command = when (t.executor) {
            Executor.CLAUDE -> listOf("claude")
            Executor.CODEX -> if (login) listOf("codex", "login", "--device-auth") else listOf("codex")
            Executor.GEMINI -> listOf("NO_BROWSER=true", "sh", "-c", GEMINI_SEED + " exec gemini")
        }
        return when (val inner = RemoteShell.join(TargetPlatform.POSIX, env + command)) {
            is Outcome.Failure -> inner
            is Outcome.Success -> Outcome.success(listOf("sh", "-lc", "exec ${inner.value}"))
        }
    }

    /**
     * Имя сессии `tmux` вкладки (D-111): постоянное у вкладки — устройство
     * ([device]: копия настроек на втором телефоне не входит в чужие сессии),
     * target, вид (`sh`, `claude`, `codex`, `gemini`) и номер среди открытых
     * вкладок того же target и вида; после обрыва связи или перезапуска
     * приложения вкладка с тем же номером возвращается в ту же сессию. Только
     * латиница, цифры и `-`: имя идёт в командную строку target.
     */
    fun tmuxName(device: String, target: String, kind: String, n: Int): String {
        fun clean(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val d = clean(device).take(TMUX_DEVICE_CHARS).trim('-').ifEmpty { "d" }
        val t = clean(target).takeLast(TMUX_TARGET_CHARS).trim('-').ifEmpty { "t" }
        return "dpai-$d-$t-${clean(kind).ifEmpty { "sh" }}-$n"
    }

    /** Команда вкладки с сессией в `tmux` (D-111) — [com.dpsmpx.dpai.execution.ssh.TmuxSession.wrap]. */
    fun inTmux(command: List<String>, name: String, socket: String? = null, resumeOnly: Boolean = false): Outcome<List<String>> =
        com.dpsmpx.dpai.execution.ssh.TmuxSession.wrap(command, name, socket, resumeOnly)

    /** Строка в терминале, когда `tmux` на target нет (D-111). */
    const val NO_TMUX: String = com.dpsmpx.dpai.execution.ssh.TmuxSession.NO_TMUX

    /** Сколько знаков метки устройства и id target в имени сессии. */
    private const val TMUX_DEVICE_CHARS = 4
    private const val TMUX_TARGET_CHARS = 6

    /** Эмулятор понимает `38;2;r;g;b` (D-71): программы узнают об этом по `COLORTERM`, как у kitty и Windows Terminal. */
    const val COLORTERM: String = "truecolor"

    /** Способ входа Gemini — аккаунт Google, если в `settings.json` слота его ещё нет (D-100). */
    const val GEMINI_SEED: String =
        "d=\"\$GEMINI_CLI_HOME/.gemini\"; mkdir -p \"\$d\"; " +
            "[ -f \"\$d/settings.json\" ] || printf '%s\\n' '{\"security\":{\"auth\":{\"selectedType\":\"oauth-personal\"}}}' > \"\$d/settings.json\";"
}
