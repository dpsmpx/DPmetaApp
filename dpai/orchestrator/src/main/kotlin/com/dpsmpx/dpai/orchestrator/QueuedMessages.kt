package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/** Сообщение в сессию, ждущее конца хода (D-60): текст человека, модель и усилие запроса. */
data class QueuedMessage(val text: String, val at: Timestamp, val model: String? = null, val effort: String? = null)

/**
 * Очередь сообщений задачи — документ `dpai.task-queued` v1 (D-60): ход
 * не прерывается, сообщения уходят в ту же сессию после него;
 * последние [MAX].
 */
object QueuedMessages {
    const val FORMAT = "dpai.task-queued"
    const val VERSION = 1
    const val MAX = 20

    fun toJson(list: List<QueuedMessage>): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "messages" to JsonArray(
                list.map { m ->
                    JsonObject(
                        linkedMapOf<String, JsonValue>("text" to JsonString(m.text), "at" to JsonNumber.of(m.at.epochMillis)).apply {
                            m.model?.let { put("model", JsonString(it)) }
                            m.effort?.let { put("effort", JsonString(it)) }
                        },
                    )
                },
            ),
        ),
    )

    fun fromJson(json: JsonObject): Outcome<List<QueuedMessage>> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
        val out = f.array("messages")?.items.orEmpty().mapIndexedNotNull { i, v ->
            (v as? JsonObject)?.let { o ->
                val r = JsonFields(o, "$.messages[$i]", d)
                val text = r.string("text")
                val at = r.long("at")?.let(::Timestamp)
                val model = r.optString("model")
                val effort = r.optString("effort")
                r.finish()
                if (text != null && at != null) QueuedMessage(text, at, model, effort) else null
            }
        }
        f.finish()
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(out)
    }
}

/** Фоновая команда прошлой сессии (D-66): когда, в какой сессии, что запущено. */
data class BackgroundJob(val at: Timestamp, val session: com.dpsmpx.dpai.core.ids.SessionId, val command: String)

/**
 * Фоновые команды задачи — документ `dpai.task-background` v1 (D-66): что
 * прошлые сессии запустили в фоне; свежая сессия на другом слоте узнаёт о
 * них из контекста. Последние [MAX].
 */
object BackgroundJobs {
    const val FORMAT = "dpai.task-background"
    const val VERSION = 1
    const val MAX = 20

    fun toJson(list: List<BackgroundJob>): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "jobs" to JsonArray(
                list.map { j ->
                    JsonObject(linkedMapOf<String, JsonValue>("at" to JsonNumber.of(j.at.epochMillis), "session" to JsonString(j.session.value), "command" to JsonString(j.command)))
                },
            ),
        ),
    )

    fun fromJson(json: JsonObject): Outcome<List<BackgroundJob>> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
        val out = f.array("jobs")?.items.orEmpty().mapIndexedNotNull { i, v ->
            (v as? JsonObject)?.let { o ->
                val r = JsonFields(o, "$.jobs[$i]", d)
                val at = r.long("at")?.let(::Timestamp)
                val session = r.string("session")?.let { com.dpsmpx.dpai.core.ids.IdKinds.SESSION.parse(it).valueOrNull() }
                val command = r.string("command")
                r.finish()
                if (at != null && session != null && command != null) BackgroundJob(at, session, command) else null
            }
        }
        f.finish()
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(out)
    }
}

/** Слот следующего хода задачи по выбору человека (D-66) — документ `dpai.task-pin` v1. */
object TaskPin {
    const val FORMAT = "dpai.task-pin"
    const val VERSION = 1

    fun toJson(target: com.dpsmpx.dpai.core.ids.ExecutionTargetId): JsonObject =
        JsonObject(linkedMapOf(*FormatHeader.write(FORMAT, VERSION), "target" to JsonString(target.value)))

    fun fromJson(json: JsonObject): Outcome<com.dpsmpx.dpai.core.ids.ExecutionTargetId> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
        val t = f.string("target")?.let { com.dpsmpx.dpai.core.ids.IdKinds.TARGET.parse(it).valueOrNull() }
        f.finish()
        return if (d.hasErrors || t == null) Outcome.Failure(d.all) else Outcome.success(t)
    }
}
