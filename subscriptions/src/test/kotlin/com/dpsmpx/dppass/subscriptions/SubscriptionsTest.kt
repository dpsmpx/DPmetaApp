package com.dpsmpx.dppass.subscriptions

import com.dpsmpx.dppass.config.ProtocolSpec
import com.dpsmpx.dppass.config.SecuritySpec
import com.dpsmpx.dppass.config.TransportSpec
import com.dpsmpx.dppass.config.VlessFlow
import com.dpsmpx.dppass.config.XHttpMode
import com.dpsmpx.dppass.core.ManualClock
import com.dpsmpx.dppass.core.SubscriptionId
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.diagnostics.Secret
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
private const val UUID1 = "00000000-0000-4000-8000-000000000011"

class ShareLinkParserTest {
    @Test
    fun realityVisionLink() {
        val link = "vless://$UUID1@203.0.113.11:443?type=tcp&security=reality&sni=www.example.com&pbk=$KEY&sid=01234567&fp=firefox&flow=xtls-rprx-vision&encryption=none#Reality%20NL"
        val p = ShareLinkParser.parse(link, "s").valueOrNull()!!
        assertEquals("Reality NL", p.name)
        assertEquals(VlessFlow.VISION, (p.protocol as ProtocolSpec.Vless).flow)
        val sec = p.security as SecuritySpec.Reality
        assertEquals("www.example.com", sec.serverName)
        assertEquals(KEY, sec.publicKey)
        assertTrue(p.id.value.startsWith("s."))
    }

    @Test
    fun idIsStableAndIgnoresName() {
        val a = ShareLinkParser.parse("vless://$UUID1@203.0.113.11:443?security=tls&sni=a.example.com#One", "s").valueOrNull()!!
        val b = ShareLinkParser.parse("vless://$UUID1@203.0.113.11:443?security=tls&sni=a.example.com#Two", "s").valueOrNull()!!
        assertEquals(a.id, b.id)
    }

    @Test
    fun xhttpAndTrojan() {
        val x = ShareLinkParser.parse("vless://$UUID1@cdn.example.net:443?type=xhttp&security=tls&path=%2Fx&mode=stream-one", "s").valueOrNull()!!
        assertEquals(TransportSpec.XHttp("/x", null, XHttpMode.STREAM_ONE), x.transport)
        val t = ShareLinkParser.parse("trojan://pass@198.51.100.1:443?sni=t.example.org", "s").valueOrNull()!!
        assertIs<SecuritySpec.Tls>(t.security)
        assertEquals(Secret("pass"), (t.protocol as ProtocolSpec.Trojan).password)
    }

    @Test
    fun unknownParameterMakesLinkUnusableExplicitly() {
        val r = ShareLinkParser.parse("vless://$UUID1@203.0.113.1:443?security=tls&xyz=1", "s")
        val e = r.errors.single()
        assertEquals(DiagCode.SHARE_LINK_INVALID, e.code)
        assertEquals(DiagCode.SHARE_LINK_PARAM_UNSUPPORTED, e.causes.single().code)
        assertTrue("xyz" in e.causes.single().message)
    }

    @Test
    fun insecureIsRejected() {
        val r = ShareLinkParser.parse("vless://$UUID1@203.0.113.1:443?security=tls&allowInsecure=1", "s")
        assertEquals(DiagCode.INSECURE_TLS_REJECTED, r.errors.single().code)
    }

    @Test
    fun malformedLinksNeverThrowAndNeverEchoSecrets() {
        for (bad in listOf("vless://", "vless://@:", "vless://$UUID1@[::1:443", "vless://$UUID1@h:99999", "vless://$UUID1@h:443?a=%zz", "vless://$UUID1@h:443?a=1&a=2", "nonsense")) {
            val r = ShareLinkParser.parse(bad, "s")
            assertIs<Outcome.Failure>(r, bad)
            assertFalse(r.diagnostics.any { UUID1 in it.message }, bad)
        }
    }
}

class MetadataParserTest {
    @Test
    fun safeFieldsApplyControlDirectivesDoNot() {
        val r = MetadataParser.parse(
            mapOf("profile-title" to "base64:0J/RgNC40LzQtdGA", "subscription-userinfo" to "upload=1; download=2; total=3; expire=4", "new-url" to "https://x.example.com/t"),
            mapOf("hide-settings" to "1", "profile-update-interval" to "6"),
        )
        assertEquals("Пример", r.metadata.title)
        assertEquals(TrafficInfo(1, 2, 3, 4), r.metadata.traffic)
        assertEquals(6, r.metadata.updateIntervalHours)
        val ignored = r.notes.single { it.code == DiagCode.SUBSCRIPTION_DIRECTIVE_IGNORED }
        assertTrue("new-url" in ignored.message && "hide-settings" in ignored.message)
        assertFalse("x.example.com" in ignored.message, "значения директив не попадают в сообщение")
    }

    @Test
    fun nonHttpsLinksAreNotAccepted() {
        val r = MetadataParser.parse(mapOf("support-url" to "http://support.example.com", "profile-web-page-url" to "javascript:alert(1)"), emptyMap())
        assertNull(r.metadata.supportUrl)
        assertNull(r.metadata.webPageUrl)
        assertEquals(2, r.notes.count { it.code == DiagCode.SUBSCRIPTION_METADATA_INVALID })
    }
}

class SubscriptionUpdaterTest {
    private val clock = ManualClock(1000)
    private val links = "vless://$UUID1@203.0.113.11:443?security=tls&sni=a.example.com#A\ntrojan://p@198.51.100.2:443#B\n"
    private val passAll = ProfileFilter { FilterResult(it, emptyList()) }

    private fun sub() = Subscription(SubscriptionId("sub1"), "Sub", SubscriptionSource.Url(Secret("https://sub.example.com/token123")))

    private fun fetcher(body: String) = SubscriptionFetcher { _, _ -> Outcome.Success(FetchedPayload(body.toByteArray(), emptyMap(), clock.nowMillis())) }

    @Test
    fun readyThenUnchanged() = runBlocking {
        val r = SubscriptionUpdater(fetcher(links), clock).update(sub(), passAll)
        val ready = assertIs<UpdateResult.Ready>(r)
        assertEquals(2, ready.candidate.profiles.size)
        val updated = ready.candidate.subscription.recordSuccess(ready.candidate.revision, ready.candidate.metadata)
        assertIs<UpdateResult.Unchanged>(SubscriptionUpdater(fetcher(links), clock).update(updated, passAll))
    }

    @Test
    fun failureKeepsLastSuccessfulRevision() = runBlocking {
        val ready = SubscriptionUpdater(fetcher(links), clock).update(sub(), passAll) as UpdateResult.Ready
        val good = ready.candidate.subscription.recordSuccess(ready.candidate.revision, ready.candidate.metadata)
        val r = SubscriptionUpdater(fetcher("<html>oops</html>"), clock).update(good, passAll)
        val failed = assertIs<UpdateResult.Failed>(r)
        assertEquals(good.lastSuccessful, failed.subscription.lastSuccessful)
        assertEquals(DiagCode.SUBSCRIPTION_FORMAT_UNKNOWN, failed.subscription.lastFailed!!.reason.code)
    }

    @Test
    fun filterExclusionsAreExplicit() = runBlocking {
        val onlyTrojan = ProfileFilter { ps ->
            FilterResult(ps.filter { it.protocol is ProtocolSpec.Trojan }, listOf(com.dpsmpx.dppass.diagnostics.Diagnostic.warning(DiagCode.PROFILE_EXCLUDED_UNSUPPORTED, "x")))
        }
        val r = SubscriptionUpdater(fetcher(links), clock).update(sub(), onlyTrojan) as UpdateResult.Ready
        assertEquals(1, r.candidate.profiles.size)
        assertTrue(r.diagnostics.any { it.code == DiagCode.PROFILE_EXCLUDED_UNSUPPORTED })
    }

    @Test
    fun fetchFailureKeepsCause() = runBlocking {
        val f = SubscriptionFetcher { _, _ -> Outcome.failure(DiagCode.ENDPOINT_UNREACHABLE, "timeout") }
        val r = SubscriptionUpdater(f, clock).update(sub(), passAll) as UpdateResult.Failed
        val reason = r.subscription.lastFailed!!.reason
        assertEquals(DiagCode.SUBSCRIPTION_FETCH_FAILED, reason.code)
        assertEquals(DiagCode.ENDPOINT_UNREACHABLE, reason.causes.single().code)
    }

    @Test
    fun oversizedPayloadIsRejected() = runBlocking {
        val s = sub().copy(fetchPolicy = FetchPolicy(maxBytes = 10))
        val r = SubscriptionUpdater(fetcher(links), clock).update(s, passAll) as UpdateResult.Failed
        assertEquals(DiagCode.SUBSCRIPTION_TOO_LARGE, r.subscription.lastFailed!!.reason.code)
    }
}
