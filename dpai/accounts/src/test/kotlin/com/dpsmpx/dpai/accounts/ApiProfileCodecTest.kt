package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** API-профиль (D-98): поля проверены при создании, документ `dpai.api-profiles` — строгий и без ключей. */
class ApiProfileCodecTest {
    private val id = IdKinds.ACCOUNT.parse("acc_01J9ZQ3K8M7N6P5R4S3T2V1W0X").valueOrNull()!!
    private val id2 = IdKinds.ACCOUNT.parse("acc_01J9ZQ3K8M7N6P5R4S3T2V1W0Y").valueOrNull()!!

    private fun form(kind: ProviderKind, url: String = "", model: String = "m", max: Int? = null, custom: Boolean = false, name: String = "Профиль") =
        ApiProfiles.form(id, name, kind, url, model, max, custom)

    @Test
    fun fixedAddressesAndCustomFlag() {
        val a = form(ProviderKind.ANTHROPIC, "https://evil.example", custom = true).valueOrNull()!!
        assertEquals("https://api.anthropic.com/v1", a.baseUrl)
        assertFalse(a.customHostAllowed, "у встроенного провайдера своего адреса нет")
        assertEquals("https://generativelanguage.googleapis.com/v1beta", form(ProviderKind.GEMINI).valueOrNull()!!.baseUrl)
        val o = form(ProviderKind.OPENAI_COMPATIBLE, " https://llm.home.example/v1/ ", custom = true).valueOrNull()!!
        assertEquals("https://llm.home.example/v1", o.baseUrl)
        assertEquals("llm.home.example", o.host)
        assertEquals("::1", form(ProviderKind.OPENAI_COMPATIBLE, "http://[::1]:8080/v1", custom = true).valueOrNull()!!.host)
    }

    @Test
    fun invalidFields() {
        for (bad in listOf(
            form(ProviderKind.OPENAI_COMPATIBLE, "ftp://x/v1"),
            form(ProviderKind.OPENAI_COMPATIBLE, "https://user:pw@x/v1"),
            form(ProviderKind.OPENAI_COMPATIBLE, "https://x/v1?key=1"),
            form(ProviderKind.OPENAI_COMPATIBLE, "https:///v1"),
            form(ProviderKind.OPENAI_COMPATIBLE, ""),
            form(ProviderKind.ANTHROPIC, name = " "),
            form(ProviderKind.ANTHROPIC, model = "bad model"),
            form(ProviderKind.ANTHROPIC, max = 0),
        )) {
            assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(bad).errors.single().code)
        }
        assertIs<Outcome.Success<*>>(form(ProviderKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", model = "openai/gpt-x:free"))
    }

    @Test
    fun roundTripWithoutKeys() {
        val ps = listOf(
            form(ProviderKind.ANTHROPIC, model = "claude-opus-5-5", max = 64000).valueOrNull()!!,
            ApiProfiles.form(id2, "Дом", ProviderKind.OPENAI_COMPATIBLE, "http://192.168.1.5:11434/v1", "llama3:8b", null, true).valueOrNull()!!,
        )
        val json = ApiProfileBook.toJson(ps)
        assertEquals(ps, ApiProfileBook.fromJson(json).valueOrNull())
        assertFalse("key" in json.toString().lowercase().replace("customhost", ""), "в документе профиля нет ключа")
        assertNull(ApiProfileBook.fromJson(json).valueOrNull()!!.last().maxOutputTokens)
    }

    @Test
    fun strictReading() {
        val good = ApiProfileBook.toJson(listOf(form(ProviderKind.ANTHROPIC).valueOrNull()!!)).toString()
        for (bad in listOf(
            good.replace("\"name\":", "\"apiKey\":\"x\",\"name\":"),
            good.replace("\"anthropic\"", "\"bedrock\""),
            good.replace("https://api.anthropic.com/v1", "https://evil.example/v1"),
            good.replace("acc_", "tgt_"),
            good.replace("\"version\":${ApiProfileBook.VERSION}", "\"version\":${ApiProfileBook.VERSION + 1}"),
            good.replace("\"profiles\":[", "\"profiles\":[1,"),
        )) {
            assertIs<Outcome.Failure>(ApiProfileBook.fromJson(JsonParser().parse(bad).valueOrNull()!!), bad)
        }
        val twice = good.replace(Regex("\"profiles\":\\[(.*)]"), "\"profiles\":[$1,$1]")
        assertIs<Outcome.Failure>(ApiProfileBook.fromJson(JsonParser().parse(twice).valueOrNull()!!), "повтор id")
    }
}
