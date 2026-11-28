package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextDeliveryTest {
    @Test fun smallChannelReturnsAReferenceInsteadOfPartiallyDeliveredCriticalRules() {
        val rule = "ОБЯЗАТЕЛЬНОЕ ПРАВИЛО ".repeat(2000)
        val packet = JsonObject.of("format" to json("dpai.context-packet"), "version" to json(1),
            "sources" to JsonArray(listOf(JsonObject.of("level" to json("PROJECT"), "text" to json(rule)))))
        val result = AutomationJson.forApi(JsonObject.of("status" to json("PASSED"), "data" to packet), 2000)
        assertEquals(json("PASSED"), result["status"], "результат операции не переписывается доставкой")
        val data = result["data"] as JsonObject
        assertEquals(json("UNAVAILABLE"), data["contextDelivery"])
        assertEquals(json(false), data["resultAvailable"])
        assertEquals(json(true), result["payloadTruncated"])
        assertFalse(CanonicalJson.write(result).contains("ОБЯЗАТЕЛЬНОЕ"))
        assertTrue(CanonicalJson.write(result).length <= 2000)
    }

    @Test fun completePacketFitsUnchangedAndNestedWorkflowPacketsAreProtectedToo() {
        val packet = JsonObject.of("format" to json("dpai.context-packet"), "version" to json(1), "sources" to JsonArray(emptyList()))
        val small = JsonObject.of("data" to packet)
        assertEquals(small, AutomationJson.forApi(small))
        val large = JsonObject.of("results" to JsonArray(listOf(JsonObject.of("data" to JsonObject(packet.fields + ("text" to json("x".repeat(10000))))))))
        val bounded = AutomationJson.forApi(large, 2000)
        assertTrue(CanonicalJson.write(bounded).contains("UNAVAILABLE"))
        assertTrue(CanonicalJson.write(bounded).length <= 2000)
    }
}
