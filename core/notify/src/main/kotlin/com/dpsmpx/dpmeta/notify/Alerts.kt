package com.dpsmpx.dpmeta.notify

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpmeta.model.AlertAction
import com.dpsmpx.dpmeta.model.AlertChannel
import com.dpsmpx.dpmeta.model.AlertImportance
import com.dpsmpx.dpmeta.model.AlertRule
import com.dpsmpx.dpmeta.model.AlertRules
import com.dpsmpx.dpmeta.model.Event
import com.dpsmpx.dpmeta.model.EventCatalog
import com.dpsmpx.dpmeta.model.EventType
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * Тихие часы (F-38): с минуты суток [fromMinute] до [toMinute] (через полночь —
 * можно: 22:00–08:00), в дни [days] (пусто — каждый день; день — тот, в который
 * тихие часы начались).
 */
data class QuietHours(val fromMinute: Int, val toMinute: Int, val days: Set<DayOfWeek> = emptySet()) {
    init {
        require(fromMinute in 0 until MINUTES_IN_DAY && toMinute in 0 until MINUTES_IN_DAY) { "минуты суток — 0…1439" }
    }

    fun covers(at: Timestamp, zone: ZoneId): Boolean {
        if (fromMinute == toMinute) return false
        val t = Instant.ofEpochMilli(at.epochMillis).atZone(zone)
        val m = t.hour * 60 + t.minute
        val inside = if (fromMinute < toMinute) m in fromMinute until toMinute else m >= fromMinute || m < toMinute
        if (!inside) return false
        if (days.isEmpty()) return true
        // После полуночи тихие часы — ещё вчерашние: день начала решает.
        val started = if (fromMinute > toMinute && m < toMinute) t.dayOfWeek.minus(1) else t.dayOfWeek
        return started in days
    }

    companion object {
        const val MINUTES_IN_DAY = 24 * 60
    }
}

/** Показанное уведомление — для антидребезга: правило, проект и когда. */
data class Shown(val rule: String, val project: String?, val at: Timestamp)

/** Решение о событии (F-52, D-85). */
sealed interface Decision {
    val event: Event

    /**
     * Уведомить: канал, важность, без звука ли (тихие часы), метка [tag] — один
     * предмет — одно уведомление, обновляемое.
     */
    data class Notify(
        override val event: Event,
        val rule: AlertRule,
        val channel: AlertChannel,
        val importance: AlertImportance,
        val silent: Boolean,
        val tag: String,
    ) : Decision

    /** В сводку-дайджест в заданное время. */
    data class Digest(override val event: Event, val rule: AlertRule) : Decision

    /** Только лента: правило так велит ([rule]), правила нет (`null`) или предел антидребезга ([limited]). */
    data class Feed(override val event: Event, val rule: AlertRule?, val limited: Boolean = false) : Decision

    /** Промолчать — правило «silent». */
    data class Silent(override val event: Event, val rule: AlertRule) : Decision

    /** Отброшено: тип не из каталога (F-51) — `RULE_INVALID`. */
    data class Dropped(override val event: Event, val diagnostic: Diagnostic) : Decision
}

/** Итог прогона: решения в порядке событий и история показа для следующего прогона. */
data class Decided(val decisions: List<Decision>, val shown: List<Shown>)

/**
 * Движок правил тревог (ТЗ, §6.7, F-51…F-53; D-85) — чистая функция: события,
 * каталог, правила, время, тихие часы и недавно показанное → решения. Решает
 * первое подходящее включённое правило; ни одного — только лента. Каналы, звук и
 * показ — у оболочки.
 */
object Alerts {
    /** Сколько помнить показанное: дольше самого длинного окна антидребезга (неделя). */
    const val KEEP_MINUTES = 10_080L

    /** Окно «что сработало бы» (F-53), дни. */
    const val TRY_DAYS = 7

    /**
     * Решить о новых событиях [events], показываемых сейчас ([now]): тихие часы и
     * антидребезг — по [now]; события — по времени, старые первыми.
     */
    fun decide(
        events: List<Event>,
        catalog: EventCatalog,
        rules: AlertRules,
        now: Timestamp,
        zone: ZoneId,
        quiet: QuietHours?,
        shown: List<Shown>,
    ): Decided = run(events.sortedBy { it.at.epochMillis }, catalog, rules.rules, zone, quiet, shown) { now }

    /**
     * «Что сработало бы» (F-53): правило [rule] одно — по сохранённым событиям за
     * [TRY_DAYS] дней до [now], как если бы каждое пришло в своё время, с пустой
     * историей показа. Итог — решения правила (без событий, которые оно не взяло).
     */
    fun tryRule(rule: AlertRule, events: List<Event>, catalog: EventCatalog, now: Timestamp, zone: ZoneId, quiet: QuietHours?): List<Decision> {
        val from = now.epochMillis - TRY_DAYS * 24L * 60 * 60 * 1000
        val window = events.filter { it.at.epochMillis in from..now.epochMillis }.sortedBy { it.at.epochMillis }
        return run(window, catalog, listOf(rule.copy(enabled = true)), zone, quiet, emptyList()) { it.at }
            .decisions.filter { d -> d !is Decision.Dropped && !(d is Decision.Feed && d.rule == null) }
    }

    private fun run(
        events: List<Event>,
        catalog: EventCatalog,
        rules: List<AlertRule>,
        zone: ZoneId,
        quiet: QuietHours?,
        shown: List<Shown>,
        time: (Event) -> Timestamp,
    ): Decided {
        val history = shown.toMutableList()
        val out = ArrayList<Decision>(events.size)
        for (e in events) {
            val type = catalog.type(e.type)
            if (type == null) {
                out += Decision.Dropped(e, Diagnostic.error(DiagCode.RULE_INVALID, "событие неизвестного типа «${e.type}» — отброшено", e.type))
                continue
            }
            val rule = rules.firstOrNull { it.enabled && matches(it, e, type, zone) }
            if (rule == null) {
                out += Decision.Feed(e, null)
                continue
            }
            val at = time(e)
            out += when (rule.action) {
                AlertAction.SILENT -> Decision.Silent(e, rule)
                AlertAction.FEED -> Decision.Feed(e, rule)
                AlertAction.DIGEST -> Decision.Digest(e, rule)
                AlertAction.NOTIFY -> {
                    val d = rule.debounce
                    val recent = d?.let { db ->
                        val since = at.epochMillis - db.windowMinutes * 60_000L
                        history.count { it.rule == rule.id && it.project == e.project && it.at.epochMillis in since..at.epochMillis }
                    } ?: 0
                    if (d != null && recent >= d.limit) {
                        Decision.Feed(e, rule, limited = true)
                    } else {
                        history += Shown(rule.id, e.project, at)
                        Decision.Notify(
                            e,
                            rule,
                            rule.channel ?: type.channel,
                            rule.importance,
                            silent = rule.quiet && quiet?.covers(at, zone) == true,
                            tag = listOf(rule.id, e.project.orEmpty(), e.key ?: e.type).joinToString(":"),
                        )
                    }
                }
            }
        }
        val newest = events.maxOfOrNull { time(it).epochMillis } ?: shown.maxOfOrNull { it.at.epochMillis }
        val kept = if (newest == null) history else history.filter { newest - it.at.epochMillis <= KEEP_MINUTES * 60_000L }
        return Decided(out, kept)
    }

    /** Подходит ли правило к событию: тип или маска, проект, текст, regex, число, день недели. */
    fun matches(rule: AlertRule, e: Event, type: EventType, zone: ZoneId): Boolean {
        val typeOk = if (rule.on.endsWith(".*")) type.type.startsWith(rule.on.removeSuffix("*")) else type.type == rule.on
        if (!typeOk) return false
        // Свойства модели — из другого модуля: умного приведения нет, значения — в локальные.
        val project = rule.project
        if (project != null && !project.equals(e.project, ignoreCase = true)) return false
        val haystack by lazy { (listOf(e.text) + e.fields.values).joinToString("\n") }
        val contains = rule.contains
        if (contains != null && !haystack.contains(contains, ignoreCase = true)) return false
        val regex = rule.regex
        if (regex != null && !regex.containsMatchIn(haystack)) return false
        rule.number?.let { n ->
            // Нет числа — условие не выполнено: неизвестное — не ноль (D-13).
            val v = e.numbers[n.field] ?: return false
            val low = n.atLeast
            val high = n.atMost
            if (low != null && v < low) return false
            if (high != null && v > high) return false
        }
        if (rule.days.isNotEmpty() && Instant.ofEpochMilli(e.at.epochMillis).atZone(zone).dayOfWeek !in rule.days) return false
        return true
    }
}
