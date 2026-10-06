package com.dpsmpx.dpai.execution.agent

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.SequenceTracker
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Канал событий одного запуска на target (ТЗ, §56; `docs/ARCHITECTURE.md`,
 * §3.2). Контроллер забирает события с номера, следующего за последним
 * принятым, поэтому обрыв связи ничего не теряет: после переподключения
 * агент отдаёт пропущенное из своего журнала.
 *
 * Пропуск номера (в ответе есть №7, а №6 нет, или агент сообщает номер
 * последнего события больше принятого, а событий не прислал) — сверка:
 * повторный запрос с первого непринятого номера. Пропуск, который сверка
 * не закрыла, — `RUN_RECONCILIATION_REQUIRED` с причиной
 * `EVENT_SEQUENCE_GAP`: применять события через дыру нельзя (D-12).
 * Тот же номер с другим событием — `EVENT_SEQUENCE_CONFLICT`.
 */
class RunChannel(private val source: RunEventSource, val run: SessionId, private val pageSize: Int = 500) {
    private val tracker = SequenceTracker()
    private val stream = StreamRef.of(run)

    /** Сколько раз канал сверялся с агентом из-за пропуска. */
    var reconciliations: Int = 0
        private set

    /** Номер последнего принятого события. */
    val lastAccepted: Long get() = tracker.expected(stream) - 1

    /** Новые события по порядку и состояние запуска. */
    data class Batch(val events: List<EventEnvelope>, val status: RunStatus?, val diagnostics: List<Diagnostic> = emptyList())

    fun poll(): Outcome<Batch> {
        val accepted = mutableListOf<EventEnvelope>()
        val notes = mutableListOf<Diagnostic>()
        var reconciled = false
        while (true) {
            val reply = when (val r = source.events(run, lastAccepted, pageSize)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            var gap: Diagnostic? = null
            for (e in reply.events) {
                if (e.stream != stream) {
                    return Outcome.failure(DiagCode.EVENT_SEQUENCE_CONFLICT, "событие чужого потока ${e.stream} в канале запуска", stream.toString())
                }
                when (val v = tracker.offer(e)) {
                    is SequenceTracker.Verdict.Accepted -> accepted += e
                    is SequenceTracker.Verdict.Duplicate -> Unit
                    is SequenceTracker.Verdict.Conflict -> return Outcome.failure(v.diagnostic)
                    is SequenceTracker.Verdict.Gap -> {
                        gap = v.diagnostic
                        break
                    }
                }
            }
            val fullPage = reply.events.size >= pageSize
            val reported = reply.run?.lastSequence ?: 0
            if (gap == null && !fullPage && reported > lastAccepted) {
                gap = Diagnostic.warning(
                    DiagCode.EVENT_SEQUENCE_GAP,
                    "агент сообщает №$reported, принято до №$lastAccepted",
                    stream.toString(),
                )
            }
            if (gap != null) {
                notes += gap
                if (reconciled) {
                    return Outcome.Failure(
                        listOf(
                            Diagnostic.of(
                                DiagCode.RUN_RECONCILIATION_REQUIRED,
                                "пропуск событий не закрыт сверкой; принято до №$lastAccepted",
                                stream.toString(),
                                causes = listOf(gap),
                            ),
                        ),
                    )
                }
                reconciled = true
                reconciliations++
                continue
            }
            // Полная страница — за ней могут быть ещё события.
            if (fullPage) continue
            // Закрытый сверкой пропуск остаётся предупреждением: он был, и это видно.
            return Outcome.success(Batch(accepted, reply.run, notes), notes)
        }
    }
}
