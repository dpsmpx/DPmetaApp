package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.accounts.Routine
import com.dpsmpx.dpai.accounts.AiAdmission
import com.dpsmpx.dpai.accounts.AiBackend
import com.dpsmpx.dpai.accounts.AiModelRef
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonValue
import java.io.IOException
import java.net.URI

/** Запуск состоялся: облачная сессия, которую открыл маршрут. */
data class RoutineFired(val sessionId: String, val sessionUrl: String)

/**
 * Запуск маршрута Claude Code (D-116; `DPmetaApp:D-08`, F-22): `POST /fire`
 * своего маршрута с Bearer-токеном маршрута и полем `text`. Контракт и
 * пределы — `docs/research/ROUTINES_FIRE.md`.
 *
 * Повтора нет: у `/fire` нет ключа идемпотентности, повтор открыл бы вторую
 * сессию. Обрыв после отправки — отдельный отказ со словами «запуск мог
 * состояться»: человек сверяет сессии в Claude до повтора. Ссылка ответа —
 * только `https://claude.ai/code/…`: иной адрес приложение не откроет.
 */
class RoutineClient(private val transport: Transport = UrlTransport(), private val admission: AiAdmission = AiAdmission.global) {
    fun fire(routine: Routine, token: Secret?, text: String, cancel: Cancel = Cancel()): Outcome<RoutineFired> {
        if (token == null || token.isEmpty) {
            return Outcome.failure(DiagCode.PROVIDER_KEY_MISSING, "у маршрута «${routine.name}» нет токена", SUBJECT)
        }
        if (text.length > MAX_TEXT) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "текст поручения длиннее $MAX_TEXT знаков", SUBJECT)
        }
        val body = if (text.isBlank()) JsonObject.of() else JsonObject.of("text" to jstr(text))
        val call = HttpCall(
            "POST",
            URI(routine.fireUrl),
            mapOf(
                "anthropic-version" to AnthropicProvider.VERSION,
                "anthropic-beta" to BETA,
                "content-type" to "application/json",
                "accept" to "application/json",
            ),
            CanonicalJson.write(body).toByteArray(Charsets.UTF_8),
            SecretHeader("Authorization", "Bearer ", token),
        )
        ProviderHosts.check(call, emptySet()).let { if (it is Outcome.Failure) return it }
        // /fire не сообщает модель и окончание облачной сессии. Не выдаём такой запуск за SINGLE.
        val owner = "routine:${ids.next(IdKinds.CORRELATION).value}"
        when (val reserved = admission.acquire(owner, AiModelRef(AiBackend.CLOUD_ROUTINE, null, routine.id))) {
            is Outcome.Failure -> return reserved
            is Outcome.Success -> Unit
        }
        val reply = when (val r = transport.open(call, cancel)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return reply.use {
            try {
                if (it.status != 200) {
                    // Эти ответы подтверждают отказ до запуска; 5xx/обрыв оставляют неопределённость.
                    if (it.status in setOf(401, 403, 404, 429)) admission.release(owner)
                    return refusal(it)
                }
                parse(JsonParser().parse(it.readText(MAX_REPLY_CHARS)).valueOrNull()).also { parsed ->
                    if (parsed is Outcome.Success) admission.cloudReference(owner, parsed.value.sessionUrl.substringBefore('?'))
                }
            } catch (e: IOException) {
                Outcome.failure(DiagCode.NETWORK_UNREACHABLE, "ответ оборвался (${e.javaClass.simpleName}): запуск мог состояться — сверьте сессии в Claude до повтора", SUBJECT)
            }
        }
    }

    private fun parse(v: JsonValue?): Outcome<RoutineFired> {
        val o = v.obj() ?: return Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "ответ — не объект JSON", SUBJECT)
        val id = o["claude_code_session_id"].str()
        val url = o["claude_code_session_url"].str()
        return when {
            id.isNullOrBlank() || url.isNullOrBlank() ->
                Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "в ответе нет сессии: запуск мог состояться — сверьте сессии в Claude", SUBJECT)
            !safeSessionUrl(url) ->
                Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "ссылка сессии не на claude.ai/code — не открывается", SUBJECT)
            else -> Outcome.success(RoutineFired(id, url))
        }
    }

    private fun refusal(reply: HttpReply): Outcome.Failure {
        val status = reply.status
        val detail = try {
            HttpProvider.errorMessage(JsonParser().parse(reply.readText(HttpProvider.MAX_ERROR_CHARS)).valueOrNull())
        } catch (_: IOException) {
            null
        }
        val tail = detail?.let { ": $it" }.orEmpty()
        return when (status) {
            401 -> Outcome.failure(DiagCode.ROUTINE_TOKEN_INVALID, "токен маршрута не принят (401) — выпустите новый в настройках маршрута$tail", SUBJECT)
            403 -> Outcome.failure(DiagCode.ROUTINE_TOKEN_INVALID, "нет права запускать маршрут (403)$tail", SUBJECT)
            404 -> Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "маршрут не найден (404) — проверьте адрес /fire$tail", SUBJECT)
            429 -> Outcome.failure(DiagCode.ROUTINE_LIMIT_REACHED, "предел запусков (429): $LIMITS" + retryAfter(reply) + tail, SUBJECT)
            in 300..399 -> Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "переадресация ($status) не выполняется", SUBJECT)
            in 500..599 -> Outcome.failure(DiagCode.PROVIDER_UNAVAILABLE, "сервис недоступен ($status): запуск мог не состояться — сверьте сессии в Claude до повтора$tail", SUBJECT)
            else -> Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "запуск отвергнут ($status)$tail", SUBJECT)
        }
    }

    private fun retryAfter(reply: HttpReply): String {
        val s = reply.header("retry-after")?.trim()?.toLongOrNull() ?: return ""
        return if (s in 1..MAX_RETRY_AFTER) "; повтор — не раньше чем через ${(s + 59) / 60} мин" else ""
    }

    companion object {
        private val ids = IdGenerator(Clock.SYSTEM, RandomSource.secure())
        const val SUBJECT = "routine"

        /** Бета-заголовок маршрутов; сервис принимает запрос и без него, с ним — так, как в документации. */
        const val BETA = "experimental-cc-routine-2026-04-01"

        /** Предел поля `text` по документации. */
        const val MAX_TEXT = 65_536
        const val MAX_REPLY_CHARS = 64 * 1024
        private const val MAX_RETRY_AFTER = 24 * 3600L
        private const val LIMITS = "30 запусков в час на маршрут, 100 — на аккаунт"

        /** Ссылка сессии, которую приложение откроет: `https://claude.ai/code/…` без входа, порта и якоря. */
        fun safeSessionUrl(url: String): Boolean {
            val u = try {
                URI(url)
            } catch (_: Exception) {
                return false
            }
            return u.scheme == "https" && u.host == "claude.ai" && u.port == -1 && u.userInfo == null &&
                u.rawPath.orEmpty().startsWith("/code/") && u.rawFragment == null
        }
    }
}
