package com.dpsmpx.dpmeta.metrics

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.vfs.Vfs

/**
 * Хранение DPmetaApp на `dpai/storage` (D-34, D-28; ТЗ, §6.3): состояние —
 * атомарный документ `state` (текущее заменяется новым), история чисел —
 * журнал событий `METRIC_POINT_RECORDED` с цепочкой хешей (только
 * добавляется). Своего хранилища (`:core:store`) нет.
 */
class Store(private val vfs: Vfs, private val clock: Clock, random: RandomSource, dir: String = "dpmeta") {
    private val documents = DocumentStore(vfs, "$dir/state")
    private val historyPath = "$dir/history.jsonl"
    private val ids = IdGenerator(clock, random)

    fun readState(): Outcome<EcosystemState?> = when (val r = documents.read(STATE_KEY)) {
        is Outcome.Failure -> r
        is Outcome.Success -> r.value?.let { EcosystemState.fromJson(it.content) } ?: Outcome.success(null)
    }

    fun writeState(state: EcosystemState): Outcome<Unit> = when (val r = documents.write(STATE_KEY, state.toJson())) {
        is Outcome.Failure -> r
        is Outcome.Success -> Outcome.success(Unit)
    }

    /** Добавить точки среза в историю; прежние точки не трогаются. */
    fun appendHistory(points: List<MetricPoint>): Outcome<Int> {
        if (points.isEmpty()) return Outcome.success(0)
        val journal = when (val j = EventJournal.open(vfs, historyPath)) {
            is Outcome.Failure -> return j
            is Outcome.Success -> j.value
        }
        val next = journal.records().lastOrNull()?.event?.sequence ?: 0L
        val events = points.mapIndexed { i, p ->
            EventEnvelope(
                id = ids.next(IdKinds.EVENT),
                type = EventType.METRIC_POINT_RECORDED,
                stream = StreamRef.SYSTEM,
                sequence = next + 1 + i,
                occurredAt = p.asOf,
                recordedAt = clock.now(),
                payload = p.toJson(),
            )
        }
        return when (val r = journal.appendAll(events)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(r.value.size)
        }
    }

    /** Лента (F-05): документ `feed`, последние [Rules.FEED_KEEP] событий. */
    fun readFeed(): Outcome<List<FeedEvent>> = when (val r = documents.read(FEED_KEY)) {
        is Outcome.Failure -> r
        is Outcome.Success -> r.value?.let { Feed.fromJson(it.content) } ?: Outcome.success(emptyList())
    }

    fun appendFeed(fresh: List<FeedEvent>): Outcome<Int> {
        if (fresh.isEmpty()) return Outcome.success(0)
        val old = when (val r = readFeed()) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return when (val w = documents.write(FEED_KEY, Feed.toJson(Feed.merge(old, fresh)))) {
            is Outcome.Failure -> w
            is Outcome.Success -> Outcome.success(fresh.size)
        }
    }

    /** История чисел: все точки по порядку записи; [metric] и [subject] — фильтр. */
    fun history(metric: String? = null, subject: String? = null): Outcome<List<MetricPoint>> {
        if (!vfs.exists(historyPath)) return Outcome.success(emptyList())
        val records = when (val r = EventJournal.readCommitted(vfs, historyPath)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val points = records.filter { it.event.type == EventType.METRIC_POINT_RECORDED }.mapNotNull { rec ->
            val p = rec.event.payload
            MetricPoint(
                (p["metric"] as? JsonString)?.value ?: return@mapNotNull null,
                (p["subject"] as? JsonString)?.value ?: return@mapNotNull null,
                (p["value"] as? JsonNumber)?.toLongExact() ?: return@mapNotNull null,
                Timestamp((p["asOf"] as? JsonNumber)?.toLongExact() ?: return@mapNotNull null),
                (p["rulesVersion"] as? JsonNumber)?.toLongExact()?.toInt() ?: return@mapNotNull null,
            )
        }
        return Outcome.success(points.filter { (metric == null || it.metric == metric) && (subject == null || it.subject == subject) })
    }

    companion object {
        const val STATE_KEY = "state"
        const val FEED_KEY = "feed"
    }
}
