package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpmeta.metrics.Feed
import com.dpsmpx.dpmeta.metrics.FeedEvent
import com.dpsmpx.dpmeta.model.AlertAction
import com.dpsmpx.dpmeta.model.AlertRule
import com.dpsmpx.dpmeta.model.AlertRules
import com.dpsmpx.dpmeta.model.EventCatalog
import com.dpsmpx.dpmeta.notify.Alerts
import com.dpsmpx.dpmeta.notify.Decision
import com.dpsmpx.dpai.notifications.QuietHours
import java.time.ZoneId

/**
 * «Ещё → Уведомления» (F-37, F-38, F-53; D-85): тихие часы, приватность экрана
 * блокировки, правила с «что сработало бы за 7 дней». Чистый Kotlin.
 */
object AlertActions {
    /** Старые коды остаются для чтения правил и истории; источники удалены (D-173, D-176). */
    private val RETIRED_TYPES = setOf(
        "decision.new", "roadmap.step.done", "todo.author.new", "question.new",
        "dpmeta.report.new", "dpmeta.risk.changed", "app.notification",
        "claude.turn.done", "claude.needs_input", "claude.turn.failed", "claude.session.ended",
    )

    fun studioCatalog(catalog: EventCatalog): EventCatalog = catalog.copy(types = catalog.types.filter {
        it.channel != com.dpsmpx.dpmeta.model.AlertChannel.SITE && it.channel != com.dpsmpx.dpmeta.model.AlertChannel.SOCIAL &&
            !it.type.startsWith("termux.") && it.type !in RETIRED_TYPES
    })

    fun studioRules(rules: AlertRules, catalog: EventCatalog): AlertRules =
        rules.copy(rules = rules.rules.filter { catalog.matching(it.on).isNotEmpty() })

    /** Время пользовательской ночи; одинаковые границы не маскируются под включённый режим. */
    fun quiet(from: String, to: String, days: Set<java.time.DayOfWeek>): com.dpsmpx.dpai.core.result.Outcome<QuietHours> {
        fun minute(value: String): Int? {
            if (!value.matches(Regex("[0-9]{2}:[0-9]{2}"))) return null
            val h = value.take(2).toInt()
            val m = value.takeLast(2).toInt()
            return if (h in 0..23 && m in 0..59) h * 60 + m else null
        }
        val start = minute(from.trim())
        val end = minute(to.trim())
        return if (start == null || end == null || start == end) {
            com.dpsmpx.dpai.core.result.Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.VALUE_INVALID, "время — ЧЧ:ММ, от 00:00 до 23:59; начало и конец должны отличаться")
        } else com.dpsmpx.dpai.core.result.Outcome.success(QuietHours(start, end, days))
    }

    /** Тихие часы на выбор: «выкл» и три обычных ночи. */
    val PRESETS: List<QuietHours?> = listOf(null, QuietHours(22 * 60, 7 * 60), QuietHours(23 * 60, 8 * 60), QuietHours(0, 8 * 60))

    /** По умолчанию — 23:00–08:00: ночью без звука; срочное правило звучит всё равно. */
    val DEFAULT_QUIET: QuietHours = QuietHours(23 * 60, 8 * 60)

    fun quietName(q: QuietHours?): String = if (q == null) "выкл" else "${hm(q.fromMinute)}–${hm(q.toMinute)}"

    private fun hm(m: Int) = "%02d:%02d".format(java.util.Locale.ROOT, m / 60, m % 60)

    fun actionName(a: AlertAction): String = when (a) {
        AlertAction.NOTIFY -> "уведомить"
        AlertAction.FEED -> "только лента"
        AlertAction.DIGEST -> "в сводку"
        AlertAction.SILENT -> "промолчать"
    }

    /** Строка правила для экрана: на что, что сделать, условия. */
    data class Row(val id: String, val title: String, val line: String, val enabled: Boolean, val tried: Int, val loud: Boolean)

    /** Подпись «на что» правила: тип каталога его подписью, маска — как есть. */
    fun onName(rule: AlertRule, catalog: EventCatalog): String = catalog.type(rule.on)?.title ?: rule.on

    /**
     * Правила с «что сработало бы» (F-53) по событиям ленты [feed] за 7 дней до [now]:
     * сколько раз правило уведомило бы (или отправило в сводку).
     */
    fun rows(rules: AlertRules, catalog: EventCatalog, feed: List<FeedEvent>, now: Timestamp, zone: ZoneId, quiet: QuietHours?, extra: List<com.dpsmpx.dpmeta.model.Event> = emptyList()): List<Row> {
        val events = feed.mapNotNull(Feed::event) + extra
        return rules.rules.map { r ->
            val tried = Alerts.tryRule(r, events, catalog, now, zone, quiet).count { it is Decision.Notify || it is Decision.Digest }
            val conditions = listOfNotNull(
                r.project?.let { "проект $it" },
                r.contains?.let { "текст «$it»" },
                r.regex?.let { "выражение ${it.pattern}" },
                r.number?.let { n -> n.field + (n.atLeast?.let { " ≥ ${it.toLong()}" } ?: "") + (n.atMost?.let { " ≤ ${it.toLong()}" } ?: "") },
                r.debounce?.let { "не чаще ${it.limit} за ${it.windowMinutes} мин" },
            )
            Row(
                r.id,
                r.title,
                onName(r, catalog) + " → " + actionName(r.action) + if (conditions.isEmpty()) "" else "; " + conditions.joinToString(", "),
                r.enabled,
                tried,
                loud = !r.quiet,
            )
        }
    }
}
