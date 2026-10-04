package com.dpsmpx.dppass.connection

import com.dpsmpx.dppass.config.ConfigIr
import com.dpsmpx.dppass.config.ConfigLoader
import com.dpsmpx.dppass.core.ManualClock
import com.dpsmpx.dppass.core.ProfileId
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.engine.mock.MockBackend
import com.dpsmpx.dppass.engine.mock.MockBackendCompiler
import com.dpsmpx.dppass.engine.mock.MockBehavior
import com.dpsmpx.dppass.engine.mock.MockProxyEngine
import com.dpsmpx.dppass.network.NetworkAssessor
import com.dpsmpx.dppass.network.NetworkType
import com.dpsmpx.dppass.network.ProbeBudget
import com.dpsmpx.dppass.network.simulation.NetworkCondition
import com.dpsmpx.dppass.network.simulation.SimulatedNetwork
import com.dpsmpx.dppass.storage.BlobStore
import com.dpsmpx.dppass.storage.InMemoryBlobStore
import com.dpsmpx.dppass.storage.Quarantine
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun ir(name: String): ConfigIr =
    ConfigLoader().load(File(System.getProperty("dppass.repoRoot"), "tests/data/configs/$name").readText()).valueOrNull()!!.ir

/** Хранилище, чтение из которого можно временно «сломать» (недоступный ключ, сбой ввода-вывода). */
internal class FlakyBlobStore(private val inner: BlobStore) : BlobStore by inner {
    var failReads = false

    override fun read(key: String): Outcome<ByteArray?> =
        if (failReads) Outcome.failure(DiagCode.STORAGE_READ_FAILED, "чтение «$key» временно не работает") else inner.read(key)
}

class ActivationServiceTest {
    private val clock = ManualClock(100)
    private val blobs = InMemoryBlobStore()
    private val engine = MockProxyEngine()
    private fun service() = ActivationService(MockBackendCompiler(), engine, ActivationStore(blobs, Quarantine(blobs, clock)), clock)

    @Test
    fun newConfigBecomesActiveOldBecomesPrevious() = runBlocking {
        val s = service()
        assertIs<ActivationResult.Activated>(s.activate(ir("valid-minimal.json"), "test", "c1"))
        assertIs<ActivationResult.Activated>(s.activate(ir("valid-multi.json"), "test", "c2"))
        assertEquals("c2", s.state.active!!.sourceChecksum)
        assertEquals(ActivationStatus.SUPERSEDED, s.state.previous!!.status)
        assertEquals(6, s.state.activeConfig!!.profiles.size)
    }

    @Test
    fun rejectedConfigLeavesActiveUntouched() = runBlocking {
        val s = service()
        s.activate(ir("valid-minimal.json"), "test", "good")
        engine.behavior = MockBehavior(rejectConfig = true)
        val r = s.activate(ir("valid-multi.json"), "test", "bad")
        val rej = assertIs<ActivationResult.Rejected>(r)
        assertEquals(ActivationStep.BACKEND_VALIDATION, rej.record.failedStep)
        assertEquals(DiagCode.ACTIVATION_REJECTED, rej.diagnostics.first().code)
        assertEquals(DiagCode.BACKEND_REJECTED_CONFIG, rej.diagnostics.first().causes.single().code)
        assertEquals("good", s.state.active!!.sourceChecksum)
        assertEquals(1, s.state.activeConfig!!.profiles.size)
        assertEquals(ActivationStatus.REJECTED, s.state.history.last().status)
    }

    @Test
    fun capabilityRejectionHappensBeforeCompile() = runBlocking {
        val s = ActivationService(MockBackendCompiler(MockBackend.LIMITED), MockProxyEngine(MockBackend.LIMITED), ActivationStore(blobs, Quarantine(blobs, clock)), clock)
        val r = s.activate(ir("valid-multi.json"), "test", "x", profileId = ProfileId("hy2"))
        assertEquals(ActivationStep.CAPABILITY, (r as ActivationResult.Rejected).record.failedStep)
        assertNull(s.state.active)
    }

    @Test
    fun failedHealthCheckRejects() = runBlocking {
        val s = service()
        val r = s.activate(ir("valid-minimal.json"), "t", "x", preCheck = { _, _ -> Outcome.failure(DiagCode.PAYLOAD_STALLED, "нет данных") })
        assertEquals(ActivationStep.HEALTH_CHECK, (r as ActivationResult.Rejected).record.failedStep)
    }

    @Test
    fun rollbackRestoresPrevious() = runBlocking {
        val s = service()
        s.activate(ir("valid-minimal.json"), "t", "old")
        s.activate(ir("valid-multi.json"), "t", "new")
        val r = s.rollback(Diagnostic.error(DiagCode.PAYLOAD_STALLED, "в работе данные не идут"))
        assertTrue(r.isSuccess)
        assertEquals("old", s.state.active!!.sourceChecksum)
        assertEquals(1, s.state.activeConfig!!.profiles.size)
        assertEquals(DiagCode.NOTHING_TO_ROLL_BACK, s.rollback(Diagnostic.error(DiagCode.PAYLOAD_STALLED, "x")).errors.single().code)
    }

    @Test
    fun unreadableStateIsNeverOverwritten() = runBlocking {
        val raw = InMemoryBlobStore()
        val flaky = FlakyBlobStore(raw)
        fun on(store: BlobStore) = ActivationService(MockBackendCompiler(), engine, ActivationStore(store, Quarantine(store, clock)), clock)
        on(flaky).activate(ir("valid-multi.json"), "t", "kept")
        val before = raw.rawSnapshot()

        flaky.failReads = true
        val s = on(flaky)
        assertEquals(DiagCode.STORAGE_READ_FAILED, s.restore().single().code)
        // Сохранённое не прочитано: запись затёрла бы действующую и предыдущую конфигурации и историю (D-07).
        val r = assertIs<ActivationResult.Rejected>(s.activate(ir("valid-minimal.json"), "t", "new"))
        assertEquals(ActivationStep.COMMIT, r.record.failedStep)
        assertEquals(DiagCode.STORAGE_READ_FAILED, r.diagnostics.first().causes.single().code)
        assertEquals(DiagCode.STORAGE_READ_FAILED, s.rollback(Diagnostic.error(DiagCode.PAYLOAD_STALLED, "x")).errors.single().causes.single().code)
        assertEquals(before.keys, raw.rawSnapshot().keys)
        before.forEach { (k, v) -> assertTrue(v.contentEquals(raw.rawSnapshot().getValue(k)), "запись $k не должна меняться") }

        // Чтение вернулось: перед записью сервис перечитывает сохранённое и продолжает с него.
        flaky.failReads = false
        assertIs<ActivationResult.Activated>(s.activate(ir("valid-minimal.json"), "t", "new"))
        assertEquals("kept", s.state.previous!!.sourceChecksum)
    }

    @Test
    fun writeWithoutRestoreReadsStoredStateFirst() = runBlocking {
        service().activate(ir("valid-multi.json"), "t", "first")
        val s = service() // restore() не вызывали
        assertIs<ActivationResult.Activated>(s.activate(ir("valid-minimal.json"), "t", "second"))
        assertEquals("first", s.state.previous!!.sourceChecksum, "сохранённая конфигурация — предыдущая, а не затёрта")
    }

    @Test
    fun stateSurvivesRestartAndCorruptionIsQuarantined() = runBlocking {
        val s = service()
        s.activate(ir("valid-multi.json"), "t", "persisted")
        val again = service()
        assertTrue(again.restore().isEmpty())
        assertEquals("persisted", again.state.active!!.sourceChecksum)
        assertEquals(s.state.activeConfig, again.state.activeConfig)

        blobs.write(ActivationStore.KEY, "{not json".toByteArray())
        val broken = service()
        val d = broken.restore()
        assertEquals(DiagCode.STORAGE_CORRUPTED, d.single().code)
        assertNull(broken.state.activeConfig, "повреждённое — не заменяется конфигурацией по умолчанию")
        assertEquals(1, Quarantine(blobs, clock).list().valueOrNull()!!.size)
    }
}

class CandidatePlannerTest {
    private val multi = ir("valid-multi.json")

    private fun assess(c: NetworkCondition) = runBlocking {
        val clock = ManualClock(0)
        NetworkAssessor(SimulatedNetwork(c), ProbeBudget(clock)) { clock.advance(it) }.assess(NetworkType.MOBILE)
    }

    private fun plan(c: NetworkCondition?, book: HealthBook = HealthBook(), now: Long = 0, lkg: ProfileId? = null) =
        CandidatePlanner.plan(multi, MockBackend.FULL, c?.let(::assess), book, lkg, now)

    @Test
    fun deterministicAndBounded() {
        val a = plan(NetworkCondition.NORMAL)
        assertEquals(a, plan(NetworkCondition.NORMAL))
        assertEquals(ConnectionPolicy().maxAttempts, a.attempts.size)
        assertTrue(a.attempts.all { it.reasons.isNotEmpty() })
        assertEquals(multi.profiles.size, a.attempts.size + a.excluded.size)
    }

    @Test
    fun udpBlockedExcludesQuic() {
        val p = plan(NetworkCondition.TCP_REACHABLE_UDP_BLOCKED)
        assertTrue(p.excluded.any { it.profileId == ProfileId("hy2") && "UDP" in it.reason })
    }

    @Test
    fun allowlistKeepsOnlyMarkedCandidates() {
        val p = plan(NetworkCondition.ALLOWLIST_LIKE)
        assertEquals(listOf(ProfileId("domestic-relay")), p.attempts.map { it.profile.id })
    }

    @Test
    fun allowlistWithoutReachableCandidateExplainsLimit() {
        val noRelay = multi.copy(profiles = multi.profiles.filter { !it.hints.reachableUnderAllowlist })
        val clock = ManualClock(0)
        val x = runBlocking { NetworkAssessor(SimulatedNetwork(NetworkCondition.ALLOWLIST_LIKE), ProbeBudget(clock)) { clock.advance(it) }.assess(NetworkType.MOBILE) }
        val p = CandidatePlanner.plan(noRelay, MockBackend.FULL, x, HealthBook(), null, 0)
        assertTrue(p.attempts.isEmpty())
        assertEquals(DiagCode.NO_VIABLE_CANDIDATE, p.verdict)
        assertTrue("D-13" in p.verdictMessage!!)
    }

    @Test
    fun offlineAndCaptiveMakeNoAttempts() {
        assertEquals(DiagCode.NETWORK_OFFLINE, plan(NetworkCondition.OFFLINE).verdict)
        assertTrue(plan(NetworkCondition.CAPTIVE_PORTAL).attempts.isEmpty())
    }

    @Test
    fun degradedTlsPutsOtherClassesFirst() {
        assertEquals(ProfileId("xhttp-cdn"), plan(NetworkCondition.TLS_DEGRADED).attempts.first().profile.id)
    }

    @Test
    fun backoffExcludesUntilItExpires() {
        val book = HealthBook()
        book.recordFailure(ProfileId("reality-1"), DiagCode.PAYLOAD_STALLED, now = 0)
        assertTrue(plan(NetworkCondition.NORMAL, book, now = 1_000).excluded.any { it.profileId == ProfileId("reality-1") && "пауза" in it.reason })
        // пауза истекла: кандидат снова допускается, но как отказывавший — в конец очереди
        val after = plan(NetworkCondition.NORMAL, book, now = 31_000)
        assertTrue(after.excluded.none { it.profileId == ProfileId("reality-1") && "пауза" in it.reason })
        assertTrue(after.attempts.none { it.profile.id == ProfileId("reality-1") } || after.attempts.last().profile.id == ProfileId("reality-1"))
        book.recordFailure(ProfileId("reality-1"), DiagCode.PAYLOAD_STALLED, now = 31_000)
        assertTrue(plan(NetworkCondition.NORMAL, book, now = 61_000).excluded.any { it.profileId == ProfileId("reality-1") }, "второй отказ — пауза вдвое")
    }

    @Test
    fun lastKnownGoodCandidateGoesFirst() {
        assertEquals(ProfileId("trojan-ws"), plan(NetworkCondition.NORMAL, lkg = ProfileId("trojan-ws")).attempts.first().profile.id)
    }
}

class OrchestratorTest {
    private fun run(c: NetworkCondition): Pair<ConnectionReport, MockProxyEngine> = runBlocking {
        val clock = ManualClock(0)
        val multi = ir("valid-multi.json")
        val net = SimulatedNetwork(c, setOf("203.0.113.10:443"))
        val x = NetworkAssessor(net, ProbeBudget(clock)) { clock.advance(it) }.assess(NetworkType.MOBILE)
        val book = HealthBook()
        val plan = CandidatePlanner.plan(multi, MockBackend.FULL, x, book, null, clock.nowMillis())
        val engine = MockProxyEngine()
        val pauses = mutableListOf<Long>()
        val log = com.dpsmpx.dppass.diagnostics.Logger("t", com.dpsmpx.dppass.diagnostics.MemoryLogSink(), clock::nowMillis)
        val report = ConnectionOrchestrator(MockBackendCompiler(), engine, SimulatedHealthChecker(net), book, clock, log) { pauses += it; clock.advance(it) }
            .connect(multi, plan, null)
        assertEquals((report.attempts.size - 1).coerceAtLeast(0), pauses.size, "пауза между каждой парой попыток")
        report to engine
    }

    @Test
    fun fallsBackFromBlockedIpToNextCandidate() {
        val (r, _) = run(NetworkCondition.IP_BLOCKED)
        val connected = assertIs<ConnectionReport.Connected>(r)
        assertEquals(ProfileId("reality-1"), r.attempts.first().profileId)
        assertEquals(DiagCode.ENDPOINT_UNREACHABLE, r.attempts.first().failure!!.code)
        assertEquals(ProfileId("xhttp-cdn"), connected.profileId)
    }

    @Test
    fun stalledPayloadCountsAsFailure() {
        val (r, _) = run(NetworkCondition.PACKET_LOSS)
        assertIs<ConnectionReport.Connected>(r)
        assertTrue(r.attempts.any { it.failure?.code == DiagCode.PAYLOAD_STALLED })
    }

    @Test
    fun exhaustsBoundedAttemptsAndStopsEachSession() {
        val (r, engine) = run(NetworkCondition.FULL_ENDPOINT_BLOCK)
        val failed = assertIs<ConnectionReport.Failed>(r)
        assertEquals(DiagCode.ATTEMPTS_EXHAUSTED, failed.reason.code)
        assertEquals(ConnectionPolicy().maxAttempts, r.attempts.size)
        assertNull(engine.runningSession(), "неудачные сессии остановлены")
    }

    @Test
    fun emptyPlanFailsWithVerdict() {
        val (r, engine) = run(NetworkCondition.OFFLINE)
        assertEquals(DiagCode.NETWORK_OFFLINE, assertIs<ConnectionReport.Failed>(r).reason.code)
        assertTrue(engine.startedProfiles.isEmpty())
        assertNotNull(r.plan.verdictMessage)
    }
}
