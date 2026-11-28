package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.automation.AutomationCategory
import com.dpsmpx.dpai.automation.AutomationDefinition
import com.dpsmpx.dpai.automation.AutomationInvocation
import com.dpsmpx.dpai.automation.AutomationParameter
import com.dpsmpx.dpai.automation.AutomationSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.execution.target.TargetPlatform

/** Те же семь операций у API, UI, подписанного CLI и сценариев, D-224. */
object ContextTools {
    private fun field(name: String, title: String, required: Boolean = false, size: Int = 1000) =
        AutomationParameter(name, title, emptyList(), text = true, required = required, maxLength = size)
    private val objective = field("objective", "Цель задачи", true, 16_000)
    private val paths = field("paths", "Пути как JSON-массив", size = 20_000)
    private val symbol = listOf(field("path", "Сохранённый файл", true), field("sha256", "SHA-256 файла", true, 64),
        field("line", "Строка с 1", size = 8), field("column", "Столбец UTF-16 с 1", size = 8))
    private val definitions = listOf(
        Triple("task", "Контекст задачи", listOf(objective, paths, field("acceptance", "Критерии приёмки", size = 8000), field("related", "Связанные задачи", size = 4000))),
        Triple("symbol", "Контекст символа", symbol),
        Triple("interfaces", "Связанные определения LSP", symbol),
        Triple("constraints", "Правила и ограничения архитектуры", listOf(paths)),
        Triple("history", "История изменений Git", emptyList()),
        Triple("evidence", "Доказательства прошлых проверок", emptyList()),
        Triple("expand", "Расширить контекст в разрешённой области", listOf(objective, paths.copy(required = true), field("reason", "Зачем требуется расширение", true))),
    )
    val aliases = linkedMapOf("get_task_context" to "task", "get_symbol_context" to "symbol", "get_related_interfaces" to "interfaces",
        "get_architecture_constraints" to "constraints", "get_change_history" to "history", "get_test_evidence" to "evidence", "expand_context" to "expand")
    val ids = definitions.map { "context.${it.first}" }.toSet()
    fun describe(root: String, platform: TargetPlatform, inApp: Boolean = true) = definitions.map { (id, title, parameters) ->
        val semantic = id in setOf("symbol", "interfaces")
        AutomationDefinition("context.$id", title, "Контекст по запросу. Неполнота и происхождение указаны; профиль доступа меняет только человек.",
            AutomationSource.DEVELOPMENT, AutomationCategory.REPOSITORY, Hashing.sha256("ContextTools:1:$id").hex, ActionLevel.READ,
            listOf(root), AutomationInvocation.DIRECT, !semantic || inApp, reason = if (semantic && !inApp) "языковая служба редактора не подключена на SSH" else null, parameters = parameters, platforms = setOf(platform), timeoutSeconds = 60,
            remote = !semantic, deterministic = false, idempotent = true, cancellable = true,
            verification = "Хеши входов и границы источника; история не выдаётся за свежую проверку", triggers = setOf("MANUAL", "AI"))
    }
    fun tools(): List<Triple<String, String, JsonObject>> = aliases.map { (name, id) ->
        val d = definitions.first { it.first == id }
        Triple(name, d.second + ". Только разрешённая область текущего профиля; никаких новых грантов.", JsonObject.of("type" to json("object"),
            "additionalProperties" to json(false), "properties" to JsonObject(d.third.associate { p -> p.name to
                if (p.name == "paths") JsonObject.of("type" to json("array"), "maxItems" to json(20), "items" to JsonObject.of("type" to json("string")))
                else JsonObject.of("type" to json("string"), "maxLength" to json(p.maxLength)) }),
            "required" to JsonArray(d.third.filter { it.required }.map { json(it.name) })))
    }
    fun request(name: String, input: JsonObject): Outcome<Pair<String, Map<String, String>>> {
        val id = aliases[name] ?: return invalid()
        val values = linkedMapOf<String, String>()
        for ((key, v) in input.fields) {
            if (key == "paths") {
                if (v !is JsonArray || v.items.size > 20 || v.items.any { it !is JsonString }) return invalid()
                values[key] = CanonicalJson.write(v)
            } else values[key] = (v as? JsonString)?.value ?: return invalid()
        }
        return if (valid("context.$id", values)) Outcome.success("context.$id" to values) else invalid()
    }
    fun valid(id: String, values: Map<String, String>): Boolean {
        val fields = definitions.firstOrNull { "context.${it.first}" == id }?.third ?: return false
        return values.all { (key, value) -> fields.any { it.name == key && it.accepts(value) } } &&
            fields.all { !it.required || values[it.name]?.isNotBlank() == true }
    }
    fun paths(values: Map<String, String>): Outcome<List<String>> {
        val raw = values["paths"] ?: return Outcome.success(emptyList())
        val parsed = JsonParser().parse(raw).valueOrNull() as? JsonArray ?: return invalid()
        if (parsed.items.size > 20 || parsed.items.any { it !is JsonString }) return invalid()
        val paths = parsed.items.map { (it as JsonString).value }
        return if (paths.distinct().size == paths.size && paths.all(DevelopmentTools::safePath)) Outcome.success(paths) else invalid()
    }
    private fun <T> invalid(): Outcome<T> = Outcome.failure(DiagCode.VALUE_INVALID, "неверные параметры инструмента контекста")
}
