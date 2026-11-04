package com.dpsmpx.dpmeta.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpmeta.app.screens.Stats
import com.dpsmpx.dpmeta.metrics.MetricPoint
import java.time.format.DateTimeFormatter

/**
 * Смысловые цвета графиков (ТЗ, N-08): один цвет на смысл во всех графиках,
 * пара для светлой и тёмной темы; контраст к фону — не ниже AA (N-07).
 * Сравнение проектов — нейтральная палитра рядов, без смысловых цветов.
 */
object ChartColors {
    private val light = mapOf(
        Stats.Hue.NEUTRAL to Color(0xFF2F5D8A),
        Stats.Hue.DECISIONS to Color(0xFF6A3FA0),
        Stats.Hue.VERIFIED to Color(0xFF2E7D32),
        Stats.Hue.ALERT to Color(0xFFB3261E),
        Stats.Hue.AI to Color(0xFF8A5A00),
        Stats.Hue.HUMAN to Color(0xFF00696E),
    )
    private val dark = mapOf(
        Stats.Hue.NEUTRAL to Color(0xFF9CC3EA),
        Stats.Hue.DECISIONS to Color(0xFFD0B8F5),
        Stats.Hue.VERIFIED to Color(0xFF8FD694),
        Stats.Hue.ALERT to Color(0xFFF2B8B5),
        Stats.Hue.AI to Color(0xFFF0C27A),
        Stats.Hue.HUMAN to Color(0xFF7FD8DC),
    )
    private val seriesLight = listOf(0xFF2F5D8A, 0xFF8A5A00, 0xFF00696E, 0xFF6A3FA0, 0xFF5B6B7A, 0xFF7A4E2D).map(::Color)
    private val seriesDark = listOf(0xFF9CC3EA, 0xFFF0C27A, 0xFF7FD8DC, 0xFFD0B8F5, 0xFFB7C4D1, 0xFFE3B48F).map(::Color)

    fun hue(h: Stats.Hue, isDark: Boolean): Color = (if (isDark) dark else light).getValue(h)

    fun series(i: Int, isDark: Boolean): Color = (if (isDark) seriesDark else seriesLight).let { it[i % it.size] }
}

private val SHORT = DateTimeFormatter.ofPattern("dd.MM")

/** Линейный график (F-34): ряды по дням, касание — значения ближайшего дня; описание — для TalkBack (N-07). */
@Composable
fun LineChart(chart: Stats.Chart, modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()
    val days = remember(chart) { chart.series.flatMap { s -> s.points.map { it.first } }.distinct().sorted() }
    val values = remember(chart) { chart.series.flatMap { s -> s.points.map { it.second } } }
    var picked by remember(chart) { mutableStateOf<Int?>(null) }
    val axis = MaterialTheme.colorScheme.outline
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(180.dp)
                .semantics { contentDescription = chart.description }
                .pointerInput(chart) {
                    detectTapGestures { o -> if (days.isNotEmpty()) picked = ((o.x / size.width) * (days.size - 1) + 0.5f).toInt().coerceIn(0, days.lastIndex) }
                },
        ) {
            if (days.isEmpty()) return@Canvas
            val low = minOf(0L, values.min())
            val high = maxOf(values.max(), low + 1)
            fun x(i: Int) = if (days.size == 1) size.width / 2 else size.width * i / (days.size - 1)
            fun y(v: Long) = size.height - size.height * (v - low) / (high - low).toFloat()
            drawLine(axis, Offset(0f, y(low)), Offset(size.width, y(low)), strokeWidth = 1f)
            chart.series.forEachIndexed { si, s ->
                val color = if (chart.series.size == 1) ChartColors.hue(chart.metric.hue, isDark) else ChartColors.series(si, isDark)
                val path = Path()
                s.points.forEachIndexed { pi, (d, v) ->
                    val p = Offset(x(days.indexOf(d)), y(v))
                    if (pi == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    drawCircle(color, radius = 4f, center = p)
                }
                drawPath(path, color, style = Stroke(width = 3f))
            }
            picked?.let { i -> drawLine(axis, Offset(x(i), 0f), Offset(x(i), size.height), strokeWidth = 1f) }
        }
        picked?.let { i ->
            val d = days[i]
            val text = chart.series.joinToString("; ") { s ->
                val v = s.points.firstOrNull { it.first == d }?.second?.let { Stats.value(it, chart.metric) } ?: "—"
                if (chart.series.size == 1) v else "${s.subject}: $v"
            }
            Text("${SHORT.format(d)} — $text", style = MaterialTheme.typography.bodySmall)
        }
        if (chart.series.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chart.series.forEachIndexed { si, s -> Text("● ${s.subject}", color = ChartColors.series(si, isDark), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

/** Спарклайн плитки: только линия, подпись — у плитки. */
@Composable
fun Sparkline(values: List<Long>, color: Color, modifier: Modifier = Modifier) = Canvas(modifier.width(96.dp).height(28.dp)) {
    if (values.size < 2) return@Canvas
    val low = values.min()
    val high = maxOf(values.max(), low + 1)
    val path = Path()
    values.forEachIndexed { i, v ->
        val p = Offset(size.width * i / (values.size - 1), size.height - size.height * (v - low) / (high - low).toFloat())
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, color, style = Stroke(width = 2f))
}

/** Плитки сводки (F-34): число, изменение и спарклайн; у плитки — подпись для TalkBack. */
@Composable
fun Tiles(tiles: List<Stats.Tile>) {
    val isDark = isSystemInDarkTheme()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in tiles) {
            Card(Modifier.semantics { contentDescription = "${t.metric.title}: ${t.value}${if (t.change.isEmpty()) "" else ", ${t.change}"}" }) {
                Column(Modifier.padding(8.dp)) {
                    Text(t.metric.title, style = MaterialTheme.typography.labelSmall)
                    Text(t.value, fontWeight = FontWeight.SemiBold)
                    if (t.change.isNotEmpty()) Text(t.change, style = MaterialTheme.typography.labelSmall)
                    Sparkline(t.spark, ChartColors.hue(t.metric.hue, isDark))
                }
            }
        }
    }
}

/**
 * Статистика (F-33, F-34): метрика, срок и чей ряд; [project] — открыта из
 * карточки, `null` — сравнение всех проектов. Рядом с графиком — таблица (N-07).
 */
@Composable
fun StatsScreen(history: List<MetricPoint>, projects: List<String>, project: String?, now: Timestamp, onBack: () -> Unit) {
    var metric by remember { mutableStateOf(Stats.CATALOG.first()) }
    var period by remember { mutableStateOf(Stats.Period.MONTH) }
    var subject by remember { mutableStateOf(project) }
    val chart = remember(history, metric, period, subject) {
        Stats.chart(history, metric, period, subject?.let(::listOf) ?: projects, now)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { TextButton(onClick = onBack) { Text("← Назад") } }
        item { Text("Статистика", style = MaterialTheme.typography.titleMedium) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in Stats.CATALOG) FilterChip(selected = metric == m, onClick = { metric = m }, label = { Text(m.title) })
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in Stats.Period.entries) FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.title) })
            }
        }
        if (metric.scope == Stats.Scope.PROJECT) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = subject == null, onClick = { subject = null }, label = { Text("Сравнить все") })
                    for (p in projects) FilterChip(selected = subject == p, onClick = { subject = p }, label = { Text(p) })
                }
            }
        }
        if (chart.empty) {
            item { Text("Точек за ${period.title} нет: история копится с каждой синхронизацией.") }
        } else {
            item { LineChart(chart) }
            item { Text("Таблица значений", fontWeight = FontWeight.SemiBold) }
            item {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(chart.table.header.joinToString("  |  "), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    for (r in chart.table.rows.asReversed()) Text(r.joinToString("  |  "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
