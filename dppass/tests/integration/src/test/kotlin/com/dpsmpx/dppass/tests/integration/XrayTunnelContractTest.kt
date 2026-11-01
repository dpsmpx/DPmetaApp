package com.dpsmpx.dppass.tests.integration

import com.dpsmpx.dppass.config.ConfigLoader
import com.dpsmpx.dppass.core.Json
import com.dpsmpx.dppass.core.JsonArray
import com.dpsmpx.dppass.core.JsonNumber
import com.dpsmpx.dppass.core.JsonObject
import com.dpsmpx.dppass.core.JsonString
import com.dpsmpx.dppass.engine.CompilePlan
import com.dpsmpx.dppass.engine.xray.XrayBackend
import com.dpsmpx.dppass.engine.xray.XrayCompiler
import com.dpsmpx.dppass.routing.AppPolicy
import com.dpsmpx.dppass.tunnel.PlatformTraits
import com.dpsmpx.dppass.tunnel.TunnelSpecBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Договор TUN между `tunnel/` и компилятором Xray: `engine` от `tunnel`
 * не зависит (`docs/ARCHITECTURE.md`, §2), поэтому MTU и DNS-адрес TUN
 * записаны в обоих местах, а совпадение проверяется здесь. Разойдись они —
 * приложения спрашивали бы DNS у адреса, который ядро не перехватывает,
 * и запросы ушли бы правилами пользователя, а не по DnsPolicy (T4).
 */
class XrayTunnelContractTest {
    private val ir = ConfigLoader().load(
        File(System.getProperty("dppass.repoRoot"), "tests/data/configs/valid-multi.json").readText(),
    ).valueOrNull()!!.ir

    private val spec = TunnelSpecBuilder.build(ir, "com.dpsmpx.dppass", PlatformTraits(supportsExcludeRoute = true)).valueOrNull()!!

    private val compiled = Json.parse(
        XrayCompiler().compile(ir, CompilePlan(ir.profiles[0].id, withTun = true)).valueOrNull()!!.payloadForEngine(),
    ) as JsonObject

    private val rules = ((compiled["routing"] as JsonObject)["rules"] as JsonArray).items.map { it as JsonObject }

    @Test
    fun constantsMatchTunnelSpec() {
        assertEquals(TunnelSpecBuilder.DEFAULT_MTU, XrayBackend.TUN_MTU)
        assertEquals(TunnelSpecBuilder.TUN_DNS4.toString(), XrayBackend.TUN_DNS4)
    }

    @Test
    fun compiledTunMatchesSpec() {
        val tun = ((compiled["inbounds"] as JsonArray).items.single() as JsonObject)
        assertEquals(spec.mtu, ((tun["settings"] as JsonObject)["mtu"] as JsonNumber).toIntOrNull())
        // Приложения в туннеле спрашивают DNS у адреса из спецификации — его и перехватывает ядро.
        val hijack = rules.first()
        assertEquals(spec.dnsServers.map { it.toString() }, (hijack["ip"] as JsonArray).items.map { (it as JsonString).value })
        assertEquals(listOf(XrayBackend.TAG_TUN_IN), (hijack["inboundTag"] as JsonArray).items.map { (it as JsonString).value })
    }

    @Test
    fun appRulesLoweredToVpnServiceAreAbsentFromEngine() {
        // «Приложения → direct» стоит первым: оно — исключение из туннеля, в ядре его нет.
        val apps = assertIs<AppPolicy.ExceptApps>(spec.apps)
        assertEquals(setOf("com.example.bank", "com.example.gosapp", "com.dpsmpx.dppass"), apps.packages)
        val tags = rules.map { (it["ruleTag"] as JsonString).value }
        assertFalse("bank-app-direct" in tags)
        assertEquals(listOf("ads-block", "ru-direct", "ru-ip-direct", "quic-block"), tags.filterNot { it.startsWith(XrayBackend.SYSTEM_PREFIX) })
    }
}
