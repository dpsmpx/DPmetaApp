package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Один старый worker, вложенные проверки и результат именно основной сборки (D-222). */
class LongJobsOwnershipTest {
    private fun jobs() = LongJobs(CoroutineScope(Dispatchers.Unconfined))

    @Test fun dynamicWorkflowOwnsGuestAndOnlyItsLivePermitAllowsOneChild() {
        val jobs = jobs(); val parent = CompletableDeferred<Unit>(); val child = CompletableDeferred<Unit>()
        assertTrue(jobs.start("workflow:project", "Сценарий", excludes = LongJobs.GUEST) { parent.await(); LongJobs.Done("готово") })
        val permit = assertNotNull(jobs.permit("workflow:project"))
        assertTrue(jobs.busyGuest())
        assertFalse(jobs.start(LongJobs.LINUX_APK, "Пакеты", excludes = LongJobs.GUEST) { LongJobs.Done("не должен") })
        assertTrue(jobs.start(LongJobs.WORKSPACE_CHILD, "Проверка", excludes = LongJobs.GUEST, parent = permit) {
            child.await(); LongJobs.Done("проверено")
        })
        assertFalse(jobs.start("other-child", "Вторая проверка", excludes = LongJobs.GUEST, parent = permit) { LongJobs.Done("не должен") })
        child.complete(Unit); assertTrue(jobs.busyGuest())
        parent.complete(Unit); assertFalse(jobs.busyGuest())
        assertFalse(jobs.start(LongJobs.WORKSPACE_CHILD, "Устаревший допуск", excludes = LongJobs.GUEST, parent = permit) { LongJobs.Done("не должен") })
    }

    @Test fun childSuccessCannotOverwriteMainFailureOrNewRunReceipt() {
        val jobs = jobs(); val gate = CompletableDeferred<Unit>(); var id = ""
        assertTrue(jobs.start(LongJobs.WORKSPACE_PROFILE, "Сборка", excludes = LongJobs.GUEST, onAccepted = { id = it }) {
            gate.await(); LongJobs.Done("after-build не прошёл", failed = true)
        })
        val proof = JsonObject.of("complete" to JsonBool(true))
        jobs.result(LongJobs.WORKSPACE_PROFILE, id, proof)
        val permit = assertNotNull(jobs.permit(LongJobs.WORKSPACE_PROFILE))
        assertTrue(jobs.start(LongJobs.WORKSPACE_CHILD, "Проверка", excludes = LongJobs.GUEST, parent = permit) { LongJobs.Done("успех") })
        gate.complete(Unit)
        val result = jobs.state.value.getValue(LongJobs.WORKSPACE_PROFILE)
        assertTrue(result.failed); assertEquals(proof, result.result); assertEquals(id, result.runId)
        val next = CompletableDeferred<Unit>(); var nextId = ""
        jobs.start(LongJobs.WORKSPACE_PROFILE, "Новая сборка", excludes = LongJobs.GUEST, onAccepted = { nextId = it }) { next.await(); LongJobs.Done("готово") }
        assertNotEquals(id, nextId)
        jobs.report(LongJobs.WORKSPACE_PROFILE, id, "поздний вывод")
        jobs.result(LongJobs.WORKSPACE_PROFILE, id, proof)
        assertEquals(null, jobs.state.value.getValue(LongJobs.WORKSPACE_PROFILE).result)
        assertEquals(null, jobs.state.value.getValue(LongJobs.WORKSPACE_PROFILE).note)
        next.complete(Unit)
    }

    @Test fun failedAcceptanceRollsBackReservationAndContinuationRunsAfterRelease() {
        val jobs = jobs()
        assertFalse(jobs.start("workflow", "Сценарий", excludes = LongJobs.GUEST, onAccepted = { error("refused") }) { error("must not run") })
        assertFalse(jobs.busyGuest()); assertEquals(null, jobs.state.value["workflow"])
        var continued = false
        assertTrue(jobs.start("workflow", "Сценарий", excludes = LongJobs.GUEST, afterFinished = {
            assertFalse(jobs.busyGuest())
            continued = jobs.start(LongJobs.WORKSPACE_PROFILE, "Следующий шаг", excludes = LongJobs.GUEST) { LongJobs.Done("готово") }
        }) { LongJobs.Done("готово") })
        assertTrue(continued)
    }
    @Test fun completionIsDeliveredEvenWhenResultPresentationThrows() {
        val failures = mutableListOf<Throwable>()
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, error -> failures += error }
        val jobs = LongJobs(CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined + handler)) { _, _ -> error("notification failed") }
        var delivered = false
        jobs.start("workflow", "Сценарий", onFinished = { error("screen callback failed") }, afterFinished = {
            assertFalse(jobs.busy("workflow")); delivered = true
        }) { LongJobs.Done("фактический результат") }
        assertTrue(delivered); assertEquals(1, failures.size)
    }

}
