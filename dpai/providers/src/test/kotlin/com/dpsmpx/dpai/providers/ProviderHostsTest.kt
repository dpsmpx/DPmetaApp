package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Список адресов провайдеров (D-98; `DPmetaApp:D-10`): чужой адрес — отказ, запрос не уходит. */
class ProviderHostsTest {
    private val key = SecretHeader("x-api-key", "", Secret(sampleKey("k")))

    private fun check(url: String, custom: Set<String> = emptySet(), secret: SecretHeader? = key) =
        ProviderHosts.check(HttpCall("GET", URI(url), secret = secret), custom)

    private fun refused(r: Outcome<*>) = assertEquals(DiagCode.HOST_NOT_ALLOWED, assertIs<Outcome.Failure>(r).errors.single().code)

    @Test
    fun builtInOnlyOverHttps() {
        for (h in ProviderHosts.BUILT_IN) assertIs<Outcome.Success<Unit>>(check("https://$h/v1/models"))
        refused(check("http://api.anthropic.com/v1/models"))
        refused(check("https://evil.example/v1/models"))
        refused(check("https://api.anthropic.com.evil.example/v1"))
        refused(check("https://user:pw@api.openai.com/v1/models"))
    }

    @Test
    fun customHostOnlyWhenAllowed() {
        refused(check("https://llm.home.example/v1/models"))
        assertIs<Outcome.Success<Unit>>(check("https://llm.home.example/v1/models", setOf("llm.home.example")))
        // http — только частная сеть числом; ключ по http — только на это устройство.
        refused(check("http://llm.home.example/v1/models", setOf("llm.home.example"), secret = null))
        assertIs<Outcome.Success<Unit>>(check("http://192.168.1.5:11434/v1/models", setOf("192.168.1.5"), secret = null))
        refused(check("http://192.168.1.5:11434/v1/models", setOf("192.168.1.5")))
        assertIs<Outcome.Success<Unit>>(check("http://127.0.0.1:8080/v1/models", setOf("127.0.0.1")))
        assertIs<Outcome.Success<Unit>>(check("http://[::1]:8080/v1/models", setOf(profile(ProviderKind.OPENAI_COMPATIBLE, "http://[::1]:8080/v1", custom = true).host)))
        refused(check("http://8.8.8.8/v1/models", setOf("8.8.8.8"), secret = null))
    }

    @Test
    fun privateAddresses() {
        for (h in listOf("10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.0.1", "100.64.1.2", "100.127.0.1", "127.0.0.1", "localhost", "::1")) {
            assertTrue(ProviderHosts.isPrivate(h), h)
        }
        for (h in listOf("172.32.0.1", "100.128.0.1", "8.8.8.8", "192.169.0.1", "llm.local", "1.2.3", "256.1.1.1")) {
            assertFalse(ProviderHosts.isPrivate(h), h)
        }
        assertTrue(ProviderHosts.isLoopback("127.1.2.3"))
        assertFalse(ProviderHosts.isLoopback("10.0.0.1"))
    }

    @Test
    fun refusedRequestNeverReachesTransport() {
        val t = FakeTransport { FakeReply(200, """{"data":[]}""") }
        val p = Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "https://llm.home.example/v1", custom = false), null, t)
        refused(p.models())
        assertTrue(t.calls.isEmpty())
        val allowed = Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "https://llm.home.example/v1", custom = true), null, t)
        assertIs<Outcome.Success<*>>(allowed.models())
        assertEquals(1, t.calls.size)
    }
}
