package com.dpsmpx.dpai.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecretTest {
    private val value = "s3cr3t-" + "value-" + "x".repeat(12)

    private data class Holder(val name: String, val key: Secret)

    @Test
    fun toStringNeverShowsValue() {
        val s = Secret(value)
        assertEquals(Secret.REDACTED, s.toString())
        assertFalse(Holder("a", s).toString().contains(value), "data-класс не раскрывает секрет")
        assertFalse("$s".contains(value))
    }

    @Test
    fun revealReturnsValueAndEqualityIsByValue() {
        assertEquals(value, Secret(value).reveal())
        assertEquals(Secret(value), Secret(value))
        assertNotEquals(Secret(value), Secret(value + "1"))
        assertEquals(Secret(value).hashCode(), Secret("другое").hashCode(), "хеш не раскрывает значение")
        assertTrue(Secret("").isEmpty)
    }
}
