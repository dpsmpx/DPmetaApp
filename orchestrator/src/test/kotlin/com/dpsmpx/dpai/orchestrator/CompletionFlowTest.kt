package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.orchestrator.verification.CompletionFlow
import com.dpsmpx.dpai.orchestrator.verification.ProcessStepRunner
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskEvent
import com.dpsmpx.dpai.tasks.Tasks
import com.dpsmpx.dpai.tasks.TasksState
import com.dpsmpx.dpai.verification.Baseline
import com.dpsmpx.dpai.verification.StepResult
import com.dpsmpx.dpai.verification.Verdict
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Проверка завершения (критерии этапа 10; D-23): «готово» — сразу проверка
 * настоящими командами в рабочем дереве; без проверки — не `DONE`.
 */
class CompletionFlowTest {
    @TempDir
    lateinit var dir: Path

    private val clock = Clock.SYSTEM
    private val ids = IdGenerator(clock, RandomSource.seeded(71))
    private val tasks = Tasks()
    private var state = TasksState.EMPTY
    private val project = ids.next(IdKinds.PROJECT)
    private val flow = CompletionFlow(tasks, Verifier(ProcessStepRunner(LocalProcessHost()), clock), null, clock)

    private val java = File(File(System.getProperty("java.home"), "bin"), if (File.separatorChar == '\\') "java.exe" else "java").path
    private val pass = VerifyStep("версия", listOf(java, "-version"))
    private val fail = VerifyStep("тесты", listOf(java, "-cp", ".", "NoSuchMainClass"))

    private fun ok(out: Outcome<List<TaskEvent>>) {
        state = Tasks.applyAll(state, assertIs<Outcome.Success<List<TaskEvent>>>(out, out.diagnostics.toString()).value)
    }

    /** Задача, которую сессия «выполнила»: в `RUNNING`. */
    private fun running(): TaskId {
        val t = ids.next(IdKinds.TASK)
        ok(tasks.create(state, t, "задача", "цель", Priority.NORMAL, clock.now()))
        ok(tasks.enqueue(state, t, clock.now()))
        ok(Outcome.success(tasks.promote(state, clock.now())))
        ok(tasks.start(state, t, clock.now()))
        return t
    }

    private fun claimDone(t: TaskId, profile: VerificationProfile?, baseline: Baseline? = null) =
        flow.onClaimedDone(state, t, profile, VerifyLevel.NORMAL, dir.toString(), baseline).also { r ->
            state = Tasks.applyAll(state, assertIs<Outcome.Success<CompletionFlow.Result>>(r).value.events)
        }.valueOrNull()!!.report

    @Test
    fun claimedDoneIsVerifiedAutomatically() {
        val t = running()
        val report = claimDone(t, VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(pass))))
        assertEquals(Verdict.PASSED, report.verdict)
        assertEquals(TaskState.VERIFIED, state.tasks.getValue(t).state)
        assertEquals(report.hash.toString(), state.tasks.getValue(t).verification!!.reportHash)
        assertEquals(0, report.steps.single().exitCode)
        ok(tasks.complete(state, t, clock.now()))
        assertEquals(TaskState.DONE, state.tasks.getValue(t).state)
        assertTrue("✓ версия" in report.render())
    }

    @Test
    fun failedCheckSendsTaskBackToWork() {
        val t = running()
        val report = claimDone(t, VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(pass, fail))))
        assertEquals(Verdict.FAILED, report.verdict)
        assertEquals(TaskState.READY, state.tasks.getValue(t).state)
        assertEquals(DiagCode.INVALID_TRANSITION, (tasks.complete(state, t, clock.now()) as Outcome.Failure).errors.single().code)
    }

    @Test
    fun withoutVerificationNoDone() {
        val t = running()
        val report = claimDone(t, profile = null)
        assertEquals(Verdict.INCOMPLETE, report.verdict)
        assertEquals(TaskState.COMPLETION_UNVERIFIED, state.tasks.getValue(t).state)
        assertEquals(DiagCode.VERIFICATION_REQUIRED, (tasks.complete(state, t, clock.now()) as Outcome.Failure).errors.single().code)
        // Обходной путь мимо complete тоже закрыт.
        assertEquals(DiagCode.VERIFICATION_REQUIRED, (tasks.move(state, t, TaskState.DONE, clock.now()) as Outcome.Failure).errors.single().code)
        ok(tasks.complete(state, t, clock.now(), waiver = "автор" to "проект без тестов, проверено вручную"))
        assertEquals(TaskState.DONE, state.tasks.getValue(t).state)
        assertEquals("WAIVED", state.tasks.getValue(t).verification!!.verdict)
    }

    @Test
    fun unavailableStepIsIncompleteNotPassed() {
        val t = running()
        val missing = VerifyStep("линтер", listOf("dpai-no-such-linter"))
        val report = claimDone(t, VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(pass, missing))))
        assertEquals(Verdict.INCOMPLETE, report.verdict)
        assertEquals(StepResult.UNAVAILABLE, report.steps.last().result)
        assertEquals(TaskState.COMPLETION_UNVERIFIED, state.tasks.getValue(t).state)
    }

    @Test
    fun preExistingFailureIsNotBlamed() {
        val t = running()
        val profile = VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(pass, fail)))
        val baseline = Verifier(ProcessStepRunner(LocalProcessHost()), clock).baseline(profile, VerifyLevel.NORMAL, dir.toString(), "base")
        assertEquals(setOf("тесты"), baseline.failing)
        val report = claimDone(t, profile, baseline)
        assertEquals(Verdict.PASSED, report.verdict)
        assertEquals(StepResult.PRE_EXISTING, report.steps.last().result)
        assertTrue(report.diagnostics.any { it.code == DiagCode.VERIFICATION_BASELINE_FAILURE }, "красное до правки названо")
    }
}
