package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/** События поставляет приложение, а не текст ответа модели (D-222). */
enum class AutomationTrigger { MANUAL, AI, EVENT, BEFORE_BUILD, AFTER_BUILD, FILES_CHANGED, BEFORE_COMMIT, BEFORE_ACCEPT, SCHEDULE }

data class WorkflowTrigger(val type: AutomationTrigger, val event: String = "", val everySeconds: Long? = null) {
    fun json() = JsonObject(mapOf("type" to JsonString(type.name), "event" to JsonString(event)) +
        (everySeconds?.let { mapOf("everySeconds" to JsonNumber.of(it)) } ?: emptyMap()))
}

data class WorkflowStep(val id: String, val revision: String, val parameters: JsonObject = JsonObject.of()) {
    fun json() = JsonObject.of("id" to JsonString(id), "revision" to JsonString(revision), "parameters" to parameters)
}

/** Ссылки на прежние инструменты; исполняемого текста у сценария нет. */
data class Workflow(val id: String, val title: String, val context: String, val steps: List<WorkflowStep>,
    val triggers: List<WorkflowTrigger> = listOf(WorkflowTrigger(AutomationTrigger.MANUAL)),
    val enabled: Boolean = false, val timeoutSeconds: Long = 3600) {
    val revision: String get() = Hashing.sha256(json()).hex
    fun json() = JsonObject.of("format" to JsonString("dpai.workflow"), "version" to JsonNumber.of(1),
        "id" to JsonString(id), "title" to JsonString(title), "context" to JsonString(context), "enabled" to JsonBool(enabled),
        "timeoutSeconds" to JsonNumber.of(timeoutSeconds), "steps" to JsonArray(steps.map { it.json() }),
        "triggers" to JsonArray(triggers.map { it.json() }))

    companion object {
        internal val ID = Regex("[a-z][a-z0-9_.-]{0,119}")
        internal val HASH = Regex("[a-f0-9]{64}")
        internal fun storedId(id: String) = ID.matches(id)

        /** Один строгий декодер для UI, CLI, API и чтения собственного документа. */
        fun parse(o: JsonObject): Outcome<Workflow> = try {
            o.only("format", "version", "id", "title", "context", "enabled", "timeoutSeconds", "steps", "triggers")
            require(o.text("format") == "dpai.workflow" && o.number("version") == 1L)
            val steps = o.array("steps").map { raw ->
                val s = raw as? JsonObject ?: error("step")
                s.only("id", "revision", "parameters")
                val p = s["parameters"] as? JsonObject ?: error("parameters")
                require(p.fields.size <= 32 && p.fields.all { (k, v) ->
                    k.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) && v is JsonString && v.value.length <= 4096 && '\u0000' !in v.value
                })
                val serialized = CanonicalJson.write(p)
                require(com.dpsmpx.dpai.core.diagnostics.Redactor.STANDARD.redact(serialized) == serialized)
                WorkflowStep(s.text("id"), s.text("revision"), p).also { require(ID.matches(it.id) && HASH.matches(it.revision)) }
            }
            val triggers = o.array("triggers").map { raw ->
                val t = raw as? JsonObject ?: error("trigger")
                t.only("type", "event", "everySeconds")
                WorkflowTrigger(AutomationTrigger.valueOf(t.text("type")), t.text("event"),
                    if ("everySeconds" in t.fields) t.number("everySeconds") else null).also {
                    require(it.event.length <= 120 && (it.event.isEmpty() || ID.matches(it.event)))
                    require((it.type == AutomationTrigger.SCHEDULE) == (it.everySeconds != null))
                    require(it.everySeconds == null || it.everySeconds in 60..31_536_000)
                    require((it.type == AutomationTrigger.EVENT) == it.event.isNotEmpty())
                }
            }
            Workflow(o.text("id"), o.text("title"), o.text("context"), steps, triggers,
                (o["enabled"] as? JsonBool)?.value ?: error("enabled"), o.number("timeoutSeconds")).also {
                require(storedId(it.id) && it.title.isNotBlank() && it.title.length <= 200 && HASH.matches(it.context))
                require(it.steps.size in 1..20 && it.triggers.size in 1..16 && it.triggers.distinct().size == it.triggers.size)
                require(it.timeoutSeconds in 1..86_400 && CanonicalJson.write(o).length <= 256_000)
            }.let { Outcome.success(it) }
        } catch (_: IllegalArgumentException) { invalid() } catch (_: IllegalStateException) { invalid() }

        private fun invalid(): Outcome<Workflow> = Outcome.failure(DiagCode.VALUE_INVALID, "неверный сценарий автоматизации")
        private fun JsonObject.only(vararg names: String) { require(fields.keys.all { it in names }) }
        private fun JsonObject.text(key: String) = (this[key] as? JsonString)?.value ?: error(key)
        private fun JsonObject.number(key: String) = (this[key] as? JsonNumber)?.toLongExact() ?: error(key)
        private fun JsonObject.array(key: String): List<JsonValue> = (this[key] as? JsonArray)?.items ?: error(key)
    }
}

/** fingerprint — наблюдаемый снимок; lineage предотвращает цикл без прогресса. */
data class AutomationEvent(val id: String, val type: AutomationTrigger, val context: String, val fingerprint: String,
    val name: String = "", val lineage: Set<String> = emptySet()) {
    fun accepts(trigger: WorkflowTrigger) = type == trigger.type && (type != AutomationTrigger.EVENT || name == trigger.event)
    fun valid() = id.length in 1..200 && '\u0000' !in id && Workflow.HASH.matches(context) &&
        Workflow.HASH.matches(fingerprint) && lineage.size <= 64 && lineage.all { Workflow.HASH.matches(it) } &&
        (name.isEmpty() || Workflow.ID.matches(name))
}
