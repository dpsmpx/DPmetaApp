package com.dpsmpx.dppass.config

import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.routing.AppPolicy
import com.dpsmpx.dppass.routing.Conditions

/**
 * Нормализация IR: только преобразования, которые **не меняют смысл**
 * (повторы значений внутри поля правила убираются с пометкой). Порядок
 * правил не трогается — он и есть смысл. Нормализация идемпотентна:
 * повторный проход ничего не меняет (проверяется тестом).
 */
object Normalizer {
    data class Result(val ir: ConfigIr, val notes: List<Diagnostic>)

    fun normalize(ir: ConfigIr): Result {
        val notes = mutableListOf<Diagnostic>()
        val rules = ir.routing.rules.mapIndexed { i, rule ->
            val c = rule.conditions
            val n = Conditions(
                domains = c.domains.distinct(),
                ipCidrs = c.ipCidrs.distinct(),
                geoSites = c.geoSites.distinct(),
                geoIps = c.geoIps.distinct(),
                apps = c.apps.distinct(),
                protocols = c.protocols,
                ports = c.ports.distinct(),
                networks = c.networks,
            )
            if (n != c) notes += Diagnostic.info(DiagCode.RULE_VALUE_DUPLICATE, "повторные значения в правиле убраны", "$.routing.rules[$i]")
            rule.copy(conditions = n)
        }
        val apps = when (val a = ir.routing.apps) {
            AppPolicy.AllApps -> a
            is AppPolicy.OnlyApps -> AppPolicy.OnlyApps(a.packages.toSortedSet())
            is AppPolicy.ExceptApps -> AppPolicy.ExceptApps(a.packages.toSortedSet())
        }
        return Result(ir.copy(routing = ir.routing.copy(rules = rules, apps = apps)), notes)
    }
}
