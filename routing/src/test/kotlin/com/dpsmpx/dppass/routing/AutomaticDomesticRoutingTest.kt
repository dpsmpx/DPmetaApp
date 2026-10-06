package com.dpsmpx.dppass.routing

import com.dpsmpx.dppass.core.Cidr
import com.dpsmpx.dppass.core.IpAddress
import com.dpsmpx.dppass.core.RuleId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Автоматический раздельный маршрут (D-45): отечественное назначение
 * идёт напрямую само, но ПОСЛЕ явных правил пользователя — смысл документа
 * не меняется (D-05).
 */
class AutomaticDomesticRoutingTest {
    private val geo = TableGeoResolver(
        sites = mapOf("category-ru" to listOf(DomainMatcher.Suffix("ru"))),
        ips = mapOf("ru" to listOf(Cidr.parse("95.0.0.0/8")!!.cidr)),
    )

    @Test
    fun domesticDestinationGoesDirectWhenEnabled() {
        val policy = RoutingPolicy(defaultAction = RouteAction.PROXY, automaticDomestic = true)
        val eval = RouteEvaluator(policy, geo)
        assertEquals(RouteAction.DIRECT, eval.decide(RouteQuery(domain = "example.ru")).action, "отечественный сайт — напрямую")
        assertEquals(RouteAction.DIRECT, eval.decide(RouteQuery(ip = IpAddress.parse("95.2.3.4"))).action, "отечественный адрес — напрямую")
        assertEquals(RouteAction.PROXY, eval.decide(RouteQuery(domain = "example.com")).action, "зарубежное — по умолчанию в туннель")
    }

    @Test
    fun disabledByDefaultSoDocumentsMeanWhatTheySay() {
        val policy = RoutingPolicy(defaultAction = RouteAction.PROXY) // automaticDomestic = false
        assertEquals(RouteAction.PROXY, RouteEvaluator(policy, geo).decide(RouteQuery(domain = "example.ru")).action)
    }

    @Test
    fun explicitUserRuleWinsOverAutomaticDomesticSplit() {
        // Пользователь явно велит example.ru — в туннель; автосплит не должен перекрыть.
        val policy = RoutingPolicy(
            rules = listOf(Rule(RuleId("force-proxy"), Conditions(domains = listOf(DomainMatcher.Full("example.ru"))), RouteAction.PROXY)),
            defaultAction = RouteAction.PROXY,
            automaticDomestic = true,
        )
        val eval = RouteEvaluator(policy, geo)
        assertEquals(RouteAction.PROXY, eval.decide(RouteQuery(domain = "example.ru")).action, "явное правило выше автосплита")
        // Другой отечественный адрес, которого явное правило не касается, — напрямую.
        assertEquals(RouteAction.DIRECT, eval.decide(RouteQuery(ip = IpAddress.parse("95.2.3.4"))).action)
    }
}
