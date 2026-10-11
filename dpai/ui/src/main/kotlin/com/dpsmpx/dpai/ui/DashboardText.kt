package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.monitoring.DashboardView
import com.dpsmpx.dpai.monitoring.SelfDiagnosis
import com.dpsmpx.dpai.monitoring.TimelineEntry

/**
 * Текстовое представление экранов без платформы (`docs/ARCHITECTURE.md`,
 * §2: `ui`): сводка (ТЗ, §38) и временная шкала (ТЗ, §40). Тот же вид
 * берут `cli` и Android. Неизвестное показывается «?», а не нулём (D-13).
 */
object DashboardText {
    fun render(view: DashboardView, health: SelfDiagnosis? = null): String = buildString {
        health?.let { appendLine("СОСТОЯНИЕ: ${it.health}" + if (it.reasons.isEmpty()) "" else " — ${it.reasons.joinToString("; ")}") }
        appendLine("АКТИВНЫХ СЕССИЙ: ${view.activeSessions}")
        appendLine("ЗАДАЧ В ОЧЕРЕДИ: ${view.queuedTasks}")
        appendLine("ЗАБЛОКИРОВАНО: ${view.blocked}")
        appendLine("УПАЛО: ${view.failed}")
        appendLine("ЖДУТ ОДОБРЕНИЯ: ${count(view.waitingApproval)}")
        appendLine("ДОСТУПНО АККАУНТОВ: ${view.availableAccounts}")
        appendLine("TARGETS В СЕТИ: ${view.onlineTargets}")
        if (view.rows.isNotEmpty()) {
            appendLine()
            val w = (view.rows.maxOf { it.task.length }).coerceIn(8, 40)
            appendLine("ЗАДАЧА".padEnd(w) + "  СЕССИЯ                          СОСТОЯНИЕ         ДАЛЕЕ")
            for (r in view.rows) {
                appendLine(r.task.take(w).padEnd(w) + "  " + r.session.value.padEnd(32) + r.state.name.padEnd(18) + (r.next ?: "—"))
            }
        }
        if (view.attention.isNotEmpty()) {
            appendLine()
            appendLine("ВНИМАНИЕ:")
            view.attention.forEach { appendLine("- ${it.kind} ${it.subject}: ${it.detail}") }
        }
    }.trimEnd()

    fun timeline(entries: List<TimelineEntry>): String =
        entries.joinToString("\n") { "${it.at.toIsoString().substring(11, 19)}  ${it.text}" }

    private fun count(m: Measure<Int>): String = m.orNull()?.toString() ?: "?"
}
