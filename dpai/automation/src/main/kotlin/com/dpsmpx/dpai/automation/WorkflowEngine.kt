package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

enum class WorkflowStatus { RUNNING, PASSED, FAILED, CANCELLED, BLOCKED, INCOMPLETE, NEEDS_REVIEW }

/** Последовательный координатор прежних вызовов; не запускает процессы или потоки (D-222). */
class WorkflowEngine(private val store: WorkflowStore, private val clock: Clock) {
    fun run(id: String, revision: String, event: AutomationEvent, cancelled: () -> Boolean,
        catalog: () -> Outcome<AutomationCatalog>, authorize: (Workflow, AutomationEvent) -> Outcome<Unit>,
        execute: (WorkflowStep, () -> Boolean, Set<String>) -> Outcome<JsonObject>,
        progress: (Int, Int) -> Unit = { _, _ -> }): Outcome<JsonObject> = store.locked {
        val w = when (val r = store.workflow(id)) { is Outcome.Failure -> return@locked r; is Outcome.Success -> r.value }
            ?: return@locked Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "сценарий не найден", id)
        if (revision != w.revision || !event.valid() || event.context != w.context)
            return@locked Outcome.failure(DiagCode.STATE_CONFLICT, "сценарий или контекст изменился", id)
        if (!w.enabled || w.triggers.none(event::accepts))
            return@locked Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "триггер сценария выключен", id)
        val cycle = Hashing.sha256(w.id + ":" + w.revision + ":" + event.fingerprint).hex
        if (cycle in event.lineage) return@locked Outcome.failure(DiagCode.STATE_CONFLICT, "цикл автоматизации без изменения состояния", id)
        // Один id события остаётся одним запуском даже после изменения определения.
        val key = Hashing.sha256(w.id + ":" + event.id).hex
        val previous = when (val r = store.run(key)) { is Outcome.Failure -> return@locked r; is Outcome.Success -> r.value }
        if (previous != null) {
            if (previous["status"] != JsonString(WorkflowStatus.RUNNING.name)) return@locked Outcome.success(previous)
            val recovered = JsonObject(previous.fields + mapOf("status" to JsonString(WorkflowStatus.INCOMPLETE.name),
                "note" to JsonString("процесс прерван; начатый шаг не повторяется, сверьте его историю")))
            return@locked store.record(key, recovered).map { recovered }
        }
        if (cancelled()) return@locked Outcome.failure(DiagCode.CANCELLED_BY_USER, "сценарий отменён до запуска")
        when (val r = authorize(w, event)) { is Outcome.Failure -> return@locked r; is Outcome.Success -> Unit }
        val begun = clock.monotonicNanos()
        fun expired() = clock.monotonicNanos() - begun >= w.timeoutSeconds * 1_000_000_000
        val completed = arrayListOf<JsonValue>()
        var current = -1
        fun state(status: WorkflowStatus, note: String = "") = JsonObject.of(
            "format" to JsonString("dpai.workflow-run"), "version" to JsonNumber.of(1), "workflow" to JsonString(w.id),
            "revision" to JsonString(w.revision), "event" to JsonString(event.id), "run" to JsonString(key),
            "context" to JsonString(event.context), "trigger" to JsonString(event.type.name),
            "fingerprint" to JsonString(event.fingerprint), "status" to JsonString(status.name), "timestamp" to JsonNumber.of(clock.now().epochMillis),
            "step" to JsonNumber.of(current), "total" to JsonNumber.of(w.steps.size), "completed" to JsonNumber.of(completed.size),
            "results" to JsonArray(completed.toList()), "note" to JsonString(Redactor.STANDARD.redact(note).take(2000)))
        fun finish(status: WorkflowStatus, note: String = ""): Outcome<JsonObject> {
            val value = state(status, note)
            return store.record(key, value).map { value }
        }
        when (val r = store.record(key, state(WorkflowStatus.RUNNING))) { is Outcome.Failure -> return@locked r; is Outcome.Success -> Unit }
        for ((index, step) in w.steps.withIndex()) {
            if (cancelled()) return@locked finish(WorkflowStatus.CANCELLED, "сценарий отменён между шагами")
            if (expired()) return@locked finish(WorkflowStatus.INCOMPLETE, "предел времени сценария")
            // Отзыв или изменение определения проверяется заново перед каждым эффектом.
            val fresh = when (val r = store.workflow(id)) { is Outcome.Failure -> return@locked r; is Outcome.Success -> r.value }
            if (fresh?.revision != w.revision) return@locked finish(WorkflowStatus.NEEDS_REVIEW, "определение сценария изменилось")
            when (val r = authorize(w, event)) { is Outcome.Failure -> return@locked finish(WorkflowStatus.BLOCKED, r.errors.first().message); is Outcome.Success -> Unit }
            val d = when (val r = catalog()) { is Outcome.Failure -> return@locked finish(WorkflowStatus.BLOCKED, r.errors.first().message); is Outcome.Success -> r.value.byId(step.id) }
            if (d == null || d.revision != step.revision) return@locked finish(WorkflowStatus.NEEDS_REVIEW, "инструмент изменился: " + step.id)
            if (!d.available || d.invocation != AutomationInvocation.DIRECT) return@locked finish(WorkflowStatus.BLOCKED, d.reason ?: "нужен интерактивный экран инструмента")
            if (step.parameters.fields.keys.any { k -> d.parameters.none { it.name == k } } ||
                d.parameters.any { p -> (p.required && p.name !in step.parameters.fields) ||
                    (step.parameters[p.name]?.let { it !is JsonString || !p.accepts(it.value) } == true) })
                return@locked finish(WorkflowStatus.NEEDS_REVIEW, "параметры не соответствуют схеме: " + step.id)
            current = index
            when (val r = store.record(key, state(WorkflowStatus.RUNNING))) { is Outcome.Failure -> return@locked r; is Outcome.Success -> Unit }
            progress(index, w.steps.size)
            val result = try { execute(step, { cancelled() || expired() }, event.lineage + cycle) }
                catch (_: java.util.concurrent.CancellationException) { Outcome.failure(DiagCode.CANCELLED_BY_USER, "вызов отменён") }
                catch (_: Exception) { Outcome.failure(DiagCode.INTERNAL_ERROR, "сбой прежнего исполнителя; проверьте его историю") }
            when (result) {
                is Outcome.Failure -> return@locked finish(WorkflowStatus.INCOMPLETE, result.errors.first().message)
                is Outcome.Success -> {
                    val bounded = safe(AutomationJson.forApi(result.value, 12_000)) as JsonObject
                    completed += JsonObject.of("id" to JsonString(step.id), "resultHash" to JsonString(Hashing.sha256(result.value).hex),
                        "result" to bounded)
                    val status = (result.value["status"] as? JsonString)?.value
                    if (status !in setOf("SUCCEEDED", "SUCCEEDED_WITH_WARNINGS", "PASSED"))
                        return@locked finish(if (cancelled()) WorkflowStatus.INCOMPLETE else WorkflowStatus.FAILED, "шаг не подтвердил успех: " + step.id)
                }
            }
            when (val r = store.record(key, state(WorkflowStatus.RUNNING))) { is Outcome.Failure -> return@locked r; is Outcome.Success -> Unit }
        }
        if (cancelled() || expired()) return@locked finish(WorkflowStatus.INCOMPLETE, "общий бюджет или отмена; сверьте завершённые шаги")
        progress(w.steps.size, w.steps.size)
        finish(WorkflowStatus.PASSED)
    }

    /** Вложенные строки очищаются до записи; полный вывод остаётся у прежнего инструмента. */
    private fun safe(v: JsonValue): JsonValue = when (v) {
        is JsonString -> JsonString(Redactor.STANDARD.redact(v.value))
        is JsonArray -> JsonArray(v.items.map(::safe))
        is JsonObject -> JsonObject(v.fields.mapValues { safe(it.value) })
        else -> v
    }
}
