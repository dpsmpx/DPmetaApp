package com.dpsmpx.dpai.monitoring

/** Состояние самого приложения (ТЗ, §79). */
enum class SystemHealth { HEALTHY, DEGRADED, RECOVERY, BLOCKED, STORAGE_ERROR, EXECUTION_UNAVAILABLE, SECURITY_LOCKDOWN }

/** Что разрешено в текущем состоянии (ТЗ, §80). Просмотр разрешён всегда. */
enum class Capability { VIEW, SCHEDULE, GIT, NOTIFY, DESTRUCTIVE_WRITES }

/** Сигналы самодиагностики; неизвестное — `null` и считается недоступным, а не здоровым (D-13). */
data class SystemSignals(
    val storageOk: Boolean?,
    val recoveryInProgress: Boolean,
    val gitAvailable: Boolean?,
    val notificationsOk: Boolean?,
    val targetsOnline: Int,
    val targetsTotal: Int,
    val securityAlert: Boolean,
    val blockedByUser: Boolean = false,
    /** Нужен ли Git: без проекта с репозиторием его отсутствие — не деградация. */
    val gitRequired: Boolean = true,
)

data class SelfDiagnosis(val health: SystemHealth, val allowed: Set<Capability>, val reasons: List<String>)

/**
 * Самодиагностика и деградация (ТЗ, §79, §80): при отказе части системы
 * остальное работает, насколько можно. Приоритет — от самого опасного:
 * тревога безопасности → только просмотр; порча хранилища → без
 * разрушительной записи и без планирования; восстановление → без
 * планирования до конца сверки; нет ни одного target → без планирования;
 * нет Git → без Git и планирования, просмотр есть; сломаны уведомления —
 * планировщик не останавливается.
 */
object SelfDiagnostics {
    fun evaluate(s: SystemSignals): SelfDiagnosis {
        val reasons = mutableListOf<String>()
        val allowed = Capability.entries.toMutableSet()
        var health = SystemHealth.HEALTHY
        fun worse(h: SystemHealth, why: String, vararg deny: Capability) {
            reasons += why
            allowed -= deny.toSet()
            if (h.ordinal > health.ordinal) health = h
        }
        if (s.notificationsOk != true) worse(SystemHealth.DEGRADED, "уведомления недоступны", Capability.NOTIFY)
        if (s.gitRequired && s.gitAvailable != true) worse(SystemHealth.DEGRADED, "Git недоступен", Capability.GIT, Capability.SCHEDULE)
        if (s.blockedByUser) worse(SystemHealth.BLOCKED, "остановлено пользователем", Capability.SCHEDULE)
        if (s.recoveryInProgress) worse(SystemHealth.RECOVERY, "идёт восстановление и сверка", Capability.SCHEDULE, Capability.DESTRUCTIVE_WRITES)
        if (s.storageOk != true) worse(SystemHealth.STORAGE_ERROR, "хранилище повреждено или недоступно", Capability.SCHEDULE, Capability.DESTRUCTIVE_WRITES)
        if (s.targetsTotal > 0 && s.targetsOnline == 0 || s.targetsTotal == 0) {
            worse(SystemHealth.EXECUTION_UNAVAILABLE, "нет доступных execution targets", Capability.SCHEDULE)
        }
        if (s.securityAlert) {
            worse(SystemHealth.SECURITY_LOCKDOWN, "тревога безопасности", *Capability.entries.filter { it != Capability.VIEW }.toTypedArray())
        }
        return SelfDiagnosis(health, allowed + Capability.VIEW, reasons)
    }
}
