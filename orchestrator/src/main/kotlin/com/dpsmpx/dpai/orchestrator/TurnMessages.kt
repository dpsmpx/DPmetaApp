package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
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

/**
 * Итоговое сообщение хода (D-42): то, на чём модель остановилась и ждёт
 * ввода, — целиком (поле `result` потока Claude Code, через редактор
 * агента, D-14). [status] — итог хода (`COMPLETED`, `INCOMPLETE`, …).
 */
data class TurnMessage(val session: SessionId, val turn: Int, val at: Timestamp, val status: String, val model: String?, val text: String)

/**
 * Сообщения ходов задачи — документ `dpai.task-messages` v1 (не журнал
 * событий: там текст модели урезан до `maxOutputChars`, а здесь — полный);
 * последние [MAX] ходов задачи.
 */
object TurnMessages {
    const val FORMAT = "dpai.task-messages"
    const val VERSION = 1

    /** Сколько последних ходов задачи хранить. */
    const val MAX = 50

    /** Предел одного сообщения: строка потока агента меньше 256 КиБ, больше и не придёт. */
    const val MAX_TEXT = 256 * 1024

    fun append(list: List<TurnMessage>, m: TurnMessage): List<TurnMessage> =
        (list.filterNot { it.session == m.session && it.turn == m.turn } + m.copy(text = m.text.take(MAX_TEXT))).takeLast(MAX)

    fun toJson(list: List<TurnMessage>): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "messages" to JsonArray(
                list.map { m ->
                    JsonObject(
                        linkedMapOf<String, JsonValue>(
                            "session" to JsonString(m.session.value), "turn" to JsonNumber.of(m.turn), "at" to JsonNumber.of(m.at.epochMillis),
                            "status" to JsonString(m.status), "text" to JsonString(m.text),
                        ).apply { m.model?.let { put("model", JsonString(it)) } },
                    )
                },
            ),
        ),
    )

    fun fromJson(json: JsonObject): Outcome<List<TurnMessage>> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
        val out = f.array("messages")?.items.orEmpty().mapIndexedNotNull { i, v ->
            (v as? JsonObject)?.let { o ->
                val r = JsonFields(o, "$.messages[$i]", d)
                val session = r.string("session")?.let { IdKinds.SESSION.parse(it).valueOrNull() }
                val turn = r.int("turn")
                val at = r.long("at")?.let(::Timestamp)
                val status = r.string("status")
                val model = r.optString("model")
                val text = r.string("text")
                r.finish()
                if (session != null && turn != null && at != null && status != null && text != null) TurnMessage(session, turn, at, status, model, text) else null
            }
        }
        f.finish()
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(out)
    }
}
