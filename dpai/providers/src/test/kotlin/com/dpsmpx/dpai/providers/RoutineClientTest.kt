package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.accounts.Routines
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Запуск маршрута `/fire` (D-116): запрос, ответ и отказы — на синтетических образцах формата документации. */
class RoutineClientTest {
    private val trig = "trig_01ABCDEFGHJKMNPQRSTVWXYZ"
    private val routine = Routines.form(
        IdKinds.ACCOUNT.parse("acc_01J9ZQ3K8M7N6P5R4S3T2V1W0X").valueOrNull()!!,
        "Работа", trig, null, "DPmetaApp", null,
    ).valueOrNull()!!
    private val tokenText = sampleKey("sk-ant-oat01")
    private val token = Secret(tokenText)
    private val ok = """{"type":"routine_fire","claude_code_session_id":"session_01HJKLMNOPQRSTUVWXYZ","claude_code_session_url":"https://claude.ai/code/session_01HJKLMNOPQRSTUVWXYZ"}"""

    private fun fire(t: Transport, text: String = "Продолжить ветку") = RoutineClient(t).fire(routine, token, text)

    @Test
    fun requestAndReply() {
        val t = FakeTransport { FakeReply(200, ok) }
        val fired = fire(t).valueOrNull()!!
        assertEquals("session_01HJKLMNOPQRSTUVWXYZ", fired.sessionId)
        assertEquals("https://claude.ai/code/session_01HJKLMNOPQRSTUVWXYZ", fired.sessionUrl)
        val call = t.last
        assertEquals("POST", call.method)
        assertEquals("https://api.anthropic.com/v1/claude_code/routines/$trig/fire", call.url.toString())
        assertEquals(AnthropicProvider.VERSION, call.headers["anthropic-version"])
        assertEquals(RoutineClient.BETA, call.headers["anthropic-beta"])
        assertEquals("application/json", call.headers["content-type"])
        assertEquals("Authorization", call.secret!!.name)
        assertEquals("Bearer ", call.secret.prefix)
        assertEquals("""{"text":"Продолжить ветку"}""", t.bodyOf())
        assertFalse(tokenText in call.url.toString() || call.headers.values.any { tokenText in it } || tokenText in t.bodyOf(), "токен — только заголовком с секретом")
        assertFalse(tokenText in call.secret.toString())
    }

    @Test
    fun blankTextSendsEmptyBody() {
        val t = FakeTransport { FakeReply(200, ok) }
        RoutineClient(t).fire(routine, token, "  ").valueOrNull()!!
        assertEquals("{}", t.bodyOf())
    }

    @Test
    fun refusedBeforeNetwork() {
        val t = FakeTransport { FakeReply(200, ok) }
        assertEquals(DiagCode.PROVIDER_KEY_MISSING, assertIs<Outcome.Failure>(RoutineClient(t).fire(routine, null, "x")).errors.single().code)
        assertEquals(DiagCode.PROVIDER_KEY_MISSING, assertIs<Outcome.Failure>(RoutineClient(t).fire(routine, Secret(""), "x")).errors.single().code)
        assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(fire(t, "я".repeat(RoutineClient.MAX_TEXT + 1))).errors.single().code)
        assertTrue(t.calls.isEmpty(), "без токена и с длинным текстом запрос не уходит")
        assertIs<Outcome.Success<*>>(fire(t, "я".repeat(RoutineClient.MAX_TEXT)))
    }

    @Test
    fun refusalsByStatus() {
        fun err(body: String) = """{"type":"error","error":{"type":"x","message":"$body"}}"""
        val cases = listOf(
            Triple(400, err("Routine is paused"), DiagCode.PROVIDER_REQUEST_REJECTED),
            Triple(401, err("Invalid bearer token"), DiagCode.ROUTINE_TOKEN_INVALID),
            Triple(403, err("Forbidden"), DiagCode.ROUTINE_TOKEN_INVALID),
            Triple(404, err("Not found"), DiagCode.PROVIDER_REQUEST_REJECTED),
            Triple(429, err("Too many"), DiagCode.ROUTINE_LIMIT_REACHED),
            Triple(500, err("Internal"), DiagCode.PROVIDER_UNAVAILABLE),
            Triple(503, err("Overloaded"), DiagCode.PROVIDER_UNAVAILABLE),
            Triple(302, "", DiagCode.PROVIDER_REQUEST_REJECTED),
        )
        for ((status, body, code) in cases) {
            val e = assertIs<Outcome.Failure>(fire(FakeTransport { FakeReply(status, body) })).errors.single()
            assertEquals(code, e.code, "$status")
            assertEquals(RoutineClient.SUBJECT, e.subject)
        }
        val paused = assertIs<Outcome.Failure>(fire(FakeTransport { FakeReply(400, err("Routine is paused")) })).errors.single()
        assertTrue("Routine is paused" in paused.message, "сообщение сервиса — в отказе")
        val limited = assertIs<Outcome.Failure>(fire(FakeTransport { FakeReply(429, err("x"), mapOf("Retry-After" to "125")) })).errors.single()
        assertTrue("через 3 мин" in limited.message, limited.message)
        assertTrue("30 запусков в час" in limited.message)
    }

    @Test
    fun replyMustNameSessionOnClaude() {
        for (bad in listOf(
            "не JSON",
            "[]",
            """{"type":"routine_fire"}""",
            """{"claude_code_session_id":"s","claude_code_session_url":"https://evil.example/code/s"}""",
            """{"claude_code_session_id":"s","claude_code_session_url":"http://claude.ai/code/s"}""",
            """{"claude_code_session_id":"s","claude_code_session_url":"https://claude.ai/settings"}""",
            """{"claude_code_session_id":"s","claude_code_session_url":"https://x@claude.ai/code/s"}""",
        )) {
            assertEquals(DiagCode.PROVIDER_REPLY_INVALID, assertIs<Outcome.Failure>(fire(FakeTransport { FakeReply(200, bad) })).errors.single().code, bad)
        }
        val extra = """{"type":"routine_fire","new_field":1,"claude_code_session_id":"s","claude_code_session_url":"https://claude.ai/code/s"}"""
        assertIs<Outcome.Success<*>>(fire(FakeTransport { FakeReply(200, extra) }), "новые поля ответа не мешают")
    }

    @Test
    fun brokenReplyMayHaveFired() {
        val t = FakeTransport { FakeReply(200, ok) { _, _ -> throw IOException("обрыв") } }
        val e = assertIs<Outcome.Failure>(fire(t)).errors.single()
        assertEquals(DiagCode.NETWORK_UNREACHABLE, e.code)
        assertTrue("мог состояться" in e.message)
        assertEquals(1, t.calls.size, "повтора нет: у /fire нет идемпотентности")
    }
}
