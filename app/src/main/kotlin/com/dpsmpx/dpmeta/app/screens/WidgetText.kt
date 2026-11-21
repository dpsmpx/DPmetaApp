package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpai.ui.ManualCounts
import com.dpsmpx.dpai.ui.ManualProfileKind

/** Только числа и даты: содержимое виджета безопасно и при выключенной приватности (D-144). */
data class WidgetText(val projects: String, val projectDate: String, val ai: String, val health: String, val runs: String = runs(null)) {
    companion object {
        fun of(projects: Screens.Summary?, ai: Screens.AiCounts?, problems: Int? = null, manual: ManualCounts? = null): WidgetText = WidgetText(
            projects = projects?.let { "Проекты: ${it.projects} · PR: ${it.pullsOpen} · CI: ${it.ciRed}" }
                ?: "Проекты: неизвестно · PR: неизвестно · Вопросы: неизвестно",
            projectDate = projects?.let { "Срез проектов: ${it.asOf}" } ?: "Нет среза проектов",
            ai = when {
                ai == null -> "ИИ: состояние неизвестно"
                !ai.configured -> "ИИ не настроен"
                else -> "ИИ: в работе ${ai.running.orNull() ?: "неизвестно"} · ждут ${ai.waiting.orNull() ?: "неизвестно"}"
            },
            health = problems?.let { "Проверка: $it проблем" } ?: "Проверка: неизвестно",
            runs = runs(manual),
        )

        /**
         * Ручные сборки и проверки (D-153): идущий запуск и итоги за сутки; не прочитано —
         * «неизвестно», а не ноль. Одна строка — в минимальном размере виджета.
         */
        fun runs(m: ManualCounts?): String {
            val now = when (m?.running) {
                null -> null
                // D-181: зависимости имеют отдельную историю, не считаются сборкой/проверкой.
                ManualProfileKind.DEPENDENCIES -> null
                ManualProfileKind.BUILD -> "идёт сборка"
                ManualProfileKind.CHECK -> "идёт проверка"
            }
            val day = if (m?.ok == null || m.failed == null) "за сутки неизвестно" else "за сутки ✓ ${m.ok} · ✗ ${m.failed}"
            return "Запуски: " + listOfNotNull(now, day).joinToString(" · ")
        }
    }
}
