package com.dpsmpx.dpai.core.result

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutcomeTest {
    private val warn = Diagnostic.warning(DiagCode.FIELD_UNKNOWN, "лишнее поле")
    private val err = Diagnostic.error(DiagCode.FIELD_MISSING, "нет поля")

    @Test
    fun contradictionsAreUnrepresentable() {
        assertFailsWith<IllegalArgumentException> { Outcome.Success(1, listOf(err)) }
        assertFailsWith<IllegalArgumentException> { Outcome.Failure(listOf(warn)) }
        assertFailsWith<IllegalArgumentException> { Outcome.Failure(emptyList()) }
    }

    @Test
    fun thenAccumulatesDiagnosticsAndStopsAtFailure() {
        val a = Outcome.success(2, listOf(warn))
        val b = a.then { Outcome.success(it * 10, listOf(warn)) }
        assertEquals(20, b.valueOrNull())
        assertEquals(2, b.diagnostics.size)
        var called = false
        val c = Outcome.failure(err).then<Int, Int> {
            called = true
            Outcome.success(it)
        }
        assertIs<Outcome.Failure>(c)
        assertTrue(!called)
        val d = a.then { Outcome.failure(err) }
        assertEquals(listOf(warn, err), d.diagnostics)
    }

    @Test
    fun mapAndWithDiagnostics() {
        assertEquals(Outcome.success("3"), Outcome.success(3).map { it.toString() })
        assertIs<Outcome.Failure>(Outcome.success(3).withDiagnostics(listOf(err)))
        assertEquals(listOf(warn), Outcome.success(3).withDiagnostics(listOf(warn)).warnings)
        assertNull(Outcome.failure(err).valueOrNull())
    }

    @Test
    fun wrapFailureBuildsCausalChain() {
        val inner = Outcome.Failure(listOf(warn, err))
        val outer = inner.wrapFailure(DiagCode.SESSION_FAILED, "сессия не началась")
        assertEquals(1, outer.errors.size)
        assertEquals(DiagCode.SESSION_FAILED, outer.errors.single().code)
        assertEquals(listOf(err), outer.errors.single().causes)
        assertEquals(listOf(warn), outer.warnings)
        assertEquals(Outcome.success(1), Outcome.success(1).wrapFailure(DiagCode.SESSION_FAILED, "не нужно"))
    }

    @Test
    fun combineCollectsAllFindings() {
        assertEquals(Outcome.success(listOf(1, 2)), listOf(Outcome.success(1), Outcome.success(2)).combine())
        val failed = listOf(Outcome.success(1), Outcome.failure(err), Outcome.failure(err)).combine()
        assertEquals(2, failed.errors.size)
    }
}
