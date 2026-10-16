package com.dpsmpx.dppass.routing

import com.dpsmpx.dppass.core.RuleId

/**
 * Перенос правил про приложения на уровень VpnService (D-09, «per-app»).
 *
 * Ядро на Android обычно не знает, какое приложение открыло соединение;
 * VpnService умеет только «приложение в туннеле или мимо». Поэтому
 * правило «приложения A — DIRECT» точно выражается исключением A из
 * туннеля, но **только** если до него нет правил с другими условиями:
 * иначе более раннее правило (например, BLOCK рекламы) перестало бы
 * действовать на A — смысл бы изменился.
 *
 * Что перенести нельзя, остаётся в [LoweredRouting.engineAppRules]: это
 * требует от ядра сопоставления приложений, а при его отсутствии —
 * ошибки возможностей, а не молчаливого упрощения.
 */
data class LoweredRouting(
    /** Кто идёт в туннель — с учётом перенесённых правил. */
    val tunnelApps: AppPolicy,
    /** Правила для ядра (перенесённые убраны). */
    val engineRules: List<Rule>,
    /** Правила, которым нужно сопоставление приложений в ядре. */
    val engineAppRules: List<RuleId>,
    /** Перенесённые на уровень VpnService правила. */
    val lowered: List<RuleId>,
)

object AppLowering {
    fun lower(policy: RoutingPolicy): LoweredRouting {
        var apps = policy.apps
        val lowered = mutableListOf<RuleId>()
        var index = 0
        while (index < policy.rules.size) {
            val rule = policy.rules[index]
            if (!rule.conditions.isAppsOnly || rule.action != RouteAction.DIRECT) break
            apps = exclude(apps, rule.conditions.apps.toSet())
            lowered += rule.id
            index++
        }
        val remaining = policy.rules.drop(index)
        val engineAppRules = remaining.filter { it.conditions.apps.isNotEmpty() }.map { it.id }
        return LoweredRouting(apps, remaining, engineAppRules, lowered)
    }

    private fun exclude(apps: AppPolicy, packages: Set<String>): AppPolicy = when (apps) {
        AppPolicy.AllApps -> AppPolicy.ExceptApps(packages)
        is AppPolicy.ExceptApps -> AppPolicy.ExceptApps(apps.packages + packages)
        is AppPolicy.OnlyApps -> AppPolicy.OnlyApps(apps.packages - packages)
    }
}
