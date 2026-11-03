package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.accounts.AccountEvent.ErrorRecorded
import com.dpsmpx.dpai.accounts.AccountEvent.ErrorsCleared
import com.dpsmpx.dpai.accounts.AccountEvent.IdentityConfirmed
import com.dpsmpx.dpai.accounts.AccountEvent.LimitObserved
import com.dpsmpx.dpai.accounts.AccountEvent.LimitReset
import com.dpsmpx.dpai.accounts.AccountEvent.LoginExpiring
import com.dpsmpx.dpai.accounts.AccountEvent.LoginObserved
import com.dpsmpx.dpai.accounts.AccountEvent.OverlapDetected
import com.dpsmpx.dpai.accounts.AccountEvent.SessionLeased
import com.dpsmpx.dpai.accounts.AccountEvent.SessionReleased
import com.dpsmpx.dpai.accounts.AccountEvent.SlotDisabled
import com.dpsmpx.dpai.accounts.AccountEvent.SlotEnabled
import com.dpsmpx.dpai.accounts.AccountEvent.SlotRegistered
import com.dpsmpx.dpai.accounts.AccountEvent.SlotStateChanged
import com.dpsmpx.dpai.accounts.AccountEvent.SubscriptionRegistered
import com.dpsmpx.dpai.accounts.AccountEvent.SubscriptionSwitched
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.SubscriptionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.AccountLifecycle
import com.dpsmpx.dpai.core.state.AccountState
import com.dpsmpx.dpai.core.state.TransitionMode
import kotlin.time.Duration

/**
 * Решения об аккаунтах (D-27, D-29, D-30; `docs/ACCOUNT_MODEL.md`).
 *
 * Каждая операция получает текущее состояние и время и возвращает события —
 * факты и следующие из них смены состояний слотов; состояние меняет только
 * [apply]. Всё детерминировано (D-22): одинаковые состояние, вход и время
 * дают одинаковые события. Режим одной подписки — частный случай: та же
 * логика, переход между подписками просто не случается.
 */
class Accounts(val policy: AccountPolicy = AccountPolicy()) {
    /** Аренда слота сессией; [events] — что записать в журнал. */
    data class Lease(val slot: AccountId, val subscription: SubscriptionId, val events: List<AccountEvent>)

    fun registerSubscription(
        state: AccountsState,
        id: SubscriptionId,
        label: String,
        now: Timestamp,
        maxParallelSessions: Int = policy.defaultMaxParallelSessions,
        rotationAllowed: Boolean = false,
        executor: Executor = Executor.CLAUDE,
    ): Outcome<List<AccountEvent>> {
        if (id in state.subscriptions) return Outcome.failure(DiagCode.VALUE_INVALID, "подписка уже зарегистрирована", "subscription $id")
        if (label.isBlank() || label.length > 100) return Outcome.failure(DiagCode.VALUE_INVALID, "метка подписки — от 1 до 100 знаков")
        if (maxParallelSessions !in 1..64) return Outcome.failure(DiagCode.VALUE_INVALID, "maxParallelSessions — от 1 до 64")
        return Outcome.success(finish(state, listOf(SubscriptionRegistered(id, label.trim(), maxParallelSessions, rotationAllowed, now, executor)), now))
    }

    fun registerSlot(
        state: AccountsState,
        id: AccountId,
        subscription: SubscriptionId,
        target: ExecutionTargetId,
        configDir: String,
        now: Timestamp,
    ): Outcome<List<AccountEvent>> {
        if (id in state.slots) return Outcome.failure(DiagCode.VALUE_INVALID, "слот уже зарегистрирован", "slot $id")
        if (subscription !in state.subscriptions) return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой подписки", "subscription $subscription")
        val dir = normalizeDir(configDir)
            ?: return Outcome.failure(DiagCode.VALUE_INVALID, "каталог настроек — непустой путь без управляющих символов, до 512 знаков")
        state.slots.values.firstOrNull { it.targetId == target && it.configDir == dir }?.let {
            return Outcome.failure(
                DiagCode.ACCOUNT_CONFIG_DIR_IN_USE,
                "каталог настроек уже занят слотом ${it.id}: два слота с одним каталогом делили бы вход",
                "slot $id",
            )
        }
        return Outcome.success(finish(state, listOf(SlotRegistered(id, subscription, target, dir, now)), now))
    }

    /**
     * Итог проверки входа слота: способ входа, аккаунт, пересечения (D-29,
     * D-30). Разрешённый способ — по исполнителю слота (D-100); у Codex и
     * Gemini аккаунт не называется — проверки пересечения нет, и это не
     * находка, а их устройство (`docs/ACCOUNT_MODEL.md`, §10).
     */
    fun observeAuthStatus(state: AccountsState, slotId: AccountId, status: AuthStatus, now: Timestamp): Outcome<List<AccountEvent>> {
        val slot = state.slots[slotId] ?: return notFound(slotId)
        val sub = state.subscriptions.getValue(slot.subscriptionId)
        val facts = mutableListOf<AccountEvent>()
        val notes = mutableListOf<Diagnostic>()
        val subject = "slot $slotId"
        val login: LoginStatus = when {
            !status.loggedIn -> LoginStatus.REQUIRED
            !status.isAllowed(slot.executor) -> {
                notes += Diagnostic.warning(
                    DiagCode.ACCOUNT_AUTH_METHOD_FORBIDDEN,
                    "${slot.executor.title} сообщает вход «${status.authMethod}»; разрешён только ${AuthStatus.allowedPhrase(slot.executor)} (D-29, D-100): слот остановлен",
                    subject,
                )
                LoginStatus.FORBIDDEN_METHOD
            }
            slot.executor != Executor.CLAUDE -> LoginStatus.OK
            status.identity == null -> {
                notes += Diagnostic.warning(
                    DiagCode.ACCOUNT_IDENTITY_UNKNOWN,
                    "Claude Code не сообщил аккаунт: проверка пересечения подписок по аккаунту недоступна",
                    subject,
                )
                LoginStatus.OK
            }
            sub.identity == null -> {
                val other = state.subscriptions.values.sortedBy { it.id.value }.firstOrNull { it.id != sub.id && it.identity == status.identity }
                if (other != null) {
                    notes += Diagnostic.warning(DiagCode.ACCOUNT_DUPLICATE_SUBSCRIPTION, "этот аккаунт Claude уже подписка ${other.id}: слот остановлен", subject)
                    if (slot.login != LoginStatus.DUPLICATE_SUBSCRIPTION) facts += OverlapDetected(slotId, OverlapKind.DUPLICATE_SUBSCRIPTION, other.id, now)
                    LoginStatus.DUPLICATE_SUBSCRIPTION
                } else {
                    facts += IdentityConfirmed(sub.id, status.identity, status.plan, now)
                    LoginStatus.OK
                }
            }
            sub.identity == status.identity -> LoginStatus.OK
            else -> {
                val owner = state.subscriptions.values.sortedBy { it.id.value }.firstOrNull { it.identity == status.identity }
                notes += Diagnostic.warning(DiagCode.ACCOUNT_IDENTITY_MISMATCH, "в каталоге слота вход другим аккаунтом, чем у его подписки: слот остановлен", subject)
                if (slot.login != LoginStatus.IDENTITY_MISMATCH) facts += OverlapDetected(slotId, OverlapKind.IDENTITY_MISMATCH, owner?.id, now)
                LoginStatus.IDENTITY_MISMATCH
            }
        }
        if (login != slot.login || status.authMethod != slot.authMethod) facts += LoginObserved(slotId, login, status.authMethod, now)
        return Outcome.success(finish(state, facts, now), notes)
    }

    /** Сообщение о пределе от любого слота действует на всю подписку (§3). */
    fun observeLimit(
        state: AccountsState,
        slotId: AccountId,
        window: LimitWindow,
        level: LimitLevel,
        resetAt: Timestamp?,
        now: Timestamp,
    ): Outcome<List<AccountEvent>> {
        val slot = state.slots[slotId] ?: return notFound(slotId)
        if (level == LimitLevel.OK) return Outcome.failure(DiagCode.VALUE_INVALID, "сброс предела — успешным запросом или по времени, а не сообщением OK")
        val sub = state.subscriptions.getValue(slot.subscriptionId)
        val cur = sub.window(window)
        val repeated = cur.level == level && cur.resetAt == resetAt
        val probeRefresh = repeated && level == LimitLevel.REACHED && resetAt == null &&
            (cur.observedAt == null || cur.observedAt + policy.unknownResetProbeInterval <= now)
        val staleNear = level == LimitLevel.NEAR && cur.limitedAt(now)
        if ((repeated && !probeRefresh) || staleNear) return Outcome.success(finish(state, emptyList(), now))
        return Outcome.success(finish(state, listOf(LimitObserved(sub.id, window, level, resetAt, now)), now))
    }

    /**
     * Загрузка окна (D-42, D-45): событие — при изменении хотя бы на
     * процент или при новом времени сброса; частые одинаковые сообщения
     * потока журнал не раздувают.
     */
    fun observeUsage(state: AccountsState, slotId: AccountId, window: LimitWindow, utilization: Double, resetAt: Timestamp?, now: Timestamp): Outcome<List<AccountEvent>> {
        val slot = state.slots[slotId] ?: return notFound(slotId)
        if (utilization.isNaN() || utilization < 0.0 || utilization > 1.5) return Outcome.failure(DiagCode.VALUE_INVALID, "загрузка окна — 0…1")
        val sub = state.subscriptions.getValue(slot.subscriptionId)
        val cur = sub.window(window)
        val same = cur.utilization != null && kotlin.math.abs(cur.utilization - utilization) < 0.01 && (resetAt == null || resetAt == cur.resetAt)
        if (same) return Outcome.success(emptyList())
        return Outcome.success(finish(state, listOf(AccountEvent.UsageObserved(sub.id, window, utilization.coerceAtMost(1.0), resetAt, now)), now))
    }

    /** Порог «у предела» подписки (D-45): 50…100 %. */
    fun setNearPercent(state: AccountsState, subscription: SubscriptionId, percent: Int, now: Timestamp): Outcome<List<AccountEvent>> {
        val sub = state.subscriptions[subscription] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой подписки", "subscription $subscription")
        if (percent !in NEAR_PERCENT_RANGE) return Outcome.failure(DiagCode.VALUE_INVALID, "порог — ${NEAR_PERCENT_RANGE.first}…${NEAR_PERCENT_RANGE.last} %", "subscription $subscription")
        if (sub.nearPercent == percent) return Outcome.success(emptyList())
        return Outcome.success(finish(state, listOf(AccountEvent.NearThresholdSet(subscription, percent, now)), now))
    }

    /**
     * Принимает ли подписка задачи других подписок, упёршихся в предел
     * (D-30, D-56): продолжение — свежей сессией с handoff, не чаще
     * [AccountPolicy.maxSwitchesPerTask] раз за [AccountPolicy.switchWindow].
     */
    fun setRotation(state: AccountsState, subscription: SubscriptionId, allowed: Boolean, now: Timestamp): Outcome<List<AccountEvent>> {
        val sub = state.subscriptions[subscription] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой подписки", "subscription $subscription")
        if (sub.rotationExplicit && sub.rotationAllowed == allowed) return Outcome.success(emptyList())
        return Outcome.success(finish(state, listOf(AccountEvent.RotationSet(subscription, allowed, now)), now))
    }

    /** Успешный запрос слота: предел подписки снят (доказано), серия ошибок слота сброшена. */
    fun observeSuccess(state: AccountsState, slotId: AccountId, now: Timestamp): Outcome<List<AccountEvent>> {
        val slot = state.slots[slotId] ?: return notFound(slotId)
        val sub = state.subscriptions.getValue(slot.subscriptionId)
        val facts = mutableListOf<AccountEvent>()
        for (w in LimitWindow.entries) {
            if (sub.window(w).level == LimitLevel.REACHED) facts += LimitReset(sub.id, w, ResetEvidence.SUCCESS, now)
        }
        if (slot.errorStreak > 0 || slot.cooldownUntil != null) facts += ErrorsCleared(slotId, now)
        return Outcome.success(finish(state, facts, now))
    }

    /** Claude Code ответил, что вход истёк или не выполнен. */
    fun observeAuthFailure(state: AccountsState, slotId: AccountId, now: Timestamp): Outcome<List<AccountEvent>> {
        val slot = state.slots[slotId] ?: return notFound(slotId)
        val facts = if (slot.login == LoginStatus.REQUIRED) emptyList() else listOf(LoginObserved(slotId, LoginStatus.REQUIRED, slot.authMethod, now))
        return Outcome.success(finish(state, facts, now))
    }

    fun observeLoginExpiring(state: AccountsState, slotId: AccountId, expiresAt: Timestamp?, now: Timestamp): Outcome<List<AccountEvent>> {
        val slot = state.slots[slotId] ?: return notFound(slotId)
        val facts = if (slot.loginExpiring && slot.loginExpiresAt == expiresAt) emptyList() else listOf(LoginExpiring(slotId, expiresAt, now))
        return Outcome.success(finish(state, facts, now))
    }

    /** Ошибка исполнения на слоте (не ошибка кода задачи): серия ведёт к паузе (§5). */
    fun observeError(state: AccountsState, slotId: AccountId, code: DiagCode, now: Timestamp): Outcome<List<AccountEvent>> {
        val slot = state.slots[slotId] ?: return notFound(slotId)
        val cooldown = if (slot.errorStreak + 1 >= policy.errorsBeforeCooldown) now + policy.errorCooldown else null
        return Outcome.success(finish(state, listOf(ErrorRecorded(slotId, code, cooldown, now)), now))
    }

    fun disable(state: AccountsState, slotId: AccountId, now: Timestamp): Outcome<List<AccountEvent>> {
        val slot = state.slots[slotId] ?: return notFound(slotId)
        return Outcome.success(finish(state, if (slot.disabled) emptyList() else listOf(SlotDisabled(slotId, now)), now))
    }

    /** Явная операция включения (D-11): вход проверяется заново. */
    fun enable(state: AccountsState, slotId: AccountId, now: Timestamp): Outcome<List<AccountEvent>> {
        val slot = state.slots[slotId] ?: return notFound(slotId)
        return Outcome.success(finish(state, if (slot.disabled) listOf(SlotEnabled(slotId, now)) else emptyList(), now))
    }

    /**
     * Слот для сессии задачи (§5). Повторная аренда той же сессии —
     * тот же слот; подходящего нет — `ACCOUNT_NONE_AVAILABLE` с причиной и
     * временем ближайшего сброса (в режиме одной подписки — «ждать»).
     */
    fun lease(
        state: AccountsState,
        session: SessionId,
        task: TaskId,
        now: Timestamp,
        target: ExecutionTargetId? = null,
        /**
         * Срочная задача (D-40, D-42): у предела подписки начинается и она —
         * человек отметил её высоким приоритетом; несрочное ждёт сброса окна.
         */
        urgent: Boolean = false,
        /** Слот, с которого задачу перенесли (D-77): на него она не идёт. */
        exclude: AccountId? = null,
        /** Исполнитель задачи (D-100): слоты только его; `null` — любой (прежние вызовы). */
        executor: Executor? = null,
    ): Outcome<Lease> {
        state.slotOf(session)?.let { s ->
            return if (s.sessions[session] == task) {
                Outcome.success(Lease(s.id, s.subscriptionId, emptyList()))
            } else {
                Outcome.failure(DiagCode.ACCOUNT_LEASE_CONFLICT, "сессия уже арендует слот ${s.id} для другой задачи", "session $session")
            }
        }
        val ticked = tick(state, now)
        val s1 = ticked.fold(state, ::apply)
        val previous = s1.taskSubscription[task]
        val candidates = s1.slots.values
            .filter { it.state == AccountState.AVAILABLE && (target == null || it.targetId == target) && it.id != exclude && (executor == null || it.executor == executor) }
            .filter { slot ->
                val sub = s1.subscriptions.getValue(slot.subscriptionId)
                // Чужую задачу подписка у предела не берёт и срочную (D-57): переход ради того, чтобы сразу
                // упереться снова, не нужен; своя срочная задача у предела начинается (D-45).
                val foreign = previous != null && previous != sub.id
                policy.startNewTasksWhenNear || !sub.nearAt(now) || previous == sub.id || (urgent && !foreign)
            }
        val own = if (previous == null) emptyList() else candidates.filter { it.subscriptionId == previous }
        val switching = previous != null && own.isEmpty()
        val pool = when {
            previous == null -> candidates
            own.isNotEmpty() -> own
            else -> candidates.filter { s1.subscriptions.getValue(it.subscriptionId).rotationAllowed }
        }
        if (pool.isEmpty()) return Outcome.failure(DiagCode.ACCOUNT_NONE_AVAILABLE, explainNone(s1, now, previous, target, urgent), "task $task")
        val limit = policy.maxSwitchesPerTask
        if (switching && limit != null) {
            val recent = s1.switches[task].orEmpty().count { it > now - policy.switchWindow }
            if (recent >= limit) {
                return Outcome.failure(
                    DiagCode.ACCOUNT_SWITCH_LOOP,
                    "задача уже переходила между подписками $recent раз за ${formatDuration(policy.switchWindow)}: переход остановлен",
                    "task $task",
                )
            }
        }
        val active = activeBySubscription(s1)
        // Больше запаса — раньше (D-57): известная загрузка окон десятками процентов, неизвестная — после
        // известной (D-13: не ноль); внутри одного десятка — меньше сессий, как раньше.
        val load = { slot: SlotState -> s1.subscriptions.getValue(slot.subscriptionId).let { sub -> LimitWindow.entries.mapNotNull { sub.window(it).utilization }.maxOrNull() } }
        val chosen = pool.minWith(
            compareBy<SlotState>({ load(it) == null }, { load(it)?.let { u -> (u * 10).toInt() } ?: 0 }, { active[it.subscriptionId] ?: 0 }, { it.sessions.size }, { it.id.value }),
        )
        val facts = buildList {
            add(SessionLeased(chosen.id, session, task, now))
            if (switching) add(SubscriptionSwitched(task, previous, chosen.subscriptionId, now))
        }
        return Outcome.success(Lease(chosen.id, chosen.subscriptionId, ticked + finish(s1, facts, now)))
    }

    /**
     * У исполнителя [executor] предел: слоты есть, и все включённые упёрлись
     * в предел подписки (`RATE_LIMITED` или окно исчерпано). Тогда задача
     * идёт к следующему исполнителю (D-100); занятые и без входа — не предел:
     * задача ждёт своего исполнителя.
     */
    fun exhausted(state: AccountsState, executor: Executor, now: Timestamp): Boolean {
        val own = state.slots.values.filter { it.executor == executor && !it.disabled }
        return own.isNotEmpty() && own.all { it.state == AccountState.RATE_LIMITED || state.subscriptions.getValue(it.subscriptionId).limitedAt(now) }
    }

    fun release(state: AccountsState, session: SessionId, now: Timestamp): Outcome<List<AccountEvent>> {
        val slot = state.slotOf(session) ?: return Outcome.success(emptyList())
        return Outcome.success(finish(state, listOf(SessionReleased(slot.id, session, now)), now))
    }

    /** Такт: сбросы окон по времени, конец пауз (§3, §5). */
    fun tick(state: AccountsState, now: Timestamp): List<AccountEvent> {
        val facts = mutableListOf<AccountEvent>()
        for (sub in state.subscriptions.values.sortedBy { it.id.value }) {
            for (w in LimitWindow.entries) {
                val ws = sub.window(w)
                val clears = ws.clearsAt(w) ?: continue
                if (clears <= now) facts += LimitReset(sub.id, w, if (ws.resetAt != null) ResetEvidence.TIME else ResetEvidence.WINDOW_EXPIRED, now)
            }
        }
        return finish(state, facts, now)
    }

    /** Когда планировщику вызвать [tick]: ближайший сброс окна или конец паузы; нечего ждать — `null`. */
    fun nextWakeUp(state: AccountsState, now: Timestamp): Timestamp? {
        val times = state.subscriptions.values.flatMap { sub -> LimitWindow.entries.mapNotNull { sub.window(it).clearsAt(it) } } +
            state.slots.values.mapNotNull { it.cooldownUntil }
        return times.filter { it > now }.minOrNull()
    }

    /** Подписки на пределе без известного времени сброса, для которых пора пробный запрос (§3). */
    fun probeDue(state: AccountsState, now: Timestamp): List<SubscriptionId> =
        state.subscriptions.values.sortedBy { it.id.value }.filter { sub ->
            LimitWindow.entries.any { w ->
                val ws = sub.window(w)
                ws.level == LimitLevel.REACHED && ws.resetAt == null && ws.observedAt != null &&
                    ws.observedAt + policy.unknownResetProbeInterval <= now
            }
        }.map { it.id }

    // ---- вывод состояния слотов из фактов ----

    private fun finish(state: AccountsState, facts: List<AccountEvent>, now: Timestamp): List<AccountEvent> {
        val after = facts.fold(state, ::apply)
        return facts + normalize(after, now)
    }

    /** Смены состояний слотов, которые следуют из фактов; путь — только по таблице `AccountLifecycle`. */
    /** Активные сессии каждой подписки — за один проход по слотам. */
    private fun activeBySubscription(state: AccountsState): Map<SubscriptionId, Int> {
        val m = HashMap<SubscriptionId, Int>()
        for (slot in state.slots.values) m.merge(slot.subscriptionId, slot.sessions.size, Int::plus)
        return m
    }

    fun normalize(state: AccountsState, now: Timestamp): List<AccountEvent> {
        var s = state
        val out = mutableListOf<AccountEvent>()
        // Смена состояния слота не меняет числа сессий: считается один раз (этап 17, замер выбора слота).
        val active = activeBySubscription(state)
        for (id in s.slots.keys.sortedBy { it.value }) {
            val slot = s.slots.getValue(id)
            val want = desired(slot, s, now, active)
            if (want == slot.state) continue
            for ((a, b) in path(slot.state, want).zipWithNext()) {
                val e = SlotStateChanged(id, a, b, now)
                out += e
                s = apply(s, e)
            }
        }
        return out
    }

    private fun desired(slot: SlotState, state: AccountsState, now: Timestamp, active: Map<SubscriptionId, Int>): AccountState {
        if (slot.disabled) return AccountState.DISABLED
        return when (slot.login) {
            LoginStatus.UNKNOWN, LoginStatus.REQUIRED -> AccountState.AUTH_REQUIRED
            LoginStatus.FORBIDDEN_METHOD, LoginStatus.IDENTITY_MISMATCH, LoginStatus.DUPLICATE_SUBSCRIPTION -> AccountState.ERROR
            LoginStatus.OK -> {
                val sub = state.subscriptions.getValue(slot.subscriptionId)
                when {
                    sub.limitedAt(now) -> AccountState.RATE_LIMITED
                    slot.cooldownUntil != null && now < slot.cooldownUntil -> AccountState.COOLDOWN
                    (active[sub.id] ?: 0) >= sub.maxParallelSessions -> AccountState.BUSY
                    else -> AccountState.AVAILABLE
                }
            }
        }
    }

    private fun explainNone(state: AccountsState, now: Timestamp, previous: SubscriptionId?, target: ExecutionTargetId?, urgent: Boolean = false): String {
        if (state.slots.isEmpty()) return "нет ни одного слота: зарегистрируйте подписку и войдите формой Claude"
        val parts = state.subscriptions.values.sortedBy { it.id.value }.map { sub ->
            val slots = state.slotsOf(sub.id).filter { target == null || it.targetId == target }
            val why = when {
                slots.isEmpty() -> "нет слотов" + (target?.let { " на $it" } ?: "")
                sub.limitedAt(now) -> "предел; " + resetPhrase(sub, now)
                slots.all { it.state == AccountState.AUTH_REQUIRED } -> "нужен вход формой Claude"
                slots.all { it.state == AccountState.ERROR || it.state == AccountState.DISABLED } -> "слоты остановлены"
                state.activeSessions(sub.id) >= sub.maxParallelSessions -> "заняты все ${sub.maxParallelSessions} сессии"
                sub.nearAt(now) && !policy.startNewTasksWhenNear && previous != null && previous != sub.id ->
                    "у предела — чужие задачи не берёт (${resetPhrase(sub, now)})"
                sub.nearAt(now) && !policy.startNewTasksWhenNear && !urgent && previous != sub.id ->
                    "близко к пределу — новые задачи отложены до сброса окна (${resetPhrase(sub, now)}); срочная — приоритет HIGH"
                slots.any { it.state == AccountState.COOLDOWN } -> "пауза после серии ошибок"
                previous != null && previous != sub.id && !sub.rotationAllowed -> "продолжение чужих задач не разрешено"
                else -> "нет свободного слота"
            }
            "«${sub.label}»: $why"
        }
        return "нет доступного слота — " + parts.joinToString("; ")
    }

    /**
     * Когда сброс: известное время — «сброс через …»; неизвестное — так и
     * сказано, с верхней границей по длине окна (D-13: выведенное, не наблюдённое).
     */
    fun resetPhrase(sub: SubscriptionState, now: Timestamp): String {
        val limited = LimitWindow.entries.filter { sub.window(it).limitedAt(now) }
        val known = limited.mapNotNull { sub.window(it).resetAt }
        return if (known.size == limited.size && known.isNotEmpty()) {
            "сброс через ${formatDuration(known.max() - now)}"
        } else {
            val bound = limited.mapNotNull { sub.window(it).clearsAt(it) }.maxOrNull()
            "время сброса неизвестно" + (bound?.let { ", не позже чем через ${formatDuration(it - now)}" } ?: "")
        }
    }

    private fun notFound(slotId: AccountId): Outcome.Failure = Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого слота", "slot $slotId")

    companion object {
        private val TABLE = AccountLifecycle.table

        /** Состояние после события: единственное место, где состояние аккаунтов меняется. */
        fun apply(state: AccountsState, event: AccountEvent): AccountsState = when (event) {
            is SubscriptionRegistered -> state.copy(
                subscriptions = state.subscriptions + (
                    event.subscription to SubscriptionState(
                        event.subscription, event.label, event.maxParallelSessions, event.rotationAllowed, executor = event.executor,
                    )
                    ),
            )
            is SlotRegistered -> state.copy(
                slots = state.slots + (
                    event.slot to SlotState(
                        event.slot, event.subscription, event.target, event.configDir,
                        executor = state.subscriptions[event.subscription]?.executor ?: Executor.CLAUDE,
                    )
                    ),
            )
            is LoginObserved -> state.slot(event.slot) {
                it.copy(login = event.login, authMethod = event.authMethod, loginExpiring = if (event.login == LoginStatus.OK) it.loginExpiring else false)
            }
            is IdentityConfirmed -> state.sub(event.subscription) { it.copy(identity = event.identity, plan = event.plan) }
            is OverlapDetected -> state
            is LimitObserved -> state.sub(event.subscription) {
                it.copy(windows = it.windows + (event.window to WindowState(event.level, event.resetAt, event.at, it.window(event.window).utilization)))
            }
            is AccountEvent.UsageObserved -> state.sub(event.subscription) {
                val w = it.window(event.window)
                it.copy(windows = it.windows + (event.window to w.copy(utilization = event.utilization, resetAt = event.resetAt ?: w.resetAt, observedAt = event.at)))
            }
            is AccountEvent.RotationSet -> state.sub(event.subscription) { it.copy(rotationAllowed = event.allowed, rotationExplicit = true) }
            is AccountEvent.NearThresholdSet -> state.sub(event.subscription) { it.copy(nearPercent = event.percent) }
            is LimitReset -> state.sub(event.subscription) { it.copy(windows = it.windows + (event.window to WindowState())) }
            is LoginExpiring -> state.slot(event.slot) { it.copy(loginExpiring = true, loginExpiresAt = event.expiresAt) }
            is ErrorRecorded -> state.slot(event.slot) {
                it.copy(errorStreak = it.errorStreak + 1, cooldownUntil = event.cooldownUntil ?: it.cooldownUntil)
            }
            is ErrorsCleared -> state.slot(event.slot) { it.copy(errorStreak = 0, cooldownUntil = null) }
            is SlotDisabled -> state.slot(event.slot) { it.copy(disabled = true) }
            is SlotEnabled -> state.slot(event.slot) { it.copy(disabled = false, login = LoginStatus.UNKNOWN) }
            is SessionLeased -> {
                val sub = state.slots.getValue(event.slot).subscriptionId
                state.slot(event.slot) { it.copy(sessions = it.sessions + (event.session to event.task)) }
                    .copy(taskSubscription = state.taskSubscription + (event.task to sub))
            }
            is SessionReleased -> state.slot(event.slot) { it.copy(sessions = it.sessions - event.session) }
            is SubscriptionSwitched -> state.copy(switches = state.switches + (event.task to state.switches[event.task].orEmpty() + event.at))
            is SlotStateChanged -> state.slot(event.slot) {
                check(it.state == event.from) { "событие смены состояния не от текущего состояния" }
                check(TABLE.isAllowed(event.from, event.to, if (event.from == AccountState.DISABLED) TransitionMode.EXPLICIT else TransitionMode.ORDINARY)) {
                    "недопустимый переход ${event.from} → ${event.to}"
                }
                it.copy(state = event.to)
            }
        }

        fun applyAll(state: AccountsState, events: List<AccountEvent>): AccountsState = events.fold(state, ::apply)

        /** Кратчайший путь по таблице; из `DISABLED` — явной операцией включения. */
        internal fun path(from: AccountState, to: AccountState): List<AccountState> {
            val prev = HashMap<AccountState, AccountState>()
            val queue = ArrayDeque(listOf(from))
            val seen = mutableSetOf(from)
            while (queue.isNotEmpty()) {
                val x = queue.removeFirst()
                if (x == to) break
                val next = TABLE.ordinaryTargets(x) + if (x == AccountState.DISABLED) TABLE.explicitTargets(x) else emptySet()
                for (n in next.sortedBy { it.ordinal }) if (seen.add(n)) {
                    prev[n] = x
                    queue += n
                }
            }
            check(to in seen) { "нет пути $from → $to в таблице аккаунта" }
            val out = ArrayDeque(listOf(to))
            while (out.first() != from) out.addFirst(prev.getValue(out.first()))
            return out.toList()
        }

        /**
         * Каталог настроек слота в том виде, в каком он хранится: без пробелов
         * по краям и без `/` или `\` в конце. Сравнивать каталоги — только так:
         * `/c/` и `/c` — один каталог (ревью 8 октября 2026).
         */
        fun normalizeDir(dir: String): String? {
            val d = dir.trim()
            if (d.isEmpty() || d.length > 512 || d.any { it < ' ' }) return null
            val trimmed = d.trimEnd('/', '\\')
            return trimmed.ifEmpty { d.take(1) }
        }

        /** Длительность по-русски для уведомлений и причин — общий словарь (D-89), второй раз не пишется. */
        fun formatDuration(d: Duration): String = com.dpsmpx.dpai.core.text.Words.duration(d)

        private inline fun AccountsState.slot(id: AccountId, f: (SlotState) -> SlotState): AccountsState =
            copy(slots = slots + (id to f(slots.getValue(id))))

        private inline fun AccountsState.sub(id: SubscriptionId, f: (SubscriptionState) -> SubscriptionState): AccountsState =
            copy(subscriptions = subscriptions + (id to f(subscriptions.getValue(id))))
    }
}
