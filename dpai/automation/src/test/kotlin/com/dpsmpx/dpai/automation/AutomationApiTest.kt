package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AutomationApiTest {
    private fun entry(id: String) = AutomationDefinition(id, id, "Проверка", AutomationSource.MANUAL, AutomationCategory.CHECK,
        Hashing.sha256(id).hex, ActionLevel.REVERSIBLE, listOf("/project"), AutomationInvocation.DIRECT, true, verification = "по результату worker")

    @Test fun catalogDefaultsToInvocableToolsAndPaginationDoesNotLoseEntries() {
        val entries = (1..55).map { entry("tool.$it") } + entry("tool.ui").copy(invocation = AutomationInvocation.OPEN_UI)
        val catalog = AutomationCatalog.of(entries).valueOrNull()!!
        val first = AutomationApi.list(catalog, JsonObject.of()).valueOrNull()!!
        assertEquals(JsonNumber.of(55), first["total"])
        val second = AutomationApi.list(catalog, JsonObject.of("offset" to first["nextOffset"]!!)).valueOrNull()!!
        val ids = listOf(first, second).flatMap { (it["entries"] as JsonArray).items }.map { (it as JsonObject)["id"] }
        assertEquals(55, ids.toSet().size)
        assertEquals(JsonNull, second["nextOffset"])
        assertEquals(JsonNumber.of(56), AutomationApi.list(catalog, JsonObject.of("includeUi" to JsonBool(true))).valueOrNull()!!["total"])
        for (bad in listOf(JsonObject.of("offset" to JsonString("0")), JsonObject.of("offset" to JsonNumber.of(-1)), JsonObject.of("includeUi" to JsonNull))) {
            assertIs<Outcome.Failure>(AutomationApi.list(catalog, bad))
        }
    }

    @Test fun largeParameterSchemasArePagedWithoutChangingTheRevision() {
        val d = entry("manual.check").copy(parameters = (1..10).map { n -> AutomationParameter("P$n", "Параметр", (1..20).map { "\"".repeat(190) + it }, defaultValue = "\"".repeat(190) + "1") })
        val catalog = AutomationCatalog.of(listOf(d)).valueOrNull()!!
        for (n in 0..9) {
            val description = AutomationApi.describe(catalog, JsonObject.of("id" to JsonString(d.id), "parameterOffset" to JsonNumber.of(n))).valueOrNull()!!
            assertEquals(JsonString(d.revision), description["revision"])
            assertTrue(CanonicalJson.write(description).length < 30_000)
            val properties = (description["inputSchema"] as JsonObject)["properties"] as JsonObject
            assertEquals(setOf("P${n + 1}"), properties.fields.keys)
        }
    }

    @Test fun duplicateParametersAndExtraArgumentsAreRejectedBeforeInvocation() {
        val d = entry("manual.check")
        val param = JsonObject.of("name" to JsonString("MODE"), "value" to JsonString("fast"))
        assertIs<Outcome.Failure>(AutomationApi.call(JsonObject.of("id" to JsonString(d.id), "revision" to JsonString(d.revision), "parameters" to JsonArray(listOf(param, param)))))
        assertIs<Outcome.Failure>(AutomationApi.call(JsonObject.of("id" to JsonString(d.id), "revision" to JsonString(d.revision), "command" to JsonString("shell"))))
    }

    @Test fun oversizedResultsKeepValidJsonAndMarkOmittedData() {
        val original = JsonObject.of("status" to JsonString("FAILED"), "operationId" to JsonString("op"),
            "evidence" to JsonObject.of("run" to JsonString("run"), "output" to JsonString("\u0001".repeat(80_000))))
        val result = AutomationJson.forApi(original)
        assertTrue(CanonicalJson.write(result).length <= 30_000)
        assertIs<Outcome.Success<*>>(JsonParser().parse(CanonicalJson.write(result)))
        assertEquals(JsonString("FAILED"), result["status"])
        assertEquals(JsonBool(true), result["payloadTruncated"])
        assertEquals(JsonString("sha256:" + Hashing.sha256(original).hex), result["payloadHash"])
    }
}
