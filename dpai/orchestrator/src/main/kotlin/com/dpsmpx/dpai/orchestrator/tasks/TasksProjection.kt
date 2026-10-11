package com.dpsmpx.dpai.orchestrator.tasks

import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.orchestrator.EventRecorder
import com.dpsmpx.dpai.storage.snapshot.Projection
import com.dpsmpx.dpai.tasks.TaskCodec
import com.dpsmpx.dpai.tasks.TaskEvent
import com.dpsmpx.dpai.tasks.Tasks
import com.dpsmpx.dpai.tasks.TasksState

/**
 * Задачи как проекция журнала (D-12, D-24): события — в потоке задачи,
 * состояние — из журнала и снимка `dpai.tasks-state`.
 */
object TasksProjection : Projection<TasksState> {
    override val name: String = "tasks"

    override fun initial(): TasksState = TasksState.EMPTY

    override fun apply(state: TasksState, event: EventEnvelope): TasksState {
        val decoded = TaskCodec.decode(event.type, event.occurredAt, event.payload) ?: return state
        // Журнал сверен хешами (D-24): негодное событие задачи — несовместимость версий, а не порча.
        val e = checkNotNull(decoded.valueOrNull()) { "событие задачи №${event.sequence} не читается: ${decoded.diagnostics}" }
        return Tasks.apply(state, e)
    }

    override fun encode(state: TasksState): JsonObject = TaskCodec.encodeState(state)

    override fun decode(json: JsonObject): Outcome<TasksState> = TaskCodec.decodeState(json)
}

/** Конверты для событий задач: тип, поток и полезная нагрузка — из [TaskCodec]. */
fun EventRecorder.taskEnvelopes(events: List<TaskEvent>): List<EventEnvelope> =
    events.map { envelope(TaskCodec.type(it), TaskCodec.stream(it), it.at, TaskCodec.payload(it)) }
