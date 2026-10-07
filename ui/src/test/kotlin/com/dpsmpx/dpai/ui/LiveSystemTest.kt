package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.accounts.SlotEnvironment
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.state.AccountState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

/** Контроллер приложения на mock target: вход слотов → задача → ход на своём target (D-35). */
class LiveSystemTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(77))
    private val host = MockAgentHost(clock, ids)
    private var loggedIn = true

    private fun entry(label: String, configDir: String?) =
        TargetForm.validate(ids.next(IdKinds.TARGET), label, "build.lan", "22", "dev", false, "/opt/dpai/bin/dpai", configDir).valueOrNull()!!

    init {
        host.onStart = { run, spec, _ ->
            if (spec.command.take(3) == listOf("claude", "auth", "status")) {
                host.output(run, if (loggedIn) """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty","subscriptionType":"pro"}""" else """{"loggedIn":false,"authMethod":"none"}""")
                host.exit(run, if (loggedIn) 0 else 1)
            } else {
                val c = spec.command
                val sid = c[c.indexOfFirst { it == "--session-id" || it == "--resume" } + 1]
                MockRunner.script(MockScenario.SUCCESS, sid).forEach { host.output(run, it) }
                host.exit(run, 0, ExitReason.EXITED)
            }
        }
    }

    private fun system(vararg entries: TargetEntry) =
        LiveSystem(clock, NioVfs(dir), entries.toList(), { _, _ -> host }, "/known_hosts", RandomSource.seeded(78))

    private fun steps(s: LiveSystem, n: Int) = repeat(n) {
        s.step()
        clock.advance(1.seconds)
    }

    @Test
    fun taskRunsOnLoggedInSlotWithItsConfigDir() {
        val t = entry("сервер", "/home/dev/.claude-dpai")
        val s = system(t, entry("старый", null))
        steps(s, 1)
        assertEquals(AccountState.AVAILABLE, s.orchestrator.accounts.slots.values.single().state)
        assertTrue(s.dashboard().attention.any { "старый" in it }, s.dashboard().attention.toString())
        val task = s.addTask("Проверка", "ответить одним словом").valueOrNull()!!
        steps(s, 4)
        val session = s.orchestrator.sessions.sessions.values.single { it.task == task }
        assertEquals(t.id, session.target)
        val turn = host.spec(session.id)!!
        assertEquals("/home/dev/.claude-dpai", turn.setEnv[SlotEnvironment.CONFIG_DIR])
        val state = s.orchestrator.tasks.tasks.getValue(task).state
        // Профиля проверки нет: ход завершён, но завершение не проверено (D-23).
        assertEquals(TaskState.COMPLETION_UNVERIFIED, state, s.problems.toString())
        assertTrue(s.session(session.id).timeline.isNotEmpty())
    }

    @Test
    fun slotWithoutLoginKeepsTasksWaiting() {
        loggedIn = false
        val s = system(entry("сервер", "/home/dev/.claude-dpai"))
        steps(s, 1)
        assertEquals(AccountState.AUTH_REQUIRED, s.orchestrator.accounts.slots.values.single().state)
        val task = s.addTask("Ждёт", "ждать входа").valueOrNull()!!
        steps(s, 3)
        assertTrue(s.orchestrator.sessions.sessions.values.none { it.task == task })
        loggedIn = true
        s.probeSlot(s.orchestrator.accounts.slots.values.single().targetId)
        steps(s, 4)
        assertTrue(s.orchestrator.sessions.sessions.values.any { it.task == task })
    }
}
