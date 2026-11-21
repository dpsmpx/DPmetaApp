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

    fun plus(e: ChatEntry): Conversation = copy(entries = (entries + e).takeLast(ChatBook.MAX_ENTRIES))

    companion object {
        /** Заголовок — начало первой реплики человека. */
        fun title(firstMessage: String): String = firstMessage.replace(Regex("\\s+"), " ").trim().let { if (it.length > 60) it.take(60) + "…" else it }
    }
}

/**
 * Переписка чатов — документ `dpai.api-chats` v2, читает v1 (D-214) (`docs/DATA_MODEL.md`).
 * Пределы держат документ небольшим: старые разговоры и реплики
 * вытесняются новыми; текст длиннее [MAX_TEXT] обрезается при записи.
 */
object ChatBook {
    const val FORMAT = "dpai.api-chats"
    const val VERSION = 2
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
            val entries = c.array("entries")?.items.orEmpty().takeLast(MAX_ENTRIES).mapIndexedNotNull { j, e -> entry(e, "$at.entries[$j]", d) }
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

    private fun entry(v: JsonValue, at: String, d: DiagnosticCollector): ChatEntry? {
        val obj = v as? JsonObject ?: return null.also { d.error(DiagCode.FIELD_TYPE, "ожидался объект", at) }
        val e = JsonFields(obj, at, d)
        val role = e.string("role")?.let { r -> ChatRole.entries.firstOrNull { it.name.lowercase() == r } }
        val text = e.string("text")
        val status = e.string("status")?.let { s -> EntryStatus.entries.firstOrNull { it.name.lowercase() == s } }
        val model = e.optString("model")
        val note = e.optString("note")
        val input = e.optLong("inputTokens")
        val output = e.optLong("outputTokens")
        e.finish()
        if (role == null || text == null || status == null) {
            d.error(DiagCode.VALUE_INVALID, "реплика неполна или значение не из перечня", at)
            return null
        }
        return ChatEntry(role, text, model, status, note, input, output)
    }
}
