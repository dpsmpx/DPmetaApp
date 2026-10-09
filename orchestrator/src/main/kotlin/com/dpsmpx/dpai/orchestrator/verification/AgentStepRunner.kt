package com.dpsmpx.dpai.orchestrator.verification

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Retryability
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.RunSpec
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.verification.StepRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Шаг проверки на удалённом target — через его агента (D-31): по SSH
 * рабочий каталог не передаётся (`SshProcessHost`, `JschProcessHost`), а
 * агент запускает процесс в нём (`RunSpec.workDir`) и пишет вывод в свой
 * журнал. Вызов ждёт выхода, опрашивая агента раз в [pollEvery]; тайм-аут
 * — остановка запуска и `PROCESS_TIMEOUT`, как у локального шага.
 *
 * Каждый вызов агента — отдельное SSH-подключение и запуск агента; на
 * Redmi Note 13 (9 октября 2026) шаг `python -m unittest` прошёл на target
 * с кодом 0, а проверка вернула `VERIFICATION_STEP_UNAVAILABLE`, повторная
 * — прошла. Поэтому повторяемый отказ вызова (D-05: класс повтора не
 * `NON_RETRYABLE`) повторяется до [attempts] раз подряд с растущей паузой,
 * в пределах срока шага; отказ запуска сперва сверяется с состоянием —
 * запуск мог дойти до агента, второй раз шаг не запускается.
 */
class AgentStepRunner(
    private val agent: AgentClient,
    private val ids: IdGenerator,
    private val clock: Clock = Clock.SYSTEM,
    private val pollEvery: Duration = 500.milliseconds,
    private val attempts: Int = 5,
    private val backoff: Duration = 1.seconds,
) : StepRunner {
    private val pace = CountDownLatch(1)

    override fun run(command: List<String>, worktree: String, timeout: Duration): Outcome<Completed> {
        val run = ids.next(IdKinds.SESSION)
        val deadline = clock.monotonicNanos() + (timeout + GRACE).inWholeNanoseconds
        val started = agent.start(run, RunSpec(command, workDir = worktree, timeout = timeout))
        if (started is Outcome.Failure) {
            // Запуск мог дойти до агента, а ответ — потеряться: есть запуск — дальше опрос, нет — повтор запуска.
            val known = retrying(deadline) { agent.status(run) }
            if (known is Outcome.Failure) {
                if (!retryable(started) || !retryable(known)) return started
                val again = retrying(deadline) { agent.start(run, RunSpec(command, workDir = worktree, timeout = timeout)) }
                if (again is Outcome.Failure) return again
            }
        }
        while (true) {
            val status = when (val s = retrying(deadline) { agent.status(run) }) {
                is Outcome.Failure -> return s
                is Outcome.Success -> s.value
            }
            if (status.finished) {
                if (status.state == RunState.LOST) return Outcome.failure(DiagCode.AGENT_RUN_LOST, "шаг проверки потерян на target", "run $run")
                val code = status.exitCode
                    ?: return Outcome.failure(DiagCode.PROCESS_TIMEOUT, "${command.first()}: не завершился за $timeout, остановлен агентом", "run $run")
                return collect(run, code)
            }
            if (clock.monotonicNanos() >= deadline) {
                agent.stop(run)
                return Outcome.failure(DiagCode.PROCESS_TIMEOUT, "${command.first()}: не завершился за $timeout, остановлен", "run $run")
            }
            pace.await(pollEvery.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
    }

    /** Вызов агента с повтором повторяемых отказов: до [attempts] раз, пауза растёт вдвое, не дольше срока. */
    private fun <T> retrying(deadline: Long, call: () -> Outcome<T>): Outcome<T> {
        var wait = backoff
        var r = call()
        repeat(attempts - 1) {
            if (r !is Outcome.Failure || !retryable(r) || clock.monotonicNanos() + wait.inWholeNanoseconds >= deadline) return r
            pace.await(wait.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            wait *= 2
            r = call()
        }
        return r
    }

    private fun retryable(r: Outcome<*>): Boolean = r.errors.none { it.retryability == Retryability.NON_RETRYABLE }

    private fun collect(run: com.dpsmpx.dpai.core.ids.SessionId, code: Int): Outcome<Completed> {
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        var after = 0L
        while (true) {
            val batch = when (val e = retrying(Long.MAX_VALUE) { agent.events(run, after, 1000) }) {
                is Outcome.Failure -> return e
                is Outcome.Success -> e.value.events
            }
            if (batch.isEmpty()) break
            for (e in batch.filter { it.type == EventType.TARGET_PROCESS_OUTPUT }) {
                val text = (e.payload["text"] as? JsonString)?.value.orEmpty()
                if ((e.payload["stream"] as? JsonString)?.value == "stderr") err += text else out += text
            }
            after = batch.last().sequence
        }
        return Outcome.success(Completed(code, out, err))
    }

    companion object {
        /** Запас сверх тайм-аута шага: агент сам останавливает процесс по тайм-ауту спецификации. */
        private val GRACE = 10.seconds
    }
}
