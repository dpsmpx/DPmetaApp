package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.orchestrator.NeighbourLoad
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.io.TempDir

/**
 * Несколько рабочих проектов одновременно (D-179; `DPmetaApp:D-147`): контур на проект,
 * места на общем target делятся — вместе не больше `maxSessions`; освободилось место у
 * одного — его берёт другой; перезапуск одного контура не трогает ходы соседа.
 */
class NeighbourContoursTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(901))
    private val host = MockAgentHost(clock, IdGenerator(clock, RandomSource.seeded(902)))
    private val target = TargetSlot(ids.next(IdKinds.TARGET), emptySet(), maxSessions = 2)
    private val accounts: AccountsState = run {
        val logic = Accounts()
        var acc = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        acc = Accounts.applyAll(acc, logic.registerSubscription(acc, sub, "основная", clock.now(), maxParallelSessions = 8).valueOrNull()!!)
        val slot = ids.next(IdKinds.ACCOUNT)
        acc = Accounts.applyAll(acc, logic.registerSlot(acc, slot, sub, target.id, "/c/0", clock.now()).valueOrNull()!!)
        val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
        Accounts.applyAll(acc, logic.observeAuthStatus(acc, slot, status, clock.now()).valueOrNull()!!)
    }

    /** Контуры по имени проекта; соседи каждого — все прочие (как у оболочки, D-179). */
    private val contours = linkedMapOf<String, Orchestrator>()
    private val runners = linkedMapOf<String, MockRunner>()
    private val projects = mapOf("A" to ids.next(IdKinds.PROJECT), "B" to ids.next(IdKinds.PROJECT))
    private var scenario = MockScenario.SLOW_OUTPUT

    private fun live(o: Orchestrator) = o.sessions.sessions.values.filter { !it.terminal }

    private fun neighboursOf(name: String) = object : NeighbourLoad {
        override fun sessions(target: ExecutionTargetId): Int = contours.filterKeys { it != name }.values.sumOf { o -> live(o).count { it.target == target } }
        override fun total(): Int = contours.filterKeys { it != name }.values.sumOf { live(it).size }
    }

    private fun boot(name: String): Orchestrator {
        val vfs = NioVfs(dir.resolve(name).also { it.toFile().mkdirs() })
        val journal = (EventJournal.open(vfs, "journal.log") as Outcome.Success).value
        val runner = MockRunner.create(clock, RandomSource.seeded(clock.now().epochMillis + name.hashCode()), host) { _, _ -> scenario }
        val project = projects.getValue(name)
        val profile = VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("ок", listOf("ok")))))
        val o = Orchestrator(
            clock, ids, RandomSource.seeded(903 + name.hashCode().toLong()), journal, runner, ProjectConfig(project, null, dir.resolve(name).toString(), profile), listOf(target), null,
            Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), accounts,
            documents = DocumentStore(vfs, "docs"), neighbours = neighboursOf(name),
        )
        o.recover { AgentClient(host, listOf("dpai"), null, "target mock") }
        contours[name] = o
        runners[name] = runner
        return o
    }

    /** Такт оболочки: контуры по очереди, затем ходы имитатора. */
    private fun round() {
        for (o in contours.values) o.tick()
        for (s in host.runIds()) for (r in runners.values) while (r.advance(s)) continue
        for (o in contours.values) {
            o.tick()
            assertEquals(emptyList(), o.invariants())
        }
        clock.advance(1.minutes)
    }

    @Test
    fun projectsShareTargetPlacesAndDoNotTouchEachOther() {
        val a = boot("A")
        val b = boot("B")
        repeat(2) { a.addTask("A$it", "цель A$it").valueOrNull()!! }
        repeat(2) { b.addTask("B$it", "цель B$it").valueOrNull()!! }
        for (o in contours.values) o.tick()
        assertEquals(2, live(a).size, "первый контур занял оба места")
        assertEquals(0, live(b).size, "второй ждёт: мест на target нет, хотя у него своих сессий нет")
        // Перезапуск соседа (B) не трогает идущие ходы A: сверка — только по своим сессиям.
        contours.remove("B")
        val b2 = boot("B")
        assertTrue(live(a).all { it.state == SessionState.RUNNING }, live(a).toString())
        assertEquals(0, live(b2).size)
        // Ходы A доходят до конца — места переходят к B; вместе — никогда больше двух.
        scenario = MockScenario.SUCCESS
        var peak = 0
        repeat(12) {
            round()
            peak = maxOf(peak, contours.values.sumOf { live(it).size })
        }
        assertTrue(peak <= 2, "вместе не больше мест target: $peak")
        assertTrue(a.tasks.tasks.values.all { it.state == TaskState.DONE }, a.tasks.tasks.values.map { it.state }.toString())
        assertTrue(b2.tasks.tasks.values.all { it.state == TaskState.DONE }, b2.tasks.tasks.values.map { it.state }.toString())
    }
}
