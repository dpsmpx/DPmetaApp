package com.dpsmpx.dpai.orchestrator.commands

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.operation.OperationStatus
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.orchestrator.EventRecorder
import com.dpsmpx.dpai.storage.snapshot.Projection

/**
 * Исполненная команда с ключом идемпотентности (ТЗ, §5.4; D-42): что,
 * с каким итогом, значение итога строкой (id созданного) и первый код
 * отказа. Повтор команды с тем же ключом возвращает это, а не действует.
 */
data class OperationRecord(val key: String, val operation: String, val status: OperationStatus, val value: String?, val code: DiagCode?, val at: Timestamp)

data class OperationsState(val byKey: Map<String, OperationRecord> = emptyMap()) {
    companion object {
        val EMPTY = OperationsState()
    }
}

/**
 * Ключи команд в журнале (D-42): событие `OPERATION_RECORDED` в системном
 * потоке и снимок `dpai.operations-state` v1 — после перезапуска повтор
 * нажатия, пришедший позже, тоже не делает второго действия.
 */
object OperationsProjection : Projection<OperationsState> {
    const val FORMAT = "dpai.operations-state"
    const val VERSION = 1

    override val name: String = "operations"

    override fun initial(): OperationsState = OperationsState.EMPTY

    override fun apply(state: OperationsState, event: EventEnvelope): OperationsState {
        if (event.type != EventType.OPERATION_RECORDED) return state
        val d = DiagnosticCollector()
        val f = JsonFields(event.payload, "$.payload", d)
        val r = read(f, event.occurredAt)
        f.finish()
        checkNotNull(r) { "событие операции №${event.sequence} не читается: ${d.all}" }
        return applyRecord(state, r)
    }

    fun applyRecord(state: OperationsState, r: OperationRecord): OperationsState = state.copy(byKey = state.byKey + (r.key to r))

    fun payload(r: OperationRecord): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>("key" to JsonString(r.key), "operation" to JsonString(r.operation), "status" to JsonString(r.status.name)).apply {
            r.value?.let { put("value", JsonString(it)) }
            r.code?.let { put("code", JsonString(it.name)) }
        },
    )

    private fun read(f: JsonFields, at: Timestamp): OperationRecord? {
        val key = f.string("key")
        val op = f.string("operation")
        val status = f.string("status")?.let { s -> OperationStatus.entries.firstOrNull { it.name == s } }
        val value = f.optString("value")
        val code = f.optString("code")?.let { c -> DiagCode.entries.firstOrNull { it.name == c } ?: return null }
        if (key == null || op == null || status == null) return null
        return OperationRecord(key, op, status, value, code, at)
    }

    override fun encode(state: OperationsState): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "operations" to JsonArray(
                state.byKey.values.sortedBy { it.key }.map { r -> JsonObject(LinkedHashMap(payload(r).fields).apply { put("at", JsonNumber.of(r.at.epochMillis)) }) },
            ),
        ),
    )

    override fun decode(json: JsonObject): Outcome<OperationsState> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
        val items = f.array("operations")?.items.orEmpty().mapIndexed { i, v ->
            (v as? JsonObject)?.let { o ->
                val r = JsonFields(o, "$.operations[$i]", d)
                val at = r.long("at")?.let(::Timestamp)
                (at?.let { read(r, it) }).also { r.finish() }
            }
        }
        f.finish()
        if (items.any { it == null }) return Outcome.Failure(d.all.ifEmpty { listOf(Diagnostic.error(DiagCode.FIELD_MISSING, "операция в снимке неполна", "$.operations")) })
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(OperationsState(items.filterNotNull().associateBy { it.key }))
    }
}

fun EventRecorder.operationEnvelopes(records: List<OperationRecord>): List<EventEnvelope> =
    records.map { envelope(EventType.OPERATION_RECORDED, StreamRef.SYSTEM, it.at, OperationsProjection.payload(it)) }
