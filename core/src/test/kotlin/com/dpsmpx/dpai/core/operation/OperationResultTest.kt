package com.dpsmpx.dpai.core.operation

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.wrapFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperationResultTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(1))
    private val provenance = Provenance(
        initiator = Actor(ActorKind.USER, "автор"),
        taskId = ids.next(IdKinds.TASK),
    )

    @Test
    fun statusIsDerivedFromOutcome() {
        val ok = OperationResult.record("task.create", provenance, ids, clock, Outcome.success(42))
        assertEquals(OperationStatus.SUCCEEDED, ok.status)
        assertEquals(42, ok.value)
        assertTrue(ok.operationId.value.startsWith("op_"))
        assertEquals(clock.now(), ok.timestamp)
        assertEquals(provenance, ok.provenance)

        val warned = OperationResult.record(
            "task.create", provenance, ids, clock,
            Outcome.success(1, listOf(Diagnostic.warning(DiagCode.FIELD_UNKNOWN, "лишнее"))),
        )
        assertEquals(OperationStatus.SUCCEEDED_WITH_WARNINGS, warned.status)
        assertEquals(1, warned.warnings.size)
    }

    @Test
    fun failureExposesRootCauses() {
        val network = Diagnostic.error(DiagCode.NETWORK_UNREACHABLE, "сеть недоступна")
        val outcome = Outcome.failure(network).wrapFailure(DiagCode.SESSION_FAILED, "сессия упала")
        val r = OperationResult.record("session.start", provenance, ids, clock, outcome)
        assertEquals(OperationStatus.FAILED, r.status)
        assertNull(r.value)
        assertEquals(listOf(DiagCode.NETWORK_UNREACHABLE), r.causes.map { it.code })
    }

    @Test
    fun operationIdsAreUniqueAndOrdered() {
        val a = OperationResult.record("x", provenance, ids, clock, Outcome.success(Unit))
        val b = OperationResult.record("x", provenance, ids, clock, Outcome.success(Unit))
        assertTrue(a.operationId.value < b.operationId.value)
    }
}
