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

/** Коммит работы задачи, сделанный контуром после проверки: хеш, ветка задачи, когда. */
data class TaskCommit(val commit: String, val branch: String, val at: Timestamp) {
    init {
        require(HASH.matches(commit)) { "хеш коммита — 40 или 64 шестнадцатеричных знака" }
    }

    companion object {
        /** SHA-1 или SHA-256 Git, строчными. */
        val HASH = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
    }
}

/**
 * Ожидающие слияния, слитые задачи с одобрением каждой и последний коммит
 * контура в ветке каждой задачи ([commits]) — из журнала (D-24).
 */
data class MergesState(
    val pending: Map<ApprovalId, PendingMerge> = emptyMap(),
    val merged: Map<TaskId, ApprovalId> = emptyMap(),
    val commits: Map<TaskId, TaskCommit> = emptyMap(),
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

    /** Контур закоммитил проверенную работу задачи (`GIT_COMMIT_CREATED`). */
    data class Committed(override val task: TaskId, val commit: TaskCommit) : MergeEvent {
        override val at: Timestamp get() = commit.at
    }
}

/**
 * События слияний в потоке задачи (`TASK_MERGE_REQUESTED`,
 * `TASK_MERGE_DECLINED`, `TASK_MERGED`, `GIT_COMMIT_CREATED`) и снимок
 * `dpai.merges-state` v2: после перезапуска ожидающий запрос снова ждёт
 * ответа с прежним сроком, а слитое не предлагается к слиянию повторно;
 * запись одобрения слияния — для инварианта «разрушительное — с
 * одобрением» (ТЗ, §118); коммит задачи — для поиска и handoff. Снимок v1
 * читается: коммитов в нём нет.
 */
object MergesProjection : Projection<MergesState> {
    const val FORMAT = "dpai.merges-state"
    const val VERSION = 2

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
        is MergeEvent.Committed -> state.copy(commits = state.commits + (e.task to e.commit))
    }

    fun type(e: MergeEvent): EventType = when (e) {
        is MergeEvent.Requested -> EventType.TASK_MERGE_REQUESTED
        is MergeEvent.Declined -> EventType.TASK_MERGE_DECLINED
        is MergeEvent.Merged -> EventType.TASK_MERGED
        is MergeEvent.Committed -> EventType.GIT_COMMIT_CREATED
    }

    fun stream(e: MergeEvent): StreamRef = StreamRef.of(e.task)

    fun payload(e: MergeEvent): JsonObject = when (e) {
        is MergeEvent.Requested -> pendingJson(e.merge)
        is MergeEvent.Declined -> JsonObject.of("task" to JsonString(e.task.value), "approval" to JsonString(e.approval.value))
        is MergeEvent.Merged -> JsonObject.of("task" to JsonString(e.task.value), "approval" to JsonString(e.approval.value))
        is MergeEvent.Committed -> commitJson(e.task, e.commit)
    }

    fun decode(type: EventType, at: Timestamp, payload: JsonObject): Outcome<MergeEvent>? {
        val d = DiagnosticCollector()
        val f = JsonFields(payload, "$.payload", d)
        val e: MergeEvent? = when (type) {
            EventType.TASK_MERGE_REQUESTED -> readPending(f)?.let { MergeEvent.Requested(it, at) }
            EventType.GIT_COMMIT_CREATED -> readCommit(f, at)?.let { (t, c) -> MergeEvent.Committed(t, c) }
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
            "commits" to JsonArray(state.commits.entries.sortedBy { it.key.value }.map { (t, c) -> commitJson(t, c) }),
        ),
    )

    override fun decode(json: JsonObject): Outcome<MergesState> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        val version = FormatHeader.read(f, FORMAT, 1..VERSION, d) ?: return Outcome.Failure(d.all)
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
        val commits = if (version < 2) {
            emptyList()
        } else {
            f.array("commits")?.items.orEmpty().mapIndexedNotNull { i, v ->
                (v as? JsonObject)?.let { o -> JsonFields(o, "$.commits[$i]", d).let { r -> readCommit(r, null).also { r.finish() } } }
                    ?: null.also { d.error(DiagCode.VALUE_INVALID, "запись коммита неполна или хеш неверен", "$.commits[$i]") }
            }
        }
        f.finish()
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(MergesState(pending.associateBy { it.approval }, merged.toMap(), commits.toMap()))
    }

    private fun commitJson(task: TaskId, c: TaskCommit): JsonObject = JsonObject(
        linkedMapOf("task" to JsonString(task.value), "commit" to JsonString(c.commit), "branch" to JsonString(c.branch), "at" to JsonNumber.of(c.at.epochMillis)),
    )

    /** [eventAt] — время события, если запись — событие: поле `at` в нём то же. */
    private fun readCommit(f: JsonFields, eventAt: Timestamp?): Pair<TaskId, TaskCommit>? {
        val task = f.string("task")?.let { IdKinds.TASK.parse(it).valueOrNull() }
        val commit = f.string("commit")?.takeIf { TaskCommit.HASH.matches(it) }
        val branch = f.string("branch")
        val at = f.long("at")?.let(::Timestamp) ?: eventAt
        if (task == null || commit == null || branch == null || at == null) return null
        return task to TaskCommit(commit, branch, at)
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
