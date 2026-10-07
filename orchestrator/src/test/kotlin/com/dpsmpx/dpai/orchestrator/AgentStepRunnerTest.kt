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
