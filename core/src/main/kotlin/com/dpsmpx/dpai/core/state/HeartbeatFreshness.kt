package com.dpsmpx.dpai.core.state

import com.dpsmpx.dpai.core.clock.Timestamp
import kotlin.time.Duration

/** Пороги свежести heartbeat (ТЗ, §15): строго возрастают. */
data class HeartbeatThresholds(val aging: Duration, val stale: Duration, val unknown: Duration) {
    init {
        require(aging.isPositive() && aging < stale && stale < unknown) { "пороги heartbeat должны возрастать" }
    }
}

/**
 * Оценка знания о сессии по возрасту последнего heartbeat
 * (`docs/SESSION_MODEL.md`, §4). Не состояние автомата: тот же `RUNNING`
 * может быть свежим или устаревшим.
 *
 * Отсутствие heartbeat никогда само не даёт [DEAD] (D-13: неизвестное — не
 * ноль): только проба target, подтвердившая, что процесса нет.
 */
enum class HeartbeatFreshness {
    FRESH, AGING, STALE, UNKNOWN, DEAD;

    companion object {
        fun classify(
            lastHeartbeat: Timestamp?,
            now: Timestamp,
            thresholds: HeartbeatThresholds,
            processConfirmedAbsent: Boolean = false,
        ): HeartbeatFreshness {
            if (processConfirmedAbsent) return DEAD
            if (lastHeartbeat == null) return UNKNOWN
            // Heartbeat «из будущего» (расхождение часов) считается только что пришедшим.
            val age = (now - lastHeartbeat).coerceAtLeast(Duration.ZERO)
            return when {
                age < thresholds.aging -> FRESH
                age < thresholds.stale -> AGING
                age < thresholds.unknown -> STALE
                else -> UNKNOWN
            }
        }
    }
}
