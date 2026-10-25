package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.execution.agent.OutputCapture
import com.dpsmpx.dpai.execution.agent.RunSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** `dpai.run-spec`: без захвата — v1 (его читает агент прежней версии), с захватом — v2 (D-69). */
class RunSpecTest {
    @Test
    fun streamSpecStaysV1AndCaptureIsV2() {
        val plain = RunSpec(listOf("claude", "-p"), "/w", mapOf("A" to "1"), listOf("B"), 30.seconds, "prompt")
        assertEquals(JsonNumber.of(1), plain.toJson()["version"])
        assertEquals(plain, RunSpec.parse(CanonicalJson.write(plain.toJson())).valueOrNull())
        val probe = plain.copy(capture = OutputCapture.AUTH_STATUS)
        assertEquals(JsonNumber.of(2), probe.toJson()["version"])
        assertEquals(probe, RunSpec.parse(CanonicalJson.write(probe.toJson())).valueOrNull())
    }
}
