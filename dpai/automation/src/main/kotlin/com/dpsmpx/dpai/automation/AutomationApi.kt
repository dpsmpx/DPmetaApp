package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.CanonicalJson

/** Общий протокол каталога, без зависимости от конкретного API-провайдера (D-215). */
object AutomationApi {
    data class Call(val id: String, val revision: String, val parameters: JsonObject)

    fun list(catalog: AutomationCatalog, input: JsonObject): Outcome<JsonObject> {
        if (input.fields.keys.any { it !in setOf("offset", "includeUi", "source") }) return invalid()
        val includeUi = when (val v = input["includeUi"]) { null -> false; is JsonBool -> v.value; else -> return invalid() }
        val source = when (val v = input["source"]) { null -> null; is JsonString -> AutomationSource.entries.firstOrNull { it.name == v.value } ?: return invalid(); else -> return invalid() }
        val entries = catalog.entries.filter { (includeUi || it.invocation == AutomationInvocation.DIRECT) && (source == null || it.source == source) }
        val offset = offset(input, "offset", entries.size) ?: return invalid()
        val page = arrayListOf<JsonObject>()
        for (d in entries.drop(offset).take(40)) {
            val summary = JsonObject.of(
            "id" to JsonString(d.id), "title" to JsonString(d.title), "source" to JsonString(d.source.name),
            "category" to JsonString(d.category.name), "revision" to JsonString(d.revision),
            "available" to JsonBool(d.available), "invocation" to JsonString(d.invocation.name),
            "reason" to (d.reason?.let(::JsonString) ?: JsonNull))
            if (CanonicalJson.write(JsonArray(page + summary)).length > 24_000) break
            page += summary
        }
        if (page.isEmpty() && offset < entries.size) return invalid()
        val next = offset + page.size
        return Outcome.success(JsonObject.of("entries" to JsonArray(page), "total" to JsonNumber.of(entries.size),
            "catalogTotal" to JsonNumber.of(catalog.entries.size), "includeUi" to JsonBool(includeUi),
            "nextOffset" to (next.takeIf { it < entries.size }?.let { JsonNumber.of(it) } ?: JsonNull)))
    }

    /** Параметры идут по одному, чтобы допустимый большой профиль не обрезал JSON результата. */
    fun describe(catalog: AutomationCatalog, input: JsonObject): Outcome<JsonObject> {
        if (input.fields.keys.any { it !in setOf("id", "parameterOffset") }) return invalid()
        val id = (input["id"] as? JsonString)?.value ?: return invalid()
        val d = catalog.byId(id) ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "автоматизации нет в каталоге", id)
        val offset = offset(input, "parameterOffset", d.parameters.size) ?: return invalid()
        val page = d.parameters.drop(offset).take(1)
        val next = offset + page.size
        val result = JsonObject(d.toJson().fields + mapOf(
            "inputSchema" to d.copy(parameters = page).inputSchema(),
            "parameterCount" to JsonNumber.of(d.parameters.size),
            "nextParameterOffset" to (next.takeIf { it < d.parameters.size }?.let { JsonNumber.of(it) } ?: JsonNull),
        ))
        return if (CanonicalJson.write(result).length <= 30_000) Outcome.success(result)
            else Outcome.failure(DiagCode.VALUE_INVALID, "описание слишком велико для одного ответа; откройте инструмент в IDE")
    }

    fun call(input: JsonObject): Outcome<Call> {
        if (input.fields.keys.any { it !in setOf("id", "revision", "parameters") }) return invalid()
        val id = (input["id"] as? JsonString)?.value?.takeIf { it.length in 1..120 } ?: return invalid()
        val revision = (input["revision"] as? JsonString)?.value?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return invalid()
        val params = when (val raw = input["parameters"]) { null -> emptyList(); is JsonArray -> raw.items; else -> return invalid() }
        if (params.size > 32) return invalid()
        val values = linkedMapOf<String, com.dpsmpx.dpai.core.serialization.JsonValue>()
        for (value in params) {
            val o = value as? JsonObject ?: return invalid()
            if (o.fields.keys != setOf("name", "value")) return invalid()
            val name = (o["name"] as? JsonString)?.value ?: return invalid()
            val text = o["value"] as? JsonString ?: return invalid()
            if (name in values || name.length > 64 || text.value.length > 4096) return invalid()
            values[name] = text
        }
        return Outcome.success(Call(id, revision, JsonObject(values)))
    }

    private fun offset(input: JsonObject, key: String, size: Int): Int? {
        if (key !in input.fields) return 0
        return (input[key] as? JsonNumber)?.toLongExact()?.takeIf { it in 0..size.toLong() }?.toInt()
    }

    private fun <T> invalid(): Outcome<T> = Outcome.failure(DiagCode.VALUE_INVALID, "неверные параметры вызова каталога")

    fun tools(): List<Triple<String, String, JsonObject>> {
        fun text() = JsonObject.of("type" to JsonString("string"))
        fun obj(properties: JsonObject, required: List<String> = emptyList()) = JsonObject.of("type" to JsonString("object"),
            "properties" to properties, "required" to JsonArray(required.map(::JsonString)))
        return listOf(
            Triple("list_automations", "Каталог выбранного проекта. По умолчанию только DIRECT, чтобы не тратить контекст. includeUi=true добавляет действия человека; source фильтрует источник. Повторяй с nextOffset, если он не null.",
                obj(JsonObject.of("offset" to JsonObject.of("type" to JsonString("integer")), "includeUi" to JsonObject.of("type" to JsonString("boolean")),
                    "source" to JsonObject.of("type" to JsonString("string"), "enum" to JsonArray(AutomationSource.entries.map { JsonString(it.name) }))))),
            Triple("describe_automation", "Полномочия, зависимости, ревизия и схема инструмента. Если nextParameterOffset не null, дочитай оставшиеся параметры.",
                obj(JsonObject.of("id" to text(), "parameterOffset" to JsonObject.of("type" to JsonString("integer"))), listOf("id"))),
            Triple("run_automation", "Вызвать DIRECT-инструмент с ревизией последнего describe/list. parameters — выбранные варианты; без них прежние настройки. OPEN_UI требует человека. Не меняй профиль через параметры.",
                obj(JsonObject.of("id" to text(), "revision" to text(), "parameters" to JsonObject.of("type" to JsonString("array"), "items" to
                    obj(JsonObject.of("name" to text(), "value" to text()), listOf("name", "value")))), listOf("id", "revision"))),
        )
    }
}
