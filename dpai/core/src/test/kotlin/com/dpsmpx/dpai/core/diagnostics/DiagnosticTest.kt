package com.dpsmpx.dpai.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticTest {
    /** Цепочка из ТЗ, §5.1. */
    private fun chain(): Diagnostic {
        val network = Diagnostic.error(DiagCode.NETWORK_UNREACHABLE, "сеть недоступна")
        val ssh = Diagnostic.error(DiagCode.SSH_CONNECTION_FAILED, "SSH не соединился").withCause(network)
        val host = Diagnostic.error(DiagCode.EXECUTION_HOST_UNAVAILABLE, "хост недоступен").withCause(ssh)
        return Diagnostic.error(DiagCode.SESSION_FAILED, "сессия упала", "session ses_x").withCause(host)
    }

    @Test
    fun causalChainRendersAndKeepsRootCause() {
        val d = chain()
        assertEquals(
            listOf(
                "ERROR SESSION_FAILED at session ses_x: сессия упала",
                "  └ ERROR EXECUTION_HOST_UNAVAILABLE: хост недоступен",
                "    └ ERROR SSH_CONNECTION_FAILED: SSH не соединился",
                "      └ ERROR NETWORK_UNREACHABLE: сеть недоступна",
            ),
            d.renderWithCauses(),
        )
        assertEquals(listOf(DiagCode.NETWORK_UNREACHABLE), d.rootCauses().map { it.code })
    }

    @Test
    fun retryabilityOfWrapperComesFromCauses() {
        assertEquals(Retryability.RETRYABLE, chain().retryability)
        val compile = Diagnostic.error(DiagCode.JSON_SYNTAX, "не разобрано")
        val wrapped = Diagnostic.error(DiagCode.SESSION_FAILED, "сессия упала").withCause(compile)
        assertEquals(Retryability.NON_RETRYABLE, wrapped.retryability)
        assertEquals(Retryability.UNKNOWN, Diagnostic.error(DiagCode.SESSION_FAILED, "упала").retryability)
        val mixed = Diagnostic.error(DiagCode.SESSION_FAILED, "упала")
            .withCauses(listOf(chain(), Diagnostic.error(DiagCode.INTERNAL_ERROR, "?")))
        assertEquals(Retryability.UNKNOWN, mixed.retryability)
    }

    @Test
    fun messageSubjectAndExceptionAreRedacted() {
        val token = "sk-" + "ant-" + "x1Y2z3".repeat(6)
        val d = Diagnostic.error(DiagCode.SESSION_FAILED, "ответ: $token", "url https://u:p4ss@host/x")
        assertFalse(d.render().contains(token))
        assertFalse(d.render().contains("p4ss"))
        val e = Diagnostic.fromThrowable(DiagCode.INTERNAL_ERROR, "сбой", IllegalStateException("password=$token"))
        assertFalse(e.render().contains(token))
        assertTrue(e.render().contains("IllegalStateException"))
    }

    @Test
    fun severityAndCollector() {
        val c = DiagnosticCollector()
        c.warning(DiagCode.FIELD_UNKNOWN, "лишнее")
        assertFalse(c.hasErrors)
        c.error(DiagCode.FIELD_MISSING, "нет поля", "$.id")
        assertTrue(c.hasErrors)
        assertEquals(2, c.all.size)
        assertEquals(Category.INPUT, c.all.last().category)
    }
}
