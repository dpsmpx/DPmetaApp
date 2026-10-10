package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.orchestrator.sessions.SessionsProjection
import com.dpsmpx.dpai.orchestrator.sessions.sessionEnvelopes
import com.dpsmpx.dpai.sessions.SessionDriver
import com.dpsmpx.dpai.sessions.SessionEvent
import com.dpsmpx.dpai.sessions.Sessions
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.snapshot.SnapshotStore
import com.dpsmpx.dpai.storage.snapshot.StateRecovery
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Сессии переживают перезапуск: журнал и снимок на диске → то же состояние и та же история (D-12, D-24). */
class SessionsJournalTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun sessionsSurviveRestart() {
        val clock = ManualClock()
        val ids = IdGenerator(clock, RandomSource.seeded(8))
        val vfs = NioVfs(dir)
        val journal = (EventJournal.open(vfs, "data/journal.log") as Outcome.Success).value
        val recorder = EventRecorder(ids, clock)
        val sessions = Sessions()
        var state = SessionsState.EMPTY
        fun commit(events: List<SessionEvent>) {
            recorder.sessionEnvelopes(events).forEach { assertTrue(journal.append(it).isSuccess) }
            state = Sessions.applyAll(state, events)
        }
        val accountsLogic = Accounts()
        var accounts = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        accounts = Accounts.applyAll(accounts, accountsLogic.registerSubscription(accounts, sub, "основная", clock.now()).valueOrNull()!!)
        val slot = ids.next(IdKinds.ACCOUNT)
        val target = ids.next(IdKinds.TARGET)
        accounts = Accounts.applyAll(accounts, accountsLogic.registerSlot(accounts, slot, sub, target, "~/.claude", clock.now()).valueOrNull()!!)
        val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
        accounts = Accounts.applyAll(accounts, accountsLogic.observeAuthStatus(accounts, slot, status, clock.now()).valueOrNull()!!)
        val session = ids.next(IdKinds.SESSION)
        val task = ids.next(IdKinds.TASK)
        accounts = Accounts.applyAll(accounts, accountsLogic.lease(accounts, session, task, clock.now()).valueOrNull()!!.events)

        commit(sessions.create(state, accounts, session, "k", task, slot, target, ProviderSessionId.newUuid(RandomSource.seeded(1)), clock.now()).valueOrNull()!!.events)
        val runner = MockRunner.create(clock, RandomSource.seeded(2)) { _, _ -> MockScenario.SUCCESS }
        val driver = SessionDriver(sessions, runner, clock)
        commit(driver.launch(state, accounts, session, ClaudeRequest("x")).valueOrNull()!!.events)

        val recovery = StateRecovery(SessionsProjection)
        SnapshotStore(DocumentStore(vfs, "data")).save(recovery.snapshotOf(journal, state))
        commit(driver.poll(state, session).valueOrNull()!!.events)
        assertEquals(SessionState.COMPLETED, state.sessions.getValue(session).state)

        val reopened = (EventJournal.open(NioVfs(dir), "data/journal.log") as Outcome.Success).value
        val r = recovery.recover(reopened, SnapshotStore(DocumentStore(NioVfs(dir), "data"))).valueOrNull()!!
        assertEquals(state, r.state)
        assertTrue(r.fromSnapshot)
        assertEquals(state, recovery.replayAll(reopened))
        // История сессии — её поток событий без пропусков.
        val history = reopened.records().map { it.event }.filter { it.stream.id == session.value }
        assertEquals((1L..history.size).toList(), history.map { it.sequence })
    }
}
