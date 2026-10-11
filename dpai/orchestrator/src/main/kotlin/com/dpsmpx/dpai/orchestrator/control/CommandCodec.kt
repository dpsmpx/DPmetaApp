package com.dpsmpx.dpai.orchestrator.control

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TypedId
import com.dpsmpx.dpai.core.operation.OperationResult
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.notifications.RequestKind
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskClass
import com.dpsmpx.dpai.tasks.TaskPolicy
import kotlin.time.Duration.Companion.minutes

/**
 * Команды оркестратора в JSON (D-47): то, что `dpai` шлёт приложению.
 * Поле `command` — имя операции (`task.hold`, `session.send`, …), остальные
 * — аргументы; разбор строгий, неизвестное поле — отказ (D-09).
 * `confirmed` — человек подтвердил опасное (ТЗ, §68): без него отмена и
 * исключение не исполняются.
 */
object CommandCodec {
    data class Parsed(val command: Command, val confirmed: Boolean)

    /** Опасные команды: теряют несохранённую работу или записывают «готово» без проверки. */
    fun dangerous(c: Command): Boolean = c is Command.Cancel || c is Command.Waive

    fun encode(c: Command, confirmed: Boolean = false): JsonObject {
        val f = linkedMapOf<String, JsonValue>("command" to JsonString(name(c)))
        fun id(k: String, v: TypedId) = f.put(k, JsonString(v.value))
        fun str(k: String, v: String?) = v?.let { f.put(k, JsonString(it)) }
        when (c) {
            is Command.AnswerRequest -> { str("uri", c.uri); str("text", c.text); str("channel", c.channel) }
            is Command.Delivered -> { id("request", c.request); str("channel", c.channel) }
            is Command.Probe -> { str("kind", c.kind.name); c.ttl?.let { f["ttlMinutes"] = JsonNumber.of(it.inWholeMinutes) } }
            is Command.AddTask -> {
                str("title", c.title); str("objective", c.objective); str("priority", c.priority.name)
                str("taskClass", c.policy.taskClass.name); str("model", c.policy.model); str("effort", c.policy.effort)
                c.policy.maxBudgetUsdCents?.let { f["budgetCents"] = JsonNumber.of(it) }
                if (c.dependencies.isNotEmpty()) f["dependencies"] = JsonArray(c.dependencies.map { it.value }.sorted().map(::JsonString))
                c.scope?.let { sc -> f["scope"] = JsonArray(sc.sorted().map(::JsonString)) }
            }
            is Command.SetPriority -> { id("task", c.task); str("priority", c.priority.name) }
            is Command.SetPolicy -> {
                id("task", c.task); str("taskClass", c.taskClass.name); str("model", c.model); str("effort", c.effort)
                c.maxBudgetUsdCents?.let { f["budgetCents"] = JsonNumber.of(it) }
            }
            is Command.Hold -> id("task", c.task)
            is Command.Resume -> id("task", c.task)
            is Command.Cancel -> id("task", c.task)
            is Command.Retry -> { id("task", c.task); str("text", c.text) }
            is Command.Verify -> id("task", c.task)
            is Command.Waive -> { id("task", c.task); str("reason", c.reason) }
            is Command.StopSession -> id("session", c.session)
            is Command.SendToSession -> { id("session", c.session); str("text", c.text); str("model", c.model); str("effort", c.effort) }
            is Command.SetNearPercent -> { id("subscription", c.subscription); f["percent"] = JsonNumber.of(c.percent) }
            is Command.SetRotation -> { id("subscription", c.subscription); f["allowed"] = JsonBool(c.allowed) }
            is Command.SetTargetEnabled -> { id("target", c.target); f["enabled"] = JsonBool(c.enabled) }
            is Command.SetSlotEnabled -> { id("slot", c.slot); f["enabled"] = JsonBool(c.enabled) }
            is Command.SetSessionLimit -> c.limit?.let { f["limit"] = JsonNumber.of(it) }
            is Command.Archive -> { id("task", c.task); f["archived"] = JsonBool(c.archived) }
            is Command.ArchiveFinished -> Unit
        }
        str("key", c.key)
        if (confirmed) f["confirmed"] = JsonBool(true)
        return JsonObject(f)
    }

    /** Имя в JSON: операция, у «свежей сессии» — своё. */
    fun name(c: Command): String = c.operation

    /**
     * Разбор команды от `dpai`; [by] — кто (в происхождение итога). Команды,
     * которые только у оболочки (`request.delivered`), снаружи не принимаются.
     */
    fun decode(json: JsonObject, by: String): Outcome<Parsed> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        val name = f.string("command") ?: return Outcome.Failure(d.all)
        val key = f.optString("key")
        val confirmed = if (f.has("confirmed")) f.boolean("confirmed") ?: false else false
        fun task() = f.string("task")?.let { IdKinds.TASK.parse(it).valueOrNull() ?: null.also { d.error(DiagCode.ID_INVALID, "ожидался TaskId", "$.task") } }
        fun session() = f.string("session")?.let { IdKinds.SESSION.parse(it).valueOrNull() ?: null.also { d.error(DiagCode.ID_INVALID, "ожидался SessionId", "$.session") } }
        // Необязательный массив строк: нет поля — `null`, не пустой список.
        fun strings(k: String): List<String>? = if (!f.has(k)) null else f.array(k)?.items?.mapIndexedNotNull { i, v ->
            (v as? JsonString)?.value ?: null.also { d.error(DiagCode.VALUE_INVALID, "ожидалась строка", "$.$k[$i]") }
        }
        fun <E : Enum<E>> enum(k: String, values: Array<E>, required: Boolean = true): E? =
            (if (required) f.string(k) else f.optString(k))?.let { v -> values.firstOrNull { it.name == v } ?: null.also { d.error(DiagCode.VALUE_INVALID, "неизвестное значение", "$.$k") } }
        val c: Command? = when (name) {
            "request.answer" -> f.string("uri")?.let { Command.AnswerRequest(it, f.optString("text"), by, f.optString("channel") ?: "cli") }
            "request.probe" -> enum("kind", RequestKind.entries.toTypedArray())?.let { Command.Probe(it, f.optLong("ttlMinutes")?.minutes, key) }
            "task.add" -> {
                val title = f.string("title")
                val objective = f.string("objective")
                val priority = enum("priority", Priority.entries.toTypedArray(), required = false) ?: Priority.NORMAL
                val cls = enum("taskClass", TaskClass.entries.toTypedArray(), required = false) ?: TaskClass.NORMAL_ENGINEERING
                val policy = runCatching { TaskPolicy(taskClass = cls, model = f.optString("model"), effort = f.optString("effort"), maxBudgetUsdCents = f.optLong("budgetCents")) }
                    .getOrElse { d.error(DiagCode.VALUE_INVALID, it.message ?: "политика недопустима", "$"); null }
                val dependencies = strings("dependencies").orEmpty().mapIndexedNotNull { i, v ->
                    IdKinds.TASK.parse(v).valueOrNull() ?: null.also { d.error(DiagCode.ID_INVALID, "ожидался TaskId", "$.dependencies[$i]") }
                }.toSet()
                val scope = strings("scope")?.toSet()
                if (title != null && objective != null && policy != null) Command.AddTask(title, objective, priority, policy, dependencies, scope, key) else null
            }
            "task.priority" -> task()?.let { t -> enum("priority", Priority.entries.toTypedArray())?.let { Command.SetPriority(t, it, key) } }
            "task.policy" -> task()?.let { t ->
                enum("taskClass", TaskClass.entries.toTypedArray())?.let { Command.SetPolicy(t, it, f.optString("model"), f.optString("effort"), f.optLong("budgetCents"), key) }
            }
            "task.hold" -> task()?.let { Command.Hold(it, key) }
            "task.resume" -> task()?.let { Command.Resume(it, key) }
            "task.cancel" -> task()?.let { Command.Cancel(it, key) }
            "task.retry" -> task()?.let { Command.Retry(it, f.optString("text"), key) }
            "task.verify" -> task()?.let { Command.Verify(it, key) }
            "task.waive" -> task()?.let { t -> f.string("reason")?.let { Command.Waive(t, it, by, key) } }
            "session.stop" -> session()?.let { Command.StopSession(it, key) }
            "session.send", "session.fresh" -> session()?.let { Command.SendToSession(it, f.optString("text"), f.optString("model"), f.optString("effort"), name == "session.fresh", key) }
            "subscription.near-percent" -> f.string("subscription")?.let { IdKinds.SUBSCRIPTION.parse(it).valueOrNull() }
                ?.let { s -> f.int("percent")?.let { Command.SetNearPercent(s, it, key) } }
            "subscription.rotation" -> f.string("subscription")?.let { IdKinds.SUBSCRIPTION.parse(it).valueOrNull() }
                ?.let { s -> f.boolean("allowed")?.let { Command.SetRotation(s, it, key) } }
            "target.enabled" -> f.string("target")?.let { IdKinds.TARGET.parse(it).valueOrNull() }?.let { t -> f.boolean("enabled")?.let { Command.SetTargetEnabled(t, it, key) } }
            "slot.enabled" -> f.string("slot")?.let { IdKinds.ACCOUNT.parse(it).valueOrNull() }?.let { s -> f.boolean("enabled")?.let { Command.SetSlotEnabled(s, it, key) } }
            "control.session-limit" -> Command.SetSessionLimit(f.optLong("limit")?.toInt(), key)
            "task.archive" -> task()?.let { t -> f.boolean("archived")?.let { Command.Archive(t, it, key) } }
            "tasks.archive-done" -> Command.ArchiveFinished(key)
            else -> null.also { d.error(DiagCode.UNSUPPORTED, "неизвестная команда: $name", "$.command") }
        }
        f.finish()
        if (d.hasErrors || c == null) return Outcome.Failure(d.all.ifEmpty { listOf(Diagnostic.error(DiagCode.FIELD_MISSING, "команда «$name» неполна", "$")) })
        return Outcome.success(Parsed(c, confirmed))
    }

    /** Итог команды в JSON: операция, статус, значение строкой, диагностика. */
    fun result(r: OperationResult<*>): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>(
            "operation" to JsonString(r.operation), "operationId" to JsonString(r.operationId.value), "status" to JsonString(r.status.name),
        ).apply {
            r.value?.let { v -> put("value", JsonString((v as? TypedId)?.value ?: v.toString())) }
            put("diagnostics", diagnostics(r.diagnostics))
        },
    )

    fun diagnostics(ds: List<Diagnostic>): JsonArray = JsonArray(
        ds.map { d ->
            JsonObject(
                linkedMapOf<String, JsonValue>("code" to JsonString(d.code.name), "severity" to JsonString(d.severity.name), "message" to JsonString(d.message)).apply {
                    d.subject?.let { put("subject", JsonString(it)) }
                },
            )
        },
    )
}
