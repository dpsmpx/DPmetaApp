package com.dpsmpx.dppass.connection

import com.dpsmpx.dppass.core.ManualClock
import com.dpsmpx.dppass.core.SubscriptionId
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.diagnostics.Secret
import com.dpsmpx.dppass.engine.mock.MockBackendCompiler
import com.dpsmpx.dppass.engine.mock.MockProxyEngine
import com.dpsmpx.dppass.routing.DnsTransport
import com.dpsmpx.dppass.storage.EncryptingBlobStore
import com.dpsmpx.dppass.storage.InMemoryBlobStore
import com.dpsmpx.dppass.storage.KeyProvider
import com.dpsmpx.dppass.storage.Quarantine
import com.dpsmpx.dppass.subscriptions.DeviceId
import com.dpsmpx.dppass.subscriptions.FetchedPayload
import com.dpsmpx.dppass.subscriptions.Subscription
import com.dpsmpx.dppass.subscriptions.SubscriptionFetcher
import com.dpsmpx.dppass.subscriptions.SubscriptionRequest
import com.dpsmpx.dppass.subscriptions.SubscriptionSource
import com.dpsmpx.dppass.subscriptions.SubscriptionsState
import com.dpsmpx.dppass.subscriptions.TrafficInfo
import com.dpsmpx.dppass.tunnel.PlatformTraits
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val URL = "https://sub.example.com/api/sub/token-0123456789"
private const val LINKS = "vless://00000000-0000-4000-8000-000000000031@203.0.113.31:443?security=tls&sni=a.example.com#A\n" +
    "trojan://test-password-1@198.51.100.32:443?sni=b.example.com#B\n"

class SubscriptionBookTest {
    private val clock = ManualClock(1_000)

    @Test
    fun roundTripKeepsEverything() {
        val blobs = InMemoryBlobStore()
        val book = SubscriptionBook(blobs, Quarantine(blobs, clock))
        val sub = Subscription(SubscriptionId("s1"), "Поставщик", SubscriptionSource.Url(Secret(URL)), deviceId = DeviceId.generate())
            .copy(metadata = com.dpsmpx.dppass.subscriptions.SubscriptionMetadata(title = "T", traffic = TrafficInfo(1, 2, null, 3)))
        val state = SubscriptionsState(listOf(sub), SubscriptionId("s1"))
        assertTrue(book.save(state).isSuccess)
        assertEquals(state, book.load().valueOrNull())
    }

    @Test
    fun corruptedStateGoesToQuarantineNotOverwritten() {
        val blobs = InMemoryBlobStore()
        blobs.write(SubscriptionBook.KEY, "{\"format\": \"dppass.subscriptions\", \"format_version\": 1, \"items\": [{\"id\": \"BAD ID\"}]}".toByteArray())
        val r = SubscriptionBook(blobs, Quarantine(blobs, clock)).load()
        assertEquals(SubscriptionsState.EMPTY, r.valueOrNull())
        assertTrue(r.diagnostics.any { it.code == DiagCode.STORAGE_CORRUPTED })
        assertTrue(Quarantine(blobs, clock).list().valueOrNull()!!.isNotEmpty(), "исходник не сохранён в карантин")
    }

    @Test
    fun secretsAreNotInPlainTextOnEncryptedStore() {
        val raw = InMemoryBlobStore()
        val store = EncryptingBlobStore(raw, KeyProvider { Outcome.Success(ByteArray(32) { 9 }) })
        val id = DeviceId.generate()
        val sub = Subscription(SubscriptionId("s1"), "S", SubscriptionSource.Url(Secret(URL)), deviceId = id)
        SubscriptionBook(store, Quarantine(store, clock)).save(SubscriptionsState(listOf(sub), null))
        val bytes = String(raw.read(SubscriptionBook.KEY).valueOrNull()!!, Charsets.ISO_8859_1)
        assertFalse("token-0123456789" in bytes || id.reveal() in bytes, "секрет подписки на диске открытым текстом")
    }
}

class SubscriptionFlowTest {
    private val clock = ManualClock(1_000_000)
    private val blobs = InMemoryBlobStore()

    private fun core() = DPpassCore(MockBackendCompiler(), MockProxyEngine(), blobs, clock, "com.dpsmpx.dppass", PlatformTraits(true))

    private class Fetcher(var body: String, var headers: Map<String, String> = emptyMap()) : SubscriptionFetcher {
        val requests = mutableListOf<SubscriptionRequest>()
        override suspend fun fetch(request: SubscriptionRequest, policy: com.dpsmpx.dppass.subscriptions.FetchPolicy) =
            Outcome.Success(FetchedPayload(body.toByteArray(), headers, 0)).also { requests += request }
    }

    private fun added(core: DPpassCore): SubscriptionId = core.subscriptions.add(URL, null).valueOrNull()!!.id

    @Test
    fun refreshAppliesServersWithBasePolicyWhenNoUserConfig() = runBlocking {
        val core = core()
        val id = added(core)
        val run = core.subscriptions.refresh(id, Fetcher(Base64.getEncoder().encodeToString(LINKS.toByteArray())), "test").valueOrNull()!!
        assertTrue(run.applied, run.diagnostics.joinToString { it.render() })
        val active = core.activation.state.activeConfig!!
        assertEquals(2, active.profiles.size)
        assertEquals(BasePolicy.ir.dns, active.dns)
        assertEquals(DnsTransport.Https(BasePolicy.DOH_URL), active.dns.servers.single().transport)
        val st = core.subscriptions.state().valueOrNull()!!
        assertEquals(id, st.activeId)
        assertTrue(st.find(id)!!.lastSuccessful != null, "нет удачной ревизии")
    }

    @Test
    fun userRoutingStaysWhenUserConfigIsActive() = runBlocking {
        val core = core()
        assertIs<ActivationResult.Activated>(core.importAndActivate(java.io.File(System.getProperty("dppass.repoRoot"), "tests/data/configs/valid-minimal.json").readText()).valueOrNull())
        val userRouting = core.activation.state.activeConfig!!.routing
        val run = core.subscriptions.refresh(added(core), Fetcher(LINKS), "test").valueOrNull()!!
        assertTrue(run.applied)
        assertEquals(userRouting, core.activation.state.activeConfig!!.routing)
    }

    @Test
    fun providerRefusalKeepsLastSuccessfulAndActiveConfig() = runBlocking {
        val core = core()
        val id = added(core)
        val fetcher = Fetcher(LINKS)
        core.subscriptions.refresh(id, fetcher, "test")
        val good = core.subscriptions.state().valueOrNull()!!.find(id)!!.lastSuccessful
        val activeBefore = core.activation.state.active
        fetcher.body = Base64.getEncoder().encodeToString("vless://00000000-0000-0000-0000-000000000000@0.0.0.0:1?security=none#App%20not%20supported".toByteArray())
        fetcher.headers = mapOf("x-hwid-active" to "true", "x-hwid-not-supported" to "true")
        val run = core.subscriptions.refresh(id, fetcher, "test").valueOrNull()!!
        assertFalse(run.applied)
        assertEquals(DiagCode.SUBSCRIPTION_DEVICE_ID_REQUIRED, run.subscription.lastFailed!!.reason.code)
        assertEquals(good, core.subscriptions.state().valueOrNull()!!.find(id)!!.lastSuccessful)
        assertEquals(activeBefore, core.activation.state.active)
    }

    @Test
    fun deviceIdIsSentOnlyWhenEnabledAndSurvivesRestart() = runBlocking {
        val core = core()
        val id = added(core)
        val fetcher = Fetcher(LINKS)
        core.subscriptions.refresh(id, fetcher, "1.0")
        assertNull(fetcher.requests.last().deviceId)
        assertEquals("DPpass/1.0", fetcher.requests.last().userAgent)

        core.subscriptions.setDeviceId(id, true)
        val first = core.subscriptions.state().valueOrNull()!!.find(id)!!.deviceId!!
        core.subscriptions.refresh(id, fetcher, "1.0")
        assertEquals(first, fetcher.requests.last().deviceId)

        // Новый процесс — тот же идентификатор из хранилища.
        assertEquals(first, core().subscriptions.state().valueOrNull()!!.find(id)!!.deviceId)

        core.subscriptions.resetDeviceId(id)
        assertNotEquals(first, core.subscriptions.state().valueOrNull()!!.find(id)!!.deviceId)
        core.subscriptions.setDeviceId(id, false)
        core.subscriptions.refresh(id, fetcher, "1.0")
        assertNull(fetcher.requests.last().deviceId)
    }

    @Test
    fun onlyHttpsAddressesAreAccepted() {
        val core = core()
        for (bad in listOf("http://sub.example.com/x", "ftp://sub.example.com", "https://sub.example.com/ x", "")) {
            assertIs<Outcome.Failure>(core.subscriptions.add(bad, null), bad)
        }
        assertEquals("sub.example.com", core.subscriptions.add(URL, null).valueOrNull()!!.name)
    }
}
