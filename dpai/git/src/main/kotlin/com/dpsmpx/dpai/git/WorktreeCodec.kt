package com.dpsmpx.dpai.git

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.IdKind
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TypedId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/**
 * События репозиториев и рабочих деревьев в конвертах `dpai.event`
 * (поток — репозиторий или дерево) и состояние в снимке
 * `dpai.worktrees-state` v1 (`docs/DATA_MODEL.md`). Так владельцы деревьев
 * переживают перезапуск: инвариант «одно дерево — одна сессия» (ТЗ, §118)
 * держится и после него. Чтение строгое (D-09).
 */
object WorktreeCodec {
    const val STATE_FORMAT = "dpai.worktrees-state"
    const val STATE_VERSION = 1

    fun type(e: WorktreeEvent): EventType = when (e) {
        is WorktreeEvent.RepositoryRegistered -> EventType.REPOSITORY_REGISTERED
        is WorktreeEvent.Created -> EventType.WORKTREE_CREATED
        is WorktreeEvent.Claimed -> EventType.WORKTREE_CLAIMED
        is WorktreeEvent.Released -> EventType.WORKTREE_RELEASED
        is WorktreeEvent.Removed -> EventType.WORKTREE_REMOVED
    }

    fun stream(e: WorktreeEvent): StreamRef = when (e) {
        is WorktreeEvent.RepositoryRegistered -> StreamRef.of(e.repository.id)
        is WorktreeEvent.Created -> StreamRef.of(e.worktree.id)
        is WorktreeEvent.Claimed -> StreamRef.of(e.worktree)
        is WorktreeEvent.Released -> StreamRef.of(e.worktree)
        is WorktreeEvent.Removed -> StreamRef.of(e.worktree)
    }

    fun payload(e: WorktreeEvent): JsonObject = when (e) {
        is WorktreeEvent.RepositoryRegistered -> repository(e.repository)
        is WorktreeEvent.Created -> obj(
            "worktree" to id(e.worktree.id), "repository" to id(e.worktree.repository),
            "path" to JsonString(e.worktree.path), "branch" to JsonString(e.worktree.branch),
        )
        is WorktreeEvent.Claimed -> obj("worktree" to id(e.worktree), "session" to id(e.session))
        is WorktreeEvent.Released -> obj("worktree" to id(e.worktree), "session" to id(e.session))
        is WorktreeEvent.Removed -> obj("worktree" to id(e.worktree))
    }

    /** Событие деревьев из конверта; чужой тип — `null`. */
    fun decode(type: EventType, at: Timestamp, payload: JsonObject): Outcome<WorktreeEvent>? {
        val d = DiagnosticCollector()
        val f = JsonFields(payload, "$.payload", d)
        val event: WorktreeEvent? = when (type) {
            EventType.REPOSITORY_REGISTERED -> readRepository(f)?.let { WorktreeEvent.RepositoryRegistered(it, at) }
            EventType.WORKTREE_CREATED -> {
                val id = f.typed("worktree", IdKinds.WORKTREE)
                val repo = f.typed("repository", IdKinds.REPOSITORY)
                val path = f.string("path")
                val branch = f.string("branch")
                if (id != null && repo != null && path != null && branch != null) WorktreeEvent.Created(Worktree(id, repo, path, branch), at) else null
            }
            EventType.WORKTREE_CLAIMED, EventType.WORKTREE_RELEASED -> {
                val id = f.typed("worktree", IdKinds.WORKTREE)
                val session = f.typed("session", IdKinds.SESSION)
                when {
                    id == null || session == null -> null
                    type == EventType.WORKTREE_CLAIMED -> WorktreeEvent.Claimed(id, session, at)
                    else -> WorktreeEvent.Released(id, session, at)
                }
            }
            EventType.WORKTREE_REMOVED -> f.typed("worktree", IdKinds.WORKTREE)?.let { WorktreeEvent.Removed(it, at) }
            else -> return null
        }
        f.finish()
        return if (d.hasErrors || event == null) {
            Outcome.Failure(d.all.ifEmpty { listOf(Diagnostic.error(DiagCode.FIELD_MISSING, "событие дерева неполно", "$.payload")) })
        } else {
            Outcome.success(event)
        }
    }

    // ---- снимок ----

    fun encodeState(s: WorktreesState): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(STATE_FORMAT, STATE_VERSION),
            "repositories" to JsonArray(s.repositories.values.sortedBy { it.id.value }.map(::repository)),
            "worktrees" to JsonArray(
                s.worktrees.values.sortedBy { it.id.value }.map { w ->
                    obj(
                        "worktree" to id(w.id), "repository" to id(w.repository), "path" to JsonString(w.path),
                        "branch" to JsonString(w.branch), "owner" to (w.owner?.let(::id) ?: JsonNull), "removed" to JsonBool(w.removed),
                    )
                },
            ),
        ),
    )

    fun decodeState(json: JsonObject): Outcome<WorktreesState> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        FormatHeader.read(f, STATE_FORMAT, STATE_VERSION..STATE_VERSION, d) ?: return Outcome.Failure(d.all)
        val repos = f.array("repositories")?.items.orEmpty().mapIndexedNotNull { i, v ->
            val o = v as? JsonObject ?: return@mapIndexedNotNull null.also { d.error(DiagCode.FIELD_TYPE, "ожидался объект", "$.repositories[$i]") }
            val r = JsonFields(o, "$.repositories[$i]", d)
            readRepository(r).also { r.finish() }
        }
        val trees = f.array("worktrees")?.items.orEmpty().mapIndexedNotNull { i, v ->
            val o = v as? JsonObject ?: return@mapIndexedNotNull null.also { d.error(DiagCode.FIELD_TYPE, "ожидался объект", "$.worktrees[$i]") }
            val w = JsonFields(o, "$.worktrees[$i]", d)
            val id = w.typed("worktree", IdKinds.WORKTREE)
            val repo = w.typed("repository", IdKinds.REPOSITORY)
            val path = w.string("path")
            val branch = w.string("branch")
            val owner = w.optString("owner")?.let { IdKinds.SESSION.parse(it).valueOrNull() ?: null.also { _ -> d.error(DiagCode.VALUE_INVALID, "неверный id сессии", "$.worktrees[$i].owner") } }
            val removed = (w.raw("removed") as? JsonBool)?.value
            w.finish()
            if (id != null && repo != null && path != null && branch != null && removed != null) Worktree(id, repo, path, branch, owner, removed) else null
        }
        f.finish()
        if (d.hasErrors) return Outcome.Failure(d.all)
        return Outcome.success(WorktreesState(repos.associateBy { it.id }, trees.associateBy { it.id }))
    }

    private fun repository(r: Repository): JsonObject =
        obj("repository" to id(r.id), "target" to id(r.target), "root" to JsonString(r.root))

    private fun readRepository(f: JsonFields): Repository? {
        val id = f.typed("repository", IdKinds.REPOSITORY)
        val target = f.typed("target", IdKinds.TARGET)
        val root = f.string("root")
        return if (id != null && target != null && root != null) Repository(id, target, root) else null
    }

    private fun obj(vararg pairs: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*pairs))

    private fun id(v: TypedId) = JsonString(v.value)

    private fun <T : TypedId> JsonFields.typed(name: String, kind: IdKind<T>): T? = string(name)?.let { v ->
        kind.parse(v).valueOrNull() ?: null.also { diagnostics.error(DiagCode.VALUE_INVALID, "неверный идентификатор ${kind.typeName}", "${path}.$name") }
    }
}
