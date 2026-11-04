package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpmeta.metrics.MetricPoint
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Экспорт истории чисел за срок (ТЗ, F-36): CSV и JSON — чистый Kotlin;
 * файл пишет экран через системный выбор места. Точки — как в истории (D-34):
 * время среза, версия правил; порядок — по времени, затем метрике и предмету,
 * чтобы два экспорта одной истории совпадали байт в байт (N-01).
 */
object Export {
    const val FORMAT = "dpmeta.history-export"
    const val VERSION = 1

    private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(Screens.ZONE)

    fun iso(t: Timestamp): String = ISO.format(Instant.ofEpochMilli(t.epochMillis))

    /** Точки за [period] до [now] по порядку экспорта. */
    fun points(history: List<MetricPoint>, period: Stats.Period, now: Timestamp): List<MetricPoint> {
        val from = period.days?.let { Stats.day(now).minusDays(it - 1) }
        return history.filter { from == null || !Stats.day(it.asOf).isBefore(from) }
            .sortedWith(compareBy<MetricPoint> { it.asOf.epochMillis }.thenBy { it.metric }.thenBy { it.subject })
    }

    /** CSV (RFC 4180): поле с запятой, кавычкой или переводом строки — в кавычках. */
    fun csv(points: List<MetricPoint>): String = buildString {
        append("asOf,metric,subject,value,rulesVersion\r\n")
        for (p in points) append(listOf(iso(p.asOf), p.metric, p.subject, p.value.toString(), p.rulesVersion.toString()).joinToString(",", transform = ::field)).append("\r\n")
    }

    private fun field(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun json(points: List<MetricPoint>, period: Stats.Period, now: Timestamp): String = CanonicalJson.write(
        JsonObject(
            linkedMapOf<String, JsonValue>(
                *FormatHeader.write(FORMAT, VERSION),
                "period" to JsonString(period.name.lowercase()),
                "exportedAt" to JsonString(iso(now)),
                "points" to JsonArray(
                    points.map { p ->
                        JsonObject.of(
                            "asOf" to JsonString(iso(p.asOf)),
                            "metric" to JsonString(p.metric),
                            "subject" to JsonString(p.subject),
                            "value" to JsonNumber.of(p.value),
                            "rulesVersion" to JsonNumber.of(p.rulesVersion),
                        )
                    },
                ),
            ),
        ),
    )

    /** Имя файла по умолчанию: срок и дата среза. */
    fun fileName(period: Stats.Period, now: Timestamp, ext: String): String =
        "dpmetaapp-history-${period.name.lowercase()}-${Stats.day(now)}.$ext"
}
