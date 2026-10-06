package com.dpsmpx.dppass.routing

import com.dpsmpx.dppass.core.Cidr
import com.dpsmpx.dppass.core.IpAddress
import com.dpsmpx.dppass.core.RuleId

/**
 * Геоданные для вычислителя. Реальный источник (geosite/geoip ядра)
 * подключается вместе с ядром; в тестах — таблица.
 */
interface GeoResolver {
    fun siteMatches(code: String, domain: String): Boolean
    fun ipMatches(code: String, ip: IpAddress): Boolean

    /** Пустая база: ни одно гео-условие не выполняется. */
    object None : GeoResolver {
        override fun siteMatches(code: String, domain: String): Boolean = false
        override fun ipMatches(code: String, ip: IpAddress): Boolean = false
    }
}

class TableGeoResolver(
    private val sites: Map<String, List<DomainMatcher>> = emptyMap(),
    private val ips: Map<String, List<Cidr>> = emptyMap(),
) : GeoResolver {
    override fun siteMatches(code: String, domain: String): Boolean = sites[code].orEmpty().any { it.matches(domain) }
    override fun ipMatches(code: String, ip: IpAddress): Boolean = ips[code].orEmpty().any { ip in it }
}

/** Что известно о соединении в момент решения. */
data class RouteQuery(
    val domain: String? = null,
    val ip: IpAddress? = null,
    val port: Int? = null,
    val protocol: TransportProtocol? = null,
    val packageName: String? = null,
    val network: NetworkKind? = null,
    /** Адрес, в который резолвится [domain] (для [DomainResolution.IF_NO_DOMAIN_MATCH]). */
    val resolvedIp: IpAddress? = null,
)

data class RouteDecision(val action: RouteAction, val ruleId: RuleId?, val reason: String)

/**
 * Эталонный смысл маршрутизации: первое сработавшее правило по порядку;
 * иначе — действие по умолчанию. Приложение вне туннеля — DIRECT до
 * всяких правил.
 *
 * Используется для объяснения решений пользователю, проверки
 * компиляторов (одинаковые ответы на корпусе запросов) и тестов. Сам
 * трафик маршрутизирует ядро.
 */
class RouteEvaluator(private val policy: RoutingPolicy, private val geo: GeoResolver = GeoResolver.None) {
    fun decide(query: RouteQuery): RouteDecision {
        val pkg = query.packageName
        if (pkg != null && !policy.apps.includes(pkg)) {
            return RouteDecision(RouteAction.DIRECT, null, "приложение $pkg вне туннеля (политика приложений)")
        }
        firstMatch(query)?.let { return it }
        if (policy.domainResolution == DomainResolution.IF_NO_DOMAIN_MATCH && query.ip == null && query.resolvedIp != null) {
            firstMatch(query.copy(ip = query.resolvedIp))?.let {
                return it.copy(reason = it.reason + " (по IP после резолва домена)")
            }
            if (policy.automaticDomestic && geo.ipMatches("ru", query.resolvedIp)) {
                return RouteDecision(RouteAction.DIRECT, null, "автоматический domestic split: geoip:ru")
            }
        }
        if (policy.automaticDomestic) {
            val domainDomestic = query.domain?.let { geo.siteMatches("ru", it) } == true
            val ipDomestic = query.ip?.let { geo.ipMatches("ru", it) } == true
            if (domainDomestic || ipDomestic) {
                return RouteDecision(RouteAction.DIRECT, null, "автоматический domestic split: geosite/geoip:ru")
            }
        }
        return RouteDecision(policy.defaultAction, null, "ни одно правило не сработало — действие по умолчанию")
    }

    private fun firstMatch(query: RouteQuery): RouteDecision? {
        for (rule in policy.rules) {
            if (matches(rule.conditions, query)) {
                return RouteDecision(rule.action, rule.id, "правило ${rule.id}")
            }
        }
        return null
    }

    private fun matches(c: Conditions, q: RouteQuery): Boolean {
        if (c.isEmpty) return false
        if (c.domains.isNotEmpty() || c.geoSites.isNotEmpty()) {
            val d = q.domain ?: return false
            val byDomain = c.domains.any { it.matches(d) }
            val byGeo = c.geoSites.any { geo.siteMatches(it, d) }
            // домены и geosite — одно поле «назначение по имени»: ИЛИ между ними
            if (!byDomain && !byGeo) return false
        }
        if (c.ipCidrs.isNotEmpty() || c.geoIps.isNotEmpty()) {
            val ip = q.ip ?: return false
            val byCidr = c.ipCidrs.any { ip in it }
            val byGeo = c.geoIps.any { geo.ipMatches(it, ip) }
            if (!byCidr && !byGeo) return false
        }
        if (c.apps.isNotEmpty() && (q.packageName == null || q.packageName !in c.apps)) return false
        if (c.protocols.isNotEmpty() && (q.protocol == null || q.protocol !in c.protocols)) return false
        if (c.ports.isNotEmpty() && (q.port == null || c.ports.none { q.port in it })) return false
        if (c.networks.isNotEmpty() && (q.network == null || q.network !in c.networks)) return false
        return true
    }
}
