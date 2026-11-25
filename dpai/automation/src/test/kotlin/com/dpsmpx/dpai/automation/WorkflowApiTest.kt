package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowApiTest {
    @Test fun boundedPagesNeverLoseRecordsAtThePayloadLimit() {
        val hash = Hashing.sha256("test").hex
        val flows = List(100) { index -> Workflow("flow." + index.toString().padStart(3, '0') + "x".repeat(108),
            "Проверка проекта ".repeat(12).take(200), hash, listOf(WorkflowStep("manual.check", hash)),
            List(16) { WorkflowTrigger(AutomationTrigger.EVENT, "event.$it") }) }
        val ids = mutableListOf<String>(); var offset = 0
        do {
            val page = WorkflowApi.list(flows, JsonObject.of("offset" to JsonNumber.of(offset))).valueOrNull()!!
            assertTrue(CanonicalJson.write(page).length < 30_000)
            ids += (page["workflows"] as JsonArray).items.map { ((it as JsonObject)["id"] as JsonString).value }
            if (page["nextOffset"] == JsonNull) break
            val next = (page["nextOffset"] as JsonNumber).toLongExact()!!.toInt()
            assertTrue(next > offset); offset = next
        } while (offset < flows.size)
        assertEquals(flows.map { it.id }, ids)
    }
}
