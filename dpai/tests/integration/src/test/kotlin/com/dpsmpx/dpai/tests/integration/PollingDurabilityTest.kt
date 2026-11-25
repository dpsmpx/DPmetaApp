package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.claude.ClaudeRunner
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.claude.RunnerState
import com.dpsmpx.dpai.execution.claude.RunnerUpdate
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.orchestrator.sessions.SessionsProjection
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.snapshot.StateRecovery
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Группируются наблюдения, а не внешние действия; fsync остаётся границей полномочий (D-24). */
class PollingDurabilityTest {
    @TempDir lateinit var root: Path

    private class Counting(private val disk: NioVfs) : Vfs by disk {
        var writes = 0
        var fail = false
        override fun appendDurably(path: String, bytes: ByteArray) {
            if (fail) throw IOException("проверяем отказ диска")
            disk.appendDurably(path, bytes)
            writes++
        }
    }

    private class Harness(root: Path) {
        val clock = ManualClock()
        val ids = IdGenerator(clock, RandomSource.seeded(223))
        val disk = Counting(NioVfs(root))
        val journal = EventJournal.open(disk, "data/journal.log").valueOrNull()!!
        val mock = MockRunner.create(clock, RandomSource.seeded(224)) { _, _ -> MockScenario.SLOW_OUTPUT }
        var afterRead: (SessionId) -> Unit = {}
        var beforeStop: () -> Unit = {}
        var verifications = 0
        val o: Orchestrator
        init {
            val target = ids.next(IdKinds.TARGET)
            val logic = Accounts()
            var accounts = AccountsState.EMPTY
            val sub = ids.next(IdKinds.SUBSCRIPTION)
            accounts = Accounts.applyAll(accounts, logic.registerSubscription(accounts, sub, "тест", clock.now(), maxParallelSessions = 8).valueOrNull()!!)
            repeat(8) { index ->
                val slot = ids.next(IdKinds.ACCOUNT)
                accounts = Accounts.applyAll(accounts, logic.registerSlot(accounts, slot, sub, target, "/slots/$index", clock.now()).valueOrNull()!!)
                accounts = Accounts.applyAll(accounts, logic.observeAuthStatus(accounts, slot,
                    AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "user@example.org"), "max"), clock.now()).valueOrNull()!!)
            }
            val runner = object : ClaudeRunner by mock {
                override fun stream(session: SessionId): Outcome<List<RunnerUpdate>> = mock.stream(session).also { afterRead(session) }
                override fun cancel(session: SessionId): Outcome<RunnerState> { beforeStop(); return mock.cancel(session) }
                override fun terminate(session: SessionId): Outcome<RunnerState> { beforeStop(); return mock.terminate(session) }
            }
            val project = ids.next(IdKinds.PROJECT)
            val profile = VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("проверка", listOf("check")))))
            o = Orchestrator(clock, ids, RandomSource.seeded(225), journal, runner,
                ProjectConfig(project, null, root.toString(), profile), listOf(TargetSlot(target, emptySet(), 8)), null,
                Verifier({ _, _, _ -> verifications++; Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), accounts)
            repeat(8) { assertTrue(o.addTask("задача $it", "цель $it").isSuccess) }
            o.tick()
            assertEquals(8, o.sessions.sessions.size)
        }
        fun advance() = o.sessions.sessions.keys.forEach { mock.advance(it) }
    }

    @Test fun nonterminalObservationsUseFewerFlushesAndReplayExactly() {
        val h = Harness(root)
        h.advance()
        val before = h.disk.writes
        val position = h.journal.position
        h.o.tick()
        assertTrue(h.journal.position > position, "опрос действительно записал новые события")
        assertTrue(h.disk.writes - before < h.o.sessions.sessions.size, "наблюдения объединены без удаления событий")
        val replayed = StateRecovery(SessionsProjection).replayAll(h.journal)
        assertEquals(h.o.sessions, replayed)
    }

    @Test fun acceptedCommandInterruptsTheGroupAfterDurableObservation() {
        val h = Harness(root); h.advance()
        val sessions = h.o.sessions.sessions.values.sortedBy { it.id.value }
        val first = sessions.first().id; val victim = sessions.last().task
        var expected = 0L; var stopped = false; var queued = false
        h.afterRead = { id -> if (!queued && id == first) {
            queued = true; expected = h.mock.position(first)!!
            assertTrue(h.o.accept(Command.Cancel(victim)).isSuccess)
        } }
        h.beforeStop = {
            stopped = true
            val persisted = StateRecovery(SessionsProjection).replayAll(h.journal)
            assertTrue(persisted.sessions.getValue(first).agentSequence >= expected,
                "отмена target не опережает запись предыдущего наблюдения")
        }
        h.o.tick()
        assertTrue(queued && stopped)
        assertEquals(TaskState.CANCELLED, h.o.tasks.tasks.getValue(victim).state)
        assertTrue(h.o.invariants().isEmpty())
    }

    @Test fun failedObservationFlushPreventsVerificationAndDone() {
        val h = Harness(root)
        h.o.sessions.sessions.keys.forEach { while (h.mock.advance(it)) continue }
        h.disk.fail = true
        assertFailsWith<IllegalStateException> { h.o.tick() }
        assertTrue(h.journal.isBroken)
        assertEquals(0, h.verifications)
        assertTrue(h.o.tasks.tasks.values.none { it.state == TaskState.DONE })
        h.o.tick()
        assertEquals(0, h.verifications, "сломанный журнал не разрешает следующий эффект")
    }
}
