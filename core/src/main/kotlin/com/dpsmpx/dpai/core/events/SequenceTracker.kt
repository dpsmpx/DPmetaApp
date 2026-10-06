package com.dpsmpx.dpai.core.events

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.EventId

/**
 * Приём событий по потокам с номерами без пропусков (D-12; ТЗ, §56;
 * `docs/ARCHITECTURE.md`, §3.2):
 *
 * - следующий по номеру — [Verdict.Accepted];
 * - то же событие ещё раз — [Verdict.Duplicate], ничего не меняется;
 * - номер дальше ожидаемого — [Verdict.Gap]: событие не применяется, пока не
 *   получены пропущенные (повтор с target или снимок);
 * - тот же номер с другим событием или то же событие с другим номером —
 *   [Verdict.Conflict]: это не догадка, а `STATE_CONFLICT` для сверки.
 *
 * Итог не зависит от порядка доставки: любые перестановки и повторы
 * событий 1…N, доставляемые до приёма, дают ровно 1…N по порядку
 * (`SequenceTrackerTest`).
 */
class SequenceTracker {
    sealed interface Verdict {
        val diagnostic: Diagnostic?

        data class Accepted(val event: EventEnvelope) : Verdict {
            override val diagnostic: Diagnostic? get() = null
        }

        data class Duplicate(val event: EventEnvelope) : Verdict {
            override val diagnostic: Diagnostic
                get() = Diagnostic.info(DiagCode.EVENT_DUPLICATE, "повтор события №${event.sequence}", event.stream.toString())
        }

        data class Gap(val stream: StreamRef, val missing: LongRange, val received: EventEnvelope) : Verdict {
            override val diagnostic: Diagnostic
                get() = Diagnostic.warning(
                    DiagCode.EVENT_SEQUENCE_GAP,
                    "пропущены события №${missing.first}…${missing.last}, пришло №${received.sequence}",
                    stream.toString(),
                )
        }

        data class Conflict(val event: EventEnvelope, val reason: String) : Verdict {
            override val diagnostic: Diagnostic
                get() = Diagnostic.error(DiagCode.EVENT_SEQUENCE_CONFLICT, reason, event.stream.toString())
        }
    }

    private val next = HashMap<StreamRef, Long>()
    private val accepted = HashMap<StreamRef, HashMap<Long, EventId>>()
    private val placeOf = HashMap<EventId, Pair<StreamRef, Long>>()

    /** Номер, который поток ждёт следующим. */
    fun expected(stream: StreamRef): Long = next[stream] ?: 1

    @Synchronized
    fun offer(event: EventEnvelope): Verdict {
        val stream = event.stream
        val want = expected(stream)
        val known = placeOf[event.id]
        if (known != null && known != (stream to event.sequence)) {
            return Verdict.Conflict(event, "событие уже принято под другим номером или в другом потоке")
        }
        return when {
            event.sequence < want -> {
                val existing = accepted[stream]?.get(event.sequence)
                if (existing == event.id) {
                    Verdict.Duplicate(event)
                } else {
                    Verdict.Conflict(event, "номер №${event.sequence} уже занят другим событием")
                }
            }
            event.sequence == want -> {
                accepted.getOrPut(stream) { HashMap() }[event.sequence] = event.id
                placeOf[event.id] = stream to event.sequence
                next[stream] = want + 1
                Verdict.Accepted(event)
            }
            else -> Verdict.Gap(stream, want until event.sequence, event)
        }
    }
}
