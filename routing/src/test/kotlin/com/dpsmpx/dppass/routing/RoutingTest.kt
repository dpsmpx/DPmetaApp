package com.dpsmpx.dppass.routing

import com.dpsmpx.dppass.core.Cidr
import com.dpsmpx.dppass.core.Endpoint
import com.dpsmpx.dppass.core.Host
import com.dpsmpx.dppass.core.IpAddress
import com.dpsmpx.dppass.core.PortRange
import com.dpsmpx.dppass.core.RuleId
import com.dpsmpx.dppass.diagnostics.DiagCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun rule(id: String, action: RouteAction, c: Conditions) = Rule(RuleId(id), c, action)
private fun cidr(s: String) = Cidr.parse(s)!!.cidr

class RouteEvaluatorTest {
    private val policy = RoutingPolicy(
        rules = listOf(
            rule("ads", RouteAction.BLOCK, Conditions(domains = listOf(DomainMatcher.Suffix("ads.example.com")))),
            rule("ru", RouteAction.DIRECT, Conditions(domains = listOf(DomainMatcher.Suffix("example.ru")))),
            rule("quic", RouteAction.BLOCK, Conditions(protocols = setOf(TransportProtocol.UDP), ports = listOf(PortRange(443, 443)))),
            rule("net", RouteAction.DIRECT, Conditions(ipCidrs = listOf(cidr("198.51.100.0/24")))),
            rule("mobile-video", RouteAction.DIRECT, Conditions(domains = listOf(DomainMatcher.Keyword("video")), networks = setOf(NetworkKind.MOBILE))),
        ),
        defaultAction = RouteAction.PROXY,
        apps = AppPolicy.ExceptApps(setOf("com.example.bank")),
    )
    private val ev = RouteEvaluator(policy)

    @Test
    fun firstMatchWins() {
        assertEquals(RouteAction.BLOCK, ev.decide(RouteQuery(domain = "x.ads.example.com")).action)
        assertEquals(RuleId("ru"), ev.decide(RouteQuery(domain = "mail.example.ru")).ruleId)
    }

    @Test
    fun suffixMatchesOnLabelBoundaryOnly() {
        assertEquals(RouteAction.PROXY, ev.decide(RouteQuery(domain = "notexample.ru")).action)
        assertEquals(RouteAction.DIRECT, ev.decide(RouteQuery(domain = "example.ru")).action)
    }

    @Test
    fun conditionsAreAndedAcrossFields() {
        assertEquals(RouteAction.BLOCK, ev.decide(RouteQuery(domain = "a.example.org", protocol = TransportProtocol.UDP, port = 443)).action)
        assertEquals(RouteAction.PROXY, ev.decide(RouteQuery(domain = "a.example.org", protocol = TransportProtocol.TCP, port = 443)).action)
        assertEquals(RouteAction.PROXY, ev.decide(RouteQuery(domain = "video.example.org", network = NetworkKind.WIFI)).action)
        assertEquals(RouteAction.DIRECT, ev.decide(RouteQuery(domain = "video.example.org", network = NetworkKind.MOBILE)).action)
    }

    @Test
    fun appOutsideTunnelIsDirectBeforeRules() {
        val d = ev.decide(RouteQuery(domain = "x.ads.example.com", packageName = "com.example.bank"))
        assertEquals(RouteAction.DIRECT, d.action)
        assertEquals(null, d.ruleId)
    }

    @Test
    fun ipRulesNeedIpUnlessResolutionAllowed() {
        val q = RouteQuery(domain = "site.example.org", resolvedIp = IpAddress.parse("198.51.100.9"))
        assertEquals(RouteAction.PROXY, ev.decide(q).action)
        val resolving = RouteEvaluator(policy.copy(domainResolution = DomainResolution.IF_NO_DOMAIN_MATCH))
        assertEquals(RouteAction.DIRECT, resolving.decide(q).action)
    }

    @Test
    fun geoUsesResolver() {
        val p = RoutingPolicy(rules = listOf(rule("geo", RouteAction.DIRECT, Conditions(geoIps = listOf("ru")))))
        val geo = TableGeoResolver(ips = mapOf("ru" to listOf(cidr("203.0.113.0/24"))))
        assertEquals(RouteAction.DIRECT, RouteEvaluator(p, geo).decide(RouteQuery(ip = IpAddress.parse("203.0.113.1"))).action)
        assertEquals(RouteAction.PROXY, RouteEvaluator(p).decide(RouteQuery(ip = IpAddress.parse("203.0.113.1"))).action)
    }
}

class RoutingValidatorTest {
    @Test
    fun detectsEmptyDuplicateAndShadowedRules() {
        val p = RoutingPolicy(
            rules = listOf(
                rule("a", RouteAction.DIRECT, Conditions(domains = listOf(DomainMatcher.Suffix("example.ru")))),
                rule("b", RouteAction.PROXY, Conditions(domains = listOf(DomainMatcher.Full("mail.example.ru")))),
                rule("c", RouteAction.DIRECT, Conditions(domains = listOf(DomainMatcher.Suffix("example.ru")))),
                rule("d", RouteAction.BLOCK, Conditions()),
                rule("a", RouteAction.BLOCK, Conditions(ipCidrs = listOf(cidr("10.0.0.0/8")))),
                rule("e", RouteAction.BLOCK, Conditions(ipCidrs = listOf(cidr("10.1.0.0/16")))),
            ),
        )
        val codes = RoutingValidator.validate(p).map { it.code to it.path }
        assertTrue(DiagCode.RULE_SHADOWED to "$.routing.rules[1]" in codes)
        assertTrue(DiagCode.RULE_DUPLICATE to "$.routing.rules[2]" in codes)
        assertTrue(DiagCode.RULE_EMPTY to "$.routing.rules[3]" in codes)
        assertTrue(DiagCode.ID_DUPLICATE to "$.routing.rules[4].id" in codes)
        assertTrue(DiagCode.RULE_SHADOWED to "$.routing.rules[5]" in codes)
    }

    @Test
    fun differentFieldsDoNotShadow() {
        val p = RoutingPolicy(
            rules = listOf(
                rule("a", RouteAction.DIRECT, Conditions(domains = listOf(DomainMatcher.Suffix("example.ru")), ports = listOf(PortRange(443, 443)))),
                rule("b", RouteAction.PROXY, Conditions(domains = listOf(DomainMatcher.Suffix("example.ru")))),
            ),
        )
        assertTrue(RoutingValidator.validate(p).isEmpty())
    }

    @Test
    fun proxyRuleForExcludedAppIsAConflict() {
        val p = RoutingPolicy(
            rules = listOf(rule("x", RouteAction.PROXY, Conditions(apps = listOf("com.example.bank")))),
            apps = AppPolicy.ExceptApps(setOf("com.example.bank")),
        )
        assertEquals(listOf(DiagCode.APP_POLICY_CONFLICT), RoutingValidator.validate(p).map { it.code })
    }
}

class AppLoweringTest {
    @Test
    fun leadingDirectAppRulesMoveToVpnServiceLevel() {
        val p = RoutingPolicy(
            rules = listOf(
                rule("bank", RouteAction.DIRECT, Conditions(apps = listOf("com.example.bank"))),
                rule("ads", RouteAction.BLOCK, Conditions(domains = listOf(DomainMatcher.Suffix("ads.example.com")))),
                rule("late-app", RouteAction.DIRECT, Conditions(apps = listOf("com.example.late"))),
            ),
        )
        val l = AppLowering.lower(p)
        assertEquals(AppPolicy.ExceptApps(setOf("com.example.bank")), l.tunnelApps)
        assertEquals(listOf(RuleId("bank")), l.lowered)
        // после правила с другими условиями перенос изменил бы смысл — правило остаётся ядру
        assertEquals(listOf(RuleId("late-app")), l.engineAppRules)
        assertEquals(2, l.engineRules.size)
    }

    @Test
    fun onlyAppsShrinks() {
        val p = RoutingPolicy(
            rules = listOf(rule("x", RouteAction.DIRECT, Conditions(apps = listOf("com.example.a")))),
            apps = AppPolicy.OnlyApps(setOf("com.example.a", "com.example.b")),
        )
        assertEquals(AppPolicy.OnlyApps(setOf("com.example.b")), AppLowering.lower(p).tunnelApps)
    }

    @Test
    fun proxyAppRuleNeedsEngineSupport() {
        val p = RoutingPolicy(rules = listOf(rule("x", RouteAction.PROXY, Conditions(apps = listOf("com.example.a")))))
        assertEquals(listOf(RuleId("x")), AppLowering.lower(p).engineAppRules)
    }
}

class DnsValidatorTest {
    private fun server(id: String, t: DnsTransport, r: DnsRoute) = DnsServer(id, t, r)

    @Test
    fun leakRiskWhenDefaultGoesDirect() {
        val p = DnsPolicy(listOf(server("local", DnsTransport.System, DnsRoute.DIRECT)), "local")
        assertEquals(listOf(DiagCode.DNS_LEAK_RISK), DnsValidator.validate(p).map { it.code })
        assertTrue(DnsValidator.validate(p.copy(leakProtection = false)).isEmpty())
    }

    @Test
    fun systemResolverCannotGoThroughTunnel() {
        val p = DnsPolicy(listOf(server("s", DnsTransport.System, DnsRoute.TUNNEL)), "s")
        assertTrue(DiagCode.DNS_SERVER_INVALID in DnsValidator.validate(p).map { it.code })
    }

    @Test
    fun referencesAreChecked() {
        val udp = server("u", DnsTransport.Udp(Endpoint(Host.parse("192.0.2.53")!!, 53)), DnsRoute.TUNNEL)
        val p = DnsPolicy(listOf(udp), "missing", rules = listOf(DnsRule(listOf(DomainMatcher.Suffix("example.ru")), emptyList(), "nope")))
        assertEquals(listOf(DiagCode.REFERENCE_UNKNOWN, DiagCode.REFERENCE_UNKNOWN), DnsValidator.validate(p).map { it.code })
    }
}
