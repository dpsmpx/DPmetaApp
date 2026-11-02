package com.dpsmpx.dpai.orchestrator.commands

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.OperationId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.storage.snapshot.Projection

/**
 * Команда, принятая контуром, пока такт был занят (D-94): кто ([by]) и
 * сама команда в формате канала `dpai` (`CommandCodec`) — так её можно
 * исполнить и после перезапуска.
 */
data class AcceptedCommand(val id: OperationId, val by: String, val command: JsonObject, val at: Timestamp)

/** Принятые и ещё не исполненные команды — по порядку приёма. */
data class AcceptedState(val pending: Map<OperationId, AcceptedCommand> = emptyMap()) {
    companion object {
        val EMPTY = AcceptedState()
    }
}

/**
 * Принятые команды в журнале (D-94): `COMMAND_ACCEPTED` и `COMMAND_SETTLED`
 * в потоке операции, снимок `dpai.accepted-commands` v1. Порядок — порядок
 * приёма: `LinkedHashMap` сохраняет его и в снимке.
 */
object AcceptedProjection : Projection<AcceptedState> {
    const val FORMAT = "dpai.accepted-commands"
    const val VERSION = 1

    override val name: String = "accepted-commands"

    override fun initial(): AcceptedState = AcceptedState.EMPTY

    override fun apply(state: AcceptedState, event: EventEnvelope): AcceptedState = when (event.type) {
        EventType.COMMAND_ACCEPTED -> {
            val d = DiagnosticCollector()
            val f = JsonFields(event.payload, "$.payload", d)
            val a = read(f, event.occurredAt)
            f.finish()
            checkNotNull(a) { "событие принятой команды №${event.sequence} не читается: ${d.all}" }
            accepted(state, a)
        }
        EventType.COMMAND_SETTLED -> {
            val id = (event.payload["id"] as? JsonString)?.value?.let { IdKinds.OPERATION.parse(it).valueOrNull() }
            checkNotNull(id) { "событие исполненной команды №${event.sequence} без id" }
            settled(state, id)
        }
        else -> state
    }

    fun accepted(state: AcceptedState, a: AcceptedCommand): AcceptedState = state.copy(pending = LinkedHashMap(state.pending).apply { put(a.id, a) })

    fun settled(state: AcceptedState, id: OperationId): AcceptedState =
        if (id !in state.pending) state else state.copy(pending = LinkedHashMap(state.pending).apply { remove(id) })

    fun payload(a: AcceptedCommand): JsonObject =
        JsonObject(linkedMapOf<String, JsonValue>("id" to JsonString(a.id.value), "by" to JsonString(a.by), "command" to a.command))

    fun settledPayload(id: OperationId, status: String): JsonObject = JsonObject.of("id" to JsonString(id.value), "status" to JsonString(status))

    private fun read(f: JsonFields, at: Timestamp): AcceptedCommand? {
        val id = f.string("id")?.let { IdKinds.OPERATION.parse(it).valueOrNull() }
        val by = f.string("by")
        // Команда — как есть: её разбирает `CommandCodec` при исполнении, а не проекция.
        val command = f.raw("command") as? JsonObject
        if (id == null || by == null || command == null) return null
        return AcceptedCommand(id, by, command, at)
    }

    override fun encode(state: AcceptedState): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "pending" to JsonArray(state.pending.values.map { a -> JsonObject(LinkedHashMap(payload(a).fields).apply { put("at", JsonNumber.of(a.at.epochMillis)) }) }),
        ),
    )

    override fun decode(json: JsonObject): Outcome<AcceptedState> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
        val items = f.array("pending")?.items.orEmpty().mapIndexed { i, v ->
            (v as? JsonObject)?.let { o ->
                val r = JsonFields(o, "$.pending[$i]", d)
                val at = r.long("at")?.let(::Timestamp)
                (at?.let { read(r, it) }).also { r.finish() }
            }
        }
        f.finish()
        if (items.any { it == null }) return Outcome.Failure(d.all.ifEmpty { listOf(Diagnostic.error(DiagCode.FIELD_MISSING, "принятая команда в снимке неполна", "$.pending")) })
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(AcceptedState(items.filterNotNull().associateByTo(LinkedHashMap()) { it.id }))
    }
}
