package com.dpsmpx.dppass.engine

import com.dpsmpx.dppass.config.ConfigIr
import com.dpsmpx.dppass.config.ServerProfile
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.routing.AppLowering
import com.dpsmpx.dppass.routing.DnsTransport
import com.dpsmpx.dppass.routing.DomainResolution

/**
 * Сверка IR с матрицей ядра. Всё, что ядро выразить не может, — ошибка
 * с путём, а не тихое упрощение (D-05). Используется:
 * - при активации (весь IR против ядра, которое будет работать);
 * - при обновлении подписки — как фильтр серверов: неподдержанный
 *   профиль исключается **с предупреждением**, а не пропадает молча.
 */
object CapabilityChecker {
    /** Находки по одному профилю (пусто — профиль ядру по силам). */
    fun checkProfile(p: ServerProfile, caps: EngineCapabilities, path: String): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        val who = "${caps.backendId} ${caps.backendVersion}"
        if (p.protocol.kind !in caps.protocols) {
            out += Diagnostic.error(DiagCode.CAPABILITY_PROTOCOL_UNSUPPORTED, "$who не поддерживает протокол ${p.protocol.kind}", "$path.protocol")
        }
        if (p.transport.kind !in caps.transports) {
            out += Diagnostic.error(DiagCode.CAPABILITY_TRANSPORT_UNSUPPORTED, "$who не поддерживает транспорт ${p.transport.kind}", "$path.transport")
        }
        if (p.security.kind !in caps.securities) {
            out += Diagnostic.error(DiagCode.CAPABILITY_SECURITY_UNSUPPORTED, "$who не поддерживает ${p.security.kind}", "$path.security")
        }
        val flow = (p.protocol as? com.dpsmpx.dppass.config.ProtocolSpec.Vless)?.flow
        if (flow != null && flow !in caps.flows) {
            out += Diagnostic.error(DiagCode.CAPABILITY_FEATURE_UNSUPPORTED, "$who не поддерживает flow ${flow.wire}", "$path.protocol.flow")
        }
        return out
    }

    /** Маршрутизация и DNS против ядра (без профилей). */
    fun checkPolicy(ir: ConfigIr, caps: EngineCapabilities): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        val who = "${caps.backendId} ${caps.backendVersion}"
        fun need(feature: EngineFeature, what: String, path: String) {
            if (feature !in caps.features) {
                out += Diagnostic.error(DiagCode.CAPABILITY_FEATURE_UNSUPPORTED, "$who: $what — нет возможности $feature", path)
            }
        }

        val lowered = AppLowering.lower(ir.routing)
        if (lowered.engineAppRules.isNotEmpty()) {
            need(
                EngineFeature.APP_RULES_IN_ENGINE,
                "правила с приложениями (${lowered.engineAppRules.joinToString()}) нельзя перенести на уровень VpnService",
                "$.routing.rules",
            )
        }
        if (ir.routing.domainResolution == DomainResolution.IF_NO_DOMAIN_MATCH) {
            need(EngineFeature.DOMAIN_RESOLUTION_IF_NO_MATCH, "domain_resolution if_no_domain_match", "$.routing.domain_resolution")
        }
        for ((i, rule) in ir.routing.rules.withIndex()) {
            val c = rule.conditions
            if (c.geoSites.isNotEmpty()) need(EngineFeature.GEOSITE, "geosite", "$.routing.rules[$i].geosite")
            if (c.geoIps.isNotEmpty()) need(EngineFeature.GEOIP, "geoip", "$.routing.rules[$i].geoip")
            if (c.networks.isNotEmpty()) need(EngineFeature.NETWORK_CONDITION, "условие network", "$.routing.rules[$i].network")
        }
        for ((i, s) in ir.dns.servers.withIndex()) {
            when (s.transport) {
                is DnsTransport.Https -> need(EngineFeature.DNS_DOH, "DNS-over-HTTPS", "$.dns.servers[$i]")
                is DnsTransport.Tls -> need(EngineFeature.DNS_DOT, "DNS-over-TLS", "$.dns.servers[$i]")
                else -> Unit
            }
        }
        if (ir.dns.rules.isNotEmpty()) need(EngineFeature.DNS_RULES, "правила DNS", "$.dns.rules")
        return out
    }

    /** Полная сверка перед активацией: политика и активный профиль. */
    fun check(ir: ConfigIr, caps: EngineCapabilities, plan: CompilePlan): List<Diagnostic> {
        val out = checkPolicy(ir, caps).toMutableList()
        val index = ir.profiles.indexOfFirst { it.id == plan.profileId }
        if (index < 0) {
            out += Diagnostic.error(DiagCode.PROFILE_NOT_FOUND, "нет профиля «${plan.profileId}»", "$.profiles")
        } else {
            out += checkProfile(ir.profiles[index], caps, "$.profiles[$index]")
        }
        if (plan.withTun && EngineFeature.TUN_FD !in caps.features) {
            out += Diagnostic.error(DiagCode.CAPABILITY_FEATURE_UNSUPPORTED, "${caps.backendId}: нет приёма fd TUN", null)
        }
        return out
    }
}
