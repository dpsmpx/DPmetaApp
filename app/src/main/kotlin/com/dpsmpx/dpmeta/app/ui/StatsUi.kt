package com.dpsmpx.dpmeta.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.dpsmpx.dpai.ui.AppPalette
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpmeta.app.screens.Stats
import com.dpsmpx.dpmeta.metrics.MetricPoint
import java.time.format.DateTimeFormatter

/**
 * Смысловые цвета графиков (ТЗ, N-08): один цвет на смысл во всех графиках,
 * пара для светлой и тёмной темы; контраст к фону — не ниже AA (N-07).
 * Сравнение проектов — нейтральная палитра рядов, без смысловых цветов.
 */
object ChartColors {
    // Смысловые цвета — общие для всего приложения (N-08, D-74): те же, что у состояний «AI».
    private fun role(h: Stats.Hue): AppPalette.Role = when (h) {
        Stats.Hue.NEUTRAL -> AppPalette.Role.NEUTRAL
        Stats.Hue.DECISIONS -> AppPalette.Role.DECISIONS
        Stats.Hue.VERIFIED -> AppPalette.Role.DONE
        Stats.Hue.ALERT -> AppPalette.Role.ERROR
        Stats.Hue.AI -> AppPalette.Role.AI
        Stats.Hue.HUMAN -> AppPalette.Role.HUMAN
    }
    // Ряды сравнения — свои оттенки, ни одного смыслового (N-08); проектов больше восьми — цвета
    // повторяются, проект различает легенда и подсказка касанием.
    private val seriesLight = listOf(0xFF1F6F8B, 0xFF9C4221, 0xFF4E6E1E, 0xFF8B2F6B, 0xFF3F3F9F, 0xFF6B5B1E, 0xFF2E6B5A, 0xFF7A3B3B).map(::Color)
    private val seriesDark = listOf(0xFF8FD3EE, 0xFFF5B08F, 0xFFC2E08A, 0xFFF0A6D6, 0xFFB4B4F5, 0xFFE8D58A, 0xFF9EDCC8, 0xFFF0B0B0).map(::Color)

    fun hue(h: Stats.Hue, isDark: Boolean): Color = Color(AppPalette.semantic(role(h), isDark))

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
                    detectTapGestures { o ->
                        if (days.isNotEmpty()) {
                            // Ближайший по дате день к точке касания.
                            val first = days.first().toEpochDay()
                            val span = (days.last().toEpochDay() - first).coerceAtLeast(1)
                            val at = first + (o.x / size.width) * span
                            picked = days.indices.minBy { kotlin.math.abs(days[it].toEpochDay() - at) }
                        }
                    }
                },
        ) {
            if (days.isEmpty()) return@Canvas
            val low = minOf(0L, values.min())
            val high = maxOf(values.max(), low + 1)
            // Ось — по датам, а не по номеру точки: пропуски во времени видны (временной ряд не сжимается).
            val first = days.first().toEpochDay()
            val span = (days.last().toEpochDay() - first).coerceAtLeast(1)
            fun x(i: Int) = if (days.size == 1) size.width / 2 else size.width * (days[i].toEpochDay() - first) / span.toFloat()
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

/**
 * Плитки сводки (UX-6; F-04, F-34): сетка в две колонки — число, изменение и
 * спарклайн, где есть история; касание — экран плитки, у плитки — подпись
 * для TalkBack.
 */
@Composable
fun SummaryTiles(tiles: List<Screens.Tile>, history: Map<String, Stats.Tile>, onOpen: (Screens.Goes) -> Unit) {
    val isDark = isSystemInDarkTheme()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in tiles.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in row) {
                    val h = t.metric?.let(history::get)
                    val change = h?.change.orEmpty()
                    Card(
                        onClick = { onOpen(t.goes) },
                        modifier = Modifier.weight(1f).semantics { contentDescription = "${t.title}: ${t.value}${if (change.isEmpty()) "" else ", $change"}; открыть «${t.goes.title}»" },
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text(t.title, style = MaterialTheme.typography.labelSmall)
                            Text(t.value, fontWeight = FontWeight.SemiBold)
                            if (change.isNotEmpty()) Text(change, style = MaterialTheme.typography.labelSmall)
                            h?.let { Sparkline(it.spark, ChartColors.hue(it.metric.hue, isDark)) }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Статистика (F-33, F-34): метрика, срок и чей ряд; [project] — открыта из
 * карточки, `null` — сравнение всех проектов. Рядом с графиком — таблица (N-07).
 */
@Composable
fun StatsScreen(
    history: List<MetricPoint>,
    projects: List<String>,
    project: String?,
    now: Timestamp,
    /** `null` — экран пункта «Статистика» (D-73): назад некуда. */
    onBack: (() -> Unit)?,
    status: String = "",
    onExport: ((json: Boolean, period: Stats.Period, share: Boolean) -> Unit)? = null,
) {
    // Выбор — rememberSaveable: поворот и смерть процесса не сбрасывают метрику и срок (UX-3).
    var metricId by rememberSaveable { mutableStateOf(Stats.CATALOG.first().id) }
    val metric = Stats.metric(metricId) ?: Stats.CATALOG.first()
    var period by rememberSaveable { mutableStateOf(Stats.Period.MONTH) }
    var subject by rememberSaveable { mutableStateOf(project) }
    var json by rememberSaveable { mutableStateOf(false) }
    val chart = remember(history, metric, period, subject) {
        Stats.chart(history, metric, period, subject?.let(::listOf) ?: projects, now)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        onBack?.let { b -> item { TextButton(onClick = b) { Text("← Назад") } } }
        item { Text("Статистика", style = MaterialTheme.typography.titleMedium) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in Stats.CATALOG) FilterChip(selected = metric == m, onClick = { metricId = m.id }, label = { Text(m.title) })
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
        if (onExport != null) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !json, onClick = { json = false }, label = { Text("CSV") })
                    FilterChip(selected = json, onClick = { json = true }, label = { Text("JSON") })
                    TextButton(onClick = { onExport(json, period, false) }) { Text("Сохранить") }
                    TextButton(onClick = { onExport(json, period, true) }) { Text("Поделиться") }
                }
            }
            if (status.isNotEmpty()) item { Text(status, style = MaterialTheme.typography.bodySmall) }
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
