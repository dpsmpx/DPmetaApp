package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.snapshot.SnapshotStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

/**
 * Хаос ТЗ, §117 на модельном времени (быстрый двойник `EnduranceTest`):
 * поток задач, перезапуски приложения и потеря target. Контур не должен
 * вставать: за каждое окно тактов задачи выполняются. Регрессия: прогон
 * выносливости 7 октября 2026 встал после четвёртого перезапуска.
 */
class ChaosLoopTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun loopKeepsProgressingThroughRestartsAndTargetLoss() {
        val clock = ManualClock()
        val random = RandomSource.seeded(116)
        val ids = IdGenerator(clock, RandomSource.seeded(117))
        val host = MockAgentHost(clock, IdGenerator(clock, RandomSource.seeded(118)))
        val targets = List(4) { TargetSlot(ids.next(IdKinds.TARGET), emptySet(), maxSessions = 2) }
        val logic = Accounts()
        var accounts = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        accounts = Accounts.applyAll(accounts, logic.registerSubscription(accounts, sub, "основная", clock.now(), maxParallelSessions = 64).valueOrNull()!!)
        for ((i, t) in targets.withIndex()) repeat(2) { k ->
            val slot = ids.next(IdKinds.ACCOUNT)
            accounts = Accounts.applyAll(accounts, logic.registerSlot(accounts, slot, sub, t.id, "/c/$i-$k", clock.now()).valueOrNull()!!)
            val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
            accounts = Accounts.applyAll(accounts, logic.observeAuthStatus(accounts, slot, status, clock.now()).valueOrNull()!!)
        }
        val project = ids.next(IdKinds.PROJECT)
        val profile = VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("ок", listOf("ok")))))
        fun boot(): Pair<Orchestrator, MockRunner> {
            val vfs = NioVfs(dir)
            val journal = (EventJournal.open(vfs, "journal.log", segmentSize = 2_000) as Outcome.Success).value
            val runner = MockRunner.create(clock, RandomSource.seeded(clock.now().epochMillis), host) { _, _ ->
                when (random.nextInt(40)) {
                    0 -> MockScenario.FAILURE
                    1 -> MockScenario.CRASH
                    2 -> MockScenario.PARTIAL_COMPLETION
                    else -> MockScenario.SLOW_OUTPUT
                }
            }
            val o = Orchestrator(
                clock, ids, random, journal, runner, ProjectConfig(project, null, dir.toString(), profile), targets, null,
                Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), accounts,
                snapshots = SnapshotStore(DocumentStore(vfs, "snapshots")), snapshotEvery = 1_500, documents = DocumentStore(vfs, "docs"),
            )
            o.recover { AgentClient(host, listOf("dpai"), null, "target mock") }
            return o to runner
        }
        var (o, runner) = boot()
        var created = 0
        var downUntil = -1
        val doneAt = mutableListOf<Int>()
        // Хаос чаще, чем у EnduranceTest (перезапуск ~раз в 400 тактов, потеря target на 30 — ~раз в 300): то же за минуту.
        val chaos = RandomSource.seeded(119)
        for (step in 0 until 6_000) {
            // BLOCKED ждёт человека и не занимает места потока работы.
            while (o.tasks.tasks.values.count { !it.terminal && it.state != TaskState.BLOCKED } < 20) o.addTask("задача ${created++}", "работа")
            for (s in host.runIds()) if (host.hasPending(s) && random.nextInt(4) == 0) runner.advance(s)
            o.tick()
            if (chaos.nextInt(400) == 0) boot().let { (a, b) -> o = a; runner = b }
            if (downUntil < 0 && chaos.nextInt(300) == 0) {
                host.reachable = false
                downUntil = step + 30
            }
            if (step == downUntil) {
                downUntil = -1
                host.reachable = true
                o.recover { AgentClient(host, listOf("dpai"), null, "target mock") }
            }
            clock.advance(1.seconds)
            if (step % 500 == 499) {
                doneAt += o.tasks.tasks.values.count { it.state == TaskState.DONE }
                assertEquals(emptyList(), o.invariants(), "инварианты, шаг $step")
                if (doneAt.size >= 2 && doneAt[doneAt.size - 1] <= doneAt[doneAt.size - 2]) {
                    val sessions = o.sessions.sessions.values.groupingBy { it.state }.eachCount()
                    val tasks = o.tasks.tasks.values.filter { !it.terminal }.groupingBy { it.state }.eachCount()
                    val slots = o.accounts.slots.values.map { "${it.state}:${it.sessions.size}" }
                    val leaked = o.accounts.slots.values.flatMap { it.sessions.keys }.filter { s -> o.sessions.sessions[s]?.terminal != false }
                    val stuck = o.tasks.tasks.values.filter { it.state == TaskState.RUNNING }.take(3).map { t ->
                        t.id.value + " " + o.sessions.sessions.values.filter { it.task == t.id }.sortedBy { it.createdAt.epochMillis }.map { "${it.state}/${it.lastReason}" }
                    }
                    error("STUCK $stuck; контур встал на шаге $step: DONE $doneAt; сессии $sessions; задачи $tasks; слоты $slots; аренды завершённых $leaked")
                }
            }
        }
        assertTrue(doneAt.last() > doneAt.first())
    }

    /**
     * Запуск при недоступном target (ТЗ, §117): сессия умирает уже при
     * старте — задача не остаётся RUNNING без живой сессии, а уходит на
     * повтор. Без перезапуска: регрессия дефекта, остановившего прогон
     * выносливости (до починки такие задачи копились, пока поток не вставал).
     */
    @Test
    fun startFailureDoesNotLeaveTaskRunning() {
        val clock = ManualClock()
        val ids = IdGenerator(clock, RandomSource.seeded(201))
        val host = MockAgentHost(clock, IdGenerator(clock, RandomSource.seeded(202)))
        val target = TargetSlot(ids.next(IdKinds.TARGET), emptySet(), maxSessions = 1)
        val logic = Accounts()
        var accounts = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        accounts = Accounts.applyAll(accounts, logic.registerSubscription(accounts, sub, "основная", clock.now()).valueOrNull()!!)
        val slot = ids.next(IdKinds.ACCOUNT)
        accounts = Accounts.applyAll(accounts, logic.registerSlot(accounts, slot, sub, target.id, "/c/0", clock.now()).valueOrNull()!!)
        val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
        accounts = Accounts.applyAll(accounts, logic.observeAuthStatus(accounts, slot, status, clock.now()).valueOrNull()!!)
        val runner = MockRunner.create(clock, RandomSource.seeded(203), host) { _, _ -> MockScenario.SUCCESS }
        val project = ids.next(IdKinds.PROJECT)
        val o = Orchestrator(
            clock, ids, RandomSource.seeded(204), (EventJournal.open(NioVfs(dir), "journal.log") as Outcome.Success).value, runner,
            ProjectConfig(project, null, dir.toString(), VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("ок", listOf("ok")))))),
            listOf(target), null, Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), accounts,
        )
        val t = o.addTask("задача", "работа").valueOrNull()!!
        host.reachable = false
        o.tick()
        val sessions = o.sessions.sessions.values.filter { it.task == t }
        assertTrue(sessions.isNotEmpty() && sessions.all { it.terminal }, sessions.map { it.state }.toString())
        assertTrue(o.tasks.tasks.getValue(t).state != TaskState.RUNNING, "задача RUNNING без живой сессии")
        host.reachable = true
        repeat(10) {
            clock.advance(1.seconds * 60)
            o.tick()
        }
        assertEquals(TaskState.DONE, o.tasks.tasks.getValue(t).state)
    }
}
