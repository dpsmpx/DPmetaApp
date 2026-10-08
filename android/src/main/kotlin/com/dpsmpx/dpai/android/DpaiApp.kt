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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.DashboardScreen
import com.dpsmpx.dpai.ui.SessionLine
import com.dpsmpx.dpai.ui.SessionScreen
import com.dpsmpx.dpai.ui.TaskBoard
import com.dpsmpx.dpai.ui.TaskCard
import com.dpsmpx.dpai.ui.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Период шага демо и обновления экрана. */
private const val STEP_MS = 1000L

/** Период шага контроллера: каждый шаг — вызовы агентов по SSH. */
private const val LIVE_STEP_MS = 3000L

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
    // Контроллер остановлен кнопкой «Остановить»: экран его не поднимает сам (D-39).
    var stopped by remember { mutableStateOf(false) }
    val selected by rememberUpdatedState(selectedId)
    val scope = rememberCoroutineScope()

    val context = androidx.compose.ui.platform.LocalContext.current
    val notifier = remember { Notifier(context.applicationContext) }
    LaunchedEffect(model) {
        while (true) {
            val s = selected
            // Контроллер шагает в службе (D-36): экран только рисует; демо шагает здесь.
            val background = withContext(Dispatchers.IO) { model.hasLive() }
            if (background && !ControllerService.running) ControllerService.start(context)
            stopped = background && !ControllerService.running && withContext(Dispatchers.IO) { ControllerService.stoppedByUser(context) }
            val next = withContext(Dispatchers.IO) { if (background) model.view(s) else model.step(s) }
            next.notifications.forEach(notifier::show)
            snapshot = next
            delay(if (next.live) LIVE_STEP_MS else STEP_MS)
        }
    }
    BackHandler(enabled = selectedId != null) { selectedId = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedId != null) "Сессия" else if (tab == 2) "DP AI · targets" else "DP AI · ${snapshot?.mode ?: "…"}") },
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
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (stopped) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Контроллер остановлен: такты не идут, уведомлений нет.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { scope.launch(Dispatchers.IO) { ControllerService.resume(context) }; stopped = false }) { Text("Запустить") }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    selectedId == null && tab == 2 -> TargetsPane(controller, model)
                    s == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    selectedId != null -> SessionPane(s.session)
                    tab == 0 -> DashboardPane(s.dashboard) { selectedId = it.value }
                    else -> BoardPane(s.board, s.live, model)
                }
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
private fun BoardPane(board: TaskBoard, live: Boolean, model: DemoModel) {
    var creating by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (live) {
            item(key = "new") { Button(onClick = { creating = true }) { Text("Новая задача") } }
        }
        board.columns.forEach { col ->
            item(key = "h:${col.title}") { Text("${col.title} (${col.cards.size})", style = MaterialTheme.typography.titleMedium) }
            items(col.cards, key = { it.id.value }) { TaskRow(it) }
        }
    }
    if (creating) NewTaskDialog(model) { creating = false }
}

/** Новая задача контроллеру: название и цель — что Claude должен сделать (ТЗ, §21). */
@Composable
private fun NewTaskDialog(model: DemoModel, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var objective by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // Второе нажатие, пока первое ставит задачу, не создаёт дубль (два хода Claude с одной целью).
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Новая задача") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(objective, { objective = it }, label = { Text("Что сделать") }, minLines = 3)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    when (val r = withContext(Dispatchers.IO) { model.addTask(title, objective) }) {
                        is Outcome.Failure -> error = r.diagnostics.first().message
                        is Outcome.Success -> onDone()
                    }
                    busy = false
                }
            }) { Text(if (busy) "Ставлю…" else "Поставить") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Отмена") } },
    )
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
