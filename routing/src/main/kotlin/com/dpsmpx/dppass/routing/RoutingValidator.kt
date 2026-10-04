package com.dpsmpx.dppass.routing

import com.dpsmpx.dppass.core.Cidr
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic

/**
 * Проверки смысла правил, которые не видны по одному значению:
 * пустые и повторённые правила, правила, которые никогда не сработают,
 * противоречие с политикой приложений.
 */
object RoutingValidator {
    private val PACKAGE = Regex("""[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+""")
    private val GEO_CODE = Regex("""[a-z0-9][a-z0-9_!@.-]{0,63}""")

    fun isValidPackage(name: String): Boolean = name.length <= 255 && PACKAGE.matches(name)
    fun isValidGeoCode(code: String): Boolean = GEO_CODE.matches(code)

    fun validate(policy: RoutingPolicy, path: String = "$.routing"): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        val seenIds = HashSet<String>()
        for ((i, rule) in policy.rules.withIndex()) {
            val rp = "$path.rules[$i]"
            if (!seenIds.add(rule.id.value)) out += Diagnostic.error(DiagCode.ID_DUPLICATE, "повторный id правила «${rule.id}»", "$rp.id")
            if (rule.conditions.isEmpty) {
                out += Diagnostic.error(DiagCode.RULE_EMPTY, "у правила нет условий; для «всего остального» есть routing.default", rp)
                continue
            }
            for (j in 0 until i) {
                val earlier = policy.rules[j]
                if (earlier.conditions.isEmpty || !covers(earlier.conditions, rule.conditions)) continue
                out += if (earlier.conditions == rule.conditions && earlier.action == rule.action) {
                    Diagnostic.warning(DiagCode.RULE_DUPLICATE, "правило повторяет «${earlier.id}»", rp)
                } else {
                    Diagnostic.warning(DiagCode.RULE_SHADOWED, "правило никогда не сработает: всё, что оно ловит, раньше ловит «${earlier.id}»", rp)
                }
                break
            }
            checkApps(policy.apps, rule, rp, out)
        }
        return out
    }

    private fun checkApps(apps: AppPolicy, rule: Rule, path: String, out: MutableList<Diagnostic>) {
        if (rule.action == RouteAction.DIRECT) return
        val outside = rule.conditions.apps.filter { !apps.includes(it) }
        if (outside.isNotEmpty()) {
            out += Diagnostic.error(
                DiagCode.APP_POLICY_CONFLICT,
                "правило ${rule.action} для приложений вне туннеля (${outside.joinToString()}) выполнить нельзя",
                "$path.apps",
            )
        }
    }

    /**
     * [a] ловит всё, что ловит [b]: каждое непустое поле [a] покрывает
     * соответствующее поле [b]. Это достаточное условие, не полное:
     * пропущенная тень — не ошибка, ложная тень — была бы.
     */
    internal fun covers(a: Conditions, b: Conditions): Boolean {
        if (a.geoSites.isNotEmpty() || a.geoIps.isNotEmpty()) {
            // гео-коды сравниваем только на равенство множеств
            if (a.geoSites.toSet() != b.geoSites.toSet() || a.geoIps.toSet() != b.geoIps.toSet()) return false
        } else if (b.geoSites.isNotEmpty() || b.geoIps.isNotEmpty()) {
            if (a.domains.isNotEmpty() || a.ipCidrs.isNotEmpty()) return false
        }
        if (a.domains.isNotEmpty()) {
            if (b.domains.isEmpty() || b.domains.any { bm -> a.domains.none { am -> domainCovers(am, bm) } }) return false
        }
        if (a.ipCidrs.isNotEmpty()) {
            if (b.ipCidrs.isEmpty() || b.ipCidrs.any { bc -> a.ipCidrs.none { ac -> cidrCovers(ac, bc) } }) return false
        }
        if (a.apps.isNotEmpty() && (b.apps.isEmpty() || !a.apps.containsAll(b.apps))) return false
        if (a.protocols.isNotEmpty() && (b.protocols.isEmpty() || !a.protocols.containsAll(b.protocols))) return false
        if (a.ports.isNotEmpty() && (b.ports.isEmpty() || b.ports.any { bp -> a.ports.none { ap -> bp.first >= ap.first && bp.last <= ap.last } })) return false
        if (a.networks.isNotEmpty() && (b.networks.isEmpty() || !a.networks.containsAll(b.networks))) return false
        return true
    }

    private fun domainCovers(a: DomainMatcher, b: DomainMatcher): Boolean = when (a) {
        is DomainMatcher.Full -> b is DomainMatcher.Full && b.name == a.name
        is DomainMatcher.Suffix -> when (b) {
            is DomainMatcher.Full -> a.matches(b.name)
            is DomainMatcher.Suffix -> a.matches(b.suffix)
            is DomainMatcher.Keyword -> false
        }
        is DomainMatcher.Keyword -> when (b) {
            is DomainMatcher.Full -> a.keyword in b.name
            is DomainMatcher.Suffix -> a.keyword in b.suffix
            is DomainMatcher.Keyword -> a.keyword in b.keyword
        }
    }

    private fun cidrCovers(a: Cidr, b: Cidr): Boolean = b.isWithin(a)
}
