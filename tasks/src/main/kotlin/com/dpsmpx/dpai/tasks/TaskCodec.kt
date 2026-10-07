package com.dpsmpx.dpai.tasks

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.state.TaskState
import kotlin.time.Duration.Companion.milliseconds

/**
 * События задач в конвертах `dpai.event` (поток — задача) и состояние
 * задач в снимке `dpai.tasks-state` v1 (`docs/DATA_MODEL.md`, §4).
 * Чтение строгое (D-09); запись в журнал — канонический JSON.
 */
object TaskCodec {
    const val STATE_FORMAT = "dpai.tasks-state"
    /** v2 — заявленная область задачи (`scope`); v1 читается, область неизвестна. */
    const val STATE_VERSION = 2

    fun type(e: TaskEvent): EventType = when (e) {
        is TaskEvent.Created -> EventType.TASK_CREATED
        is TaskEvent.StateChanged -> EventType.TASK_STATE_CHANGED
        is TaskEvent.DependenciesChanged -> EventType.TASK_DEPENDENCIES_CHANGED
        is TaskEvent.PriorityChanged -> EventType.TASK_PRIORITY_CHANGED
        is TaskEvent.AttemptFailed -> EventType.TASK_ATTEMPT_FAILED
        is TaskEvent.Continued -> EventType.TASK_CONTINUED
        is TaskEvent.VerificationRecorded -> EventType.TASK_VERIFICATION_RECORDED
        is TaskEvent.ScopeChanged -> EventType.TASK_SCOPE_CHANGED
    }

    fun stream(e: TaskEvent): StreamRef = StreamRef.of(e.task)

    fun payload(e: TaskEvent): JsonObject = when (e) {
        is TaskEvent.Created -> obj(
            "task" to id(e.task), "title" to JsonString(e.title), "objective" to JsonString(e.objective),
            "priority" to JsonString(e.priority.name), "dependencies" to ids(e.dependencies),
            "capabilities" to JsonArray(e.capabilities.sorted().map(::JsonString)), "policy" to policy(e.policy),
        )
        is TaskEvent.StateChanged -> obj(
            "task" to id(e.task), "from" to JsonString(e.from.name), "to" to JsonString(e.to.name),
            "reason" to (e.reason?.let { JsonString(it.name) } ?: JsonNull), "notBefore" to time(e.notBefore),
        )
        is TaskEvent.DependenciesChanged -> obj("task" to id(e.task), "dependencies" to ids(e.dependencies))
        is TaskEvent.PriorityChanged -> obj(
            "task" to id(e.task), "priority" to JsonString(e.priority.name), "manual" to (e.manual?.let { JsonNumber.of(it) } ?: JsonNull),
        )
        is TaskEvent.AttemptFailed -> obj("task" to id(e.task), "loopKey" to JsonString(e.loopKey), "code" to JsonString(e.code.name))
        is TaskEvent.Continued -> obj("task" to id(e.task))
        is TaskEvent.VerificationRecorded -> obj("task" to id(e.task)).let { JsonObject(it.fields + verification(e.record).fields) }
        is TaskEvent.ScopeChanged -> obj("task" to id(e.task), "scope" to paths(e.scope))
    }

    fun decode(type: EventType, at: Timestamp, payload: JsonObject): Outcome<TaskEvent>? {
        val d = DiagnosticCollector()
        val f = JsonFields(payload, "$.payload", d)
        val event: TaskEvent? = when (type) {
            EventType.TASK_CREATED -> {
                val task = f.taskId("task")
                val title = f.string("title")
                val objective = f.string("objective")
                val priority = f.enumOf<Priority>("priority")
                val deps = f.taskIds("dependencies")
                val caps = f.array("capabilities")?.items?.map { (it as? JsonString)?.value }
                val pol = f.obj("policy")?.let(::readPolicy)
                if (caps != null && caps.any { it == null }) d.error(DiagCode.FIELD_TYPE, "ожидались строки", "$.payload.capabilities")
                if (task != null && title != null && objective != null && priority != null && deps != null && caps != null && caps.none { it == null } && pol != null) {
                    TaskEvent.Created(task, title, objective, priority, deps, caps.filterNotNull().toSet(), pol, at)
                } else {
                    null
                }
            }
            EventType.TASK_STATE_CHANGED -> {
                val task = f.taskId("task")
                val from = f.enumOf<TaskState>("from")
                val to = f.enumOf<TaskState>("to")
                val reason = f.optString("reason")?.let { code(it, d) }
                val notBefore = f.optLong("notBefore")?.let(::Timestamp)
                if (task != null && from != null && to != null) TaskEvent.StateChanged(task, from, to, reason, notBefore, at) else null
            }
            EventType.TASK_DEPENDENCIES_CHANGED -> {
                val task = f.taskId("task")
                val deps = f.taskIds("dependencies")
                if (task != null && deps != null) TaskEvent.DependenciesChanged(task, deps, at) else null
            }
            EventType.TASK_PRIORITY_CHANGED -> {
                val task = f.taskId("task")
                val priority = f.enumOf<Priority>("priority")
                val manual = f.optLong("manual")?.toInt()
                if (task != null && priority != null) TaskEvent.PriorityChanged(task, priority, manual, at) else null
            }
            EventType.TASK_ATTEMPT_FAILED -> {
                val task = f.taskId("task")
                val key = f.string("loopKey")
                val c = f.string("code")?.let { code(it, d) }
                if (task != null && key != null && c != null) TaskEvent.AttemptFailed(task, key, c, at) else null
            }
            EventType.TASK_CONTINUED -> f.taskId("task")?.let { TaskEvent.Continued(it, at) }
            EventType.TASK_VERIFICATION_RECORDED -> {
                val task = f.taskId("task")
                val record = readVerification(f)
                if (task != null && record != null) TaskEvent.VerificationRecorded(task, record, at) else null
            }
            EventType.TASK_SCOPE_CHANGED -> {
                val task = f.taskId("task")
                val scope = f.paths("scope")
                if (task != null && scope != null) TaskEvent.ScopeChanged(task, scope, at) else null
            }
            else -> return null
        }
        f.finish()
        return if (d.hasErrors || event == null) {
            Outcome.Failure(d.all.ifEmpty { listOf(Diagnostic.error(DiagCode.FIELD_MISSING, "событие задачи неполно", "$.payload")) })
        } else {
            Outcome.success(event)
        }
    }

    // ---- снимок ----

    fun encodeState(s: TasksState): JsonObject = JsonObject(
        linkedMapOf(*FormatHeader.write(STATE_FORMAT, STATE_VERSION), "tasks" to JsonArray(s.tasks.values.sortedBy { it.id.value }.map(::encodeRecord))),
    )

    private fun encodeRecord(t: TaskRecord): JsonObject = obj(
        "id" to id(t.id), "title" to JsonString(t.title), "objective" to JsonString(t.objective), "priority" to JsonString(t.priority.name),
        "dependencies" to ids(t.dependencies), "capabilities" to JsonArray(t.capabilities.sorted().map(::JsonString)), "policy" to policy(t.policy),
        "createdAt" to JsonNumber.of(t.createdAt.epochMillis), "state" to JsonString(t.state.name),
        "manualPriority" to (t.manualPriority?.let { JsonNumber.of(it) } ?: JsonNull), "queuedAt" to time(t.queuedAt),
        "updatedAt" to JsonNumber.of(t.updatedAt.epochMillis), "retries" to JsonNumber.of(t.retries), "continuations" to JsonNumber.of(t.continuations),
        "notBefore" to time(t.notBefore), "lastReason" to (t.lastReason?.let { JsonString(it.name) } ?: JsonNull),
        "failures" to JsonObject(t.failures.toSortedMap().mapValues { JsonNumber.of(it.value) }),
        "verification" to (t.verification?.let(::verification) ?: JsonNull),
        "scope" to (t.scope?.let(::paths) ?: JsonNull),
    )

    fun decodeState(json: JsonObject): Outcome<TasksState> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        val version = FormatHeader.read(f, STATE_FORMAT, 1..STATE_VERSION, d) ?: return Outcome.Failure(d.all)
        val records = f.array("tasks")?.items.orEmpty().mapIndexedNotNull { i, item ->
            val o = item as? JsonObject ?: return@mapIndexedNotNull null.also { d.error(DiagCode.FIELD_TYPE, "ожидался объект", "$.tasks[$i]") }
            val r = JsonFields(o, "$.tasks[$i]", d)
            val rec = runCatching {
                TaskRecord(
                    id = r.taskId("id")!!,
                    title = r.string("title")!!,
                    objective = r.string("objective")!!,
                    priority = r.enumOf<Priority>("priority")!!,
                    dependencies = r.taskIds("dependencies")!!,
                    capabilities = r.array("capabilities")!!.items.map { (it as JsonString).value }.toSet(),
                    policy = readPolicy(r.obj("policy")!!)!!,
                    createdAt = Timestamp(r.long("createdAt")!!),
                    state = r.enumOf<TaskState>("state")!!,
                    manualPriority = r.optLong("manualPriority")?.toInt(),
                    queuedAt = r.optLong("queuedAt")?.let(::Timestamp),
                    updatedAt = Timestamp(r.long("updatedAt")!!),
                    retries = r.int("retries")!!,
                    continuations = r.int("continuations")!!,
                    notBefore = r.optLong("notBefore")?.let(::Timestamp),
                    lastReason = r.optString("lastReason")?.let { n -> DiagCode.entries.first { it.name == n } },
                    failures = (r.raw("failures") as JsonObject).fields.mapValues { (it.value as JsonNumber).toLongExact()!!.toInt() },
                    verification = r.optObj("verification")?.let { v -> readVerification(v).also { v.finish() } },
                    scope = if (version >= 2 && r.has("scope")) r.paths("scope") else null,
                )
            }.getOrNull()
            r.finish()
            rec ?: null.also { d.error(DiagCode.VALUE_INVALID, "запись задачи не читается", "$.tasks[$i]") }
        }
        f.finish()
        if (d.hasErrors) return Outcome.Failure(d.all)
        return Outcome.success(TasksState(records.associateBy { it.id }))
    }

    private fun policy(p: TaskPolicy): JsonObject = obj(
        "maxRetries" to JsonNumber.of(p.maxRetries), "retryBackoffMs" to JsonNumber.of(p.retryBackoff.inWholeMilliseconds),
        "maxContinuations" to JsonNumber.of(p.maxContinuations), "turnTimeoutMs" to JsonNumber.of(p.turnTimeout.inWholeMilliseconds),
        "maxBudgetUsdCents" to (p.maxBudgetUsdCents?.let { JsonNumber.of(it) } ?: JsonNull),
    )

    private fun readPolicy(f: JsonFields): TaskPolicy? {
        val maxRetries = f.int("maxRetries")
        val backoff = f.long("retryBackoffMs")
        val maxCont = f.int("maxContinuations")
        val timeout = f.long("turnTimeoutMs")
        val budget = f.optLong("maxBudgetUsdCents")
        f.finish()
        if (maxRetries == null || backoff == null || maxCont == null || timeout == null) return null
        return runCatching { TaskPolicy(maxRetries, backoff.milliseconds, maxCont, timeout.milliseconds, budget) }.getOrNull()
            ?: null.also { f.diagnostics.error(DiagCode.VALUE_INVALID, "политика задачи недопустима", f.path) }
    }

    private fun verification(v: VerificationRecord): JsonObject = obj(
        "verdict" to JsonString(v.verdict),
        "reportHash" to (v.reportHash?.let(::JsonString) ?: JsonNull),
        "waivedBy" to (v.waivedBy?.let(::JsonString) ?: JsonNull),
        "reason" to (v.reason?.let(::JsonString) ?: JsonNull),
    )

    /** Поля записи проверки в [f]; закрывает чтение полей вызывающий. */
    private fun readVerification(f: JsonFields): VerificationRecord? {
        val verdict = f.string("verdict") ?: return null
        return VerificationRecord(verdict, f.optString("reportHash"), f.optString("waivedBy"), f.optString("reason"))
    }

    private fun code(name: String, d: DiagnosticCollector): DiagCode? =
        DiagCode.entries.firstOrNull { it.name == name } ?: null.also { d.error(DiagCode.VALUE_INVALID, "неизвестный код", "$.payload") }

    private fun obj(vararg pairs: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*pairs).filterValues { it != JsonNull })

    private fun id(v: TaskId) = JsonString(v.value)

    private fun paths(v: Set<String>) = JsonArray(v.sorted().map(::JsonString))

    private fun JsonFields.paths(name: String): Set<String>? = array(name)?.items?.let { items ->
        val out = items.map { (it as? JsonString)?.value }
        if (out.any { it == null }) null.also { diagnostics.error(DiagCode.FIELD_TYPE, "ожидались строки", "$path.$name") } else out.filterNotNull().toSet()
    }

    private fun ids(v: Set<TaskId>) = JsonArray(v.map { it.value }.sorted().map(::JsonString))

    private fun time(t: Timestamp?): JsonValue = t?.let { JsonNumber.of(it.epochMillis) } ?: JsonNull

    private fun JsonFields.taskId(name: String): TaskId? = string(name)?.let { v ->
        IdKinds.TASK.parse(v).valueOrNull() ?: null.also { diagnostics.error(DiagCode.ID_INVALID, "ожидался TaskId", "$path.$name") }
    }

    private fun JsonFields.taskIds(name: String): Set<TaskId>? = array(name)?.items?.mapNotNull { item ->
        (item as? JsonString)?.value?.let { IdKinds.TASK.parse(it).valueOrNull() } ?: null.also { diagnostics.error(DiagCode.ID_INVALID, "ожидался TaskId", "$path.$name") }
    }?.toSet()

    private inline fun <reified E : Enum<E>> JsonFields.enumOf(name: String): E? = string(name)?.let { v ->
        enumValues<E>().firstOrNull { it.name == v } ?: null.also { diagnostics.error(DiagCode.VALUE_INVALID, "неизвестное значение", "$path.$name") }
    }
}
