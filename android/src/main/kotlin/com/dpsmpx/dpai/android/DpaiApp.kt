package com.dpsmpx.dpai.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.DashboardScreen
import com.dpsmpx.dpai.ui.RequestCard
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

/** Экран сессии в переходе между экранами (вкладки — 0…4). */
private const val SESSION_SCREEN = -1

/** Период шага контроллера: каждый шаг — вызовы агентов по SSH. */
private const val LIVE_STEP_MS = 3000L

/** Пауза ввода перед поиском (ТЗ, §75): поиск — когда человек перестал печатать. */
private const val SEARCH_PAUSE_MS = 350L

/**
 * Приложение: сводка и доска задач, экран сессии по нажатию (ТЗ, §38–§41).
 * Шаг системы идёт вне главного потока; главный поток только рисует
 * готовый снимок — так 20+ сессий не тормозят кадр (критерий этапа 11).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DpaiApp(model: DemoModel, controller: Controller, incoming: Incoming = Incoming(), onIncoming: () -> Unit = {}) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    // Контроллер остановлен кнопкой «Остановить»: экран его не поднимает сам (D-39).
    var stopped by remember { mutableStateOf(false) }
    // Сбой контроллера (журнал не открылся, target недоступен при запуске): показать, а не упасть —
    // иначе приложение падало бы при каждом запуске и target нельзя было бы исправить.
    var failure by remember { mutableStateOf<String?>(null) }
    // Глобальный поиск (ТЗ, §75) и задача, открытая из него, — лист задачи поверх любой вкладки.
    var searching by rememberSaveable { mutableStateOf(false) }
    var foundTask by remember { mutableStateOf<com.dpsmpx.dpai.core.ids.TaskId?>(null) }
    val selected by rememberUpdatedState(selectedId)
    val scope = rememberCoroutineScope()

    val context = androidx.compose.ui.platform.LocalContext.current
    val notifier = remember { Notifier(context.applicationContext) }
    // Ключ — и выбранная сессия: её экран грузится сразу, а не через такт (раньше — до 3 с крутилки).
    // Только пока окно видно: свёрнутое приложение не шагает демо и не копит снимки (ревью 8 октября 2026, A-31);
    // контроллер в это время шагает в службе.
    val visible = rememberVisible()
    LaunchedEffect(model, selectedId, visible) {
        while (visible) {
            val s = selected
            // Контроллер шагает в службе (D-36): экран только рисует; демо шагает здесь.
            val background = withContext(Dispatchers.IO) { model.hasLive() }
            if (background && !ControllerService.running) ControllerService.start(context)
            stopped = background && !ControllerService.running && withContext(Dispatchers.IO) { ControllerService.stoppedByUser(context) }
            val next = try {
                withContext(Dispatchers.IO) {
                    // Копия настроек — после изменения и раз в час (D-51); служба делает то же в своём такте.
                    runCatching { SettingsBackups.tick(context.applicationContext, Controller.get(context.filesDir, null)) }
                    if (background) model.view(s) else model.step(s)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = Redactor.STANDARD.redact("${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (next != null) {
                failure = null
                next.notifications.forEach(notifier::show)
                // Такт держит замок — снимок опубликован службой без экрана сессии: прежний экран остаётся,
                // а не сменяется крутилкой и обратно (экран «прыгал», проверка на Redmi Note 13).
                val keep = snapshot?.session?.takeIf { next.session == null && s != null && it.session.value == s }
                snapshot = if (keep != null) next.copy(session = keep) else next
                // Запросы демо и контроллера без службы — сверка здесь; со службой её делает служба (D-44).
                if (!ControllerService.running) withContext(Dispatchers.IO) { runCatching { RequestPresenter.sync(context.applicationContext, model, model.pending()) } }
            }
            delay(if (next?.live != false) LIVE_STEP_MS else STEP_MS)
        }
    }
    BackHandler(enabled = selectedId != null) { selectedId = null }
    // Ссылка из уведомления (ТЗ, §76): сессия — её экран; задача — экран её последней сессии, иначе доска;
    // запрос — вкладка «Запросы». Разбор строгий (DeepLink.parse): чужое ничего не открывает.
    var confirmUri by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(incoming) {
        incoming.link?.let { link ->
            val parsed = com.dpsmpx.dpai.notifications.DeepLink.parse(link).valueOrNull()
            when (parsed?.kind) {
                com.dpsmpx.dpai.notifications.DeepLink.Kind.SESSION -> selectedId = parsed.id
                com.dpsmpx.dpai.notifications.DeepLink.Kind.TASK -> {
                    val task = com.dpsmpx.dpai.core.ids.IdKinds.TASK.parse(parsed.id).valueOrNull()
                    val session = task?.let { withContext(Dispatchers.IO) { model.sessionOf(it) } }
                    if (session != null) selectedId = session.value else { selectedId = null; tab = 1 }
                }
                com.dpsmpx.dpai.notifications.DeepLink.Kind.REQUEST -> { selectedId = null; tab = 3 }
                else -> Unit
            }
        }
        incoming.confirm?.let { confirmUri = it }
        if (incoming != Incoming()) onIncoming()
    }
    confirmUri?.let { uri -> ConfirmDialog(model, snapshot?.requests.orEmpty(), uri) { confirmUri = null } }
    // Найденное ведёт к сущности: сессия — её экран, задача — её лист, слоты и проекты — вкладка Targets.
    if (searching) {
        SearchDialog(model, onDone = { searching = false }) { link ->
            searching = false
            when (link.kind) {
                com.dpsmpx.dpai.notifications.DeepLink.Kind.SESSION -> selectedId = link.id
                com.dpsmpx.dpai.notifications.DeepLink.Kind.TASK -> foundTask = com.dpsmpx.dpai.core.ids.IdKinds.TASK.parse(link.id).valueOrNull()
                com.dpsmpx.dpai.notifications.DeepLink.Kind.REQUEST -> { selectedId = null; tab = 3 }
                com.dpsmpx.dpai.notifications.DeepLink.Kind.EVENT -> Unit
                else -> { selectedId = null; tab = 2 }
            }
        }
    }
    foundTask?.let { t -> TaskSheet(model, t, onSession = { s -> foundTask = null; selectedId = s }) { foundTask = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedId != null) "Сессия" else if (tab == 2) "DP AI · targets" else if (tab == 3) "DP AI · запросы" else if (tab == 4) "DP AI · журнал" else "DP AI · ${snapshot?.mode ?: "…"}") },
                navigationIcon = {
                    if (selectedId != null) TextButton(onClick = { selectedId = null }) { Text("←") }
                },
                actions = { TextButton(onClick = { searching = true }) { Text("Найти") } },
            )
        },
        bottomBar = {
            // Панель — всегда: её исчезновение на экране сессии сдвигало всё содержимое.
            NavigationBar {
                fun go(t: Int) {
                    tab = t
                    selectedId = null
                }
                NavigationBarItem(selected = tab == 0 && selectedId == null, onClick = { go(0) }, icon = { Text("◉") }, label = { Text("Сводка") })
                NavigationBarItem(selected = tab == 1 && selectedId == null, onClick = { go(1) }, icon = { Text("▦") }, label = { Text("Задачи") })
                NavigationBarItem(selected = tab == 2 && selectedId == null, onClick = { go(2) }, icon = { Text("⇄") }, label = { Text("Targets") })
                val waiting = snapshot?.requests?.size ?: 0
                // Число — значком поверх: ширина пункта не меняется с числом запросов.
                NavigationBarItem(
                    selected = tab == 3 && selectedId == null, onClick = { go(3) },
                    icon = { BadgedBox(badge = { if (waiting > 0) Badge { Text("$waiting") } }) { Text("?") } },
                    label = { Text("Запросы") },
                )
                NavigationBarItem(selected = tab == 4 && selectedId == null, onClick = { go(4) }, icon = { Text("≡") }, label = { Text("Журнал") })
            }
        },
    ) { padding ->
        val s = snapshot
        Column(Modifier.padding(padding).fillMaxSize()) {
            failure?.let {
                Text(
                    "Контроллер не работает: $it. Проверьте targets на вкладке Targets.",
                    Modifier.fillMaxWidth().padding(12.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (stopped) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Контроллер остановлен: такты не идут, уведомлений нет.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { scope.launch(Dispatchers.IO) { ControllerService.resume(context) }; stopped = false }) { Text("Запустить") }
                }
            }
            // Смена вкладки или экрана — плавно, без скачка содержимого; кадр перехода рисует свой экран.
            Crossfade(targetState = if (selectedId != null) SESSION_SCREEN else tab, label = "screen", modifier = Modifier.weight(1f).fillMaxWidth()) { screen ->
                Box(Modifier.fillMaxSize()) {
                    when {
                        screen == 2 -> TargetsPane(controller, model)
                        screen == 3 -> RequestsPane(model, s)
                        screen == 4 -> LogPane(model)
                        s == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        screen == SESSION_SCREEN -> SessionPane(s.session, model) { foundTask = it }
                        screen == 0 -> Column(Modifier.fillMaxSize()) {
                            QuickRow(s.quick, s.live, s.requests.size, model, onTask = { foundTask = it }) { selectedId = null; tab = 3 }
                            Box(Modifier.weight(1f)) { DashboardPane(s.dashboard, model) { selectedId = it.value } }
                        }
                        else -> BoardPane(s.board, s.live, model, s.archived) { selectedId = it }
                    }
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

/**
 * Видно ли окно (активность между ON_START и ON_STOP). Цикл Compose сам не
 * останавливается, когда приложение свёрнуто или экран гаснет (Redmi Note 13:
 * пробы `/usage` шли при выключенном экране), — циклы экрана привязаны к этому признаку.
 */
@Composable
private fun rememberVisible(): Boolean {
    val lifecycle = (androidx.activity.compose.LocalActivity.current as? androidx.activity.ComponentActivity)?.lifecycle
    var visible by remember { mutableStateOf(lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) ?: true) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) visible = true
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) visible = false
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    return visible
}

@Composable
private fun DashboardPane(screen: DashboardScreen, model: DemoModel, onSession: (SessionId) -> Unit) {
    // Загрузка окон — из `/usage` раз в минуту, пока сводка видна (D-48).
    if (screen.limits.isNotEmpty() && rememberVisible()) {
        LaunchedEffect(Unit) {
            while (true) {
                withContext(Dispatchers.IO) { model.refreshUsage() }
                kotlinx.coroutines.delay(60_000)
            }
        }
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Состояние: ${screen.health}", color = toneColor(screen.healthTone), fontWeight = FontWeight.Bold)
            screen.reasons.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
        }
        items(screen.limits, key = { "limits:${it.subscription.value}" }) { LimitsCard(it, model) }
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
private fun SessionPane(screen: SessionScreen?, model: DemoModel, onTask: (com.dpsmpx.dpai.core.ids.TaskId) -> Unit) {
    if (screen == null) {
        Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        return
    }
    var context by remember(screen.session) { mutableStateOf(false) }
    screen.task?.let { t -> if (context) ContextDialog(model, t) { context = false } }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            screen.line?.let { SessionRow(it) } ?: Text(screen.session.value)
            // Расход сессии (D-42, п. 4): ходы, токены, стоимость.
            screen.usage?.let { Text("Расход: $it", style = MaterialTheme.typography.labelSmall) }
            // Быстрые действия сессии (ТЗ, §74): пауза, продолжение, повтор, проверка, изменения — в листе задачи; контекст хода.
            screen.task?.let { t ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = { onTask(t) }) { Text("Задача") }
                    androidx.compose.material3.OutlinedButton(onClick = { context = true }) { Text("Контекст") }
                }
            }
            // Живой вид хода (D-42): что модель делает сейчас.
            screen.live?.let { l ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Сейчас · ${l.ago}", style = MaterialTheme.typography.labelSmall)
                        l.tool?.let { t -> Text("$t${l.target?.let { " · $it" } ?: ""}", fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        l.text?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            if (screen.actions.isNotEmpty()) SessionActions(screen, model)
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
private fun BoardPane(board: TaskBoard, live: Boolean, model: DemoModel, archived: Int, onSession: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var showArchived by remember { mutableStateOf(model.showArchived) }
    var opened by remember { mutableStateOf<com.dpsmpx.dpai.core.ids.TaskId?>(null) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (live) {
            item(key = "new") { Button(onClick = { creating = true }) { Text("Новая задача") } }
        }
        // Архив (D-42): завершённые — с доски, история в журнале остаётся.
        item(key = "archive") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { model.command(com.dpsmpx.dpai.orchestrator.commands.Command.ArchiveFinished()) } }
                }) { Text("Убрать завершённые") }
                // Вся надпись с переключателем — одна цель нажатия (на Redmi нажимали надпись — ничего).
                Row(
                    Modifier.weight(1f).toggleable(value = showArchived, role = androidx.compose.ui.semantics.Role.Switch) {
                        showArchived = it
                        model.showArchived = it
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Показать архив ($archived)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.Switch(checked = showArchived, onCheckedChange = null)
                }
            }
        }
        board.columns.forEach { col ->
            item(key = "h:${col.title}") { Text("${col.title} (${col.cards.size})", style = MaterialTheme.typography.titleMedium) }
            items(col.cards, key = { it.id.value }) { c -> TaskRow(c, Modifier.clickable { opened = c.id }) }
        }
    }
    if (creating) NewTaskDialog(model) { creating = false }
    opened?.let { t -> TaskSheet(model, t, onSession = { s -> opened = null; onSession(s) }) { opened = null } }
}

/**
 * Глобальный поиск (ТЗ, §75): ищет после паузы ввода, а не на каждую букву;
 * найденное — по видам, касание ведёт к сущности ([onOpen]). Чего поиск не
 * видел (события во время такта, старые сегменты журнала), сказано над
 * списком (D-05).
 */
@Composable
private fun SearchDialog(model: DemoModel, onDone: () -> Unit, onOpen: (com.dpsmpx.dpai.notifications.DeepLink) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<com.dpsmpx.dpai.ui.GlobalSearch.Result?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.trim().length < com.dpsmpx.dpai.ui.GlobalSearch.MIN_QUERY) {
            result = null
            unavailable = false
            return@LaunchedEffect
        }
        delay(SEARCH_PAUSE_MS)
        busy = true
        try {
            val r = withContext(Dispatchers.IO) { model.search(query) }
            unavailable = r == null
            result = r
        } finally {
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Поиск") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Задача, сессия, ветка, коммит, код…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                when {
                    busy -> Text("Ищу…", style = MaterialTheme.typography.bodySmall)
                    unavailable -> Text("Контур ещё не запущен — искать негде.", style = MaterialTheme.typography.bodySmall)
                    result?.hits?.isEmpty() == true -> Text("Ничего не найдено.", style = MaterialTheme.typography.bodySmall)
                }
                val r = result
                if (r != null) {
                    LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(r.notes) { n -> Text(n, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        r.hits.groupBy { it.kind }.forEach { (kind, hits) ->
                            item(key = "h:${kind.name}") {
                                val more = r.more[kind]?.let { " и ещё $it" } ?: ""
                                Text("${kind.label} (${hits.size}$more)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                            }
                            items(hits) { h ->
                                // Системное событие ведёт к самому событию — экрана события нет, касание ничего не делает.
                                val link = h.link?.takeIf { it.kind != com.dpsmpx.dpai.notifications.DeepLink.Kind.EVENT }
                                Column(Modifier.fillMaxWidth().then(if (link != null) Modifier.clickable { onOpen(link) } else Modifier).padding(vertical = 2.dp)) {
                                    Text(h.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                    Text(h.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Закрыть") } },
    )
}

/** Новая задача контроллеру: название и цель — что Claude должен сделать (ТЗ, §21). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun NewTaskDialog(model: DemoModel, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var objective by remember { mutableStateOf("") }
    // Всё, что задаётся при создании (D-42, п. 1), — под «Дополнительно»: обычно хватает названия и цели.
    var more by remember { mutableStateOf(false) }
    var priority by remember { mutableStateOf(com.dpsmpx.dpai.tasks.Priority.NORMAL) }
    var taskClass by remember { mutableStateOf(com.dpsmpx.dpai.tasks.TaskClass.NORMAL_ENGINEERING) }
    var modelName by remember { mutableStateOf("") }
    var effort by remember { mutableStateOf("") }
    var budget by remember { mutableStateOf("") }
    var paths by remember { mutableStateOf("") }
    var deps by remember { mutableStateOf(setOf<com.dpsmpx.dpai.core.ids.TaskId>()) }
    val choices = remember { model.dependencyChoices() }
    // Ключ нажатия (D-42): повтор «Поставить» в этом диалоге — та же задача, а не вторая.
    val keys = remember { com.dpsmpx.dpai.core.ids.IdGenerator(com.dpsmpx.dpai.core.clock.Clock.SYSTEM, com.dpsmpx.dpai.core.clock.RandomSource.secure()) }
    // После настоящего отказа ключ новый: исправленная задача — новая команда, а не «уже исполнена: отказ».
    var key by remember { mutableStateOf(keys.next(com.dpsmpx.dpai.core.ids.IdKinds.OPERATION).value) }
    var error by remember { mutableStateOf<String?>(null) }
    // Второе нажатие, пока первое ставит задачу, не создаёт дубль (два хода Claude с одной целью).
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Новая задача") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(objective, { objective = it }, label = { Text("Что сделать") }, minLines = 3)
                TextButton(onClick = { more = !more }) { Text(if (more) "Дополнительно ▾" else "Дополнительно ▸") }
                if (more) {
                    Text("Приоритет", style = MaterialTheme.typography.labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (p in com.dpsmpx.dpai.tasks.Priority.entries) {
                            androidx.compose.material3.FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.name.lowercase()) })
                        }
                    }
                    Text("Класс — маршрут модели (D-40)", style = MaterialTheme.typography.labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in com.dpsmpx.dpai.tasks.TaskClass.entries) {
                            androidx.compose.material3.FilterChip(selected = taskClass == c, onClick = { taskClass = c }, label = { Text(c.name.lowercase()) })
                        }
                    }
                    OutlinedTextField(modelName, { modelName = it }, label = { Text("Модель (пусто — по классу)") }, singleLine = true)
                    OutlinedTextField(effort, { effort = it }, label = { Text("Усилие: low, medium, high (пусто — по классу)") }, singleLine = true)
                    OutlinedTextField(budget, { budget = it }, label = { Text("Предел стоимости хода, $ (пусто — без предела)") }, singleLine = true)
                    OutlinedTextField(paths, { paths = it }, label = { Text("Область: пути в репозитории, через запятую или с новой строки") }, minLines = 2)
                    if (choices.isNotEmpty()) {
                        Text("Зависит от (начнётся после них)", style = MaterialTheme.typography.labelSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (c in choices) {
                                androidx.compose.material3.FilterChip(
                                    selected = c.id in deps,
                                    onClick = { deps = if (c.id in deps) deps - c.id else deps + c.id },
                                    label = { Text(c.title.take(32)) },
                                )
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val form = com.dpsmpx.dpai.ui.NewTaskForm.command(title, objective, key, priority, taskClass, modelName, effort, budget, paths, deps)
                    val r = when (form) {
                        is Outcome.Failure -> form
                        is Outcome.Success -> withContext(Dispatchers.IO) { model.addTask(form.value) }
                    }
                    when (r) {
                        is Outcome.Failure -> {
                            val d = r.diagnostics.first()
                            // «В очереди» — команда применится в такте: окно закрывается, задача появится на доске.
                            if (d.code == com.dpsmpx.dpai.core.diagnostics.DiagCode.COMMAND_QUEUED) {
                                onDone()
                            } else {
                                error = d.message
                                key = keys.next(com.dpsmpx.dpai.core.ids.IdKinds.OPERATION).value
                            }
                        }
                        is Outcome.Success -> onDone()
                    }
                    busy = false
                }
            }) { Text(if (busy) "Ставлю…" else "Поставить") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Отмена") } },
    )
}

/**
 * Подтверждение опасного ответа из уведомления (D-44): текст — из ждущего
 * запроса оркестратора, а не из намерения; запрос уже закрыт — сказать.
 */
@Composable
private fun ConfirmDialog(model: DemoModel, requests: List<RequestCard>, uri: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val action = com.dpsmpx.dpai.notifications.RequestAction.parse(uri).valueOrNull()
    val card = requests.firstOrNull { it.id == action?.request }
    val button = card?.buttons?.firstOrNull { it.uri == uri }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (button != null) "Подтвердить: ${button.label}?" else "Запрос уже не ждёт ответа") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (card != null) {
                    Text(card.title, fontWeight = FontWeight.Bold)
                    Text(card.body, style = MaterialTheme.typography.bodySmall)
                    Text(card.origin, style = MaterialTheme.typography.labelSmall)
                } else {
                    Text("Он отвечен, истёк или снят.", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (card != null && button != null) {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        when (val r = withContext(Dispatchers.IO) { Answers.apply(context, card.id.value, uri, null, button.label, "app") }) {
                            is Outcome.Failure -> error = r.diagnostics.first().message
                            is Outcome.Success -> onDone()
                        }
                        busy = false
                    }
                }) { Text(if (busy) "Применяю…" else button.label, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Отмена") } },
    )
}

@Composable
private fun TaskRow(card: TaskCard, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().padding(start = (card.level * 16).dp)) {
        Column(Modifier.padding(10.dp)) {
            Row {
                Text(card.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(card.state + if (card.held) " · пауза" else "", color = toneColor(card.tone), style = MaterialTheme.typography.labelMedium)
            }
            Text(
                "приоритет ${card.priority}" + if (card.dependsOn.isEmpty()) "" else " · ждёт: ${card.dependsOn.joinToString()}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Действия с сессией (D-42, п. 3): остановить ход; запрос — новый ход той
 * же сессии Claude с моделью и усилием; свежая сессия с handoff.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SessionActions(screen: SessionScreen, model: DemoModel) {
    val scope = rememberCoroutineScope()
    val keys = remember { com.dpsmpx.dpai.core.ids.IdGenerator(com.dpsmpx.dpai.core.clock.Clock.SYSTEM, com.dpsmpx.dpai.core.clock.RandomSource.secure()) }
    var text by remember(screen.session) { mutableStateOf("") }
    var modelName by remember(screen.session) { mutableStateOf("") }
    var effort by remember(screen.session) { mutableStateOf("") }
    var status by remember(screen.session) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun send(c: (String) -> com.dpsmpx.dpai.orchestrator.commands.Command) {
        busy = true
        val key = keys.next(com.dpsmpx.dpai.core.ids.IdKinds.OPERATION).value
        scope.launch {
            status = when (val r = withContext(Dispatchers.IO) { model.command(c(key)) }) {
                is Outcome.Success -> "Принято".also { text = "" }
                is Outcome.Failure -> "Не принято: ${r.diagnostics.first().message}"
            }
            busy = false
        }
    }
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (com.dpsmpx.dpai.ui.SessionAction.STOP in screen.actions) {
            Button(enabled = !busy, onClick = { send { com.dpsmpx.dpai.orchestrator.commands.Command.StopSession(screen.session, it) } }) { Text("Остановить ход") }
        }
        if (com.dpsmpx.dpai.ui.SessionAction.SEND in screen.actions) {
            OutlinedTextField(text, { text = it }, label = { Text("Запрос в сессию") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (m in listOf("", "haiku", "sonnet", "opus")) {
                    androidx.compose.material3.FilterChip(selected = modelName == m, onClick = { modelName = m }, label = { Text(m.ifEmpty { "модель задачи" }) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (e in listOf("", "low", "medium", "high")) {
                    androidx.compose.material3.FilterChip(selected = effort == e, onClick = { effort = e }, label = { Text(e.ifEmpty { "усилие задачи" }) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && text.isNotBlank(), onClick = {
                    send { com.dpsmpx.dpai.orchestrator.commands.Command.SendToSession(screen.session, text, modelName.ifEmpty { null }, effort.ifEmpty { null }, key = it) }
                }) { Text("Отправить") }
                if (com.dpsmpx.dpai.ui.SessionAction.FRESH in screen.actions) {
                    TextButton(enabled = !busy, onClick = {
                        send { com.dpsmpx.dpai.orchestrator.commands.Command.SendToSession(screen.session, text.ifBlank { null }, modelName.ifEmpty { null }, effort.ifEmpty { null }, fresh = true, key = it) }
                    }) { Text("Свежая сессия") }
                }
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * Подписка на сводке (D-42, D-45): загрузка окон «5 ч» и «неделя» со
 * временем сброса и порог «у предела» — выше него несрочные задачи ждут
 * сброса окна, а контур экономит (D-40).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LimitsCard(l: com.dpsmpx.dpai.ui.SubscriptionLimits, model: DemoModel) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(l.label + if (l.near) " · у предела" else "", fontWeight = FontWeight.Bold, color = if (l.near) toneColor(Tone.WARNING) else Color.Unspecified)
            for (w in l.windows) {
                Text("${w.window}: ${w.percent}${w.reset?.let { " · сброс $it" } ?: ""}", color = toneColor(w.tone), style = MaterialTheme.typography.bodySmall)
            }
            var usage by remember { mutableStateOf(model.usageState()) }
            LaunchedEffect(Unit) {
                while (true) {
                    usage = model.usageState()
                    kotlinx.coroutines.delay(1000)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (refreshing, at) = usage
                Text(
                    when {
                        refreshing -> "Обновляю…"
                        at != null -> "Обновлено в ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(at.epochMillis))} · раз в минуту"
                        else -> "Из последнего хода"
                    },
                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                )
                TextButton(enabled = !refreshing, onClick = { scope.launch { withContext(Dispatchers.IO) { model.refreshUsage() } } }) { Text("Обновить") }
            }
            Text("Порог «у предела»: выше — несрочные задачи ждут сброса окна", style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (p in listOf(70, 80, 90, 95, 100)) {
                    androidx.compose.material3.FilterChip(selected = l.nearPercent == p, onClick = {
                        scope.launch {
                            status = when (val r = withContext(Dispatchers.IO) { model.command(com.dpsmpx.dpai.orchestrator.commands.Command.SetNearPercent(l.subscription, p)) }) {
                                is Outcome.Success -> null
                                is Outcome.Failure -> "Не принято: ${r.diagnostics.first().message}"
                            }
                        }
                    }, label = { Text("$p %") })
                }
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        }
    }
}
