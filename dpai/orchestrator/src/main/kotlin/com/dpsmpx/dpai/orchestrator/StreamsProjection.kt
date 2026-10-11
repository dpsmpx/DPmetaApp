package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.snapshot.Projection

/**
 * Последний номер каждого потока событий — чтобы после перезапуска
 * продолжить нумерацию без пропусков (D-12), не читая всю историю журнала
 * (D-37): снимок `dpai.streams-state` v1 и хвост журнала после него.
 */
object StreamsProjection : Projection<Map<StreamRef, Long>> {
    const val FORMAT = "dpai.streams-state"
    const val VERSION = 1

    override val name: String = "streams"

    override fun initial(): Map<StreamRef, Long> = emptyMap()

    override fun apply(state: Map<StreamRef, Long>, event: EventEnvelope): Map<StreamRef, Long> =
        if ((state[event.stream] ?: 0) >= event.sequence) state else state + (event.stream to event.sequence)

    override fun encode(state: Map<StreamRef, Long>): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "streams" to JsonArray(
                state.entries.sortedBy { it.key.toString() }.map { (s, last) ->
                    JsonObject(linkedMapOf("kind" to JsonString(s.kind.name.lowercase()), "id" to JsonString(s.id), "last" to JsonNumber.of(last)))
                },
            ),
        ),
    )

    override fun decode(json: JsonObject): Outcome<Map<StreamRef, Long>> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
        val out = HashMap<StreamRef, Long>()
        for ((i, item) in f.array("streams")?.items.orEmpty().withIndex()) {
            val o = item as? JsonObject
            if (o == null) {
                d.error(DiagCode.FIELD_TYPE, "ожидался объект", "$.streams[$i]")
                continue
            }
            val r = JsonFields(o, "$.streams[$i]", d)
            val kind = r.string("kind")
            val id = r.string("id")
            val last = r.long("last")
            r.finish()
            val stream = if (kind != null && id != null) StreamRef.parse(kind, id).valueOrNull() else null
            if (stream == null || last == null) d.error(DiagCode.VALUE_INVALID, "поток не читается", "$.streams[$i]") else out[stream] = last
        }
        f.finish()
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(out)
    }
}
