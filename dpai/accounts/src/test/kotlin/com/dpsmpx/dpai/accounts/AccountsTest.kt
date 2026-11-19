package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.AccountState
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Сценарии `docs/ACCOUNT_MODEL.md`: режим одной подписки — первым. */
class AccountsTest {
    @Test
    fun sharedCapacityChangesAreOrderedWithoutMutatingTheEarlierState() {
        val f = Fixture()
        val sub = f.subscription(max = 1)
        val slots = List(3) { f.slot(sub).also { f.login(it) } }
        f.state = f.state.copy(slots = f.state.slots.entries.reversed().associate { it.toPair() })
        val before = f.state
        val (_, result) = f.lease(f.task())
        val events = kotlin.test.assertIs<Outcome.Success<Accounts.Lease>>(result).value.events
        assertEquals(slots.sortedBy { it.value }, events.filterIsInstance<AccountEvent.SlotStateChanged>().map { it.slot })
        assertTrue(f.state.slots.values.all { it.state == AccountState.BUSY })
        assertTrue(f.accounts.normalize(f.state, f.now).isEmpty())
        assertTrue(before.slots.values.all { it.state == AccountState.AVAILABLE && it.sessions.isEmpty() })
    }

    private fun Fixture.stateOf(slot: com.dpsmpx.dpai.core.ids.AccountId) = state.slots.getValue(slot).state

    private fun failure(r: Outcome<*>): DiagCode = assertIs<Outcome.Failure>(r).errors.single().code

    @Test
    fun singleSubscriptionFullCycle() {
        val f = Fixture()
        val sub = f.subscription(max = 2)
        val slot = f.slot(sub)
        assertEquals(AccountState.AUTH_REQUIRED, f.stateOf(slot))
        assertEquals(DiagCode.ACCOUNT_NONE_AVAILABLE, failure(f.lease(f.task()).second))

        assertTrue(f.login(slot).isSuccess)
        assertEquals(AccountState.AVAILABLE, f.stateOf(slot))
        assertTrue(f.events.any { it is AccountEvent.IdentityConfirmed })

        val (s1, r1) = f.lease(f.task())
        assertEquals(slot, r1.valueOrNull()!!.slot)
        f.lease(f.task())
        assertEquals(AccountState.BUSY, f.stateOf(slot))
        val third = f.lease(f.task()).second
        assertEquals(DiagCode.ACCOUNT_NONE_AVAILABLE, failure(third))
        assertContains(third.errors.single().message, "заняты все 2 сессии")

        f.ok(f.accounts.release(f.state, s1, f.now))
        assertEquals(AccountState.AVAILABLE, f.stateOf(slot))
    }

    @Test
    fun limitReachedWaitsForResetTime() {
        val f = Fixture()
        val sub = f.subscription()
        val slot = f.slot(sub)
        f.login(slot)
        val resetAt = f.now + 2.hours + 10.minutes
        f.ok(f.accounts.observeLimit(f.state, slot, LimitWindow.SESSION, LimitLevel.REACHED, resetAt, f.now))
        assertEquals(AccountState.RATE_LIMITED, f.stateOf(slot))
        val r = f.lease(f.task()).second
        assertContains(r.errors.single().message, "сброс через 2 ч 10 мин")
        assertEquals(resetAt, f.accounts.nextWakeUp(f.state, f.now))

        f.clock.advance(1.hours)
        assertTrue(f.tick().none { it is AccountEvent.LimitReset })
        f.clock.advance(1.hours + 10.minutes)
        val ev = f.tick()
        assertEquals(ResetEvidence.TIME, ev.filterIsInstance<AccountEvent.LimitReset>().single().evidence)
        assertEquals(AccountState.AVAILABLE, f.stateOf(slot))
        assertTrue(f.lease(f.task()).second.isSuccess)
    }

    @Test
    fun unknownResetTimeIsNotInventedAndProbedLater() {
        val f = Fixture()
        val slot = f.slot(f.subscription())
        f.login(slot)
        f.ok(f.accounts.observeLimit(f.state, slot, LimitWindow.SESSION, LimitLevel.REACHED, null, f.now))
        assertContains(f.lease(f.task()).second.errors.single().message, "время сброса неизвестно, не позже чем через 5 ч")
        assertEquals(emptyList(), f.accounts.probeDue(f.state, f.now))
        f.clock.advance(31.minutes)
        assertEquals(1, f.accounts.probeDue(f.state, f.now).size)
        // Пробный запрос удался — предел снят доказательством.
        val ev = f.ok(f.accounts.observeSuccess(f.state, slot, f.now))
        assertEquals(ResetEvidence.SUCCESS, ev.filterIsInstance<AccountEvent.LimitReset>().single().evidence)
        assertEquals(AccountState.AVAILABLE, f.stateOf(slot))
    }

    @Test
    fun windowExpiresAfterItsLengthWhenResetUnknown() {
        val f = Fixture()
        val slot = f.slot(f.subscription())
        f.login(slot)
        f.ok(f.accounts.observeLimit(f.state, slot, LimitWindow.SESSION, LimitLevel.REACHED, null, f.now))
        f.clock.advance(5.hours)
        assertEquals(ResetEvidence.WINDOW_EXPIRED, f.tick().filterIsInstance<AccountEvent.LimitReset>().single().evidence)
    }

    @Test
    fun nearLimitDefersNewTasksButContinuesRunningOnes() {
        val f = Fixture()
        val sub = f.subscription(max = 3)
        val slot = f.slot(sub)
        f.login(slot)
        val running = f.task()
        val (s, _) = f.lease(running)
        f.ok(f.accounts.release(f.state, s, f.now))
        f.ok(f.accounts.observeLimit(f.state, slot, LimitWindow.SESSION, LimitLevel.NEAR, f.now + 1.hours, f.now))
        assertContains(f.lease(f.task()).second.errors.single().message, "близко к пределу")
        assertTrue(f.lease(running).second.isSuccess, "продолжение задачи, уже шедшей на подписке")
    }

    @Test
    fun onlyClaudeFormLoginIsAllowed() {
        for (method in listOf("api_key", "oauth_token", "api_key_helper", "third_party")) {
            val f = Fixture()
            val slot = f.slot(f.subscription())
            val r = f.login(slot, method = method)
            assertEquals(DiagCode.ACCOUNT_AUTH_METHOD_FORBIDDEN, r.warnings.single().code, method)
            assertEquals(AccountState.ERROR, f.stateOf(slot), method)
        }
        val f = Fixture()
        val slot = f.slot(f.subscription())
        f.record(f.accounts.observeAuthStatus(f.state, slot, AuthStatus(true, AuthStatus.CLAUDE_AI, "bedrock", null, null), f.now))
        assertEquals(AccountState.ERROR, f.stateOf(slot), "вход claude.ai через другого провайдера")
    }

    @Test
    fun loggedOutAndExpiredLoginNeedReLogin() {
        val f = Fixture()
        val slot = f.slot(f.subscription())
        f.record(f.accounts.observeAuthStatus(f.state, slot, AuthStatus(false, "none", null, null, null), f.now))
        assertEquals(AccountState.AUTH_REQUIRED, f.stateOf(slot))
        f.login(slot)
        f.ok(f.accounts.observeLoginExpiring(f.state, slot, null, f.now))
        assertTrue(f.ok(f.accounts.observeLoginExpiring(f.state, slot, null, f.now)).isEmpty(), "повтор не плодит событий")
        f.ok(f.accounts.observeAuthFailure(f.state, slot, f.now))
        assertEquals(AccountState.AUTH_REQUIRED, f.stateOf(slot))
        f.login(slot)
        assertEquals(AccountState.AVAILABLE, f.stateOf(slot))
    }

    @Test
    fun identityUnknownWorksButWarns() {
        val f = Fixture()
        val slot = f.slot(f.subscription())
        val r = f.record(f.accounts.observeAuthStatus(f.state, slot, AuthStatus(true, AuthStatus.CLAUDE_AI, null, null, null), f.now))
        assertEquals(DiagCode.ACCOUNT_IDENTITY_UNKNOWN, r.warnings.single().code)
        assertEquals(AccountState.AVAILABLE, f.stateOf(slot))
    }

    @Test
    fun overlapProtections() {
        val f = Fixture()
        val a = f.subscription("A")
        val b = f.subscription("B")
        val linux = f.target()
        val slotA = f.slot(a, linux, "~/.claude-a/")
        // Тот же каталог на том же target — отказ; на другом target — можно.
        assertEquals(DiagCode.ACCOUNT_CONFIG_DIR_IN_USE, failure(f.accounts.registerSlot(f.state, f.ids.next(IdKinds.ACCOUNT), b, linux, "~/.claude-a", f.now)))
        f.slot(b, f.target(), "~/.claude-a")
        f.login(slotA, email = "a@example.org")
        // В каталоге слота подписки A — вход аккаунтом B.
        f.login(slotA, email = "b@example.org")
        assertEquals(AccountState.ERROR, f.stateOf(slotA))
        assertEquals(OverlapKind.IDENTITY_MISMATCH, f.events.filterIsInstance<AccountEvent.OverlapDetected>().last().kind)
        // Аккаунт A как вторая подписка — двойной счёт пределов.
        val slotB = f.slot(b, linux, "~/.claude-b")
        val r = f.login(slotB, email = "a@example.org")
        assertEquals(DiagCode.ACCOUNT_DUPLICATE_SUBSCRIPTION, r.warnings.single().code)
        assertEquals(AccountState.ERROR, f.stateOf(slotB))
        assertNull(f.state.subscriptions.getValue(b).identity)
    }

    @Test
    fun limitSeenBySlotAppliesToWholeSubscription() {
        val f = Fixture()
        val sub = f.subscription()
        val termux = f.slot(sub, f.target())
        val linux = f.slot(sub, f.target())
        f.login(termux)
        f.login(linux)
        f.ok(f.accounts.observeLimit(f.state, termux, LimitWindow.WEEKLY, LimitLevel.REACHED, f.now + 30.hours, f.now))
        assertEquals(AccountState.RATE_LIMITED, f.stateOf(linux))
    }

    @Test
    fun continuationOnAnotherSubscriptionOnlyWhenAllowedAndWithoutLoops() {
        val f = Fixture(Accounts(AccountPolicy(maxSwitchesPerTask = 2)))
        val a = f.subscription("A")
        val b = f.subscription("B", rotation = false)
        val slotA = f.slot(a, dir = "~/.claude-a")
        val slotB = f.slot(b, dir = "~/.claude-b")
        f.login(slotA, email = "a@example.org")
        f.login(slotB, email = "b@example.org")
        val task = f.task()
        val (s1, r1) = f.lease(task)
        assertEquals(a, r1.valueOrNull()!!.subscription)
        f.ok(f.accounts.release(f.state, s1, f.now))
        f.ok(f.accounts.observeLimit(f.state, slotA, LimitWindow.SESSION, LimitLevel.REACHED, f.now + 3.hours, f.now))
        assertContains(f.lease(task).second.errors.single().message, "продолжение чужих задач не разрешено")

        // Автор разрешил подписке B продолжать чужие задачи.
        val f2 = Fixture(Accounts(AccountPolicy(maxSwitchesPerTask = 2)))
        val a2 = f2.subscription("A", rotation = true)
        val b2 = f2.subscription("B", rotation = true)
        val sa = f2.slot(a2, dir = "~/.claude-a")
        val sb = f2.slot(b2, dir = "~/.claude-b")
        f2.login(sa, email = "a@example.org")
        f2.login(sb, email = "b@example.org")
        val t = f2.task()
        var current = a2
        val (first, _) = f2.lease(t)
        f2.ok(f2.accounts.release(f2.state, first, f2.now))
        repeat(2) {
            val limitedSlot = if (current == a2) sa else sb
            f2.ok(f2.accounts.observeLimit(f2.state, limitedSlot, LimitWindow.SESSION, LimitLevel.REACHED, f2.now + 3.hours, f2.now))
            val (s, r) = f2.lease(t)
            val lease = r.valueOrNull()!!
            assertTrue(lease.subscription != current)
            assertTrue(lease.events.any { it is AccountEvent.SubscriptionSwitched })
            current = lease.subscription
            f2.ok(f2.accounts.release(f2.state, s, f2.now))
            f2.ok(f2.accounts.observeSuccess(f2.state, if (current == a2) sb else sa, f2.now)) // прежняя снова доступна
        }
        f2.ok(f2.accounts.observeLimit(f2.state, if (current == a2) sa else sb, LimitWindow.SESSION, LimitLevel.REACHED, f2.now + 3.hours, f2.now))
        assertEquals(DiagCode.ACCOUNT_SWITCH_LOOP, failure(f2.lease(t).second))
    }

    @Test
    fun rotationSwitchedOnLaterLetsTheTaskMoveToAnotherSubscription() {
        // D-56: подписки заведены без ротации (как до решения), затем ротация включена событием.
        val f = Fixture()
        val a = f.subscription("A")
        val b = f.subscription("B")
        val slotA = f.slot(a, dir = "~/.claude-a")
        val slotB = f.slot(b, dir = "~/.claude-b")
        f.login(slotA, email = "a@example.org")
        f.login(slotB, email = "b@example.org")
        val task = f.task()
        val (s1, r1) = f.lease(task)
        assertEquals(a, r1.valueOrNull()!!.subscription)
        f.ok(f.accounts.release(f.state, s1, f.now))
        f.ok(f.accounts.observeLimit(f.state, slotA, LimitWindow.WEEKLY, LimitLevel.REACHED, f.now + 3.hours, f.now))
        assertContains(f.lease(task).second.errors.single().message, "продолжение чужих задач не разрешено")
        f.ok(f.accounts.setRotation(f.state, b, true, f.now))
        assertTrue(f.state.subscriptions.getValue(b).rotationExplicit)
        assertEquals(emptyList(), f.accounts.setRotation(f.state, b, true, f.now).valueOrNull(), "то же значение — без события")
        val lease = f.lease(task).second.valueOrNull()!!
        assertEquals(b, lease.subscription, "недельный предел A — задача продолжает на B")
        assertTrue(lease.events.any { it is AccountEvent.SubscriptionSwitched })
        assertEquals(DiagCode.ENTITY_NOT_FOUND, failure(f.accounts.setRotation(f.state, f.ids.next(com.dpsmpx.dpai.core.ids.IdKinds.SUBSCRIPTION), true, f.now)))
    }

    @Test
    fun foreignTaskNeverGoesToSubscriptionNearLimitAndMoreHeadroomWins() {
        // D-57: задача упёршейся подписки не уходит на подписку у предела — даже срочная;
        // из подходящих — с большим запасом окна.
        val f = Fixture()
        val a = f.subscription("A", rotation = true)
        val b = f.subscription("B", rotation = true)
        val c = f.subscription("C", rotation = true)
        val slotA = f.slot(a, dir = "~/.claude-a")
        val slotB = f.slot(b, dir = "~/.claude-b")
        val slotC = f.slot(c, dir = "~/.claude-c")
        f.login(slotA, email = "a@example.org")
        f.login(slotB, email = "b@example.org")
        f.login(slotC, email = "c@example.org")
        f.ok(f.accounts.observeUsage(f.state, slotA, LimitWindow.SESSION, 0.10, null, f.now))
        f.ok(f.accounts.observeUsage(f.state, slotB, LimitWindow.SESSION, 0.90, null, f.now))
        f.ok(f.accounts.observeUsage(f.state, slotC, LimitWindow.WEEKLY, 0.40, null, f.now))
        val task = f.task()
        val (s1, r1) = f.lease(task)
        assertEquals(a, r1.valueOrNull()!!.subscription, "новая задача — на подписку с наибольшим запасом")
        f.ok(f.accounts.release(f.state, s1, f.now))
        f.ok(f.accounts.observeLimit(f.state, slotA, LimitWindow.SESSION, LimitLevel.REACHED, f.now + 3.hours, f.now))
        val urgent = f.accounts.lease(f.state, f.ids.next(com.dpsmpx.dpai.core.ids.IdKinds.SESSION), task, f.now, urgent = true).valueOrNull()!!
        assertEquals(c, urgent.subscription, "B у предела (90 % ≥ 80 %) — чужую задачу не берёт и срочную; C — с запасом")
        // Только B: задача ждёт, причина названа.
        val g = Fixture()
        val ga = g.subscription("A", rotation = true)
        val gb = g.subscription("B", rotation = true)
        val ra = g.slot(ga, dir = "~/.claude-a")
        val rb = g.slot(gb, dir = "~/.claude-b")
        g.login(ra, email = "a@example.org")
        g.login(rb, email = "b@example.org")
        val t = g.task()
        val (gs, _) = g.lease(t)
        g.ok(g.accounts.release(g.state, gs, g.now))
        g.ok(g.accounts.observeUsage(g.state, rb, LimitWindow.SESSION, 0.95, null, g.now))
        g.ok(g.accounts.observeLimit(g.state, ra, LimitWindow.SESSION, LimitLevel.REACHED, g.now + 3.hours, g.now))
        val none = g.accounts.lease(g.state, g.ids.next(com.dpsmpx.dpai.core.ids.IdKinds.SESSION), t, g.now, urgent = true)
        assertContains(assertIs<Outcome.Failure>(none).errors.single().message, "у предела — чужие задачи не берёт")
    }

    @Test
    fun byDefaultTaskSwitchesSubscriptionsWithoutLimit() {
        // D-67: предела числа переходов нет — задача переходит к подписке с запасом столько раз, сколько нужно.
        val f = Fixture()
        val a = f.subscription("A", rotation = true)
        val b = f.subscription("B", rotation = true)
        val sa = f.slot(a, dir = "~/.claude-a")
        val sb = f.slot(b, dir = "~/.claude-b")
        f.login(sa, email = "a@example.org")
        f.login(sb, email = "b@example.org")
        val t = f.task()
        var current = f.lease(t).let { (s, r) -> f.ok(f.accounts.release(f.state, s, f.now)); r.valueOrNull()!!.subscription }
        repeat(6) {
            f.ok(f.accounts.observeLimit(f.state, if (current == a) sa else sb, LimitWindow.SESSION, LimitLevel.REACHED, f.now + 3.hours, f.now))
            val (s, r) = f.lease(t)
            val lease = r.valueOrNull() ?: error("переход ${it + 1} отклонён: ${(r as Outcome.Failure).errors}")
            assertTrue(lease.subscription != current)
            current = lease.subscription
            f.ok(f.accounts.release(f.state, s, f.now))
            f.ok(f.accounts.observeSuccess(f.state, if (current == a) sb else sa, f.now))
        }
    }

    @Test
    fun errorSeriesCoolsDownAndSuccessClears() {
        val f = Fixture()
        val slot = f.slot(f.subscription())
        f.login(slot)
        repeat(3) { f.ok(f.accounts.observeError(f.state, slot, DiagCode.EXECUTION_HOST_UNAVAILABLE, f.now)) }
        assertEquals(AccountState.COOLDOWN, f.stateOf(slot))
        f.clock.advance(10.minutes)
        f.tick()
        assertEquals(AccountState.AVAILABLE, f.stateOf(slot))
        f.ok(f.accounts.observeSuccess(f.state, slot, f.now))
        assertEquals(0, f.state.slots.getValue(slot).errorStreak)
    }

    @Test
    fun disableAndExplicitEnable() {
        val f = Fixture()
        val slot = f.slot(f.subscription())
        f.login(slot)
        f.ok(f.accounts.disable(f.state, slot, f.now))
        assertEquals(AccountState.DISABLED, f.stateOf(slot))
        assertTrue(!f.lease(f.task()).second.isSuccess)
        f.ok(f.accounts.enable(f.state, slot, f.now))
        assertEquals(AccountState.AUTH_REQUIRED, f.stateOf(slot), "после включения вход проверяется заново")
    }

    @Test
    fun leaseIsIdempotentAndExclusive() {
        val f = Fixture()
        val slot = f.slot(f.subscription())
        f.login(slot)
        val task = f.task()
        val (s, r) = f.lease(task)
        val again = f.accounts.lease(f.state, s, task, f.now)
        assertEquals(r.valueOrNull()!!.slot, again.valueOrNull()!!.slot)
        assertTrue(again.valueOrNull()!!.events.isEmpty())
        assertEquals(DiagCode.ACCOUNT_LEASE_CONFLICT, failure(f.accounts.lease(f.state, s, f.task(), f.now)))
    }

    /** D-77: перенесённая задача не идёт на исключённый слот; другого нет — отказ с причиной, а не тот же слот. */
    @Test
    fun excludedSlotIsNotLeased() {
        val f = Fixture()
        val sub = f.subscription(max = 4)
        val target = f.target()
        val a = f.slot(sub, target, "~/.claude-a")
        val b = f.slot(sub, target, "~/.claude-b")
        f.login(a)
        f.login(b)
        val task = f.task()
        val chosen = f.accounts.lease(f.state, f.ids.next(IdKinds.SESSION), task, f.now, target, exclude = a)
        assertEquals(b, chosen.valueOrNull()!!.slot)
        f.state = Accounts.applyAll(f.state, chosen.valueOrNull()!!.events)
        val alone = Fixture()
        val only = alone.slot(alone.subscription(), alone.target())
        alone.login(only)
        assertEquals(DiagCode.ACCOUNT_NONE_AVAILABLE, failure(alone.accounts.lease(alone.state, alone.ids.next(IdKinds.SESSION), alone.task(), alone.now, exclude = only)))
    }

    @Test
    fun loadIsSpreadAcrossSlotsAndDeterministic() {
        fun run(): List<AccountEvent> {
            val f = Fixture(seed = 3)
            val sub = f.subscription(max = 4)
            val s1 = f.slot(sub, f.target())
            val s2 = f.slot(sub, f.target())
            f.login(s1)
            f.login(s2)
            repeat(4) { f.lease(f.task()) }
            assertEquals(2, f.state.slots.getValue(s1).sessions.size)
            assertEquals(2, f.state.slots.getValue(s2).sessions.size)
            return f.events
        }
        assertEquals(run(), run())
    }

    @Test
    fun twelveSlotsLiveThroughTheirLifecycleTogether() {
        // Этап 5: 10+ слотов одновременно — 3 подписки по 4 слота на 4 targets.
        val f = Fixture(seed = 12)
        val targets = List(4) { f.target() }
        val subs = List(3) { f.subscription("S$it", max = 4) }
        val slots = subs.mapIndexed { i, sub -> targets.map { t -> f.slot(sub, t, "~/.claude-s$i") } }
        slots.forEachIndexed { i, row -> row.forEach { assertTrue(f.login(it, email = "s$i@example.org").isSuccess) } }
        assertTrue(slots.flatten().all { f.stateOf(it) == AccountState.AVAILABLE })

        val leased = List(12) { f.lease(f.task()) }
        assertTrue(leased.all { it.second.isSuccess })
        assertEquals(List(3) { 4 }, subs.map { f.state.activeSessions(it) })
        assertTrue(slots.flatten().all { f.stateOf(it) == AccountState.BUSY })
        assertEquals(DiagCode.ACCOUNT_NONE_AVAILABLE, failure(f.lease(f.task()).second))

        // Предел одной подписки — все её 4 слота, остальные 8 не задеты.
        f.ok(f.accounts.observeLimit(f.state, slots[0][2], LimitWindow.SESSION, LimitLevel.REACHED, f.now + 1.hours, f.now))
        assertTrue(slots[0].all { f.stateOf(it) == AccountState.RATE_LIMITED })
        assertTrue(slots.drop(1).flatten().all { f.stateOf(it) == AccountState.BUSY })

        leased.forEach { (s, _) -> f.ok(f.accounts.release(f.state, s, f.now)) }
        f.ok(f.accounts.disable(f.state, slots[1][0], f.now))
        f.ok(f.accounts.observeAuthFailure(f.state, slots[2][3], f.now))
        f.clock.advance(1.hours)
        f.tick()
        assertTrue(slots[0].all { f.stateOf(it) == AccountState.AVAILABLE })
        assertEquals(AccountState.DISABLED, f.stateOf(slots[1][0]))
        assertEquals(AccountState.AUTH_REQUIRED, f.stateOf(slots[2][3]))
        assertEquals(10, slots.flatten().count { f.stateOf(it) == AccountState.AVAILABLE })
        // Вся история переходов допустима: её можно заново свернуть из журнала.
        assertEquals(f.state, Accounts.applyAll(AccountsState.EMPTY, f.events))
    }

    @Test
    fun durationsInRussian() {
        assertEquals("меньше минуты", Accounts.formatDuration(kotlin.time.Duration.ZERO))
        assertEquals("5 мин", Accounts.formatDuration(5.minutes))
        assertEquals("3 ч", Accounts.formatDuration(3.hours))
        assertEquals("2 ч 10 мин", Accounts.formatDuration(130.minutes))
        assertEquals(Timestamp(0), Timestamp(0))
    }
}
