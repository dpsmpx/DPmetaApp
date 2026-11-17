package com.dpsmpx.dpmeta.github

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpmeta.net.FormBody
import com.dpsmpx.dpmeta.net.HttpRequest
import com.dpsmpx.dpmeta.net.Net
import java.net.URI

/**
 * Вход в GitHub App автора по device flow (D-17, ТЗ, §6.2): приложение
 * показывает код, автор вводит его на github.com, приложение спрашивает
 * токен. Ожидание между опросами — дело вызывающего (WorkManager или
 * другая оболочка): здесь нет ни сна, ни своих потоков.
 *
 * PAT — запасной путь (D-17): тот же [Secret], без этого класса.
 */
class DeviceFlow(private val net: Net, private val clientId: String, private val base: URI = URI("https://github.com/")) {
    /** Код для автора; [deviceCode] — секрет приложения, автору не показывается. */
    class Code(val deviceCode: Secret, val userCode: String, val verificationUri: String, val intervalSeconds: Int, val expiresInSeconds: Int)

    sealed interface Poll {
        /** Автор ещё не ввёл код: спросить снова через [intervalSeconds]. */
        data class Pending(val intervalSeconds: Int) : Poll

        /** Токен получен. */
        class Token(val token: Secret) : Poll

        /** Автор отказал или код истёк: начинать заново. */
        data class Stopped(val reason: String) : Poll
    }

    private val json = JsonParser()

    fun start(): Outcome<Code> = post("login/device/code", FormBody.of("client_id" to clientId)).let { r ->
        when (r) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                val o = r.value
                val device = (o["device_code"] as? JsonString)?.value
                val user = (o["user_code"] as? JsonString)?.value
                val uri = (o["verification_uri"] as? JsonString)?.value
                val interval = (o["interval"] as? JsonNumber)?.toLongExact()?.toInt() ?: 5
                val expires = (o["expires_in"] as? JsonNumber)?.toLongExact()?.toInt() ?: 900
                if (device == null || user == null || uri == null) {
                    Outcome.failure(DiagCode.CONNECTOR_REPLY_INVALID, "ответ device flow без кода", "github.com")
                } else {
                    Outcome.success(Code(Secret(device), user, uri, interval, expires))
                }
            }
        }
    }

    fun poll(code: Code): Outcome<Poll> {
        val body = FormBody.of(
            "client_id" to clientId,
            "device_code" to code.deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        )
        val o = when (val r = post("login/oauth/access_token", body)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        (o["access_token"] as? JsonString)?.value?.let { return Outcome.success(Poll.Token(Secret(it))) }
        return when (val error = (o["error"] as? JsonString)?.value) {
            "authorization_pending" -> Outcome.success(Poll.Pending(code.intervalSeconds))
            "slow_down" -> Outcome.success(Poll.Pending((o["interval"] as? JsonNumber)?.toLongExact()?.toInt() ?: (code.intervalSeconds + 5)))
            "expired_token" -> Outcome.success(Poll.Stopped("код истёк"))
            "access_denied" -> Outcome.success(Poll.Stopped("автор отказал"))
            else -> Outcome.failure(DiagCode.GITHUB_AUTH_EXPIRED, "вход в GitHub не удался: ${error ?: "ответ без токена"}", "github.com")
        }
    }

    private fun post(path: String, body: ByteArray): Outcome<JsonObject> {
        val request = HttpRequest(
            "POST",
            base.resolve(path),
            mapOf("Accept" to "application/json", "Content-Type" to FormBody.CONTENT_TYPE, "User-Agent" to GitHub.USER_AGENT),
            body,
        )
        val r = when (val s = net.send(request)) {
            is Outcome.Failure -> return s
            is Outcome.Success -> s.value
        }
        if (r.status !in 200..299) return Outcome.failure(DiagCode.CONNECTOR_REPLY_INVALID, "github.com ответил ${r.status}", "github.com")
        return when (val v = json.parse(r.text)) {
            is Outcome.Failure -> Outcome.failure(DiagCode.CONNECTOR_REPLY_INVALID, "ответ device flow — не JSON", "github.com")
            is Outcome.Success -> (v.value as? JsonObject)?.let { Outcome.success(it) }
                ?: Outcome.failure(DiagCode.CONNECTOR_REPLY_INVALID, "ответ device flow — не объект", "github.com")
        }
    }
}
