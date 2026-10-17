package com.dpsmpx.dppass.routing

import com.dpsmpx.dppass.core.Endpoint
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic

/**
 * DNS — отдельная подсистема (D-10): политика (какой сервер для каких
 * имён), транспорт (как спрашивать), маршрут запроса (через туннель или
 * напрямую), стратегия адресов и защита от утечек. Кеш и сам резолвер —
 * дело ядра; DPpass задаёт смысл и проверяет, что ядро может его
 * выполнить (иначе — ошибка, не «похожая» замена).
 */
sealed interface DnsTransport {
    /** Обычный DNS по UDP/53 (или другому порту). */
    data class Udp(val server: Endpoint) : DnsTransport

    data class Tcp(val server: Endpoint) : DnsTransport

    /** DNS-over-HTTPS: [url] — `https://хост[:порт]/путь`. */
    data class Https(val url: String) : DnsTransport

    /** DNS-over-TLS. */
    data class Tls(val server: Endpoint, val serverName: String?) : DnsTransport

    /** Резолвер физической сети (только для запросов мимо туннеля). */
    data object System : DnsTransport
}

/** Куда идёт сам DNS-запрос. */
enum class DnsRoute { TUNNEL, DIRECT }

data class DnsServer(val id: String, val transport: DnsTransport, val route: DnsRoute)

data class DnsRule(val domains: List<DomainMatcher>, val geoSites: List<String>, val serverId: String)

enum class DnsStrategy { DUAL, PREFER_IPV4, PREFER_IPV6, IPV4_ONLY, IPV6_ONLY }

data class DnsPolicy(
    val servers: List<DnsServer>,
    val defaultServerId: String,
    val rules: List<DnsRule> = emptyList(),
    val strategy: DnsStrategy = DnsStrategy.PREFER_IPV4,
    /**
     * Запросы приложений в туннеле не уходят мимо туннеля открытым
     * текстом. При включённой защите сервер по умолчанию обязан идти
     * через туннель.
     */
    val leakProtection: Boolean = true,
) {
    fun server(id: String): DnsServer? = servers.firstOrNull { it.id == id }
}

object DnsValidator {
    fun validate(policy: DnsPolicy, path: String = "$.dns"): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        if (policy.servers.isEmpty()) {
            out += Diagnostic.error(DiagCode.DNS_SERVERS_EMPTY, "нет ни одного DNS-сервера", "$path.servers")
            return out
        }
        val ids = HashSet<String>()
        for ((i, s) in policy.servers.withIndex()) {
            val sp = "$path.servers[$i]"
            if (!ids.add(s.id)) out += Diagnostic.error(DiagCode.ID_DUPLICATE, "повторный id DNS-сервера «${s.id}»", "$sp.id")
            if (s.transport == DnsTransport.System && s.route == DnsRoute.TUNNEL) {
                out += Diagnostic.error(DiagCode.DNS_SERVER_INVALID, "системный резолвер не может работать через туннель", "$sp.route")
            }
            if (s.transport is DnsTransport.Https && !s.transport.url.startsWith("https://")) {
                out += Diagnostic.error(DiagCode.DNS_SERVER_INVALID, "адрес DoH должен начинаться с https://", "$sp.url")
            }
        }
        val default = policy.server(policy.defaultServerId)
        if (default == null) {
            out += Diagnostic.error(DiagCode.REFERENCE_UNKNOWN, "нет DNS-сервера «${policy.defaultServerId}»", "$path.default")
        } else if (policy.leakProtection && default.route == DnsRoute.DIRECT) {
            out += Diagnostic.warning(
                DiagCode.DNS_LEAK_RISK,
                "защита от утечек включена, но сервер по умолчанию «${default.id}» идёт мимо туннеля: имена из туннеля увидит сеть",
                "$path.default",
            )
        }
        for ((i, r) in policy.rules.withIndex()) {
            if (policy.server(r.serverId) == null) {
                out += Diagnostic.error(DiagCode.REFERENCE_UNKNOWN, "нет DNS-сервера «${r.serverId}»", "$path.rules[$i].server")
            }
            if (r.domains.isEmpty() && r.geoSites.isEmpty()) {
                out += Diagnostic.error(DiagCode.RULE_EMPTY, "у DNS-правила нет условий", "$path.rules[$i]")
            }
        }
        return out
    }
}
