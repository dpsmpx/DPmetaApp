package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.SubscriptionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.assertIs

/** Стенд аккаунтов: часы, идентификаторы, состояние, все события по порядку. */
class Fixture(val accounts: Accounts = Accounts(), seed: Long = 1) {
    val clock = ManualClock()
    val ids = IdGenerator(clock, RandomSource.seeded(seed))
    var state = AccountsState.EMPTY
    val events = mutableListOf<AccountEvent>()

    val now get() = clock.now()

    fun record(out: Outcome<List<AccountEvent>>): Outcome<List<AccountEvent>> {
        out.valueOrNull()?.let { es ->
            events += es
            state = Accounts.applyAll(state, es)
        }
        return out
    }

    fun ok(out: Outcome<List<AccountEvent>>): List<AccountEvent> = assertIs<Outcome.Success<List<AccountEvent>>>(record(out)).value

    fun subscription(label: String = "основная", max: Int = 2, rotation: Boolean = false): SubscriptionId =
        ids.next(IdKinds.SUBSCRIPTION).also { ok(accounts.registerSubscription(state, it, label, now, max, rotation)) }

    fun target(): ExecutionTargetId = ids.next(IdKinds.TARGET)

    fun slot(sub: SubscriptionId, target: ExecutionTargetId = target(), dir: String = "~/.claude"): AccountId =
        ids.next(IdKinds.ACCOUNT).also { ok(accounts.registerSlot(state, it, sub, target, dir, now)) }

    fun login(slot: AccountId, email: String = "author@example.org", org: String = "org-1", method: String = AuthStatus.CLAUDE_AI) =
        record(accounts.observeAuthStatus(state, slot, AuthStatus(true, method, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint(org, email), "max"), now))

    fun lease(task: TaskId, session: SessionId = ids.next(IdKinds.SESSION)): Pair<SessionId, Outcome<Accounts.Lease>> {
        val r = accounts.lease(state, session, task, now)
        r.valueOrNull()?.let { l ->
            events += l.events
            state = Accounts.applyAll(state, l.events)
        }
        return session to r
    }

    fun task(): TaskId = ids.next(IdKinds.TASK)

    fun tick() = ok(Outcome.success(accounts.tick(state, now)))
}
