package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.SlotEnvironment
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.AccountState
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.orchestrator.accounts.SlotAuthProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Проба входа слота через агента (D-27, D-29): окружение слота, разбор, применение к слоту. */
class SlotAuthProbeTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(41))
    private val host = MockAgentHost(clock, ids)
    private val probe = SlotAuthProbe(AgentClient(host, listOf("dpai"), null, "target mock"))
    private val accounts = Accounts()
    private var state = AccountsState.EMPTY
    private val target = ids.next(IdKinds.TARGET)
    private val slot = ids.next(IdKinds.ACCOUNT)

    init {
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        state = Accounts.applyAll(state, accounts.registerSubscription(state, sub, "основная", clock.now()).valueOrNull()!!)
        state = Accounts.applyAll(state, accounts.registerSlot(state, slot, sub, target, "/home/u/.claude-slot1", clock.now()).valueOrNull()!!)
    }

    private fun answer(stdout: String, code: Int) {
        host.onStart = { run, _, _ ->
            host.output(run, stdout)
            host.exit(run, code)
        }
    }

    @Test
    fun loggedInSlotBecomesAvailableWithSlotEnvironment() {
        answer("""{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty","email":"a@example.org","orgId":"o1","subscriptionType":"max"}""", 0)
        val run = ids.next(IdKinds.SESSION)
        assertTrue(probe.start(run, state.slots.getValue(slot)).isSuccess)
        val spec = host.spec(run)!!
        assertEquals(listOf("claude", "auth", "status", "--json"), spec.command)
        assertEquals("/home/u/.claude-slot1", spec.setEnv[SlotEnvironment.CONFIG_DIR])
        assertTrue("ANTHROPIC_API_KEY" in spec.unsetEnv)
        // Агент сводит вывод в отпечаток на target (D-55, A-72).
        assertEquals(com.dpsmpx.dpai.execution.agent.OutputCapture.AUTH_STATUS, spec.capture)
        val status = probe.poll(run).valueOrNull()!!
        assertTrue(status.isClaudeLogin)
        assertEquals(com.dpsmpx.dpai.accounts.AuthStatus.fingerprint("o1", "a@example.org"), status.identity)
        state = Accounts.applyAll(state, accounts.observeAuthStatus(state, slot, status, clock.now()).valueOrNull()!!)
        assertEquals(AccountState.AVAILABLE, state.slots.getValue(slot).state)
    }

    @Test
    fun notLoggedInIsCodeOneWithSameJson() {
        answer("""{"loggedIn":false,"authMethod":"none","apiProvider":"firstParty"}""", 1)
        val run = ids.next(IdKinds.SESSION)
        probe.start(run, state.slots.getValue(slot))
        val status = probe.poll(run).valueOrNull()!!
        assertEquals(false, status.loggedIn)
        state = Accounts.applyAll(state, accounts.observeAuthStatus(state, slot, status, clock.now()).valueOrNull()!!)
        assertEquals(AccountState.AUTH_REQUIRED, state.slots.getValue(slot).state)
    }

    @Test
    fun runningProbeIsNullAndGarbageIsDiagnosed() {
        host.onStart = { _, _, _ -> }
        val run = ids.next(IdKinds.SESSION)
        probe.start(run, state.slots.getValue(slot))
        assertNull(probe.poll(run).valueOrNull())
        host.output(run, "command not found")
        host.exit(run, 127)
        assertEquals(DiagCode.CLAUDE_AUTH_STATUS_INVALID, (probe.poll(run) as Outcome.Failure).diagnostics.first().code)
    }
}
