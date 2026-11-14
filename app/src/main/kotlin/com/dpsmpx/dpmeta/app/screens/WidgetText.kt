package com.dpsmpx.dpmeta.app.screens

/** Только числа и даты: содержимое виджета безопасно и при выключенной приватности (D-144). */
data class WidgetText(val projects: String, val projectDate: String, val ai: String, val health: String) {
    companion object {
        fun of(projects: Screens.Summary?, ai: Screens.AiCounts?, problems: Int? = null): WidgetText = WidgetText(
            projects = projects?.let { "Проекты: ${it.active} · PR: ${it.pullsWaiting} · Вопросы: ${it.questions}" }
                ?: "Проекты: неизвестно · PR: неизвестно · Вопросы: неизвестно",
            projectDate = projects?.let { "Срез проектов: ${it.asOf}" } ?: "Нет среза проектов",
            ai = when {
                ai == null -> "ИИ: состояние неизвестно"
                !ai.configured -> "ИИ не настроен"
                else -> "ИИ: в работе ${ai.running.orNull() ?: "неизвестно"} · ждут ${ai.waiting.orNull() ?: "неизвестно"}"
            },
            health = problems?.let { "Проверка: $it проблем" } ?: "Проверка: неизвестно",
        )
    }
}
