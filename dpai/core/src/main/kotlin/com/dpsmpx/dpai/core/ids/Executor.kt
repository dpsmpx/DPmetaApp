package com.dpsmpx.dpai.core.ids

/**
 * Исполнитель хода (D-100): официальный CLI, который ведёт сессию задачи
 * на target. [key] — имя в форматах и на экране (`claude`, `codex`,
 * `gemini`); формы без поля исполнителя читаются как [CLAUDE].
 */
enum class Executor(val key: String, val title: String) {
    CLAUDE("claude", "Claude Code"),
    CODEX("codex", "Codex"),
    GEMINI("gemini", "Gemini"),
    ;

    /** Следующий по кругу Claude → Codex → Gemini — для перехода у предела (D-100). */
    val next: Executor get() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun byKey(key: String): Executor? = entries.firstOrNull { it.key == key }
    }
}
