package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.operation.OperationResult
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString

/** Только представление OperationResult; статус и ошибки не переопределяются (D-215). */
object AutomationJson {
    fun result(operation: OperationResult<JsonObject>): JsonObject = JsonObject.of(
        "operationId" to JsonString(operation.operationId.value), "operation" to JsonString(operation.operation),
        "status" to JsonString(operation.status.name), "timestamp" to JsonNumber.of(operation.timestamp.epochMillis),
        "project" to (operation.provenance.projectId?.let { JsonString(it.value) } ?: JsonNull),
        "target" to (operation.provenance.executionTargetId?.let { JsonString(it.value) } ?: JsonNull),
        "data" to (operation.value ?: JsonNull),
        "diagnostics" to JsonArray(operation.diagnostics.map { d -> JsonObject.of(
            "code" to JsonString(d.code.name), "message" to JsonString(Redactor.STANDARD.redact(d.message)),
            "subject" to (d.subject?.let { JsonString(Redactor.STANDARD.redact(it)) } ?: JsonNull)) }),
    )
}
