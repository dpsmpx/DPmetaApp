package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Маршрут Claude Code (D-116): адрес — только свой `/fire` на api.anthropic.com, документ `dpai.routines` — строгий и без токенов. */
class RoutineCodecTest {
    private val id = IdKinds.ACCOUNT.parse("acc_01J9ZQ3K8M7N6P5R4S3T2V1W0X").valueOrNull()!!
    private val id2 = IdKinds.ACCOUNT.parse("acc_01J9ZQ3K8M7N6P5R4S3T2V1W0Y").valueOrNull()!!
    private val sub = IdKinds.SUBSCRIPTION.parse("sub_01J9ZQ3K8M7N6P5R4S3T2V1W0Z").valueOrNull()!!
    private val trig = "trig_01ABCDEFGHJKMNPQRSTVWXYZ"

    @Test
    fun triggerFromUrlOrId() {
        assertEquals(trig, Routines.trigger(trig))
        assertEquals(trig, Routines.trigger("  https://api.anthropic.com/v1/claude_code/routines/$trig/fire "))
        assertEquals(trig, Routines.trigger("HTTPS://API.anthropic.com/v1/claude_code/routines/$trig/fire"))
        for (bad in listOf(
            "",
            "trig_",
            "trig_01AB/../x",
            "http://api.anthropic.com/v1/claude_code/routines/$trig/fire",
            "https://api.anthropic.com.evil.example/v1/claude_code/routines/$trig/fire",
            "https://evil.example/v1/claude_code/routines/$trig/fire",
            "https://user@api.anthropic.com/v1/claude_code/routines/$trig/fire",
            "https://api.anthropic.com:8443/v1/claude_code/routines/$trig/fire",
            "https://api.anthropic.com/v1/claude_code/routines/$trig/fire?x=1",
            "https://api.anthropic.com/v1/claude_code/routines/$trig",
            "https://api.anthropic.com/v1/claude_code/routines/$trig/../other/fire",
            "https://api.anthropic.com/v1/messages",
        )) {
            assertNull(Routines.trigger(bad), bad)
        }
    }

    @Test
    fun formChecksFields() {
        val r = Routines.form(id, " Работа DPmetaApp ", "https://api.anthropic.com/v1/claude_code/routines/$trig/fire", sub, "DPmetaApp", "continue-branch").valueOrNull()!!
        assertEquals("Работа DPmetaApp", r.name)
        assertEquals("https://api.anthropic.com/v1/claude_code/routines/$trig/fire", r.fireUrl)
        assertNull(Routines.form(id, "x", trig, null, " ", "").valueOrNull()!!.project, "пустое поле — не указано")
        for (bad in listOf(
            Routines.form(id, "", trig, null, null, null),
            Routines.form(id, "a\u0007b", trig, null, null, null),
            Routines.form(id, "x".repeat(Routines.MAX_NAME + 1), trig, null, null, null),
            Routines.form(id, "x", "https://evil.example/fire", null, null, null),
            Routines.form(id, "x", trig, null, "../etc", null),
            Routines.form(id, "x", trig, null, null, "a b"),
        )) {
            assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(bad).errors.single().code)
        }
    }

    @Test
    fun roundTripWithoutTokens() {
        val a = Routines.form(id, "Работа", trig, sub, "DPmetaApp", "continue-branch").valueOrNull()!!
        val b = Routines.form(id2, "Мета", "trig_02XYZ", null, null, null).valueOrNull()!!
        val json = RoutineBook.toJson(listOf(a, b))
        val text = CanonicalJson.write(json)
        assertFalse("token" in text || "Bearer" in text, "токенов в документе нет")
        assertEquals(listOf(a, b), RoutineBook.fromJson(JsonParser().parse(text).valueOrNull()!!).valueOrNull())
    }

    @Test
    fun strictReading() {
        fun read(routines: String) = RoutineBook.fromJson(JsonParser().parse("""{"format":"dpai.routines","version":1,"routines":[$routines]}""").valueOrNull()!!)
        val ok = """{"id":"${id.value}","name":"Р","trigger":"$trig"}"""
        assertEquals(1, read(ok).valueOrNull()!!.size)
        for (bad in listOf(
            """{"id":"${id.value}","name":"Р","trigger":"$trig","token":"x"}""",
            """{"id":"${id.value}","name":"Р","trigger":"https://api.anthropic.com/v1/claude_code/routines/$trig/fire"}""",
            """{"id":"${id.value}","name":"Р","trigger":"$trig","subscription":"acc_01J9ZQ3K8M7N6P5R4S3T2V1W0X"}""",
            """{"id":"${sub.value}","name":"Р","trigger":"$trig"}""",
            """{"id":"${id.value}","name":"Р"}""",
            "$ok,$ok",
            "1",
        )) {
            assertIs<Outcome.Failure>(read(bad), bad)
        }
        assertIs<Outcome.Failure>(RoutineBook.fromJson(JsonParser().parse("""{"format":"dpai.routines","version":2,"routines":[]}""").valueOrNull()!!))
        assertIs<Outcome.Failure>(RoutineBook.fromJson(JsonParser().parse("""{"format":"dpai.api-profiles","version":1,"routines":[]}""").valueOrNull()!!))
    }
}
