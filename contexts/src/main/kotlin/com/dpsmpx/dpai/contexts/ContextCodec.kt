package com.dpsmpx.dpai.contexts

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.ids.IdKind
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TypedId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.security.Origin
import com.dpsmpx.dpai.security.Sensitivity

/**
 * Чтение версий контекста (`dpai.context` v2) и handoff (`dpai.handoff` v1)
 * — строгое (D-09): неизвестное поле, неверный id, хеш, не совпавший с
 * содержимым, — отказ. Так контур после перезапуска продолжает задачу с
 * тем же контекстом и handoff (`docs/RECOVERY.md`), а не с пустыми.
 */
object ContextCodec {
    fun readContext(json: JsonObject): Outcome<ContextBundle> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        val format = FormatHeader.read(f, ContextBundle.FORMAT, 2..ContextBundle.VERSION, d) ?: return Outcome.Failure(d.all)
        val id = f.typed("id", IdKinds.CONTEXT)
        val number = f.int("contextVersion")
        val task = f.typed("task", IdKinds.TASK)
        val project = f.typed("project", IdKinds.PROJECT)
        val objective = f.string("objective")
        val nextAction = f.optString("nextAction")
        val sources = f.array("sources")?.items.orEmpty().mapIndexedNotNull { i, v ->
            val o = v as? JsonObject ?: return@mapIndexedNotNull null.also { d.error(DiagCode.FIELD_TYPE, "ожидался объект", "$.sources[$i]") }
            val s = JsonFields(o, "$.sources[$i]", d)
            val key = s.string("key")
            val level = s.enumOf<SourceLevel>("level")
            val sensitivity = s.enumOf<Sensitivity>("sensitivity")
            val origin = s.enumOf<Origin>("origin")
            val hash = s.hash("hash")
            val content = s.string("content")
            s.finish()
            if (key != null && level != null && sensitivity != null && origin != null && hash != null && content != null) {
                IncludedSource(key, level, sensitivity, origin, hash, content)
            } else {
                null
            }
        }
        val omitted = if (format < 3) {
            emptyList()
        } else {
            f.array("omitted")?.items.orEmpty().mapIndexedNotNull { i, v ->
                val o = v as? JsonObject ?: return@mapIndexedNotNull null.also { d.error(DiagCode.FIELD_TYPE, "ожидался объект", "$.omitted[$i]") }
                val s = JsonFields(o, "$.omitted[$i]", d)
                val key = s.string("key")
                val level = s.enumOf<SourceLevel>("level")
                val tokens = s.long("estimatedTokens")
                val sameAs = s.optString("sameAs")
                s.finish()
                if (key != null && level != null && tokens != null) OmittedSource(key, level, tokens, sameAs) else null
            }
        }
        val previous = f.optString("previous")?.let { ContentHash.parse(it).valueOrNull() ?: null.also { _ -> d.error(DiagCode.VALUE_INVALID, "неверный хеш", "$.previous") } }
        val createdAt = f.long("createdAt")?.let(::Timestamp)
        val hash = f.hash("hash")
        f.finish()
        if (d.hasErrors || id == null || number == null || task == null || project == null || objective == null || createdAt == null || hash == null) {
            return Outcome.Failure(d.all.ifEmpty { listOf(com.dpsmpx.dpai.core.diagnostics.Diagnostic.error(DiagCode.FIELD_MISSING, "версия контекста неполна", "$")) })
        }
        val bundle = ContextBundle(id, number, task, project, objective, nextAction, sources, previous, createdAt, omitted)
        if (bundle.hashAt(format) != hash) return Outcome.failure(DiagCode.HASH_MISMATCH, "хеш версии контекста не совпал с содержимым", "context $id")
        return Outcome.success(bundle)
    }

    fun readHandoff(json: JsonObject): Outcome<Handoff> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        FormatHeader.read(f, Handoff.FORMAT, Handoff.VERSION..Handoff.VERSION, d) ?: return Outcome.Failure(d.all)
        val id = f.typed("id", IdKinds.HANDOFF)
        val key = f.string("key")
        val reason = f.enumOf<HandoffReason>("reason")
        val session = f.typed("session", IdKinds.SESSION)
        val task = f.typed("task", IdKinds.TASK)
        val project = f.typed("project", IdKinds.PROJECT)
        val context = f.typed("context", IdKinds.CONTEXT)
        val contextHash = f.hash("contextHash")
        val state = f.string("state")
        val lists = listOf("completed", "verified", "failed", "warnings", "openProblems").associateWith { f.strings(it) }
        val currentAction = f.optString("currentAction")
        val nextAction = f.string("nextAction")
        val modified = f.strings("modifiedFiles")
        val commit = f.optString("commit")
        val branch = f.optString("branch")
        val workspace = f.optString("workspace")
        val createdAt = f.long("createdAt")?.let(::Timestamp)
        val hash = f.hash("hash")
        f.finish()
        if (d.hasErrors || id == null || key == null || reason == null || session == null || task == null || project == null || context == null ||
            contextHash == null || state == null || lists.values.any { it == null } || nextAction == null || modified == null || createdAt == null || hash == null
        ) {
            return Outcome.Failure(d.all.ifEmpty { listOf(com.dpsmpx.dpai.core.diagnostics.Diagnostic.error(DiagCode.FIELD_MISSING, "handoff неполон", "$")) })
        }
        val h = Handoff(
            id, key, reason, session, task, project, context, contextHash, state,
            lists.getValue("completed")!!, lists.getValue("verified")!!, lists.getValue("failed")!!, lists.getValue("warnings")!!,
            lists.getValue("openProblems")!!, currentAction, nextAction, modified, commit, branch, workspace, createdAt,
        )
        if ((h.toJson()["hash"] as JsonString).value != hash.toString()) {
            return Outcome.failure(DiagCode.HASH_MISMATCH, "хеш handoff не совпал с содержимым", "handoff $id")
        }
        return Outcome.success(h)
    }

    private fun <T : TypedId> JsonFields.typed(name: String, kind: IdKind<T>): T? = string(name)?.let { v ->
        kind.parse(v).valueOrNull() ?: null.also { diagnostics.error(DiagCode.VALUE_INVALID, "неверный идентификатор ${kind.typeName}", "${path}.$name") }
    }

    private fun JsonFields.hash(name: String): ContentHash? = string(name)?.let { v ->
        ContentHash.parse(v).valueOrNull() ?: null.also { diagnostics.error(DiagCode.VALUE_INVALID, "неверный хеш", "${path}.$name") }
    }

    private fun JsonFields.strings(name: String): List<String>? = array(name)?.items?.let { items ->
        val out = items.map { (it as? JsonString)?.value }
        if (out.any { it == null }) null.also { diagnostics.error(DiagCode.FIELD_TYPE, "ожидались строки", "${path}.$name") } else out.filterNotNull()
    }

    private inline fun <reified E : Enum<E>> JsonFields.enumOf(name: String): E? = string(name)?.let { v ->
        enumValues<E>().firstOrNull { it.name == v } ?: null.also { diagnostics.error(DiagCode.VALUE_INVALID, "неизвестное значение $v", "${path}.$name") }
    }
}
