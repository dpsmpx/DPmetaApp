package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.accounts.AccountEvent
import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.accounts.LimitLevel
import com.dpsmpx.dpai.accounts.LimitWindow
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.accounts.AccountsProjection
import com.dpsmpx.dpai.orchestrator.accounts.accountEnvelopes
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.snapshot.SnapshotStore
import com.dpsmpx.dpai.storage.snapshot.StateRecovery
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import org.junit.jupiter.api.io.TempDir

/** Состояние аккаунтов переживает перезапуск: журнал на диске → то же состояние (D-12, D-24). */
class AccountsJournalTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun accountsStateSurvivesRestart() {
        val clock = ManualClock()
        val ids = IdGenerator(clock, RandomSource.seeded(5))
        val accounts = Accounts()
        val vfs = NioVfs(dir)
        val journal = (EventJournal.open(vfs, "data/journal.log") as Outcome.Success).value
        val recorder = EventRecorder(ids, clock)
        var state = AccountsState.EMPTY
        fun commit(events: List<AccountEvent>) {
            recorder.accountEnvelopes(events).forEach { assertTrue(journal.append(it).isSuccess) }
            state = Accounts.applyAll(state, events)
        }
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        commit(accounts.registerSubscription(state, sub, "основная", clock.now()).valueOrNull()!!)
        val slot = ids.next(IdKinds.ACCOUNT)
        commit(accounts.registerSlot(state, slot, sub, ids.next(IdKinds.TARGET), "~/.claude", clock.now()).valueOrNull()!!)
        val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
        commit(accounts.observeAuthStatus(state, slot, status, clock.now()).valueOrNull()!!)
        commit(accounts.lease(state, ids.next(IdKinds.SESSION), ids.next(IdKinds.TASK), clock.now()).valueOrNull()!!.events)

        val recovery = StateRecovery(AccountsProjection)
        val snapshots = SnapshotStore(DocumentStore(vfs, "data"))
        snapshots.save(recovery.snapshotOf(journal, state))
        commit(accounts.observeLimit(state, slot, LimitWindow.SESSION, LimitLevel.REACHED, clock.now() + 2.hours, clock.now()).valueOrNull()!!)

        // «Перезапуск»: всё заново с диска.
        val reopened = (EventJournal.open(NioVfs(dir), "data/journal.log") as Outcome.Success).value
        val r = recovery.recover(reopened, SnapshotStore(DocumentStore(NioVfs(dir), "data")))
        assertEquals(state, r.valueOrNull()!!.state)
        assertEquals(true, r.valueOrNull()!!.fromSnapshot)
        assertEquals(state, recovery.replayAll(reopened))

        // Номера потоков продолжаются после перезапуска без пропусков.
        val again = EventRecorder(ids, clock).also { it.resumeFrom(reopened.records()) }
        val next = again.accountEnvelopes(accounts.observeSuccess(state, slot, clock.now()).valueOrNull()!!)
        for (e in next) assertTrue(reopened.append(e).isSuccess)
        val streams = reopened.records().groupBy { it.event.stream }
        for ((_, recs) in streams) assertEquals((1L..recs.size).toList(), recs.map { it.event.sequence })
    }
}
