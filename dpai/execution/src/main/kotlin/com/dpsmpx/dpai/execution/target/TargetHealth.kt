package com.dpsmpx.dpai.execution.target

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.state.CircuitLifecycle
import com.dpsmpx.dpai.core.state.CircuitState
import com.dpsmpx.dpai.core.state.TargetLifecycle
import com.dpsmpx.dpai.core.state.TargetState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Итог одной пробы target: дошли ли до агента и сколько это заняло.
 * Задержка недошедшей пробы неизвестна, а не ноль (D-13).
 */
data class ProbeResult(
    val at: Timestamp,
    val reachable: Boolean,
    val latency: Measure<Duration>,
    val diagnostics: List<Diagnostic> = emptyList(),
)

/**
 * Пороги здоровья (ТЗ, §14, §81). В `OFFLINE` target уходит, только когда
 * выполнены оба порога: подряд не меньше [offlineAfterFailures] неудачных
 * проб, и с первой из них прошло не меньше [offlineAfter]. Ни одиночный
 * пропуск, ни быстрая серия проб за секунду target не роняют. Возврат из
 * `OFFLINE` — после [recoverAfterSuccesses] удачных проб подряд.
 */
data class TargetHealthPolicy(
    val offlineAfterFailures: Int = 3,
    val offlineAfter: Duration = 1.minutes,
    val recoverAfterSuccesses: Int = 2,
    val slowLatency: Duration = 5.seconds,
    /** Пауза предохранителя: пока она не прошла, к `OFFLINE` target пробы не идут. */
    val openPause: Duration = 30.seconds,
) {
    init {
        require(offlineAfterFailures >= 2) { "OFFLINE не по одному пропуску: offlineAfterFailures ≥ 2" }
        require(offlineAfter.isPositive() && openPause.isPositive() && slowLatency.isPositive()) { "пороги — положительные" }
        require(recoverAfterSuccesses >= 1) { "recoverAfterSuccesses ≥ 1" }
    }
}

/** Здоровье target: состояние автомата, серии проб и предохранитель. */
data class TargetHealth(
    val state: TargetState = TargetLifecycle.table.initial,
    val circuit: CircuitState = CircuitLifecycle.table.initial,
    val consecutiveFailures: Int = 0,
    val consecutiveSuccesses: Int = 0,
    val failingSince: Timestamp? = null,
    val lastProbeAt: Timestamp? = null,
    val lastSeen: Measure<Timestamp> = Measure.unknown("target ещё не отвечал"),
    val lastLatency: Measure<Duration> = Measure.unknown("проб ещё не было"),
) {
    /** Можно ли пробовать сейчас: у открытого предохранителя — только после паузы (ТЗ, §81). */
    fun probeAllowed(now: Timestamp, policy: TargetHealthPolicy): Boolean =
        state != TargetState.DISABLED &&
            (circuit != CircuitState.OPEN || lastProbeAt == null || now - lastProbeAt >= policy.openPause)
}

/**
 * Свёртка проб в здоровье (`docs/EXECUTION_MODEL.md`, §1–§2). Чистая
 * функция: одни и те же пробы дают одно и то же здоровье (D-22). Переходы
 * — только по таблицам `TargetLifecycle` и `CircuitLifecycle`; выключенный
 * target пробы не меняют.
 */
object TargetHealthTracker {
    /** Новое здоровье; [changedFrom] — прежнее состояние, если оно сменилось. */
    data class Step(val health: TargetHealth, val changedFrom: TargetState?)

    fun observe(current: TargetHealth, probe: ProbeResult, policy: TargetHealthPolicy): Step {
        if (current.state == TargetState.DISABLED) return Step(current, null)
        val next = if (probe.reachable) onSuccess(current, probe, policy) else onFailure(current, probe, policy)
        check(TargetLifecycle.table.isAllowed(current.state, next.state)) { "target: ${current.state} → ${next.state}" }
        check(CircuitLifecycle.table.isAllowed(current.circuit, next.circuit)) { "предохранитель: ${current.circuit} → ${next.circuit}" }
        return Step(next, current.state.takeIf { it != next.state })
    }

    private fun onSuccess(h: TargetHealth, probe: ProbeResult, policy: TargetHealthPolicy): TargetHealth {
        val successes = h.consecutiveSuccesses + 1
        val slow = probe.latency.orNull()?.let { it > policy.slowLatency } ?: false
        val state = when {
            h.state == TargetState.OFFLINE && successes < policy.recoverAfterSuccesses -> TargetState.OFFLINE
            slow -> TargetState.DEGRADED
            else -> TargetState.ONLINE
        }
        val circuit = when (h.circuit) {
            CircuitState.HEALTHY, CircuitState.FAILING, CircuitState.RECOVERED -> CircuitState.HEALTHY
            CircuitState.OPEN -> CircuitState.HALF_OPEN
            CircuitState.HALF_OPEN -> CircuitState.RECOVERED
        }
        return h.copy(
            state = state,
            circuit = circuit,
            consecutiveFailures = 0,
            consecutiveSuccesses = successes,
            failingSince = null,
            lastProbeAt = probe.at,
            lastSeen = Measure.observed(probe.at),
            lastLatency = probe.latency,
        )
    }

    private fun onFailure(h: TargetHealth, probe: ProbeResult, policy: TargetHealthPolicy): TargetHealth {
        val failures = h.consecutiveFailures + 1
        val since = h.failingSince ?: probe.at
        val thresholdsMet = failures >= policy.offlineAfterFailures && probe.at - since >= policy.offlineAfter
        val state = when {
            h.state == TargetState.OFFLINE -> TargetState.OFFLINE
            thresholdsMet && h.state != TargetState.ONLINE -> TargetState.OFFLINE
            else -> TargetState.DEGRADED
        }
        val circuit = when (h.circuit) {
            CircuitState.HEALTHY, CircuitState.RECOVERED -> CircuitState.FAILING
            CircuitState.FAILING -> if (state == TargetState.OFFLINE) CircuitState.OPEN else CircuitState.FAILING
            CircuitState.HALF_OPEN, CircuitState.OPEN -> CircuitState.OPEN
        }
        return h.copy(
            state = state,
            circuit = circuit,
            consecutiveFailures = failures,
            consecutiveSuccesses = 0,
            failingSince = since,
            lastProbeAt = probe.at,
            lastLatency = Measure.unknown("проба не дошла"),
        )
    }
}
