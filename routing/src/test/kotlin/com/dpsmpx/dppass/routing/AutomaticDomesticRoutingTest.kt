package com.dpsmpx.dppass.routing

import com.dpsmpx.dppass.core.Cidr
import com.dpsmpx.dppass.core.IpAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class AutomaticDomesticRoutingTest {
    @Test
    fun explicitUserRuleWinsOverAutomaticDomesticSplit() {
        val policy = RoutingPolicy(
            rules = listOf(
                Rule(
                    RuleId("force-proxy"),
                    Conditions(domains = listOf(DomainMatcher.Full("example.ru"))),
                    RouteAction.PROXY,
                ),
            ),
            defaultAction = RouteAction.PROXY,
            automaticDomestic = true,
        )
        val geo = TableGeoResolver(
            sites = mapOf("ru" to listOf(DomainMatcher.Suffix("ru"))),
            ips = mapOf("ru" to listOf(Cidr.parse("95.0.0.0/8")!!.cidr)),
        )
        val domain = RouteEvaluator(policy, geo).decide(RouteQuery(domain = "example.ru"))
        val ip = RouteEvaluator(policy, geo).decide(RouteQuery(ip = IpAddress.parse("95.2.3.4")))
        val foreign = RouteEvaluator(policy, geo).decide(RouteQuery(domain = "example.com"))

        assertEquals(RouteAction.PROXY, domain.action)
        assertEquals(RouteAction.DIRECT, ip.action)
        assertEquals(RouteAction.PROXY, foreign.action)
    }
}
