package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Репозитории Alpine гостя (D-193; `DPmetaApp:D-171`, шаг 3): ветка — выпуск корневой
 * системы (`v3.24`) или `edge`, `testing` — только у `edge` (у выпусков его нет). Зеркало —
 * прежнее [MIRROR]: своих адресов нет, адресаты сети гостя не меняются. Файл, который
 * правили руками, узнаётся как «свой список» и без согласия человека не переписывается.
 */
object AlpineRepositories {
    const val MIRROR = "https://dl-cdn.alpinelinux.org/alpine"

    /** Выбор человека. */
    data class Choice(val edge: Boolean, val testing: Boolean) {
        init {
            require(!testing || edge) { "testing есть только у edge" }
        }
    }

    private val RELEASE = Regex("^(\\d{1,2})\\.(\\d{1,3})(?:\\.\\d{1,3})?$")

    /** Ветка выпуска по `/etc/alpine-release` (`3.24.3` → `v3.24`); непонятный выпуск — `null`. */
    fun branch(release: String?): String? = release?.trim()?.let(RELEASE::matchEntire)?.let { "v${it.groupValues[1]}.${it.groupValues[2]}" }

    /** Текст `/etc/apk/repositories` для [choice]; ветка выпуска нужна, когда выбран не `edge`. */
    fun text(choice: Choice, release: String?): Outcome<String> {
        val b = if (choice.edge) "edge" else branch(release) ?: return Outcome.failure(DiagCode.VALUE_INVALID, "выпуск Alpine не понятен: ${release ?: "нет /etc/alpine-release"}")
        val repos = listOf("main", "community") + listOfNotNull("testing".takeIf { choice.testing })
        return Outcome.success(repos.joinToString("") { "$MIRROR/$b/$it\n" })
    }

    /** Что выбрано сейчас: файл в одном из видов [text] — выбор; иначе (правили руками) — `null`. */
    fun parse(lines: List<String>, release: String?): Choice? {
        val got = lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }
        for (c in listOf(Choice(false, false), Choice(true, false), Choice(true, true))) {
            val want = (text(c, release) as? Outcome.Success)?.value?.lines()?.filter { it.isNotEmpty() } ?: continue
            if (got == want) return c
        }
        return null
    }

    /** Предупреждение к выбору: чем рискует человек. */
    fun warning(choice: Choice): String? = when {
        choice.testing -> "testing — пакеты на пробе: они могут не собраться, не запуститься или исчезнуть. Ставьте из него только то, чего нет в main и community."
        choice.edge -> "edge — разрабатываемая ветка: пакеты новее, но обновление может сломать поставленное. Перед переходом сделайте снимок окружения."
        else -> null
    }
}
