package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.SubscriptionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.state.AccountState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Окно предела подписки (`docs/ACCOUNT_MODEL.md`, §3). */
enum class LimitWindow(val length: Duration) {
    /** Скользящее окно пяти часов. */
    SESSION(5.hours),

    /** Недельный предел. */
    WEEKLY(7.days),
}

enum class LimitLevel { OK, NEAR, REACHED }

/** Почему окно сброшено: наступило время, успешный запрос доказал, окно истекло от наблюдения. */
enum class ResetEvidence { TIME, SUCCESS, WINDOW_EXPIRED }

/** Что известно о входе слота (`docs/ACCOUNT_MODEL.md`, §2, §4). */
enum class LoginStatus { UNKNOWN, OK, REQUIRED, FORBIDDEN_METHOD, IDENTITY_MISMATCH, DUPLICATE_SUBSCRIPTION }

enum class OverlapKind { IDENTITY_MISMATCH, DUPLICATE_SUBSCRIPTION }

/**
 * Состояние окна предела. Время сброса неизвестно — `null`, а не ноль
 * (D-13). [utilization] — загрузка окна 0…1, как её сообщил Claude Code
 * (`rate_limit_event`); неизвестна — `null` (D-45).
 */
data class WindowState(
    val level: LimitLevel = LimitLevel.OK,
    val resetAt: Timestamp? = null,
    val observedAt: Timestamp? = null,
    val utilization: Double? = null,
) {
    fun limitedAt(now: Timestamp): Boolean = level == LimitLevel.REACHED && (resetAt == null || now < resetAt)

    /**
     * У предела (D-45): загрузка известна — она не меньше порога [threshold]
     * (0…1, задаёт человек); неизвестна — по предупреждению Claude Code.
     */
    fun nearAt(now: Timestamp, threshold: Double = DEFAULT_NEAR_PERCENT / 100.0): Boolean {
        if (resetAt != null && now >= resetAt) return false
        val u = utilization ?: return level == LimitLevel.NEAR
        return u >= threshold
    }

    /** Когда окно точно сброшено по времени: известное время сброса или длина окна от наблюдения. */
    fun clearsAt(window: LimitWindow): Timestamp? = when {
        level == LimitLevel.OK -> null
        resetAt != null -> resetAt
        observedAt != null -> observedAt + window.length
        else -> null
    }
}

/** Подписка Claude: общие пределы всех её слотов. [identity] — отпечаток аккаунта, без email (D-30). */
data class SubscriptionState(
    val id: SubscriptionId,
    val label: String,
    val maxParallelSessions: Int,
    val rotationAllowed: Boolean,
    val identity: ContentHash? = null,
    val plan: String? = null,
    val windows: Map<LimitWindow, WindowState> = LimitWindow.entries.associateWith { WindowState() },
    /** Порог «у предела» в процентах загрузки окна (D-45): его задаёт человек, на нём — откладывание и экономия. */
    val nearPercent: Int = DEFAULT_NEAR_PERCENT,
    /** [rotationAllowed] задано событием `RotationSet` (D-56), а не значением при регистрации до D-56. */
    val rotationExplicit: Boolean = false,
    /** Исполнитель подписки и всех её слотов (D-100). */
    val executor: Executor = Executor.CLAUDE,
) {
    fun window(w: LimitWindow): WindowState = windows[w] ?: WindowState()

    fun limitedAt(now: Timestamp): Boolean = LimitWindow.entries.any { window(it).limitedAt(now) }

    fun nearAt(now: Timestamp): Boolean = LimitWindow.entries.any { window(it).nearAt(now, nearPercent / 100.0) }
}

/** Порог «у предела» по умолчанию (D-45), процентов загрузки окна. */
const val DEFAULT_NEAR_PERCENT = 80

/** Допустимые пороги (D-45): ниже половины окна предел ещё далеко, выше 100 % — не бывает. */
val NEAR_PERCENT_RANGE = 50..100

/**
 * Слот: подписка, вошедшая в Claude Code на [targetId] в каталоге настроек
 * [configDir] (`credentialRef`, D-27). [state] выводится из фактов
 * (`Accounts.normalize`), напрямую не задаётся.
 */
data class SlotState(
    val id: AccountId,
    val subscriptionId: SubscriptionId,
    val targetId: ExecutionTargetId,
    val configDir: String,
    val state: AccountState = AccountState.AUTH_REQUIRED,
    val login: LoginStatus = LoginStatus.UNKNOWN,
    val authMethod: String? = null,
    val disabled: Boolean = false,
    val sessions: Map<SessionId, TaskId> = emptyMap(),
    val errorStreak: Int = 0,
    val cooldownUntil: Timestamp? = null,
    /** Claude Code предупредил, что вход истекает; [loginExpiresAt] — если сообщил когда. */
    val loginExpiring: Boolean = false,
    val loginExpiresAt: Timestamp? = null,
    /** Исполнитель — как у подписки слота (D-100): копия для отбора слотов задачи. */
    val executor: Executor = Executor.CLAUDE,
)

/** Всё состояние аккаунтов: производное от событий (D-12). */
data class AccountsState(
    val subscriptions: Map<SubscriptionId, SubscriptionState> = emptyMap(),
    val slots: Map<AccountId, SlotState> = emptyMap(),
    /**
     * На какой подписке задача шла последней — предпочтение при продолжении;
     * у каждого исполнителя своя (D-100): ревью Codex не делает подписку
     * Claude, на которой задача работает, чужой.
     */
    val taskSubscription: Map<TaskId, Map<Executor, SubscriptionId>> = emptyMap(),
    /** Моменты переходов задачи между подписками — защита от петли (ТЗ, §22). */
    val switches: Map<TaskId, List<Timestamp>> = emptyMap(),
) {
    fun activeSessions(subscription: SubscriptionId): Int =
        slots.values.filter { it.subscriptionId == subscription }.sumOf { it.sessions.size }

    fun slotOf(session: SessionId): SlotState? = slots.values.firstOrNull { session in it.sessions }

    fun slotsOf(subscription: SubscriptionId): List<SlotState> =
        slots.values.filter { it.subscriptionId == subscription }.sortedBy { it.id.value }

    companion object {
        val EMPTY = AccountsState()
    }
}

/** Политика аккаунтов; значения по умолчанию — для одной подписки (D-30). */
data class AccountPolicy(
    val defaultMaxParallelSessions: Int = 2,
    val startNewTasksWhenNear: Boolean = false,
    val unknownResetProbeInterval: Duration = 30.minutes,
    val errorsBeforeCooldown: Int = 3,
    val errorCooldown: Duration = 10.minutes,
    /**
     * Сколько раз задача может сменить подписку за [switchWindow]; `null` —
     * без предела (D-67, слово автора: переход между слотами не
     * ограничивается — он и так только к подписке с запасом, D-57).
     */
    val maxSwitchesPerTask: Int? = null,
    val switchWindow: Duration = 1.days,
) {
    init {
        require(defaultMaxParallelSessions >= 1 && errorsBeforeCooldown >= 1 && (maxSwitchesPerTask ?: 0) >= 0) { "политика аккаунтов неверна" }
    }
}
