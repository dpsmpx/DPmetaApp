package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/**
 * Запрос к истории (ТЗ, §42; бюджет «Поиск» `docs/PERFORMANCE.md`): по
 * виду события, потоку, времени и тексту в строках полезной нагрузки
 * (без учёта регистра). Пустое условие — любое.
 */
data class EventQuery(
    val types: Set<EventType> = emptySet(),
    val stream: StreamRef? = null,
    val from: Timestamp? = null,
    val to: Timestamp? = null,
    val text: String? = null,
    val limit: Int = 100,
) {
    init {
        require(limit in 1..10_000) { "limit — 1…10000" }
    }
}

/**
 * Поиск по журналу — проход по событиям в памяти, новые первыми. Индекса
 * нет: источник истины — журнал (D-24), и проход 100 тыс. событий
 * укладывается в бюджет (`PerformanceTest`); индекс — по замеру
 * («Отложено», этап 11).
 */
object EventSearch {
    fun search(events: List<EventEnvelope>, q: EventQuery): List<EventEnvelope> {
        val needle = q.text?.lowercase()?.takeIf { it.isNotBlank() }
        val out = ArrayList<EventEnvelope>(minOf(q.limit, 128))
        for (i in events.indices.reversed()) {
            val e = events[i]
            if (q.types.isNotEmpty() && e.type !in q.types) continue
            if (q.stream != null && e.stream != q.stream) continue
            if (q.from != null && e.occurredAt < q.from) continue
            if (q.to != null && e.occurredAt > q.to) continue
            if (needle != null && !contains(e.payload, needle)) continue
            out += e
            if (out.size >= q.limit) break
        }
        return out
    }

    private fun contains(v: JsonValue, needle: String): Boolean = when (v) {
        is JsonString -> v.value.lowercase().contains(needle)
        is JsonObject -> v.fields.values.any { contains(it, needle) }
        is JsonArray -> v.items.any { contains(it, needle) }
        else -> false
    }
}
