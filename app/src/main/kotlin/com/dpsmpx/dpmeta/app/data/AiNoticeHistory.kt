package com.dpsmpx.dpmeta.app.data

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.core.result.then
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpmeta.model.Event

data class RecordedNotice(val event: Event, val inFeed: Boolean, val truncated: Boolean = false)

/** Ограниченная история кандидатов уведомлений, а не журнал сессии (D-142). */
class AiNoticeHistory(private val documents: DocumentStore) {
    fun read(now: Timestamp): Outcome<List<RecordedNotice>> = documents.read(KEY).then { document ->
        if (document == null) return@then Outcome.success(emptyList())
        val ds = DiagnosticCollector()
        val fields = JsonFields.root(document.content, ds) ?: return@then Outcome.Failure(ds.all)
        FormatHeader.read(fields, FORMAT, 1..VERSION, ds) ?: return@then Outcome.Failure(ds.all)
        val values = fields.array("items")
        fields.finish()
        if (ds.hasErrors || values == null) return@then Outcome.Failure(ds.all)
        if (values.items.size > MAX_ITEMS) return@then Outcome.failure(DiagCode.VALUE_INVALID, "история уведомлений превышает предел")
        val result = mutableListOf<RecordedNotice>()
        val keys = hashSetOf<String>()
        for ((i, item) in values.items.withIndex()) {
            val o = item as? JsonObject ?: return@then Outcome.failure(DiagCode.FIELD_TYPE, "запись истории — объект")
            val f = JsonFields(o, "$.items[$i]", ds)
            val type = f.string("type")
            val at = f.long("at")
            val key = f.string("key")
            val text = f.string("text")
            val title = f.string("title")
            val count = f.int("count")
            val visible = f.boolean("inFeed")
            val truncated = f.boolean("truncated")
            f.finish()
            if (ds.hasErrors || type == null || at == null || key == null || text == null || title == null || count == null || visible == null || truncated == null) return@then Outcome.Failure(ds.all)
            if (type.length > 64 || !type.matches(Regex("ai\\.[a-z_]+")) || key.isEmpty() || key.length > MAX_KEY || !keys.add(key) || text.length > MAX_TEXT || title.length > MAX_TITLE || count < 1) {
                return@then Outcome.failure(DiagCode.VALUE_INVALID, "неверная запись истории уведомлений")
            }
            result += RecordedNotice(Event(type, Timestamp(at), null, text, mapOf("title" to title, "category" to type.removePrefix("ai.")), mapOf("count" to count.toDouble()), key), visible, truncated)
        }
        Outcome.success(result.filter { now.epochMillis - it.event.at.epochMillis <= RETAIN_MILLIS })
    }

    /** Вызовы сериализует владелец политики; неуспешная запись не затирает последнюю целую. */
    fun add(record: RecordedNotice, now: Timestamp): Outcome<List<RecordedNotice>> {
        val key = record.event.key ?: return Outcome.failure(DiagCode.VALUE_INVALID, "у уведомления нет ключа")
        val count = record.event.numbers["count"]
        if (key.isEmpty() || key.length > MAX_KEY || record.event.project != null || record.event.type.length > 64 || !record.event.type.matches(Regex("ai\\.[a-z_]+")) || count == null || !count.isFinite() || count < 1 || count > Int.MAX_VALUE || count.toInt().toDouble() != count) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "неверный ключ или счётчик уведомления")
        }
        val body = cut(record.event.text, MAX_TEXT)
        val title = cut(record.event.fields["title"].orEmpty(), MAX_TITLE)
        val bounded = record.copy(event = record.event.copy(text = body, fields = mapOf("title" to title, "category" to record.event.type.removePrefix("ai."))), truncated = record.truncated || body != record.event.text || title != record.event.fields["title"])
        return read(now).then { old ->
            val items = (old.filter { it.event.key != key } + bounded)
                .filter { now.epochMillis - it.event.at.epochMillis <= RETAIN_MILLIS }
                .sortedBy { it.event.at.epochMillis }.takeLast(MAX_ITEMS)
            val payload = JsonObject.of(*FormatHeader.write(FORMAT, VERSION), "items" to JsonArray(items.map { r ->
                val e = r.event
                JsonObject.of(
                    "type" to JsonString(e.type), "at" to JsonNumber.of(e.at.epochMillis), "key" to JsonString(checkNotNull(e.key)),
                    "text" to JsonString(e.text), "title" to JsonString(e.fields["title"].orEmpty()), "count" to JsonNumber.of(checkNotNull(e.numbers["count"]).toInt()),
                    "inFeed" to JsonBool(r.inFeed), "truncated" to JsonBool(r.truncated),
                )
            }))
            documents.write(KEY, payload).map { items }
        }
    }

    private fun cut(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val end = if (text[limit - 1].isHighSurrogate() && text[limit].isLowSurrogate()) limit - 1 else limit
        return text.substring(0, end)
    }

    companion object {
        const val FORMAT = "dpmeta.ai-notices"
        const val VERSION = 1
        const val MAX_ITEMS = 200
        const val MAX_TEXT = 2048
        private const val MAX_TITLE = 256
        private const val MAX_KEY = 1024
        private const val RETAIN_MILLIS = 7 * 24 * 60 * 60 * 1000L
        private const val KEY = "ai-notices"
    }
}
