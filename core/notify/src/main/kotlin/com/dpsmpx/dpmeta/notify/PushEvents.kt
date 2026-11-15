package com.dpsmpx.dpmeta.notify

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpmeta.model.Event

/**
 * Событие мгновенного канала (D-23, D-152; F-55): только тип, ключ проекта, ветка,
 * время, id сессии, причина и id события — без текста ответа Claude. Подпись —
 * HMAC-SHA256 общим ключом над [canonical]; её считает `:core:net` (`PushSignature`),
 * сюда приходит функцией [mac] — модуль от сети не зависит.
 */
data class PushEvent(
    val type: String,
    val project: String,
    val branch: String,
    val atSeconds: Long,
    val session: String,
    val reason: String,
    val id: String,
) {
    /** Что подписывается: поля по порядку через перевод строки, с версией впереди. */
    val canonical: String get() = listOf(VERSION_TAG, type, project, branch, atSeconds.toString(), session, reason, id).joinToString("\n")

    companion object {
        const val VERSION_TAG = "dpmeta-push/1"
        const val MAX_AGE_SECONDS = 10 * 60L

        /** Типы канала — только о ходах Claude; остальное приложение узнаёт опросом. */
        val TYPES: Set<String> = setOf("claude.turn.done", "claude.needs_input", "claude.turn.failed", "claude.session.ended")
    }
}

object PushEvents {
    private val TYPE = Regex("[a-z][a-z0-9_.]{0,60}")
    private val PROJECT = Regex("[A-Za-z0-9._-]{0,100}")
    private val SESSION = Regex("[A-Za-z0-9_-]{0,100}")
    private val REASON = Regex("[a-z_]{0,40}")
    private val ID = Regex("[0-9a-f]{16,64}")
    private val SIG = Regex("[0-9a-f]{64}")

    /**
     * Сообщение темы ntfy (строка потока `/json`): `event` = `message` — текст события
     * в `message`; служебные (`open`, `keepalive`) — `null` без отказа.
     */
    fun message(line: String): String? {
        val o = JsonParser().parse(line).valueOrNull() as? JsonObject ?: return null
        if ((o["event"] as? JsonString)?.value != "message") return null
        return (o["message"] as? JsonString)?.value
    }

    /**
     * Проверить событие [text]: формат, тип канала, подпись [mac], срок 10 минут от [now]
     * в обе стороны (часы источника могут спешить) и повтор по [seen]. Любой отказ —
     * `PUSH_SIGNATURE_INVALID` с причиной; подделка даёт только отказ, не действие.
     */
    fun verify(text: String, now: Timestamp, seen: Set<String>, mac: (String) -> String): Outcome<PushEvent> {
        fun bad(why: String): Outcome<PushEvent> = Outcome.failure(DiagCode.PUSH_SIGNATURE_INVALID, "событие канала отброшено: $why", "push")
        if (text.length > 4096) return bad("слишком длинное")
        val o = JsonParser().parse(text).valueOrNull() as? JsonObject ?: return bad("не JSON")
        if ((o["v"] as? JsonNumber)?.toLongExact() != 1L) return bad("версия не 1")
        fun str(k: String, rx: Regex): String? = (o[k] as? JsonString)?.value?.takeIf { rx.matches(it) } ?: if (o[k] == null) "" else null
        val type = (o["type"] as? JsonString)?.value?.takeIf { TYPE.matches(it) } ?: return bad("тип")
        if (type !in PushEvent.TYPES) return bad("тип $type не для канала")
        val e = PushEvent(
            type,
            str("project", PROJECT) ?: return bad("проект"),
            (o["branch"] as? JsonString)?.value?.takeIf { it.length <= 200 && '\n' !in it } ?: if (o["branch"] == null) "" else return bad("ветка"),
            (o["at"] as? JsonNumber)?.toLongExact() ?: return bad("время"),
            str("session", SESSION) ?: return bad("сессия"),
            str("reason", REASON) ?: return bad("причина"),
            (o["id"] as? JsonString)?.value?.takeIf { ID.matches(it) } ?: return bad("id"),
        )
        val sig = (o["sig"] as? JsonString)?.value?.takeIf { SIG.matches(it) } ?: return bad("нет подписи")
        if (!constantTimeEquals(mac(e.canonical), sig)) return bad("подпись неверна")
        if (kotlin.math.abs(now.epochMillis / 1000 - e.atSeconds) > PushEvent.MAX_AGE_SECONDS) return bad("старше 10 минут")
        if (e.id in seen) return bad("повтор")
        return Outcome.success(e)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /** Событие для движка правил: текст — словами, без содержимого ответа. */
    fun event(e: PushEvent): Event {
        val what = when (e.type) {
            "claude.turn.done" -> "Claude закончил ответ"
            "claude.needs_input" -> if (e.reason == "permission") "Claude ждёт разрешения" else "Claude ждёт ввода"
            "claude.turn.failed" -> "Ход Claude прервался" + if (e.reason.isNotEmpty()) " (${e.reason})" else ""
            else -> "Сессия Claude закончилась"
        }
        val text = what + if (e.branch.isNotEmpty()) " · ветка ${e.branch}" else ""
        return Event(e.type, Timestamp(e.atSeconds * 1000), e.project.ifEmpty { null }, text,
            fields = buildMap { if (e.branch.isNotEmpty()) put("branch", e.branch); if (e.session.isNotEmpty()) put("session", e.session) },
            key = e.session.ifEmpty { e.id })
    }

    /** Ссылка «Открыть сессию»: только облачные сессии claude.ai (`session_…`). */
    fun sessionUrl(e: Event): String? = e.fields["session"]?.takeIf { Regex("session_[A-Za-z0-9]{8,80}").matches(it) }?.let { "https://claude.ai/code/$it" }
}
