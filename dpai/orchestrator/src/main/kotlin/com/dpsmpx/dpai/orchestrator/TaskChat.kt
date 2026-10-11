package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/** Чья запись ленты чата (D-59). */
enum class ChatRole {
    /** Запрос человека: цель задачи, сообщение в сессию, ответ на запрос, повтор с текстом. */
    USER,

    /** Рассуждение модели — кратко: экран показывает его свёрнутым. */
    THINKING,

    /** Текст модели. */
    ASSISTANT,

    /** Событие контура одной строкой: сессия начата на слоте, передана, остановлена. */
    EVENT,
}

/** Запись ленты: [session] — в какой сессии (у цели задачи — нет). */
data class ChatEntry(val at: Timestamp, val role: ChatRole, val text: String, val session: SessionId? = null)

/**
 * Лента чата задачи — документ `dpai.task-chat` v1 (D-59): все её сессии в
 * одном пространстве по порядку — запросы человека, рассуждения и тексты
 * модели, события контура. Вызовов инструментов и команд в ленте нет.
 * Текст — через редактор секретов (D-14), с пределом длины по роли;
 * хранятся последние [MAX] записей.
 */
object TaskChat {
    const val FORMAT = "dpai.task-chat"
    const val VERSION = 1

    const val MAX = 400

    /** Пределы длины записи: рассуждение — кратко, текст модели — целиком в разумных пределах. */
    fun limit(role: ChatRole): Int = when (role) {
        ChatRole.THINKING -> 4_000
        ChatRole.ASSISTANT -> 32 * 1024
        ChatRole.USER -> 16 * 1024
        ChatRole.EVENT -> 300
    }

    fun entry(at: Timestamp, role: ChatRole, text: String, session: SessionId? = null): ChatEntry {
        val clean = Redactor.STANDARD.redact(text.trim())
        val max = limit(role)
        return ChatEntry(at, role, if (clean.length > max) clean.take(max) + " …" else clean, session)
    }

    fun append(list: List<ChatEntry>, add: List<ChatEntry>): List<ChatEntry> = (list + add.filter { it.text.isNotBlank() }).takeLast(MAX)

    fun toJson(list: List<ChatEntry>): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "entries" to JsonArray(
                list.map { e ->
                    JsonObject(
                        linkedMapOf<String, JsonValue>(
                            "at" to JsonNumber.of(e.at.epochMillis), "role" to JsonString(e.role.name), "text" to JsonString(e.text),
                        ).apply { e.session?.let { put("session", JsonString(it.value)) } },
                    )
                },
            ),
        ),
    )

    fun fromJson(json: JsonObject): Outcome<List<ChatEntry>> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
        val out = f.array("entries")?.items.orEmpty().mapIndexedNotNull { i, v ->
            (v as? JsonObject)?.let { o ->
                val r = JsonFields(o, "$.entries[$i]", d)
                val at = r.long("at")?.let(::Timestamp)
                val role = r.string("role")?.let { name -> ChatRole.entries.firstOrNull { it.name == name } }
                val text = r.string("text")
                val session = r.optString("session")?.let { IdKinds.SESSION.parse(it).valueOrNull() }
                r.finish()
                if (at != null && role != null && text != null) ChatEntry(at, role, text, session) else null
            }
        }
        f.finish()
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(out)
    }
}
