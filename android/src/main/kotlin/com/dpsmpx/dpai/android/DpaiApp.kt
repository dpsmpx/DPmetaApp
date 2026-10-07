package com.dpsmpx.dpai.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.ui.DashboardScreen
import com.dpsmpx.dpai.ui.SessionLine
import com.dpsmpx.dpai.ui.SessionScreen
import com.dpsmpx.dpai.ui.TaskBoard
import com.dpsmpx.dpai.ui.TaskCard
import com.dpsmpx.dpai.ui.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Период шага демо и обновления экрана. */
private const val STEP_MS = 1000L

/**
 * Приложение: сводка и доска задач, экран сессии по нажатию (ТЗ, §38–§41).
 * Шаг системы идёт вне главного потока; главный поток только рисует
 * готовый снимок — так 20+ сессий не тормозят кадр (критерий этапа 11).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DpaiApp(model: DemoModel, controller: Controller) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    val selected by rememberUpdatedState(selectedId)

    LaunchedEffect(model) {
        while (true) {
            val s = selected
            snapshot = withContext(Dispatchers.Default) { model.step(s) }
            delay(STEP_MS)
        }
    }
    BackHandler(enabled = selectedId != null) { selectedId = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedId != null) "Сессия" else if (tab == 2) "DP AI · targets" else "DP AI · демо") },
                navigationIcon = {
                    if (selectedId != null) TextButton(onClick = { selectedId = null }) { Text("←") }
                },
            )
        },
        bottomBar = {
            if (selectedId == null) {
                NavigationBar {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("◉") }, label = { Text("Сводка") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("▦") }, label = { Text("Задачи") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("⇄") }, label = { Text("Targets") })
                }
            }
        },
    ) { padding ->
        val s = snapshot
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                selectedId == null && tab == 2 -> TargetsPane(controller)
                s == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                selectedId != null -> SessionPane(s.session)
                tab == 0 -> DashboardPane(s.dashboard) { selectedId = it.value }
                else -> BoardPane(s.board)
            }
        }
    }
}

@Composable
private fun toneColor(t: Tone): Color {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return when (t) {
        Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        Tone.ACTIVE -> MaterialTheme.colorScheme.primary
        Tone.WARNING -> if (dark) Color(0xFFFFB74D) else Color(0xFFB26A00)
        Tone.ERROR -> MaterialTheme.colorScheme.error
        Tone.DONE -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
    }
}

@Composable
private fun DashboardPane(screen: DashboardScreen, onSession: (SessionId) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Состояние: ${screen.health}", color = toneColor(screen.healthTone), fontWeight = FontWeight.Bold)
            screen.reasons.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
        }
        item { Counters(screen) }
        if (screen.attention.isNotEmpty()) {
            item { Text("Внимание", style = MaterialTheme.typography.titleMedium) }
            items(screen.attention) { Text("· $it", color = toneColor(Tone.WARNING), style = MaterialTheme.typography.bodySmall) }
        }
        item { Text("Сессии (${screen.sessions.size})", style = MaterialTheme.typography.titleMedium) }
        items(screen.sessions, key = { it.session.value }) { line ->
            SessionRow(line, Modifier.clickable { onSession(line.session) })
            HorizontalDivider()
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Counters(screen: DashboardScreen) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        screen.counters.forEach { c ->
            Card {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(c.value, style = MaterialTheme.typography.titleLarge, color = toneColor(c.tone))
                    Text(c.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SessionRow(line: SessionLine, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(line.task, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line.session.value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Column(Modifier.width(120.dp), horizontalAlignment = Alignment.End) {
            Text(line.state, color = toneColor(line.tone), style = MaterialTheme.typography.labelMedium)
            Text("тишина ${line.silent} · ${line.next}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun SessionPane(screen: SessionScreen?) {
    if (screen == null) {
        Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            screen.line?.let { SessionRow(it) } ?: Text(screen.session.value)
            HorizontalDivider()
            Text("Временная шкала", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        }
        items(screen.timeline) { e ->
            Row {
                Text(e.time, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(64.dp))
                Text(e.text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun BoardPane(board: TaskBoard) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        board.columns.forEach { col ->
            item(key = "h:${col.title}") { Text("${col.title} (${col.cards.size})", style = MaterialTheme.typography.titleMedium) }
            items(col.cards, key = { it.id.value }) { TaskRow(it) }
        }
    }
}

@Composable
private fun TaskRow(card: TaskCard) {
    Card(Modifier.fillMaxWidth().padding(start = (card.level * 16).dp)) {
        Column(Modifier.padding(10.dp)) {
            Row {
                Text(card.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(card.state, color = toneColor(card.tone), style = MaterialTheme.typography.labelMedium)
            }
            Text(
                "приоритет ${card.priority}" + if (card.dependsOn.isEmpty()) "" else " · ждёт: ${card.dependsOn.joinToString()}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
