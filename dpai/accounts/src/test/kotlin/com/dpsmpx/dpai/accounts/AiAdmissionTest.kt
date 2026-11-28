package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AiAdmissionTest {
    private val id = IdKinds.ACCOUNT.trusted("acc_01J9ZQ3K8M7N6P5R4S3T2V1W0X")
    private val profile = ApiProfiles.form(id, "API", ProviderKind.ANTHROPIC, "", "model-a", null, false).valueOrNull()!!
    private val api = AiModelRef.api(profile)
    private val cli = AiModelRef.native(Executor.CLAUDE, "model-a")

    private fun gate(mode: AiMode = AiMode.SINGLE, allowed: List<AiModelRef> = listOf(api)) = AiAdmission().also {
        assertIs<Outcome.Success<*>>(it.configure("unconfigured", AiModePolicy(mode, allowed), true))
    }

    @Test fun allEntryPointsUseExactIdentityAndCannotChangeModeWhileBusy() {
        val g = gate()
        for (ref in listOf(cli, api.copy(model = "model-b"), api.copy(address = "https://other.example/v1"), AiModelRef(AiBackend.CLOUD_ROUTINE, null, id))) {
            assertIs<Outcome.Failure>(g.acquire("other", ref))
        }
        val permit = g.acquire("chat", api).valueOrNull()!!
        val before = g.snapshot()
        assertIs<Outcome.Failure>(g.acquire("completion", api))
        assertIs<Outcome.Failure>(g.configure(before.revision, null, true))
        assertEquals(before, g.snapshot())
        permit.release()
        assertIs<Outcome.Success<*>>(g.configure(before.revision, null, true))
    }

    @Test fun sequentialAdmissionIsAtomicAcrossThreads() {
        val g = gate(AiMode.MULTI_SEQUENTIAL, listOf(api, cli))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val work = (1..8).map { n -> pool.submit<Outcome<AiAdmission.Permit>> {
                check(start.await(5, TimeUnit.SECONDS))
                g.acquire("$n", if (n % 2 == 0) api else cli)
            } }
            start.countDown()
            val results = work.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, g.active())
            results.mapNotNull { it.valueOrNull() }.single().release()
            assertEquals(0, g.active())
        } finally { pool.shutdownNow() }
    }

    @Test fun durableIntentSurvivesRestartAndFailedPersistenceNeverPermitsLaunch() {
        var saved = AiRoutingState(AiModePolicy(AiMode.SINGLE, listOf(cli)))
        var fail = false
        val g = AiAdmission()
        g.install(Outcome.success(saved)) { next -> if (fail) Outcome.failure(DiagCode.STORAGE_IO_FAILED, "диск недоступен") else Outcome.success(Unit).also { saved = next } }
        fail = true
        assertIs<Outcome.Failure>(g.acquire("native", cli))
        assertEquals(0, g.active())
        fail = false
        assertIs<Outcome.Success<*>>(g.acquire("native", cli))
        val restored = AiAdmission()
        restored.install(AiRoutingState.read(saved.json())) { Outcome.success(Unit) }
        assertIs<Outcome.Failure>(restored.acquire("new", cli))
        assertIs<Outcome.Failure>(restored.configure(saved.revision, null, true))
        fail = true
        assertIs<Outcome.Failure>(g.release("native"))
        assertEquals(1, g.active())
        restored.release("native")
        assertIs<Outcome.Success<*>>(restored.acquire("new", cli))
    }

    @Test fun strictCodecAndUnreadableDocumentFailClosed() {
        val state = AiRoutingState(AiModePolicy(AiMode.SINGLE, listOf(cli)), listOf(AiReservation("session", cli)))
        assertEquals(state, AiRoutingState.read(state.json()).valueOrNull())
        val bad = JsonObject(state.json().fields + ("version" to JsonNumber.of(2)))
        val g = AiAdmission()
        g.install(AiRoutingState.read(bad)) { Outcome.success(Unit) }
        assertIs<Outcome.Failure>(g.acquire("chat", api))
        assertIs<Outcome.Failure>(g.configure("unconfigured", null, true))
        assertNull(g.snapshot().policy)
    }

    @Test fun legacyCallsRemainVisibleForLaterActivation() {
        val g = AiAdmission()
        val permit = g.acquire("chat", api).valueOrNull()!!
        assertIs<Outcome.Failure>(g.configure("unconfigured", AiModePolicy(AiMode.SINGLE, listOf(api)), true))
        permit.release()
        assertIs<Outcome.Failure>(g.configure("unconfigured", AiModePolicy(AiMode.SINGLE, listOf(api)), false))
    }
}
