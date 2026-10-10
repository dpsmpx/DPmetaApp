package com.dpsmpx.dpai.core.hashing

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonGen
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.testkit.Property
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class HashingTest {
    @Test
    fun knownVectors() {
        assertEquals("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.sha256("").toString())
        assertEquals("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256("abc").toString())
    }

    @Test
    fun parseAndVerify() {
        val h = Hashing.sha256("abc")
        assertEquals(h, ContentHash.parse(h.toString()).valueOrNull())
        assertEquals(DiagCode.HASH_INVALID, assertIs<Outcome.Failure>(ContentHash.parse("md5:00")).errors.single().code)
        assertEquals(DiagCode.HASH_INVALID, assertIs<Outcome.Failure>(ContentHash.parse("sha256:ABC")).errors.single().code)
        assertIs<Outcome.Success<ContentHash>>(Hashing.verify("abc".toByteArray(), h))
        assertEquals(DiagCode.HASH_MISMATCH, assertIs<Outcome.Failure>(Hashing.verify("abd".toByteArray(), h)).errors.single().code)
    }

    @Test
    fun valueHashIgnoresKeyOrderButNotContent() {
        val a = JsonObject.of("x" to json(1), "y" to json("два"))
        val b = JsonObject.of("y" to json("два"), "x" to json(1))
        assertEquals(Hashing.sha256(a), Hashing.sha256(b))
        assertNotEquals(Hashing.sha256(a), Hashing.sha256(JsonObject.of("x" to json(2), "y" to json("два"))))
    }

    /** Свойство: хеш значения не зависит от порядка полей. */
    @Test
    fun hashIsOrderIndependent() {
        Property.forAll(JsonGen.obj(3)) { o ->
            val reversed = JsonObject(LinkedHashMap(o.fields.entries.reversed().associate { it.key to it.value }))
            assertEquals(Hashing.sha256(o), Hashing.sha256(reversed))
        }
    }
}
