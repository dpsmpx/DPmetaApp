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
import com.dpsmpx.dpmeta.notify.QuietHours
import java.time.ZoneId

/**
 * «Ещё → Уведомления» (F-37, F-38, F-53; D-85): тихие часы, приватность экрана
 * блокировки, правила с «что сработало бы за 7 дней». Чистый Kotlin.
 */
object AlertActions {
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
    fun rows(rules: AlertRules, catalog: EventCatalog, feed: List<FeedEvent>, now: Timestamp, zone: ZoneId, quiet: QuietHours?): List<Row> {
        val events = feed.mapNotNull(Feed::event)
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
