package com.dpsmpx.dpai.orchestrator.efficiency

import com.dpsmpx.dpai.tasks.TaskRecord

/**
 * Маршрут хода: модель, усилие, запасная модель, право на субагентов и
 * почему ([route]: `pinned` — выбор автора, `class`, `escalated:N`,
 * `budget`, `legacy`). [downgraded] — режим бюджета удешевил маршрут.
 */
data class RouteDecision(
    val model: String?,
    val effort: String?,
    val fallbackModel: String?,
    val subagents: Boolean,
    val route: String,
    val downgraded: Boolean = false,
)

/**
 * Модель и усилие по классу задачи (D-40, п. 5; `docs/EFFICIENCY.md`, §7):
 *
 * - явная модель или усилие задачи — как есть, без эскалации и удешевления;
 * - иначе — маршрут класса; после каждых `escalateAfter` проваленных
 *   проверок — ступенью выше и модель (если она задана маршрутом), и
 *   усилие: дешёвое сначала, сильное — когда дешёвое не справилось;
 * - режим `FRUGAL` удешевляет только классы из `downgradable` и только до
 *   первого провала: правильность не меняется на экономию молча;
 * - запасная модель — ступенью ниже выбранной (`--fallback-model`), на
 *   случай перегрузки или недоступности модели на плане подписки.
 */
class ModelRouter(private val policy: RoutingPolicy) {
    fun route(task: TaskRecord, mode: BudgetMode): RouteDecision {
        val p = task.policy
        if (p.model != null || p.effort != null) return RouteDecision(p.model, p.effort, null, subagents = true, route = "pinned")
        if (!policy.enabled) return RouteDecision(null, null, null, subagents = true, route = "legacy")
        val base = policy.routes[p.taskClass] ?: Route()
        val failures = qualityFailures(task)
        val steps = failures / policy.escalateAfter
        var model = base.model?.let { up(it, steps) }
        var effort = if (steps > 0) upEffort(base.effort, steps) else base.effort
        var route = if (steps > 0) "escalated:$steps" else "class"
        var downgraded = false
        if (mode == BudgetMode.FRUGAL && p.taskClass in policy.downgradable && failures == 0) {
            val cheap = policy.ladder.first()
            val low = policy.efforts.first()
            downgraded = model != cheap || effort != low
            model = cheap
            effort = low
            route = "budget"
        }
        val fallback = model?.let { m -> policy.ladder.indexOf(m).takeIf { it > 0 }?.let { policy.ladder[it - 1] } }
        return RouteDecision(model, effort, fallback, base.subagents, route, downgraded)
    }

    /** Провалы по качеству — проваленные проверки задачи (ключи петли `verification:…`, `Tasks.recordVerification`). */
    fun qualityFailures(task: TaskRecord): Int = task.failures.filterKeys { it.startsWith("verification:") }.values.sum()

    private fun up(model: String, steps: Int): String {
        val i = policy.ladder.indexOf(model)
        return if (i < 0) model else policy.ladder[(i + steps).coerceAtMost(policy.ladder.lastIndex)]
    }

    /** Усилие по умолчанию Claude Code не сообщает: эскалация считает от второй ступени. */
    private fun upEffort(effort: String?, steps: Int): String {
        val i = effort?.let { policy.efforts.indexOf(it) }?.takeIf { it >= 0 } ?: 1.coerceAtMost(policy.efforts.lastIndex)
        return policy.efforts[(i + steps).coerceAtMost(policy.efforts.lastIndex)]
    }
}
