package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.claude.ClaudeCodeCli
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.ClaudeRunner
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.claude.RunnerUpdate
import com.dpsmpx.dpai.execution.claude.TurnStatus
import java.io.File
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `ClaudeRunner` на `MockRunner` (критерии этапа 4): синтетический сценарий
 * проходит от начала до конца; каждый сценарий ТЗ, §89 даёт ожидаемое
 * состояние и диагностику; адаптер вызывает только документированные флаги.
 */
class MockRunnerTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(9))

    private fun runner(vararg turns: MockScenario): MockRunner =
        MockRunner.create(clock, RandomSource.seeded(1)) { _, turn -> turns[(turn - 1).coerceAtMost(turns.size - 1)] }

    private fun drain(r: ClaudeRunner, s: SessionId): List<RunnerUpdate> = r.stream(s).valueOrNull()!!

    private fun finished(updates: List<RunnerUpdate>) = updates.filterIsInstance<RunnerUpdate.TurnFinished>().single().result

    @Test
    fun syntheticSessionEndToEnd() {
        val r = runner(MockScenario.SUCCESS, MockScenario.SUCCESS)
        val s = ids.next(IdKinds.SESSION)
        val started = r.start(s, ClaudeRequest("сделай задачу"))
        assertIs<Outcome.Success<*>>(started)
        val first = drain(r, s)
        assertIs<RunnerUpdate.TurnStarted>(first.first())
        assertEquals(TurnStatus.COMPLETED, finished(first).status)
        // Продолжение — новый ход той же сессии провайдера, номера событий подряд.
        assertTrue(r.send(s, ClaudeRequest("дальше")).isSuccess)
        val second = drain(r, s)
        assertEquals(2, (second.first() as RunnerUpdate.TurnStarted).turn)
        assertEquals(TurnStatus.COMPLETED, finished(second).status)
        val state = r.inspect(s).valueOrNull()!!
        assertEquals(2, state.turn)
        assertEquals(started.valueOrNull()!!.provider, state.provider, "providerSessionId один на сессию (D-08)")
    }

    private data class Expect(val status: TurnStatus, val code: DiagCode?)

    @Test
    fun everyScenarioGivesExpectedStateAndDiagnostic() {
        val expected = mapOf(
            MockScenario.SUCCESS to Expect(TurnStatus.COMPLETED, null),
            MockScenario.FAILURE to Expect(TurnStatus.FAILED, DiagCode.CLAUDE_RESULT_ERROR),
            MockScenario.TIMEOUT to Expect(TurnStatus.INCOMPLETE, DiagCode.CLAUDE_STREAM_INCOMPLETE),
            MockScenario.CRASH to Expect(TurnStatus.FAILED, DiagCode.CLAUDE_STREAM_INCOMPLETE),
            MockScenario.SLOW_OUTPUT to Expect(TurnStatus.COMPLETED, null),
            MockScenario.DUPLICATE_EVENTS to Expect(TurnStatus.COMPLETED, DiagCode.EVENT_DUPLICATE),
            MockScenario.MISSING_EVENTS to Expect(TurnStatus.COMPLETED, null),
            MockScenario.RATE_LIMIT to Expect(TurnStatus.FAILED, DiagCode.CLAUDE_RATE_LIMITED),
            MockScenario.AUTH_ERROR to Expect(TurnStatus.FAILED, DiagCode.CLAUDE_AUTH_REQUIRED),
            MockScenario.PARTIAL_COMPLETION to Expect(TurnStatus.INCOMPLETE, DiagCode.CLAUDE_MAX_TURNS),
        )
        assertEquals(MockScenario.entries.toSet(), expected.keys, "у каждого сценария ТЗ, §89 — ожидание")
        for ((scenario, want) in expected) {
            val r = runner(scenario)
            val s = ids.next(IdKinds.SESSION)
            r.start(s, ClaudeRequest("x"))
            val updates = mutableListOf<RunnerUpdate>()
            var polls = 0
            while (updates.none { it is RunnerUpdate.TurnFinished }) {
                updates += drain(r, s)
                if (updates.none { it is RunnerUpdate.TurnFinished }) assertTrue(r.advance(s), "$scenario: поток встал")
                polls++
            }
            val t = finished(updates)
            assertEquals(want.status, t.status, "$scenario: ${t.diagnostics}")
            want.code?.let { assertTrue(t.diagnostics.any { d -> d.code == it }, "$scenario: нет $it в ${t.diagnostics}") }
            if (scenario == MockScenario.SLOW_OUTPUT) assertTrue(polls > 3, "медленный вывод приходит частями")
        }
    }

    @Test
    fun missingEventIsReconciledNotSkipped() {
        val r = runner(MockScenario.MISSING_EVENTS)
        val s = ids.next(IdKinds.SESSION)
        r.start(s, ClaudeRequest("x"))
        val updates = r.stream(s)
        assertTrue(updates.warnings.any { it.code == DiagCode.EVENT_SEQUENCE_GAP }, "пропуск замечен")
        val provider = updates.valueOrNull()!!.filterIsInstance<RunnerUpdate.Provider>()
        assertEquals(provider.map { it.sequence }.sorted(), provider.map { it.sequence }, "по порядку")
        assertEquals(TurnStatus.COMPLETED, finished(updates.valueOrNull()!!).status)
    }

    @Test
    fun pauseStopsTheTurnAndResumeContinuesSameProviderSession() {
        val r = runner(MockScenario.SLOW_OUTPUT, MockScenario.SUCCESS)
        val s = ids.next(IdKinds.SESSION)
        val provider = r.start(s, ClaudeRequest("x")).valueOrNull()!!.provider
        drain(r, s)
        r.advance(s)
        assertEquals(DiagCode.RUNNER_TURN_ACTIVE, (r.send(s, ClaudeRequest("y")) as Outcome.Failure).errors.single().code)
        assertTrue(r.pause(s).isSuccess)
        assertEquals(TurnStatus.INCOMPLETE, finished(drain(r, s)).status)
        assertTrue(r.resume(s, ClaudeRequest("продолжай")).isSuccess)
        assertEquals(TurnStatus.COMPLETED, finished(drain(r, s)).status)
        assertEquals(provider, r.inspect(s).valueOrNull()!!.provider)
        assertTrue(r.terminate(s).isSuccess)
        assertIs<Outcome.Failure>(r.send(s, ClaudeRequest("после terminate")))
    }

    @Test
    fun adapterUsesOnlyDocumentedFlagsAndNoPromptInArguments() {
        val request = ClaudeRequest(
            prompt = "секретов тут нет, но prompt всё равно не в аргументах",
            model = "claude-opus-5-5", permissionMode = "acceptEdits", tools = listOf("Read", "Edit"),
            allowedTools = listOf("Read"), disallowedTools = listOf("Bash"), maxBudgetUsd = BigDecimal("1.50"),
            jsonSchema = com.dpsmpx.dpai.core.serialization.JsonObject.of("type" to com.dpsmpx.dpai.core.serialization.JsonString("object")),
            addDirs = listOf("/w"), appendSystemPrompt = "правила",
        )
        val sid = ProviderSessionId.newUuid(RandomSource.seeded(2))
        val documented = documentedFlags()
        for (resume in listOf(false, true)) {
            val argv = ClaudeCodeCli.argv(listOf("claude"), request, sid, resume)
            val flags = ClaudeCodeCli.flagsOf(argv)
            assertTrue(documented.containsAll(flags), "недокументированные флаги: ${flags - documented}")
            assertTrue(request.prompt !in argv)
            assertTrue((if (resume) "--resume" else "--session-id") in argv)
        }
        assertTrue(documented.containsAll(ClaudeCodeCli.DOCUMENTED_FLAGS), "список адаптера шире исследования: ${ClaudeCodeCli.DOCUMENTED_FLAGS - documented}")
    }

    /** Флаги из таблиц `docs/research/CLAUDE_CODE_CLI.md` (§2 и §8). */
    private fun documentedFlags(): Set<String> {
        val root = System.getProperty("dpai.repoRoot") ?: error("нет dpai.repoRoot")
        val text = File(root, "docs/research/CLAUDE_CODE_CLI.md").readText()
        return text.lines().filter { it.startsWith("| `-") }.flatMap { row ->
            Regex("`(-{1,2}[A-Za-z][A-Za-z-]*)").findAll(row.split('|')[1]).map { it.groupValues[1] }.toList()
        }.toSet()
    }

    @Test
    fun uuidIsVersion4() {
        val id = ProviderSessionId.newUuid(RandomSource.seeded(5)).value
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id), id)
    }
}
