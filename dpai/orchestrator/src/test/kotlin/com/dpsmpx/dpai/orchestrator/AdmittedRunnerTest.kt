package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.accounts.AiAdmission
import com.dpsmpx.dpai.accounts.AiMode
import com.dpsmpx.dpai.accounts.AiModePolicy
import com.dpsmpx.dpai.accounts.AiModelRef
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.claude.ClaudeCodeCliRunner
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdmittedRunnerTest {
    private val clock = ManualClock()
    private val random = RandomSource.seeded(225)
    private val ids = IdGenerator(clock, random)
    private val host = MockAgentHost(clock, ids)
    private val agent = AgentClient(host, listOf("dpai"), null, "тест")
    private val target = ids.next(IdKinds.TARGET)
    private val model = AiModelRef.native(Executor.CLAUDE, "model-a")
    private val gate = AiAdmission().also { it.configure("unconfigured", AiModePolicy(AiMode.SINGLE, listOf(model)), true) }
    private fun runner(g: AiAdmission = gate) = AdmittedRunner(ClaudeCodeCliRunner(agent, { ProviderSessionId.newUuid(random) }), agent, target, g)
    private val request = ClaudeRequest("Проверить проект", model = "model-a", fallbackModel = "other")

    @Test fun cliBlocksCompetingLaunchAndPreservesContinuationAfterNormalExit() {
        val r = runner()
        val session = ids.next(IdKinds.SESSION)
        assertIs<Outcome.Success<*>>(r.start(session, request))
        assertEquals(1, gate.active())
        val command = host.spec(session)!!.command
        assertFalse("--fallback-model" in command)
        assertTrue("Agent" in command)
        val other = ids.next(IdKinds.SESSION)
        assertIs<Outcome.Failure>(r.start(other, request))
        assertEquals(null, host.spec(other))
        host.exit(session, 0)
        r.stream(session)
        assertEquals(0, gate.active())
        assertIs<Outcome.Success<*>>(r.send(session, request))
        assertEquals(2, agent.status(session).valueOrNull()!!.turn)
        assertEquals(1, gate.active())
        r.cancel(session)
        assertEquals(0, gate.active())
    }

    @Test fun appRestartKeepsRemoteRunReservedUntilConfirmedExit() {
        val r = runner()
        val session = ids.next(IdKinds.SESSION)
        val started = r.start(session, request).valueOrNull()!!
        val restored = AiAdmission()
        restored.install(Outcome.success(gate.snapshot())) { Outcome.success(Unit) }
        val adopted = runner(restored)
        host.reachable = false
        adopted.adopt(session, started.provider, 0, true)
        assertEquals(1, restored.active())
        assertIs<Outcome.Failure>(adopted.start(ids.next(IdKinds.SESSION), request))
        host.reachable = true
        host.exit(session, 0)
        assertIs<Outcome.Success<*>>(adopted.reconcile(session))
        assertEquals(0, restored.active())
    }

    @Test fun previousExitedTurnDoesNotClearNewUnconfirmedIntent() {
        val r = runner()
        val session = ids.next(IdKinds.SESSION)
        r.start(session, request)
        host.exit(session, 0)
        r.stream(session)
        // Намерение следующего хода записано, но ответа на start пока нет.
        gate.acquire(session.value, model, target, turn = 2, location = com.dpsmpx.dpai.core.hashing.Hashing.sha256(target.value).hex)
        assertIs<Outcome.Success<*>>(r.reconcile(session))
        assertEquals(1, gate.active())
    }

    @Test fun editingTargetAddressCannotConfirmOldRunOnDifferentHost() {
        val session = ids.next(IdKinds.SESSION)
        val r = runner()
        r.start(session, request)
        host.exit(session, 0)
        val moved = AdmittedRunner(ClaudeCodeCliRunner(agent, { ProviderSessionId.newUuid(random) }), agent, target, gate, "b".repeat(64))
        assertIs<Outcome.Failure>(moved.reconcile(session))
        assertEquals(1, gate.active())
        assertIs<Outcome.Success<*>>(r.reconcile(session))
        assertEquals(0, gate.active())
    }
}
