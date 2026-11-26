package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.operation.OperationResult
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonValue

/** Только представление OperationResult; статус и ошибки не переопределяются (D-215). */
object AutomationJson {
    /** Общий конверт прежнего OperationResult; данные и доказательства описываются самим инструментом. */
    fun schema(): JsonObject {
        fun type(name: String) = JsonObject.of("type" to JsonString(name))
        val fields = linkedMapOf(
            "operationId" to type("string"), "operation" to type("string"),
            "status" to JsonObject.of("type" to JsonString("string"), "enum" to JsonArray(
                com.dpsmpx.dpai.core.operation.OperationStatus.entries.map { JsonString(it.name) })),
            "timestamp" to type("integer"),
            "project" to JsonObject.of("type" to JsonArray(listOf(JsonString("string"), JsonString("null")))),
            "target" to JsonObject.of("type" to JsonArray(listOf(JsonString("string"), JsonString("null")))),
            "data" to JsonObject.of("type" to JsonArray(listOf(JsonString("object"), JsonString("null")))),
            "evidence" to JsonObject.of("type" to JsonArray(listOf(JsonString("object"), JsonString("null")))),
            "diagnostics" to JsonObject.of("type" to JsonString("array"), "items" to JsonObject.of(
                "type" to JsonString("object"), "properties" to JsonObject.of(
                    "code" to type("string"), "message" to type("string"),
                    "subject" to JsonObject.of("type" to JsonArray(listOf(JsonString("string"), JsonString("null"))))),
                "required" to JsonArray(listOf("code", "message", "subject").map(::JsonString)))),
        )
        return JsonObject.of("type" to JsonString("object"), "properties" to JsonObject(fields),
            "required" to JsonArray(fields.keys.map(::JsonString)))
    }

    /** Ограничение канала не должно разрезать JSON посреди строки и выдавать обрыв за полный результат. */
    fun forApi(result: JsonObject, limit: Int = 30_000): JsonObject {
        require(limit >= 2000)
        if (CanonicalJson.write(result).length <= limit) return result
        val hash = JsonString("sha256:" + Hashing.sha256(result).hex)
        // Пакет контекста нельзя разрезать внутри обязательных правил или выдавать ссылку за доставленный текст.
        fun protect(value: JsonValue): JsonValue = when (value) {
            is JsonObject -> if (value["format"] == JsonString("dpai.context-packet")) JsonObject.of(
                "contextDelivery" to JsonString("UNAVAILABLE"), "packetHash" to JsonString(Hashing.sha256(value).hex),
                "resultAvailable" to JsonBool(false), "note" to JsonString("пакет не доставлен из-за предела канала; запросите меньшую выборку или откройте полный пакет в IDE"))
                else JsonObject(value.fields.mapValues { protect(it.value) })
            is JsonArray -> JsonArray(value.items.map(::protect))
            else -> value
        }
        val protected = protect(result) as JsonObject
        val protectedEnvelope = JsonObject(protected.fields + mapOf("payloadTruncated" to JsonBool(true), "payloadHash" to hash))
        if (protected != result && CanonicalJson.write(protectedEnvelope).length <= limit) return protectedEnvelope
        var chars = 2000
        var count = 32
        while (chars >= 64) {
            fun shrink(value: JsonValue): JsonValue = when (value) {
                is JsonString -> JsonString(value.value.take(chars))
                is JsonArray -> JsonArray(value.items.take(count).map(::shrink))
                is JsonObject -> {
                    val fields = value.fields.mapValues { (key, v) -> if (key in setOf("run", "outputHash")) v else shrink(v) }
                    val shortened = (value["output"] as? JsonString)?.value != (fields["output"] as? JsonString)?.value
                    JsonObject(if (shortened) fields + ("outputTruncated" to JsonBool(true)) else fields)
                }
                else -> value
            }
            val fixed = result.fields.filterKeys { it in setOf("operationId", "operation", "status", "timestamp", "project", "target") }
            val smaller = JsonObject((shrink(protected) as JsonObject).fields + fixed + mapOf("payloadTruncated" to JsonBool(true), "payloadHash" to hash))
            if (CanonicalJson.write(smaller).length <= limit) return smaller
            chars /= 2; count = (count / 2).coerceAtLeast(1)
        }
        return JsonObject(result.fields.filterKeys { it in setOf("operationId", "operation", "status", "timestamp", "project", "target") } + mapOf(
            "data" to JsonNull, "evidence" to JsonNull, "payloadTruncated" to JsonBool(true), "payloadHash" to hash,
            "resultAvailable" to JsonBool(false), "deliveryNote" to JsonString("полный результат слишком велик; откройте вывод и историю инструмента в IDE")))
    }

    fun result(operation: OperationResult<JsonObject>, evidence: JsonObject? = null): JsonObject = JsonObject.of(
        "operationId" to JsonString(operation.operationId.value), "operation" to JsonString(operation.operation),
        "status" to JsonString(operation.status.name), "timestamp" to JsonNumber.of(operation.timestamp.epochMillis),
        "project" to (operation.provenance.projectId?.let { JsonString(it.value) } ?: JsonNull),
        "target" to (operation.provenance.executionTargetId?.let { JsonString(it.value) } ?: JsonNull),
        "data" to (operation.value ?: JsonNull),
        "evidence" to (evidence ?: JsonNull),
        "diagnostics" to JsonArray(operation.diagnostics.map { d -> JsonObject.of(
            "code" to JsonString(d.code.name), "message" to JsonString(Redactor.STANDARD.redact(d.message)),
            "subject" to (d.subject?.let { JsonString(Redactor.STANDARD.redact(it)) } ?: JsonNull)) }),
    )
}
