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
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.document.DocumentStore
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
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.io.TempDir

/**
 * Перезапуск контура посреди работы (ТЗ, §54, §117): аккаунты, переданные
 * при создании, в журнал не пишутся — журнал проигрывается поверх них.
 * Регрессия: стенд выносливости падал на первом перезапуске
 * (`NoSuchElementException` слота при проекции аренды).
 */
class RestartTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(54))
    private val host = MockAgentHost(clock, IdGenerator(clock, RandomSource.seeded(55)))
    private val targets = List(2) { TargetSlot(ids.next(IdKinds.TARGET), emptySet(), maxSessions = 2) }
    private val accounts: AccountsState = run {
        val logic = Accounts()
        var acc = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        acc = Accounts.applyAll(acc, logic.registerSubscription(acc, sub, "основная", clock.now(), maxParallelSessions = 8).valueOrNull()!!)
        for ((i, t) in targets.withIndex()) {
            val slot = ids.next(IdKinds.ACCOUNT)
            acc = Accounts.applyAll(acc, logic.registerSlot(acc, slot, sub, t.id, "/c/$i", clock.now()).valueOrNull()!!)
            val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
            acc = Accounts.applyAll(acc, logic.observeAuthStatus(acc, slot, status, clock.now()).valueOrNull()!!)
        }
        acc
    }
    private val project = ids.next(IdKinds.PROJECT)

    /** [segmented] — маленькие сегменты журнала и частые снимки (D-37): перезапуск — из снимка и хвоста. */
    private fun boot(segmented: Boolean = false): Pair<Orchestrator, MockRunner> {
        val vfs = NioVfs(dir)
        val journal = (EventJournal.open(vfs, "journal.log", segmentSize = if (segmented) 40 else EventJournal.NO_SEGMENTS) as Outcome.Success).value
        val snapshots = if (segmented) SnapshotStore(DocumentStore(vfs, "snapshots")) else null
        val runner = MockRunner.create(clock, RandomSource.seeded(clock.now().epochMillis), host) { _, _ -> MockScenario.SLOW_OUTPUT }
        val profile = VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("ок", listOf("ok")))))
        val o = Orchestrator(
            clock, ids, RandomSource.seeded(56), journal, runner, ProjectConfig(project, null, dir.toString(), profile), targets, null,
            Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), accounts,
            snapshots = snapshots, snapshotEvery = 30,
        )
        o.recover { AgentClient(host, listOf("dpai"), null, "target mock") }
        return o to runner
    }

    @Test
    fun restartMidWorkKeepsAccountsSessionsAndFinishes() {
        val (first, _) = boot()
        val tasks = (1..4).map { first.addTask("задача $it", "цель").valueOrNull()!! }
        first.tick()
        val leased = first.accounts.slots.values.sumOf { it.sessions.size }
        assertTrue(leased > 0, "слоты арендованы до перезапуска")

        val (second, runner) = boot()
        assertEquals(first.accounts, second.accounts, "аккаунты после перезапуска — те же")
        assertEquals(first.tasks, second.tasks, "задачи после перезапуска — те же")
        repeat(20) {
            for (s in host.runIds()) while (runner.advance(s)) continue
            second.tick()
            assertEquals(emptyList(), second.invariants(), "инварианты после перезапуска")
            clock.advance(1.minutes)
        }
        assertTrue(tasks.all { second.tasks.tasks.getValue(it).state == TaskState.DONE }, tasks.map { second.tasks.tasks.getValue(it).state }.toString())
    }

    /**
     * Журнал из многих сегментов и снимки (D-37): после перезапуска состояние
     * то же, нумерация потоков продолжается без пропусков, работа доходит до
     * конца; инварианты держатся на окне активного сегмента.
     */
    @Test
    fun restartFromSnapshotsAndSegmentedJournal() {
        val (first, firstRunner) = boot(segmented = true)
        val tasks = (1..6).map { first.addTask("задача $it", "цель").valueOrNull()!! }
        repeat(6) {
            first.tick()
            for (s in host.runIds()) if (firstRunner.advance(s)) firstRunner.advance(s)
            clock.advance(1.minutes)
        }
        assertTrue(dir.toFile().listFiles().orEmpty().count { it.name.startsWith("journal.log.seg-") } >= 2, "журнал разбит на сегменты")
        assertTrue(dir.resolve("snapshots").toFile().listFiles().orEmpty().isNotEmpty(), "снимки записаны")

        val (second, runner) = boot(segmented = true)
        assertEquals(first.accounts, second.accounts)
        // Работавшие сессии сверка переводит через RECOVERY_PENDING; завершённые — те же записи.
        assertEquals(first.sessions.sessions.keys, second.sessions.sessions.keys)
        for (r in first.sessions.sessions.values.filter { it.terminal }) assertEquals(r, second.sessions.sessions[r.id])
        assertEquals(first.tasks.tasks.keys, second.tasks.tasks.keys)
        repeat(30) {
            for (s in host.runIds()) while (runner.advance(s)) continue
            second.tick()
            assertEquals(emptyList(), second.invariants(), "инварианты после перезапуска из снимка")
            clock.advance(1.minutes)
        }
        assertTrue(tasks.all { second.tasks.tasks.getValue(it).state == TaskState.DONE }, tasks.map { second.tasks.tasks.getValue(it).state }.toString())
    }
}
