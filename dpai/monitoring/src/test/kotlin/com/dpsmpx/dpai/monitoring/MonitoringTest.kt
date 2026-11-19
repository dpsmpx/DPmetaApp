package com.dpsmpx.dpai.monitoring

import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.state.HeartbeatFreshness
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.sessions.SessionRecord
import com.dpsmpx.dpai.sessions.Sessions
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.TasksState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Мониторинг (критерии этапа 11; ТЗ, §38, §40, §79, §80, §122). */
class MonitoringTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(81))
    private val sessions = Sessions()

    private fun record(state: SessionState, lastHeartbeat: kotlin.time.Duration?): SessionRecord {
        val now = clock.now()
        return SessionRecord(
            ids.next(IdKinds.SESSION), "k" + ids.next(IdKinds.OPERATION).value, ids.next(IdKinds.TASK), ids.next(IdKinds.ACCOUNT),
            ids.next(IdKinds.SUBSCRIPTION), ids.next(IdKinds.TARGET), "~/.claude", ProviderSessionId.newUuid(RandomSource.seeded(1)), null,
            state, createdAt = now - 2.minutes * 60, updatedAt = now - 2.minutes * 60, lastHeartbeat = lastHeartbeat?.let { now - it },
        )
    }

    @Test
    fun rowAndAttentionOrderDoNotDependOnMapOrderOrStaleDisplayState() {
        val stale = record(SessionState.RUNNING, 47.minutes)
        val waiting = record(SessionState.WAITING, 10.seconds)
        val blocked = List(2) { record(SessionState.BLOCKED, 10.seconds) }
        val done = record(SessionState.COMPLETED, 10.seconds)
        val records = listOf(done, blocked[1], waiting, blocked[0], stale)
        fun build(records: List<SessionRecord>) = Dashboard.build(SessionsState(records.associateBy { it.id }),
            TasksState.EMPTY, AccountsState.EMPTY, emptyMap(), Measure.observed(0), sessions, clock.now())
        val view = build(records)
        assertEquals(view, build(records.reversed()))
        assertEquals(listOf(stale, waiting, *blocked.sortedBy { it.id.value }.toTypedArray(), done).map { it.id }, view.rows.map { it.session })
        assertEquals(listOf("session-blocked", "session-blocked", "session-stale"), view.attention.map { it.kind })
        assertEquals(2, view.activeSessions)
        assertEquals(2, view.blocked)
        assertEquals(DisplayState.STALE, view.rows.first().state)
    }

    @Test
    fun silentRunningSessionIsStaleNotRunning() {
        val fresh = record(SessionState.RUNNING, 10.seconds)
        val silent = record(SessionState.RUNNING, 47.minutes)
        val paused = record(SessionState.PAUSED, 47.minutes)
        val state = SessionsState(listOf(fresh, silent, paused).associateBy { it.id })
        val view = Dashboard.build(state, TasksState.EMPTY, AccountsState.EMPTY, emptyMap(), Measure.unknown("нет одобрений"), sessions, clock.now())
        val byId = view.rows.associateBy { it.session }
        assertEquals(DisplayState.RUNNING, byId.getValue(fresh.id).state)
        assertEquals(DisplayState.STALE, byId.getValue(silent.id).state)
        assertEquals(HeartbeatFreshness.UNKNOWN, byId.getValue(silent.id).freshness)
        assertEquals(DisplayState.PAUSED, byId.getValue(paused.id).state, "приостановленная молчит законно")
        assertTrue(view.attention.any { it.kind == "session-stale" && it.subject == silent.id.value })
        assertEquals(SessionState.RUNNING, state.sessions.getValue(silent.id).state, "сводка не меняет состояние — только показывает")
    }

    @Test
    fun twentyFiveSessionsBuildWithinFrameBudget() {
        val records = List(25) { i -> record(if (i % 5 == 0) SessionState.WAITING else SessionState.RUNNING, (i * 30).seconds) }
        val state = SessionsState(records.associateBy { it.id })
        // Прогрев JIT, затем средний кадр (`docs/PERFORMANCE.md`: сводка ≤ 16 мс при 20 сессиях).
        repeat(200) { Dashboard.build(state, TasksState.EMPTY, AccountsState.EMPTY, emptyMap(), Measure.observed(0), sessions, clock.now()) }
        val real = com.dpsmpx.dpai.core.clock.Clock.SYSTEM
        val frames = 200
        val t0 = real.monotonicNanos()
        repeat(frames) { Dashboard.build(state, TasksState.EMPTY, AccountsState.EMPTY, emptyMap(), Measure.observed(0), sessions, clock.now()) }
        val perFrameMs = (real.monotonicNanos() - t0) / 1_000_000.0 / frames
        assertTrue(perFrameMs < 16.0, "сводка 25 сессий — $perFrameMs мс на кадр")
        val view = Dashboard.build(state, TasksState.EMPTY, AccountsState.EMPTY, emptyMap(), Measure.observed(0), sessions, clock.now())
        assertEquals(25, view.rows.size)
        assertEquals(25, view.activeSessions)
    }

    @Test
    fun selfDiagnosisDegradesInsteadOfStopping() {
        val ok = SystemSignals(storageOk = true, recoveryInProgress = false, gitAvailable = true, notificationsOk = true, targetsOnline = 1, targetsTotal = 1, securityAlert = false)
        assertEquals(SystemHealth.HEALTHY, SelfDiagnostics.evaluate(ok).health)
        val noGit = SelfDiagnostics.evaluate(ok.copy(gitAvailable = false))
        assertEquals(SystemHealth.DEGRADED, noGit.health)
        assertTrue(Capability.VIEW in noGit.allowed && Capability.SCHEDULE !in noGit.allowed)
        // Без проекта Git не нужен: его отсутствие — не деградация; неизвестный уже нужный — недоступен (D-13).
        assertEquals(SystemHealth.HEALTHY, SelfDiagnostics.evaluate(ok.copy(gitAvailable = null, gitRequired = false)).health)
        assertEquals(SystemHealth.DEGRADED, SelfDiagnostics.evaluate(ok.copy(gitAvailable = null)).health)
        val noNotify = SelfDiagnostics.evaluate(ok.copy(notificationsOk = false))
        assertTrue(Capability.SCHEDULE in noNotify.allowed, "сломанные уведомления не останавливают планировщик (ТЗ, §80)")
        val storage = SelfDiagnostics.evaluate(ok.copy(storageOk = false))
        assertEquals(SystemHealth.STORAGE_ERROR, storage.health)
        assertFalse(Capability.DESTRUCTIVE_WRITES in storage.allowed)
        val unknownStorage = SelfDiagnostics.evaluate(ok.copy(storageOk = null))
        assertEquals(SystemHealth.STORAGE_ERROR, unknownStorage.health, "неизвестное — не «в порядке» (D-13)")
        val lockdown = SelfDiagnostics.evaluate(ok.copy(securityAlert = true, gitAvailable = false))
        assertEquals(SystemHealth.SECURITY_LOCKDOWN, lockdown.health)
        assertEquals(setOf(Capability.VIEW), lockdown.allowed)
        assertEquals(SystemHealth.EXECUTION_UNAVAILABLE, SelfDiagnostics.evaluate(ok.copy(targetsOnline = 0)).health)
    }

    @Test
    fun timelineFollowsSequence() {
        val s = ids.next(IdKinds.SESSION)
        fun e(seq: Long, type: EventType, vararg kv: Pair<String, String>) = EventEnvelope(
            ids.next(IdKinds.EVENT), type, StreamRef.of(s), seq, clock.now() + seq.seconds, clock.now(),
            payload = JsonObject(kv.associate { it.first to JsonString(it.second) }),
        )
        val events = listOf(
            e(3, EventType.SESSION_TOOL_STARTED, "tool" to "Edit"),
            e(1, EventType.SESSION_CREATED),
            e(2, EventType.SESSION_STATE_CHANGED, "from" to "QUEUED", "to" to "STARTING"),
            e(4, EventType.SESSION_OUTPUT, "text" to "думаю"),
        )
        val t = Timeline.of(events)
        assertEquals(listOf(1L, 2L, 3L), t.map { it.sequence }, "по номерам; текст модели — не шкала")
        assertEquals("состояние: QUEUED → STARTING", t[1].text)
    }
}
