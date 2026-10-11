package com.dpsmpx.dpai.orchestrator.verification

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.orchestrator.git.GitService
import com.dpsmpx.dpai.tasks.TaskEvent
import com.dpsmpx.dpai.tasks.Tasks
import com.dpsmpx.dpai.tasks.TasksState
import com.dpsmpx.dpai.verification.Baseline
import com.dpsmpx.dpai.verification.StepRunner
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.VerificationReport
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import kotlin.time.Duration

/** Шаг проверки как процесс в рабочем дереве (`execution`, D-03). */
class ProcessStepRunner(private val host: ProcessHost) : StepRunner {
    override fun run(command: List<String>, worktree: String, timeout: Duration): Outcome<Completed> =
        host.run(ProcessSpec(command, workDir = worktree), timeout)
}

/**
 * Завершение задачи (D-23; ТЗ, §32, §139, Phase 10): «готово» от Claude —
 * `IMPLEMENTED`, затем сразу проверка по профилю проекта в рабочем дереве
 * задачи и итог — `VERIFIED`, `READY` (не прошла) или
 * `COMPLETION_UNVERIFIED` (проверить не удалось). `DONE` — отдельный шаг
 * [Tasks.complete].
 */
class CompletionFlow(private val tasks: Tasks, private val verifier: Verifier, private val git: GitService?, private val clock: Clock) {
    data class Result(val events: List<TaskEvent>, val report: VerificationReport)

    fun onClaimedDone(
        state: TasksState,
        task: TaskId,
        profile: VerificationProfile?,
        level: VerifyLevel,
        worktree: String,
        baseline: Baseline? = null,
    ): Outcome<Result> {
        val events = mutableListOf<TaskEvent>()
        var s = state
        if (s.tasks[task]?.state == TaskState.RUNNING) {
            when (val r = tasks.implemented(s, task, clock.now())) {
                is Outcome.Failure -> return r
                is Outcome.Success -> { events += r.value; s = Tasks.applyAll(s, r.value) }
            }
        }
        val commit = git?.status(worktree)?.valueOrNull()?.head
        val report = verifier.verify(profile, level, worktree, commit, baseline)
        return when (val r = tasks.recordVerification(s, task, report.verdict.name, report.hash.toString(), clock.now())) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(Result(events + r.value, report), report.diagnostics.filterNot { it.isError })
        }
    }
}
