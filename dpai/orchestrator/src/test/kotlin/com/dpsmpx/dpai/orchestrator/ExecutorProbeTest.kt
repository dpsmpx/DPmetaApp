package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.SlotEnvironment
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.state.AccountState
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.orchestrator.accounts.SlotAuthProbe
import com.dpsmpx.dpai.orchestrator.review.Review
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Проба входа слотов Codex и Gemini (D-100) и вердикт ревью текстом. */
class ExecutorProbeTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(43))
    private val host = MockAgentHost(clock, ids)
    private val probe = SlotAuthProbe(AgentClient(host, listOf("dpai"), null, "target mock"))
    private val accounts = Accounts()
    private var state = AccountsState.EMPTY
    private val target = ids.next(IdKinds.TARGET)

    private fun slot(executor: Executor, dir: String): com.dpsmpx.dpai.core.ids.AccountId {
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        state = Accounts.applyAll(state, accounts.registerSubscription(state, sub, executor.title, clock.now(), executor = executor).valueOrNull()!!)
        val slot = ids.next(IdKinds.ACCOUNT)
        state = Accounts.applyAll(state, accounts.registerSlot(state, slot, sub, target, dir, clock.now()).valueOrNull()!!)
        return slot
    }

    private fun answer(out: String, code: Int) {
        host.onStart = { run, _, _ ->
            host.output(run, out)
            host.exit(run, code)
        }
    }

    @Test
    fun codexProbe() {
        val slot = slot(Executor.CODEX, "/root/.codex-slot-1")
        answer("Logged in using ChatGPT", 0)
        val run = ids.next(IdKinds.SESSION)
        probe.start(run, state.slots.getValue(slot))
        val spec = host.spec(run)!!
        assertEquals(listOf("codex", "login", "status"), spec.command)
        assertEquals("/root/.codex-slot-1", spec.setEnv[SlotEnvironment.CODEX_HOME])
        assertTrue("OPENAI_API_KEY" in spec.unsetEnv)
        val status = probe.poll(run).valueOrNull()!!
        state = Accounts.applyAll(state, accounts.observeAuthStatus(state, slot, status, clock.now()).valueOrNull()!!)
        assertEquals(AccountState.AVAILABLE, state.slots.getValue(slot).state)
    }

    @Test
    fun geminiProbe() {
        val slot = slot(Executor.GEMINI, "/root/.gemini-slot-1")
        answer("selectedType=oauth-personal\ncreds=no", 0)
        val run = ids.next(IdKinds.SESSION)
        probe.start(run, state.slots.getValue(slot))
        val spec = host.spec(run)!!
        assertEquals(listOf("sh", "-c", SlotAuthProbe.GEMINI_PROBE), spec.command)
        assertEquals("/root/.gemini-slot-1", spec.setEnv[SlotEnvironment.GEMINI_HOME])
        assertTrue("GEMINI_API_KEY" in spec.unsetEnv)
        val status = probe.poll(run).valueOrNull()!!
        state = Accounts.applyAll(state, accounts.observeAuthStatus(state, slot, status, clock.now()).valueOrNull()!!)
        assertEquals(AccountState.AUTH_REQUIRED, state.slots.getValue(slot).state, "без файла входа — вход нужен")
    }

    @Test
    fun missingCliIsNamed() {
        val slot = slot(Executor.CODEX, "/root/.codex-slot-2")
        host.onStart = { run, _, _ -> host.exit(run, null, com.dpsmpx.dpai.execution.agent.ExitReason.START_FAILED) }
        val run = ids.next(IdKinds.SESSION)
        probe.start(run, state.slots.getValue(slot))
        val e = assertIs<Outcome.Failure>(probe.poll(run)).errors.single()
        assertEquals(DiagCode.PROCESS_START_FAILED, e.code)
        assertTrue("Codex" in e.message)
    }

    @Test
    fun reviewVerdictFromText() {
        val text = "Посмотрел изменения.\n\n```json\n{\"findings\": [{\"severity\": \"MAJOR\", \"title\": \"нет проверки\", \"file\": \"a.kt\", \"line\": 3, \"detail\": \"…\"}]}\n```\n"
        val v = assertIs<JsonObject>(Review.structuredFromText(text))
        assertEquals(1, (v["findings"] as JsonArray).items.size)
        assertIs<JsonObject>(Review.structuredFromText("Итог: {\"findings\": []} — находок нет."))
        assertNull(Review.structuredFromText("находок нет"))
        assertNull(Review.structuredFromText(null))
    }
}
