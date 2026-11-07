package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.ids.Executor

/**
 * Что человек выбирает у исполнителя (D-100; аудит UI/UX 5 ноября 2026,
 * AI:A-10): лестница моделей и класс задачи — только у Claude Code, усилие —
 * у Claude и Codex (`model_reasoning_effort`), у Gemini его нет. Варианты,
 * которые не подействуют, на экран не выводятся (D-05: смысл не меняется
 * молча).
 */
object ExecutorOptions {
    /** Короткие имена моделей для чипов; у Codex и Gemini — только «модель задачи». */
    fun models(e: Executor): List<String> = if (e == Executor.CLAUDE) listOf("haiku", "sonnet", "opus") else emptyList()

    fun efforts(e: Executor): List<String> = when (e) {
        Executor.CLAUDE, Executor.CODEX -> listOf("low", "medium", "high")
        Executor.GEMINI -> emptyList()
    }

    /** Класс задачи — маршрут моделей D-40 — есть только у Claude. */
    fun hasClass(e: Executor): Boolean = e == Executor.CLAUDE

    /** Подсказка поля модели: у Claude пусто — по классу, у других — модель CLI по умолчанию. */
    fun modelHint(e: Executor): String = if (hasClass(e)) "Модель (пусто — по классу)" else "Модель ${e.title} (пусто — по умолчанию)"

    fun effortHint(e: Executor): String = if (hasClass(e)) "Усилие: low, medium, high (пусто — по классу)" else "Усилие: low, medium, high (пусто — по умолчанию)"
}
