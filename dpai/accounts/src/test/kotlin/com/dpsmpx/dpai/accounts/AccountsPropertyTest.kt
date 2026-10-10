package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.state.AccountState
import com.dpsmpx.dpai.testkit.Gen
import com.dpsmpx.dpai.testkit.Property
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Свойства D-30 на случайных последовательностях операций (1–3 подписки,
 * до 4 слотов): инварианты держатся после каждого шага, состояние всегда
 * выведено из фактов, кодек обратим.
 */
class AccountsPropertyTest {
    private sealed interface Op {
        data class Login(val slot: Int, val email: Int, val method: Int) : Op
        data class Limit(val slot: Int, val reached: Boolean, val window: Int, val resetIn: Int?) : Op
        data class Success(val slot: Int) : Op
        data class Error(val slot: Int) : Op
        data class AuthFail(val slot: Int) : Op
        data class Lease(val task: Int) : Op
        data class Release(val pick: Int) : Op
        data class Toggle(val slot: Int) : Op
        data class Advance(val minutes: Int) : Op
    }

    private val op: Gen<Op> = Gen { r ->
        when (r.nextInt(9)) {
            0 -> Op.Login(r.nextInt(4), r.nextInt(3), if (r.nextInt(6) == 0) 1 else 0)
            1 -> Op.Limit(r.nextInt(4), r.nextInt(2) == 0, r.nextInt(2), if (r.nextInt(3) == 0) null else r.nextInt(400))
            2 -> Op.Success(r.nextInt(4))
            3 -> Op.Error(r.nextInt(4))
            4 -> Op.AuthFail(r.nextInt(4))
            5, 6 -> Op.Lease(r.nextInt(6))
            7 -> Op.Release(r.nextInt(8))
            else -> if (r.nextInt(4) == 0) Op.Toggle(r.nextInt(4)) else Op.Advance(r.nextInt(120))
        }
    }

    private data class Case(val subscriptions: Int, val rotation: Boolean, val ops: List<Op>)

    private val case: Gen<Case> = Gen { r -> Case(1 + r.nextInt(3), r.nextInt(2) == 0, Gen.list(op, 1..80).next(r)) }

    @Test
    fun invariantsHoldAfterEveryStep() {
        Property.forAll(case, iterations = Property.iterations / 2) { c ->
            val f = Fixture(seed = c.ops.size.toLong())
            val subs = List(c.subscriptions) { f.subscription("S$it", max = 1 + it, rotation = c.rotation) }
            val slots: List<AccountId> = List(4) { i -> f.slot(subs[i % subs.size], f.target(), "~/.claude-$i") }
            val tasks: List<TaskId> = List(6) { f.task() }
            val live = mutableListOf<SessionId>()
            for (o in c.ops) {
                when (o) {
                    is Op.Login -> f.login(slots[o.slot], email = "u${o.email}@example.org", method = if (o.method == 1) "api_key" else AuthStatus.CLAUDE_AI)
                    is Op.Limit -> f.record(
                        f.accounts.observeLimit(
                            f.state, slots[o.slot], LimitWindow.entries[o.window],
                            if (o.reached) LimitLevel.REACHED else LimitLevel.NEAR, o.resetIn?.let { f.now + it.minutes }, f.now,
                        ),
                    )
                    is Op.Success -> f.record(f.accounts.observeSuccess(f.state, slots[o.slot], f.now))
                    is Op.Error -> f.record(f.accounts.observeError(f.state, slots[o.slot], DiagCode.EXECUTION_HOST_UNAVAILABLE, f.now))
                    is Op.AuthFail -> f.record(f.accounts.observeAuthFailure(f.state, slots[o.slot], f.now))
                    is Op.Lease -> {
                        val before = f.state
                        val (s, r) = f.lease(tasks[o.task])
                        r.valueOrNull()?.let { lease ->
                            live += s
                            // Аренда — только слота, который после такта был AVAILABLE.
                            val ticked = Accounts.applyAll(before, f.accounts.tick(before, f.now))
                            assertEquals(AccountState.AVAILABLE, ticked.slots.getValue(lease.slot).state)
                        }
                    }
                    is Op.Release -> if (live.isNotEmpty()) {
                        val s = live.removeAt(o.pick % live.size)
                        f.record(f.accounts.release(f.state, s, f.now))
                    }
                    is Op.Toggle -> {
                        val slot = f.state.slots.getValue(slots[o.slot])
                        f.record(if (slot.disabled) f.accounts.enable(f.state, slot.id, f.now) else f.accounts.disable(f.state, slot.id, f.now))
                    }
                    is Op.Advance -> {
                        f.clock.advance(o.minutes.minutes)
                        f.tick()
                    }
                }
                check(f)
            }
        }
    }

    private fun check(f: Fixture) {
        val s = f.state
        // Сессия — не больше чем на одном слоте.
        val all = s.slots.values.flatMap { it.sessions.keys }
        assertEquals(all.size, all.toSet().size, "сессия на двух слотах")
        // Параллельность подписки — не больше её предела.
        for (sub in s.subscriptions.values) assertTrue(s.activeSessions(sub.id) <= sub.maxParallelSessions, "превышен предел ${sub.label}")
        // Один аккаунт — не больше одной подписки.
        val ids = s.subscriptions.values.mapNotNull { it.identity }
        assertEquals(ids.size, ids.toSet().size, "аккаунт у двух подписок")
        // Состояние слотов выведено из фактов: нормализация ничего не меняет.
        assertEquals(emptyList(), f.accounts.normalize(s, f.now))
        // Запрещённый способ входа и пересечения — всегда ERROR.
        for (slot in s.slots.values) {
            if (!slot.disabled && slot.login in setOf(LoginStatus.FORBIDDEN_METHOD, LoginStatus.IDENTITY_MISMATCH, LoginStatus.DUPLICATE_SUBSCRIPTION)) {
                assertEquals(AccountState.ERROR, slot.state)
            }
        }
        // Снимок обратим.
        val text = CanonicalJson.write(AccountCodec.encodeState(s))
        val back = AccountCodec.decodeState(JsonParser().parse(text).valueOrNull() as JsonObject)
        assertEquals(s, back.valueOrNull(), "снимок состояния: ${back.diagnostics}")
        // История событий воспроизводит то же состояние (D-12).
        assertEquals(s, Accounts.applyAll(AccountsState.EMPTY, f.events))
    }

    @Test
    fun everyEventRoundTripsThroughItsCodec() {
        Property.forAll(case, iterations = 40) { c ->
            val f = Fixture(seed = 7)
            val subs = List(c.subscriptions) { f.subscription("S$it", rotation = c.rotation) }
            val slots = List(3) { i -> f.slot(subs[i % subs.size], f.target(), "~/.c$i") }
            slots.forEach { f.login(it, email = "x${it.value.takeLast(2)}@example.org") }
            f.record(f.accounts.observeLimit(f.state, slots[0], LimitWindow.SESSION, LimitLevel.REACHED, null, f.now))
            f.record(f.accounts.observeError(f.state, slots[1], DiagCode.NETWORK_UNREACHABLE, f.now))
            f.record(f.accounts.observeLoginExpiring(f.state, slots[2], f.now + 60.minutes, f.now))
            f.lease(f.task(), f.ids.next(IdKinds.SESSION))
            for (e in f.events) {
                val payload = JsonParser().parse(CanonicalJson.write(AccountCodec.payload(e))).valueOrNull() as JsonObject
                assertEquals(e, AccountCodec.decode(AccountCodec.type(e), e.at, payload)!!.valueOrNull(), "событие $e")
                AccountCodec.stream(e)
            }
        }
    }
}
