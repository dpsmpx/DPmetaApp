package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString

/** Модель вызывает только сохранённые и явно включённые человеком сценарии. */
object WorkflowApi {
    fun list(workflows: List<Workflow>, input: JsonObject): Outcome<JsonObject> {
        if (input.fields.keys.any { it != "offset" }) return invalid()
        val offset = if ("offset" !in input.fields) 0 else
            (input["offset"] as? JsonNumber)?.toLongExact()?.takeIf { it in 0..workflows.size.toLong() }?.toInt() ?: return invalid()
        val page = arrayListOf<JsonObject>()
        var chars = 0
        for (w in workflows.sortedBy { it.id }.drop(offset).take(40)) {
            val value = JsonObject.of("id" to JsonString(w.id),
                "title" to JsonString(com.dpsmpx.dpai.core.diagnostics.Redactor.STANDARD.redact(w.title)),
                "revision" to JsonString(w.revision), "enabled" to JsonBool(w.enabled),
                "triggers" to JsonArray(w.triggers.map { JsonString(it.type.name) }))
            val length = com.dpsmpx.dpai.core.serialization.CanonicalJson.write(value).length
            if (chars + length > 24_000 && page.isNotEmpty()) break
            chars += length; page += value
        }
        val next = offset + page.size
        return Outcome.success(JsonObject.of("workflows" to JsonArray(page),
            "nextOffset" to (next.takeIf { it < workflows.size }?.let { JsonNumber.of(it) } ?: JsonNull)))
    }

    fun tools(): List<Triple<String, String, JsonObject>> {
        val text = JsonObject.of("type" to JsonString("string"))
        return listOf(
            Triple("list_workflows", "Сохранённые сценарии проекта; включение делает человек. Дочитай nextOffset.",
                JsonObject.of("type" to JsonString("object"), "properties" to JsonObject.of("offset" to JsonObject.of("type" to JsonString("integer"))))),
            Triple("run_workflow", "Вызвать включённый сценарий с триггером AI и точной ревизией. Права и подтверждения каждого шага сохраняются; готовность задачи не меняется.",
                JsonObject.of("type" to JsonString("object"), "properties" to JsonObject.of("id" to text, "revision" to text),
                    "required" to JsonArray(listOf(JsonString("id"), JsonString("revision"))))),
        )
    }
    private fun <T> invalid(): Outcome<T> = Outcome.failure(DiagCode.VALUE_INVALID, "неверный запрос сценария")
}
