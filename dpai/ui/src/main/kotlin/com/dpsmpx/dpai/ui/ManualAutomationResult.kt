package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString

/** Соседний finished не доказывает исполнение нашего запроса, даже если он новее (D-215). */
object ManualAutomationResult {
    fun evidence(prepared: SessionId?, view: ManualRunView): Outcome<JsonObject> {
        if (prepared == null || view.finished != prepared) return Outcome.failure(DiagCode.STATE_CONFLICT, view.note)
        val commandStarted = view.step != null || view.results.isNotEmpty()
        return Outcome.success(JsonObject.of("complete" to JsonBool(view.complete), "run" to JsonString(prepared.value), "note" to JsonString(view.note.take(1000)),
            "output" to JsonString(view.output.takeLast(3000)), "outputTruncated" to JsonBool(view.output.length > 3000),
            "phase" to JsonString(if (!commandStarted && !view.complete) "PREFLIGHT" else "EXECUTION"),
            "commandStarted" to JsonBool(commandStarted),
            "outputHash" to JsonString("sha256:" + Hashing.sha256(view.output).hex),
            "steps" to JsonArray(view.results.map { r -> JsonObject.of("step" to JsonNumber.of(r.step),
                "exitCode" to (r.exitCode?.let { JsonNumber.of(it) } ?: JsonNull), "reason" to JsonString(r.reason.take(300))) })))
    }
}
