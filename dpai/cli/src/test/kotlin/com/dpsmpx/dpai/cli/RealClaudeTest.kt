package com.dpsmpx.dpai.cli

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.execution.claude.ClaudeCodeCliRunner
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.ClaudeTurn
import com.dpsmpx.dpai.execution.claude.RunnerUpdate
import com.dpsmpx.dpai.execution.claude.TurnStatus
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.target.ExecutionTarget
import com.dpsmpx.dpai.execution.target.ExecutionTargetManager
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.execution.target.TargetType
import com.dpsmpx.dpai.testkit.RealTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Настоящий Claude Code на target (критерии этапов 3, 4, 19): два хода
 * через агента по SSH — первый с `--session-id`, второй — `--resume`
 * той же сессии провайдера. Тратит лимит подписки, поэтому включается
 * только явно: `-Ddpai.test.claude=true` вместе с `dpai.test.ssh`
 * (`docs/TESTING.md`, §8). Вход в Claude Code на target делает человек
 * (D-27).
 */
class RealClaudeTest {
    @Test
    fun twoTurnsWithResumeOnRealTarget() {
        val spec = System.getProperty("dpai.test.ssh")
        assumeTrue(spec != null && System.getProperty("dpai.test.claude") == "true", "dpai.test.claude и dpai.test.ssh не заданы: ход Claude тратит лимит")
        val (userHost, port, platform, agent) = spec!!.split(":", limit = 4)
        val endpoint = SshEndpoint.of(
            userHost.substringAfter('@'), userHost.substringBefore('@'), port.toInt(),
            identityFile = System.getProperty("dpai.test.ssh.identity"), knownHostsFile = System.getProperty("dpai.test.ssh.knownHosts"),
        ).valueOrNull()!!
        val p = if (platform == "windows") TargetPlatform.WINDOWS else TargetPlatform.POSIX
        val random = RandomSource.secure()
        val ids = IdGenerator(Clock.SYSTEM, random)
        val manager = ExecutionTargetManager(Clock.SYSTEM, local = LocalProcessHost())
        val target = ExecutionTarget(ids.next(IdKinds.TARGET), if (p == TargetPlatform.WINDOWS) TargetType.WINDOWS_REMOTE else TargetType.LINUX_SSH, "claude", p, endpoint, listOf(agent))
        manager.register(target)
        val runner = ClaudeCodeCliRunner(manager.client(target.id).valueOrNull()!!, { ProviderSessionId.newUuid(random) })
        val session = ids.next(IdKinds.SESSION)

        fun finished(): ClaudeTurn = RealTime.await(5.minutes, "итог хода Claude") {
            runner.stream(session).valueOrNull()?.filterIsInstance<RunnerUpdate.TurnFinished>()?.lastOrNull()?.result
        }

        val started = runner.start(session, ClaudeRequest("Ответь ровно одним словом: готово", tools = emptyList()))
        assertTrue(started.isSuccess, started.diagnostics.toString())
        val first = finished()
        assertEquals(TurnStatus.COMPLETED, first.status, first.toString())
        assertTrue(first.text.orEmpty().lowercase().contains("готово"), first.text)

        val sent = runner.send(session, ClaudeRequest("Какое слово ты ответил в прошлый раз? Повтори его одним словом.", tools = emptyList()))
        assertTrue(sent.isSuccess, sent.diagnostics.toString())
        val second = finished()
        assertEquals(TurnStatus.COMPLETED, second.status, second.toString())
        assertEquals(first.providerSession, second.providerSession)
        assertTrue(second.text.orEmpty().lowercase().contains("готово"), second.text)
        runner.terminate(session)
    }
}
