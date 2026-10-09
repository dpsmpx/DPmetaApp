package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.OutputStream
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.target.ExecutionTarget
import com.dpsmpx.dpai.execution.target.ExecutionTargetManager
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.execution.target.TargetType
import com.dpsmpx.dpai.orchestrator.verification.AgentStepRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Шаг проверки через агента target (D-31): каталог — в спецификации, вывод — из журнала агента. */
class AgentStepRunnerTest {
    private val clock = Clock.SYSTEM
    private val ids = IdGenerator(clock, RandomSource.seeded(71))

    @Test
    fun stepRunsInWorktreeAndReturnsOutputAndCode() {
        val host = MockAgentHost(clock, ids)
        host.onStart = { run, spec, _ ->
            host.output(run, "в каталоге ${spec.workDir}")
            host.output(run, "предупреждение", OutputStream.STDERR)
            host.exit(run, 3)
        }
        val r = AgentStepRunner(AgentClient(host, listOf("dpai"), null, "target mock"), ids).run(listOf("make", "test"), "/w/task-1", 30.seconds)
        val done = r.valueOrNull()!!
        assertEquals(3, done.exitCode)
        assertEquals(listOf("в каталоге /w/task-1"), done.stdout)
        assertEquals(listOf("предупреждение"), done.stderr)
    }

    @Test
    fun hangingStepTimesOut() {
        val host = MockAgentHost(clock, ids)
        host.onStart = { _, _, _ -> }
        val r = AgentStepRunner(AgentClient(host, listOf("dpai"), null, "target mock"), ids, pollEvery = 10.milliseconds).run(listOf("sleep", "inf"), "/w", 100.milliseconds)
        assertEquals(DiagCode.PROCESS_TIMEOUT, (r as Outcome.Failure).diagnostics.first().code)
    }

    /**
     * Ненадёжный SSH (Redmi Note 13, 9 октября 2026): отказы вызовов агента
     * не превращают прошедший шаг в «недоступен» — опрос и чтение вывода
     * повторяются; потерянный ответ на запуск не запускает шаг второй раз.
     */
    @Test
    fun transientAgentCallFailuresAreRetried() {
        val mock = MockAgentHost(clock, ids)
        var starts = 0
        mock.onStart = { run, _, _ ->
            starts++
            mock.output(run, "Ran 5 tests")
            mock.exit(run, 0)
        }
        var call = 0
        val failOn = setOf(1, 2, 4, 5)
        val flaky = object : ProcessHost {
            override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
                call++
                // Первый вызов (start) дошёл до агента, а ответ потерян.
                if (call == 1) mock.start(spec, onOutput)
                return if (call in failOn) Outcome.failure(DiagCode.SSH_CONNECTION_FAILED, "Connection reset", "ssh") else mock.start(spec, onOutput)
            }
        }
        val retried = mutableListOf<DiagCode>()
        val r = AgentStepRunner(AgentClient(flaky, listOf("dpai"), null, "target mock"), ids, pollEvery = 1.milliseconds, backoff = 1.milliseconds, onRetry = { retried += it.code })
            .run(listOf("python", "-m", "unittest"), "/w", 30.seconds)
        val done = r.valueOrNull()
        assertEquals(0, done?.exitCode, r.diagnostics.toString())
        assertEquals(listOf("Ran 5 tests"), done!!.stdout)
        assertEquals(1, starts, "шаг запущен один раз")
        assertEquals(List(3) { DiagCode.SSH_CONNECTION_FAILED }, retried, "каждый повтор — с причиной наружу")
    }

    @Test
    fun persistentFailureStillReportsCause() {
        val flaky = object : ProcessHost {
            override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> =
                Outcome.failure(DiagCode.SSH_CONNECTION_FAILED, "Connection refused", "ssh")
        }
        val r = AgentStepRunner(AgentClient(flaky, listOf("dpai"), null, "target mock"), ids, pollEvery = 1.milliseconds, backoff = 1.milliseconds)
            .run(listOf("python"), "/w", 30.seconds)
        assertEquals(DiagCode.SSH_CONNECTION_FAILED, (r as Outcome.Failure).diagnostics.first().code)
    }

    /** На настоящем POSIX target по SSH: шаг в заданном каталоге, код и потоки — как у процесса. */
    @Test
    fun stepOnRealTarget() {
        val spec = System.getProperty("dpai.test.ssh")
        assumeTrue(spec != null && spec.split(":")[2] == "posix", "dpai.test.ssh (posix) не задан")
        val (userHost, port, _, agent) = spec!!.split(":", limit = 4)
        val endpoint = SshEndpoint.of(
            userHost.substringAfter('@'), userHost.substringBefore('@'), port.toInt(),
            System.getProperty("dpai.test.ssh.identity"), System.getProperty("dpai.test.ssh.knownHosts"),
        ).valueOrNull()!!
        val manager = ExecutionTargetManager(clock, local = LocalProcessHost())
        val target = ExecutionTarget(ids.next(IdKinds.TARGET), TargetType.LINUX_SSH, "ssh", TargetPlatform.POSIX, endpoint, listOf(agent))
        manager.register(target)
        val r = AgentStepRunner(manager.client(target.id).valueOrNull()!!, ids).run(listOf("sh", "-c", "pwd; echo err >&2; exit 3"), "/tmp", 60.seconds)
        val done = r.valueOrNull()!!
        assertEquals(3, done.exitCode, r.diagnostics.toString())
        assertEquals(listOf("/tmp"), done.stdout)
        assertEquals(listOf("err"), done.stderr)
    }
}
