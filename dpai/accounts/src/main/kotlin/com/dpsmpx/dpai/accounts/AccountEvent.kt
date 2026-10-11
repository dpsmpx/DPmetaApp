package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.SubscriptionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.state.AccountState

/**
 * Факт об аккаунтах (D-12, D-30): всё состояние — [Accounts.apply] по
 * этим событиям. Секретов и email в событиях нет (D-14): аккаунт — только
 * отпечатком.
 */
sealed interface AccountEvent {
    val at: Timestamp

    data class SubscriptionRegistered(
        val subscription: SubscriptionId,
        val label: String,
        val maxParallelSessions: Int,
        val rotationAllowed: Boolean,
        override val at: Timestamp,
    ) : AccountEvent

    data class SlotRegistered(
        val slot: AccountId,
        val subscription: SubscriptionId,
        val target: ExecutionTargetId,
        val configDir: String,
        override val at: Timestamp,
    ) : AccountEvent

    data class LoginObserved(val slot: AccountId, val login: LoginStatus, val authMethod: String?, override val at: Timestamp) : AccountEvent

    data class IdentityConfirmed(val subscription: SubscriptionId, val identity: ContentHash, val plan: String?, override val at: Timestamp) : AccountEvent

    data class OverlapDetected(val slot: AccountId, val kind: OverlapKind, val other: SubscriptionId?, override val at: Timestamp) : AccountEvent

    data class LimitObserved(
        val subscription: SubscriptionId,
        val window: LimitWindow,
        val level: LimitLevel,
        val resetAt: Timestamp?,
        override val at: Timestamp,
    ) : AccountEvent

    data class LimitReset(val subscription: SubscriptionId, val window: LimitWindow, val evidence: ResetEvidence, override val at: Timestamp) : AccountEvent

    /** Загрузка окна (D-42, D-45): доля 0…1 и время сброса, как их сообщил Claude Code. */
    data class UsageObserved(
        val subscription: SubscriptionId,
        val window: LimitWindow,
        val utilization: Double,
        val resetAt: Timestamp?,
        override val at: Timestamp,
    ) : AccountEvent

    /** Порог «у предела» подписки задан человеком (D-45), процентов. */
    /**
     * Принимает ли подписка задачи, начатые на других подписках, когда те
     * упёрлись в предел (D-30, D-56): задаёт человек или перенос значения по
     * умолчанию для подписок, заведённых до D-56.
     */
    data class RotationSet(val subscription: SubscriptionId, val allowed: Boolean, override val at: Timestamp) : AccountEvent

    data class NearThresholdSet(val subscription: SubscriptionId, val percent: Int, override val at: Timestamp) : AccountEvent

    data class LoginExpiring(val slot: AccountId, val expiresAt: Timestamp?, override val at: Timestamp) : AccountEvent

    /** Ошибка слота; [cooldownUntil] — пауза, если серия достигла порога политики. */
    data class ErrorRecorded(val slot: AccountId, val code: DiagCode, val cooldownUntil: Timestamp?, override val at: Timestamp) : AccountEvent

    data class ErrorsCleared(val slot: AccountId, override val at: Timestamp) : AccountEvent

    data class SlotDisabled(val slot: AccountId, override val at: Timestamp) : AccountEvent

    data class SlotEnabled(val slot: AccountId, override val at: Timestamp) : AccountEvent

    data class SessionLeased(val slot: AccountId, val session: SessionId, val task: TaskId, override val at: Timestamp) : AccountEvent

    data class SessionReleased(val slot: AccountId, val session: SessionId, override val at: Timestamp) : AccountEvent

    data class SubscriptionSwitched(val task: TaskId, val from: SubscriptionId, val to: SubscriptionId, override val at: Timestamp) : AccountEvent

    data class SlotStateChanged(val slot: AccountId, val from: AccountState, val to: AccountState, override val at: Timestamp) : AccountEvent
}
