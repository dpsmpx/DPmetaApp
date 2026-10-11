package com.dpsmpx.dpai.orchestrator.git

import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.git.WorktreeCodec
import com.dpsmpx.dpai.git.WorktreeEvent
import com.dpsmpx.dpai.git.Worktrees
import com.dpsmpx.dpai.git.WorktreesState
import com.dpsmpx.dpai.orchestrator.EventRecorder
import com.dpsmpx.dpai.storage.snapshot.Projection

/**
 * Репозитории и рабочие деревья как проекция журнала (D-12, D-24): владелец
 * дерева после перезапуска — из журнала, а не из памяти (ТЗ, §118).
 */
object WorktreesProjection : Projection<WorktreesState> {
    override val name: String = "worktrees"

    override fun initial(): WorktreesState = WorktreesState.EMPTY

    override fun apply(state: WorktreesState, event: EventEnvelope): WorktreesState {
        val decoded = WorktreeCodec.decode(event.type, event.occurredAt, event.payload) ?: return state
        // Журнал сверен хешами (D-24): негодное событие дерева — несовместимость версий, а не порча.
        val e = checkNotNull(decoded.valueOrNull()) { "событие дерева №${event.sequence} не читается: ${decoded.diagnostics}" }
        return Worktrees.apply(state, e)
    }

    override fun encode(state: WorktreesState): JsonObject = WorktreeCodec.encodeState(state)

    override fun decode(json: JsonObject): Outcome<WorktreesState> = WorktreeCodec.decodeState(json)
}

/** Конверты для событий деревьев: тип, поток и полезная нагрузка — из [WorktreeCodec]. */
fun EventRecorder.worktreeEnvelopes(events: List<WorktreeEvent>): List<EventEnvelope> =
    events.map { envelope(WorktreeCodec.type(it), WorktreeCodec.stream(it), it.at, WorktreeCodec.payload(it)) }
