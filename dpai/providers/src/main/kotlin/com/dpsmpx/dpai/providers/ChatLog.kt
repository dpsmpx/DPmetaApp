package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.CorrelationId
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/**
 * Как кончилась реплика: [OK] — ответ целиком; [LENGTH] — по пределу
 * длины; [REFUSED] — модель отказалась; [CANCELLED] — прервал человек;
 * [FAILED] — отказ сети или провайдера ([ChatEntry.note]).
 */
enum class EntryStatus { OK, LENGTH, REFUSED, CANCELLED, FAILED }

/** Реплика чата; у ответа — модель, расход (если провайдер сообщил) и как он кончился. */
data class ChatEntry(
    val role: ChatRole,
    val text: String,
    val model: String? = null,
    val status: EntryStatus = EntryStatus.OK,
    val note: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val contextScope: String? = null,
    val contextSources: Map<String, String?> = emptyMap(),
    val contextCommit: String? = null,
)

/**
 * Разговор с API-профилем (D-98): переписка — в данных приложения, без
 * ключей; в журнал событий и в контекст задач не попадает.
 */
data class Conversation(val id: CorrelationId, val profile: AccountId, val title: String, val entries: List<ChatEntry>, val project: com.dpsmpx.dpai.core.ids.ProjectId? = null) {
    /**
     * Переписка для следующего запроса: ответы, прерванные и неудачные, не
     * уходят — модель продолжала бы оборванный или отвергнутый текст;
     * пустые реплики Anthropic отвергает.
     */
    fun context(): List<ChatMessage> = entries
        .filter { it.text.isNotBlank() && (it.role == ChatRole.USER || it.status == EntryStatus.OK || it.status == EntryStatus.LENGTH) }
        .map { ChatMessage(it.role, it.text) }

    /** Смена области/байтов отзывает прошлые ответы; история в UI сохраняется полностью. */
    fun projectContext(scope: String, hashes: Map<String, String?>, allowLegacy: Boolean, maxMessages: Int,
        maxChars: Int, maxTokens: Long, commit: String? = null): Outcome<ProjectChatContext> {
        val candidates = entries.withIndex().filter { (i, e) ->
            e.text.isNotBlank() && (e.role == ChatRole.USER || e.status == EntryStatus.OK || e.status == EntryStatus.LENGTH) &&
                (i == entries.lastIndex && e.role == ChatRole.USER || e.contextScope == scope && (e.role == ChatRole.USER || e.contextCommit == commit) && e.contextSources.all { (p, h) -> hashes.containsKey(p) && hashes[p] == h } ||
                    allowLegacy && e.contextScope == null && e.role == ChatRole.USER)
        }
        val latest = candidates.lastOrNull()?.takeIf { it.value.role == ChatRole.USER }
            ?: return Outcome.failure(DiagCode.VALUE_INVALID, "нет текущего запроса человека")
        var chars = maxChars - latest.value.text.length
        var tokens = maxTokens - com.dpsmpx.dpai.contexts.TokenEstimate.of(latest.value.text)
        if (chars < 0 || tokens < 0) return Outcome.failure(DiagCode.CONTEXT_BUDGET_EXCEEDED, "текущий запрос человека превышает бюджет контекста")
        val chosen = linkedSetOf(latest)
        // Первая цель + недавнее; старые промежуточные ответы остаются в журнале, не в каждом запросе.
        val first = candidates.firstOrNull { it.value.role == ChatRole.USER }
        if (maxMessages > 0 && first != null && first != latest && (first.value.text.length > chars ||
            com.dpsmpx.dpai.contexts.TokenEstimate.of(first.value.text) > tokens))
            return Outcome.failure(DiagCode.CONTEXT_BUDGET_EXCEEDED, "исходная цель вместе с текущим запросом не помещается в бюджет")
        val ranked = listOfNotNull(first?.takeIf { maxMessages > 0 }) + candidates.dropLast(1).takeLast(maxMessages).asReversed()
        for (e in ranked.distinct()) {
            if (e in chosen || chosen.size >= maxMessages + 1) continue
            val cost = com.dpsmpx.dpai.contexts.TokenEstimate.of(e.value.text)
            if (e.value.text.length <= chars && cost <= tokens) { chosen += e; chars -= e.value.text.length; tokens -= cost }
        }
        val messages = chosen.sortedBy { it.index }.map { ChatMessage(it.value.role, it.value.text) }
        return Outcome.success(ProjectChatContext(messages, entries.size - messages.size))
    }

    fun plus(e: ChatEntry): Conversation = copy(entries = (entries + e).takeLast(ChatBook.MAX_ENTRIES))

    companion object {
        /** Заголовок — начало первой реплики человека. */
        fun title(firstMessage: String): String = firstMessage.replace(Regex("\\s+"), " ").trim().let { if (it.length > 60) it.take(60) + "…" else it }
    }
}

data class ProjectChatContext(val messages: List<ChatMessage>, val omitted: Int)

/**
 * Переписка чатов — документ `dpai.api-chats` v3, читает v1/v2 (D-214, D-224) (`docs/DATA_MODEL.md`).
 * Пределы держат документ небольшим: старые разговоры и реплики
 * вытесняются новыми; текст длиннее [MAX_TEXT] обрезается при записи.
 */
object ChatBook {
    const val FORMAT = "dpai.api-chats"
    const val VERSION = 3
    const val MAX_CONVERSATIONS = 50
    const val MAX_ENTRIES = 400
    const val MAX_TEXT = 200_000

    fun toJson(conversations: List<Conversation>): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "conversations" to JsonArray(
                conversations.takeLast(MAX_CONVERSATIONS).map { c ->
                    JsonObject(linkedMapOf<String, JsonValue>(
                        "id" to JsonString(c.id.value),
                        "profile" to JsonString(c.profile.value),
                        "title" to JsonString(c.title),
                        "entries" to JsonArray(c.entries.takeLast(MAX_ENTRIES).map(::entry)),
                    ).also { fields -> c.project?.let { fields["project"] = JsonString(it.value) } })
                },
            ),
        ),
    )

    private fun entry(e: ChatEntry): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>(
            "role" to JsonString(e.role.name.lowercase()),
            "text" to JsonString(e.text.take(MAX_TEXT)),
            "status" to JsonString(e.status.name.lowercase()),
        ).also { m ->
            e.model?.let { m["model"] = JsonString(it) }
            e.note?.let { m["note"] = JsonString(it) }
            e.inputTokens?.let { m["inputTokens"] = JsonNumber.of(it) }
            e.outputTokens?.let { m["outputTokens"] = JsonNumber.of(it) }
            e.contextScope?.let { m["contextScope"] = JsonString(it) }
            e.contextCommit?.let { m["contextCommit"] = JsonString(it) }
            if (e.contextSources.isNotEmpty()) m["contextSources"] = JsonObject(e.contextSources.mapValues { (_, hash) -> hash?.let(::JsonString) ?: com.dpsmpx.dpai.core.serialization.JsonNull })
        },
    )

    /** Чтение строгое (D-09): находка в любом разговоре — отказ с путём, а не молча урезанная переписка. */
    fun fromJson(value: JsonValue): Outcome<List<Conversation>> {
        val d = DiagnosticCollector()
        val f = JsonFields.root(value, d) ?: return Outcome.Failure(d.all)
        val version = FormatHeader.read(f, FORMAT, 1..VERSION, d) ?: return Outcome.Failure(d.all)
        val items = f.array("conversations")?.items.orEmpty()
        f.finish()
        val out = items.takeLast(MAX_CONVERSATIONS).mapIndexedNotNull { i, item ->
            val at = "$.conversations[$i]"
            val obj = item as? JsonObject ?: return@mapIndexedNotNull null.also { d.error(DiagCode.FIELD_TYPE, "ожидался объект", at) }
            val c = JsonFields(obj, at, d)
            val id = c.string("id")?.let { IdKinds.CORRELATION.parse(it).valueOrNull() }
            val profile = c.string("profile")?.let { IdKinds.ACCOUNT.parse(it).valueOrNull() }
            val title = c.string("title")
            val projectText = c.optString("project")
            if (version == 1 && projectText != null) d.error(DiagCode.VALUE_INVALID, "привязка проекта требует версии 2", "$at.project")
            val project = projectText?.let { IdKinds.PROJECT.parse(it).valueOrNull() }
            if (projectText != null && project == null) d.error(DiagCode.VALUE_INVALID, "неверный id проекта разговора", "$at.project")
            val entries = c.array("entries")?.items.orEmpty().takeLast(MAX_ENTRIES).mapIndexedNotNull { j, e -> entry(e, "$at.entries[$j]", version, d) }
            c.finish()
            if (id == null || profile == null || title == null) {
                d.error(DiagCode.VALUE_INVALID, "разговор неполон или id чужого вида", at)
                null
            } else {
                Conversation(id, profile, title, entries, project)
            }
        }
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(out)
    }

    private fun entry(v: JsonValue, at: String, version: Int, d: DiagnosticCollector): ChatEntry? {
        val obj = v as? JsonObject ?: return null.also { d.error(DiagCode.FIELD_TYPE, "ожидался объект", at) }
        val e = JsonFields(obj, at, d)
        val role = e.string("role")?.let { r -> ChatRole.entries.firstOrNull { it.name.lowercase() == r } }
        val text = e.string("text")
        val status = e.string("status")?.let { s -> EntryStatus.entries.firstOrNull { it.name.lowercase() == s } }
        val model = e.optString("model")
        val note = e.optString("note")
        val input = e.optLong("inputTokens")
        val output = e.optLong("outputTokens")
        val contextScope = e.optString("contextScope")
        val contextCommit = e.optString("contextCommit")
        if (contextCommit != null && (contextScope == null || !contextCommit.matches(Regex("[a-f0-9]{40}|[a-f0-9]{64}"))))
            d.error(DiagCode.VALUE_INVALID, "неверный commit контекста", at)
        val rawSources = e.raw("contextSources", required = false)
        val contextSources = linkedMapOf<String, String?>()
        if (rawSources != null) {
            if (rawSources !is JsonObject || rawSources.fields.size > 128) d.error(DiagCode.FIELD_TYPE, "неверные ссылки контекста", at)
            else for ((path, hash) in rawSources.fields) {
                val value = (hash as? JsonString)?.value
                if (!com.dpsmpx.dpai.contexts.ContextProfile.path(path) || hash != com.dpsmpx.dpai.core.serialization.JsonNull && value?.matches(Regex("[a-f0-9]{64}")) != true)
                    d.error(DiagCode.VALUE_INVALID, "неверный путь или хеш источника", at)
                else contextSources[path] = value
            }
        }
        if (contextScope != null && !contextScope.matches(Regex("[a-f0-9]{64}")) || contextScope == null && contextSources.isNotEmpty() ||
            version < 3 && (contextScope != null || rawSources != null || contextCommit != null)) d.error(DiagCode.VALUE_INVALID, "неверная привязка контекста реплики", at)
        e.finish()
        if (role == null || text == null || status == null) {
            d.error(DiagCode.VALUE_INVALID, "реплика неполна или значение не из перечня", at)
            return null
        }
        return ChatEntry(role, text, model, status, note, input, output, contextScope, contextSources, contextCommit)
    }
}
