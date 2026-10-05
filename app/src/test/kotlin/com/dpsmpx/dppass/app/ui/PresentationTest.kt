package com.dpsmpx.dppass.app.ui

import com.dpsmpx.dppass.config.ProtocolSpec
import com.dpsmpx.dppass.config.SecuritySpec
import com.dpsmpx.dppass.config.ServerProfile
import com.dpsmpx.dppass.config.ShadowsocksMethod
import com.dpsmpx.dppass.config.TlsFingerprint
import com.dpsmpx.dppass.config.TransportSpec
import com.dpsmpx.dppass.config.XHttpMode
import com.dpsmpx.dppass.connection.CandidateHealth
import com.dpsmpx.dppass.connection.HealthState
import com.dpsmpx.dppass.core.Endpoint
import com.dpsmpx.dppass.core.Host
import com.dpsmpx.dppass.core.ProfileId
import com.dpsmpx.dppass.core.SubscriptionId
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.FailureKind
import com.dpsmpx.dppass.diagnostics.Secret
import com.dpsmpx.dppass.subscriptions.FailedRevision
import com.dpsmpx.dppass.subscriptions.Subscription
import com.dpsmpx.dppass.subscriptions.SubscriptionMetadata
import com.dpsmpx.dppass.subscriptions.SubscriptionRevision
import com.dpsmpx.dppass.subscriptions.SubscriptionSource
import com.dpsmpx.dppass.subscriptions.SubscriptionsState
import com.dpsmpx.dppass.subscriptions.TrafficInfo
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Логика экранов (D-34) — без Android. */
class PresentationTest {
    private val endpoint = Endpoint(Host.Domain("vpn.example.net"), 443)
    private val tls = SecuritySpec.Tls("vpn.example.net", emptyList(), TlsFingerprint.CHROME)

    private fun profile(id: String, protocol: ProtocolSpec, transport: TransportSpec, security: SecuritySpec) =
        ServerProfile(ProfileId(id), "Сервер $id", endpoint, protocol, transport, security)

    private val reality = profile(
        "a",
        ProtocolSpec.Vless(Secret("00000000-0000-4000-8000-000000000001")),
        TransportSpec.Tcp,
        SecuritySpec.Reality("www.example.com", "pk", Secret("ab"), TlsFingerprint.CHROME, null),
    )
    private val trojanWs = profile("b", ProtocolSpec.Trojan(Secret("p")), TransportSpec.WebSocket("/ws", null), tls)
    private val hy2 = profile("c", ProtocolSpec.Hysteria2(Secret("p")), TransportSpec.Native, tls)
    private val ss = profile("d", ProtocolSpec.Shadowsocks(ShadowsocksMethod.BLAKE3_AES_128_GCM, Secret("k")), TransportSpec.Native, SecuritySpec.None)
    private val xhttp = profile("e", ProtocolSpec.Vless(Secret("00000000-0000-4000-8000-000000000002")), TransportSpec.XHttp("/x", null, XHttpMode.AUTO), tls)

    @Test
    fun summaryNamesProtocolSecurityAndTransport() {
        assertEquals("VLESS · REALITY · TCP", ServerList.summary(reality))
        assertEquals("Trojan · TLS · WebSocket", ServerList.summary(trojanWs))
        assertEquals("VLESS · TLS · XHTTP", ServerList.summary(xhttp))
        // У Hysteria2 TLS — часть протокола, транспорт — QUIC.
        assertEquals("Hysteria2 · QUIC", ServerList.summary(hy2))
        assertEquals("Shadowsocks 2022", ServerList.summary(ss))
    }

    @Test
    fun rowsKeepConfigOrderAndShowHealth() {
        val now = 1_000_000L
        val health = mapOf(
            reality.id to CandidateHealth(HealthState.HEALTHY, lastSuccessMillis = now - 5),
            trojanWs.id to CandidateHealth(HealthState.FAILED, 1, DiagCode.TLS_HANDSHAKE_FAILED, backoffUntilMillis = now + 1_500),
            hy2.id to CandidateHealth(HealthState.FAILED, 3, DiagCode.ENDPOINT_UNREACHABLE, backoffUntilMillis = now - 1),
        )
        val rows = ServerList.rows(listOf(reality, trojanWs, hy2, ss), { health[it] ?: CandidateHealth() }, trojanWs.id, reality.id, now)
        assertEquals(listOf("a", "b", "c", "d"), rows.map { it.id.value })
        assertEquals(listOf(RowHealth.HEALTHY, RowHealth.PAUSED, RowHealth.FAILED, RowHealth.UNKNOWN), rows.map { it.health })
        assertEquals(2, rows[1].pauseSeconds, "остаток паузы — вверх до секунды")
        assertEquals(FailureKind.TLS_HANDSHAKE_FAILED, rows[1].lastFailure)
        assertEquals(listOf(false, true, false, false), rows.map { it.selected })
        assertEquals(listOf(true, false, false, false), rows.map { it.connected })
    }

    @Test
    fun selectionOfVanishedServerIsAutomatic() {
        // Подписка обновилась, выбранного сервера в ней больше нет — выбор «автоматически».
        assertNull(ServerList.effectiveSelection(listOf(reality), ProfileId("gone")))
        val rows = ServerList.rows(listOf(reality), { CandidateHealth() }, ProfileId("gone"), null, 0)
        assertFalse(rows.single().selected)
        assertEquals(reality.id, ServerList.effectiveSelection(listOf(reality, hy2), reality.id))
    }

    @Test
    fun configSourceFromActivationRecord() {
        assertEquals(ConfigSource.None, ConfigSource.of(null))
        assertEquals(ConfigSource.Own, ConfigSource.of("user-import"))
        assertEquals(ConfigSource.FromSubscription(SubscriptionId("s2")), ConfigSource.of("subscription:s2#7"))
    }

    @Test
    fun subscriptionRowShowsLastResultTrafficAndExpiry() {
        val ok = SubscriptionRevision(3, "c3", fetchedAtMillis = 5_000, profileCount = 4)
        val fail = FailedRevision(null, 6_000, Diagnostic.error(DiagCode.SUBSCRIPTION_DEVICE_ID_REQUIRED, "нужен идентификатор"))
        val s1 = Subscription(
            SubscriptionId("s1"),
            "provider.example.net",
            SubscriptionSource.Url(Secret("https://provider.example.net/sub/token")),
            metadata = SubscriptionMetadata(
                title = "Мой поставщик",
                traffic = TrafficInfo(uploadBytes = 1024, downloadBytes = 2048, totalBytes = 0, expireEpochSeconds = 7),
                announce = "Профилактика\u0007 в 03:00\n",
            ),
            lastSuccessful = ok,
            lastFailed = fail,
        )
        val s2 = Subscription(SubscriptionId("s2"), "второй", SubscriptionSource.Url(Secret("https://b.example.org/x")), lastSuccessful = ok,
            lastFailed = FailedRevision(null, 4_000, fail.reason))
        val rows = SubscriptionList.rows(SubscriptionsState(listOf(s1, s2), SubscriptionId("s1")), ConfigSource.FromSubscription(SubscriptionId("s1")), 7_000)
        val r = rows[0]
        assertEquals("Мой поставщик", r.name, "название поставщика — вместо имени по адресу")
        assertTrue(r.active)
        assertEquals(4, r.profileCount)
        assertEquals(DiagCode.SUBSCRIPTION_DEVICE_ID_REQUIRED, r.failure?.code, "отказ позже удачной загрузки — показывается")
        assertEquals(3072L, r.usedBytes)
        assertNull(r.totalBytes, "0 у поставщика — без предела")
        assertTrue(r.expired)
        assertEquals("Профилактика в 03:00", r.announce)
        assertFalse(rows[1].active)
        assertNull(rows[1].failure, "отказ раньше удачной загрузки — уже не новость")
    }

    @Test
    fun subscriptionIsNotActiveWhenOwnConfigReplacedIt() {
        val s = Subscription(SubscriptionId("s1"), "a", SubscriptionSource.Url(Secret("https://a.example.net/s")))
        val rows = SubscriptionList.rows(SubscriptionsState(listOf(s), SubscriptionId("s1")), ConfigSource.Own, 0)
        assertFalse(rows.single().active)
    }

    @Test
    fun announceIsLimited() {
        val long = "а".repeat(ANNOUNCE_LIMIT + 50)
        assertEquals(ANNOUNCE_LIMIT, SubscriptionList.plain(long).length)
        assertTrue(SubscriptionList.plain(long).endsWith("…"))
    }

    @Test
    fun bytesInBinaryUnits() {
        val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        val ru = Locale.forLanguageTag("ru")
        assertEquals("512 Б", Formats.bytes(512, units, ru))
        assertEquals("1,5 КБ", Formats.bytes(1536, units, ru))
        assertEquals("1.5 KB", Formats.bytes(1536, listOf("B", "KB", "MB", "GB", "TB"), Locale.ENGLISH))
        assertEquals("50 ГБ", Formats.bytes(50L shl 30, units, ru))
        assertEquals("2048 ТБ", Formats.bytes(2048L shl 40, units, ru))
        assertEquals("0 Б", Formats.bytes(-5, units, ru))
    }
}
