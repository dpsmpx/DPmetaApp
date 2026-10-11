package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.ApprovalId
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.security.Proposal
import com.dpsmpx.dpai.storage.snapshot.Projection

/** Слияние задачи, ждущее одобрения (ТЗ, §68): запрос, предложение, срок. */
data class PendingMerge(val task: TaskId, val approval: ApprovalId, val proposal: Proposal, val expiresAt: Timestamp)

/** Ожидающие слияния и слитые задачи с одобрением каждой — из журнала (D-24). */
data class MergesState(
    val pending: Map<ApprovalId, PendingMerge> = emptyMap(),
    val merged: Map<TaskId, ApprovalId> = emptyMap(),
) {
    companion object {
        val EMPTY = MergesState()
    }
}

/** Факт о слиянии задачи в ветку интеграции. */
sealed interface MergeEvent {
    val task: TaskId
    val at: Timestamp

    data class Requested(val merge: PendingMerge, override val at: Timestamp) : MergeEvent {
        override val task: TaskId get() = merge.task
    }

    data class Declined(override val task: TaskId, val approval: ApprovalId, override val at: Timestamp) : MergeEvent

    data class Merged(override val task: TaskId, val approval: ApprovalId, override val at: Timestamp) : MergeEvent
}

/**
 * События слияний в потоке задачи (`TASK_MERGE_REQUESTED`,
 * `TASK_MERGE_DECLINED`, `TASK_MERGED`) и снимок `dpai.merges-state` v1:
 * после перезапуска ожидающий запрос снова ждёт ответа с прежним сроком, а
 * слитое не предлагается к слиянию повторно; запись одобрения слияния —
 * для инварианта «разрушительное — с одобрением» (ТЗ, §118).
 */
object MergesProjection : Projection<MergesState> {
    const val FORMAT = "dpai.merges-state"
    const val VERSION = 1

    override val name: String = "merges"

    override fun initial(): MergesState = MergesState.EMPTY

    override fun apply(state: MergesState, event: EventEnvelope): MergesState {
        val decoded = decode(event.type, event.occurredAt, event.payload) ?: return state
        val e = checkNotNull(decoded.valueOrNull()) { "событие слияния №${event.sequence} не читается: ${decoded.diagnostics}" }
        return applyEvent(state, e)
    }

    fun applyEvent(state: MergesState, e: MergeEvent): MergesState = when (e) {
        is MergeEvent.Requested -> state.copy(pending = state.pending + (e.merge.approval to e.merge))
        is MergeEvent.Declined -> state.copy(pending = state.pending - e.approval)
        is MergeEvent.Merged -> state.copy(pending = state.pending - e.approval, merged = state.merged + (e.task to e.approval))
    }

    fun type(e: MergeEvent): EventType = when (e) {
        is MergeEvent.Requested -> EventType.TASK_MERGE_REQUESTED
        is MergeEvent.Declined -> EventType.TASK_MERGE_DECLINED
        is MergeEvent.Merged -> EventType.TASK_MERGED
    }

    fun stream(e: MergeEvent): StreamRef = StreamRef.of(e.task)

    fun payload(e: MergeEvent): JsonObject = when (e) {
        is MergeEvent.Requested -> pendingJson(e.merge)
        is MergeEvent.Declined -> JsonObject.of("task" to JsonString(e.task.value), "approval" to JsonString(e.approval.value))
        is MergeEvent.Merged -> JsonObject.of("task" to JsonString(e.task.value), "approval" to JsonString(e.approval.value))
    }

    fun decode(type: EventType, at: Timestamp, payload: JsonObject): Outcome<MergeEvent>? {
        val d = DiagnosticCollector()
        val f = JsonFields(payload, "$.payload", d)
        val e: MergeEvent? = when (type) {
            EventType.TASK_MERGE_REQUESTED -> readPending(f)?.let { MergeEvent.Requested(it, at) }
            EventType.TASK_MERGE_DECLINED, EventType.TASK_MERGED -> {
                val task = f.string("task")?.let { IdKinds.TASK.parse(it).valueOrNull() }
                val approval = f.string("approval")?.let { IdKinds.APPROVAL.parse(it).valueOrNull() }
                when {
                    task == null || approval == null -> null
                    type == EventType.TASK_MERGED -> MergeEvent.Merged(task, approval, at)
                    else -> MergeEvent.Declined(task, approval, at)
                }
            }
            else -> return null
        }
        f.finish()
        return if (d.hasErrors || e == null) {
            Outcome.Failure(d.all.ifEmpty { listOf(Diagnostic.error(DiagCode.FIELD_MISSING, "событие слияния неполно", "$.payload")) })
        } else {
            Outcome.success(e)
        }
    }

    override fun encode(state: MergesState): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "pending" to JsonArray(state.pending.values.sortedBy { it.approval.value }.map(::pendingJson)),
            "merged" to JsonArray(
                state.merged.entries.sortedBy { it.key.value }.map { (t, a) -> JsonObject.of("task" to JsonString(t.value), "approval" to JsonString(a.value)) },
            ),
        ),
    )

    override fun decode(json: JsonObject): Outcome<MergesState> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
        val pending = f.array("pending")?.items.orEmpty().mapIndexedNotNull { i, v ->
            (v as? JsonObject)?.let { o -> JsonFields(o, "$.pending[$i]", d).let { r -> readPending(r).also { r.finish() } } }
        }
        val merged = f.array("merged")?.items.orEmpty().mapIndexedNotNull { i, v ->
            (v as? JsonObject)?.let { o ->
                val r = JsonFields(o, "$.merged[$i]", d)
                val t = r.string("task")?.let { IdKinds.TASK.parse(it).valueOrNull() }
                val a = r.string("approval")?.let { IdKinds.APPROVAL.parse(it).valueOrNull() }
                r.finish()
                if (t != null && a != null) t to a else null
            }
        }
        f.finish()
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(MergesState(pending.associateBy { it.approval }, merged.toMap()))
    }

    private fun pendingJson(m: PendingMerge): JsonObject = JsonObject(
        linkedMapOf(
            "task" to JsonString(m.task.value), "approval" to JsonString(m.approval.value),
            "action" to JsonString(m.proposal.action), "subject" to JsonString(m.proposal.subject),
            "arguments" to JsonArray(m.proposal.arguments.map(::JsonString)), "expiresAt" to JsonNumber.of(m.expiresAt.epochMillis),
        ),
    )

    private fun readPending(f: JsonFields): PendingMerge? {
        val task = f.string("task")?.let { IdKinds.TASK.parse(it).valueOrNull() }
        val approval = f.string("approval")?.let { IdKinds.APPROVAL.parse(it).valueOrNull() }
        val action = f.string("action")
        val subject = f.string("subject")
        val args = f.array("arguments")?.items?.map { (it as? JsonString)?.value }
        val expires = f.long("expiresAt")?.let(::Timestamp)
        if (task == null || approval == null || action == null || subject == null || args == null || args.any { it == null } || expires == null) return null
        return PendingMerge(task, approval, Proposal(action, subject, args.filterNotNull()), expires)
    }
}
