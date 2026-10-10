package com.dpsmpx.dpai.orchestrator.efficiency

import com.dpsmpx.dpai.contexts.Handoff
import com.dpsmpx.dpai.contexts.HandoffReason
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.sessions.PromptMode
import com.dpsmpx.dpai.sessions.SessionRecord
import com.dpsmpx.dpai.sessions.SessionsState

/** Как продолжать: режим prompt, продолжать ли сессию провайдера и почему. */
data class ContinuationPlan(val mode: PromptMode, val reuseProvider: Boolean, val why: String)

/**
 * Продолжение прерванной или непроверенной работы (D-40, п. 4;
 * `docs/EFFICIENCY.md`, §4). Решение — по фактам журнала, без догадок:
 *
 * - другой слот или target — сессию провайдера не продолжить (её файлы — в
 *   каталоге настроек слота на своём target): свежая сессия с handoff;
 * - переполнение контекста — свежая сессия с handoff, а не повтор того же;
 * - история сессии не меньше [ResumePolicy.checkpointAt] окна — свежая
 *   сессия с handoff (контрольная точка): каждый следующий вызов API
 *   перечитывал бы всю историю;
 * - иначе `--resume`: разница (`DELTA`), если сессия точно видела прежнюю
 *   версию контекста, — все её ходы с момента полного prompt сообщили
 *   расход, без сжатия истории; иначе полный prompt.
 */
object Continuation {
    fun plan(
        prev: SessionRecord?,
        handoff: Handoff?,
        sessions: SessionsState,
        slot: AccountId,
        target: ExecutionTargetId,
        policy: ResumePolicy,
        defaultWindow: Long,
    ): ContinuationPlan {
        if (prev == null) return ContinuationPlan(PromptMode.FULL, false, "новая сессия")
        if (!policy.enabled) return ContinuationPlan(PromptMode.FULL, true, "продолжение (слой D-40 выключен)")
        if (prev.slot != slot || prev.target != target) {
            return ContinuationPlan(PromptMode.CHECKPOINT, false, "другой слот или target: свежая сессия с handoff")
        }
        if (handoff?.reason == HandoffReason.TURN_FINISHED) {
            return ContinuationPlan(PromptMode.CHECKPOINT, false, "свежая сессия по запросу человека: handoff вместо --resume")
        }
        if (policy.checkpointOnOverflow && handoff?.reason == HandoffReason.CONTEXT_OVERFLOW) {
            return ContinuationPlan(PromptMode.CHECKPOINT, false, "переполнение контекста: свежая сессия с handoff")
        }
        val window = prev.usage.contextWindow ?: defaultWindow
        val history = prev.usage.lastContextTokens
        if (policy.checkpointAt != null && history != null && history >= policy.checkpointAt * window) {
            return ContinuationPlan(PromptMode.CHECKPOINT, false, "история $history из $window токенов: свежая сессия с handoff")
        }
        if (policy.delta && sawPreviousContext(prev, sessions)) {
            return ContinuationPlan(PromptMode.DELTA, true, "сессия видела прежнюю версию контекста")
        }
        return ContinuationPlan(PromptMode.FULL, true, "продолжение: что видела сессия, неизвестно — полный prompt")
    }

    /** Ходы сессии провайдера с последнего полного prompt сообщили расход и не сжимали историю. */
    private fun sawPreviousContext(prev: SessionRecord, sessions: SessionsState): Boolean {
        var r: SessionRecord = prev
        while (true) {
            val u = r.usage
            val last = u.lastPrompt ?: return false
            if (u.turns == 0 || u.unknownTurns > 0 || u.compacted) return false
            if (last.mode != PromptMode.DELTA) return true
            val back = r.continues?.let { sessions.sessions[it] } ?: return false
            if (back.provider != r.provider) return false
            r = back
        }
    }
}
