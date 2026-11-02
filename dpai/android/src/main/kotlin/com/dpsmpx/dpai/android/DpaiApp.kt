package com.dpsmpx.dpai.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.ui.DashboardScreen
import com.dpsmpx.dpai.ui.RequestCard
import com.dpsmpx.dpai.ui.SessionScreen
import com.dpsmpx.dpai.ui.TaskBoard
import com.dpsmpx.dpai.ui.TaskCard
import com.dpsmpx.dpai.ui.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    /** Журнал испорчен в середине (D-24): что именно — до решения человека контур не строится. */
    var corrupted by remember { mutableStateOf<String?>(null) }
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
            } catch (e: com.dpsmpx.dpai.storage.journal.JournalSegmentUnreadable) {
                failure = "старая часть журнала контроллера не читается (${e.message}): её записи подтверждены и молча не пропускаются — " +
                    "контроллер не запустится, пока файл не восстановлен (папка live в данных приложения)"
                null
            } catch (e: com.dpsmpx.dpai.ui.JournalNeedsConfirmation) {
                corrupted = e.message
                failure = "журнал контроллера испорчен — нужно ваше решение"
                null
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
                // Подписки и их окна — на сводке (LimitsCard); слоты и проект — на вкладке Targets.
                com.dpsmpx.dpai.notifications.DeepLink.Kind.SUBSCRIPTION -> { selectedId = null; tab = 0 }
                else -> { selectedId = null; tab = 2 }
            }
        }
    }
    foundTask?.let { t -> TaskSheet(model, t, onSession = { s -> foundTask = null; selectedId = s }) { foundTask = null } }

    // Окно сессии: чат или временная шкала (D-59) — переключатель в заголовке, а не в окне (D-61).
    var sessionEvents by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    // Лист задачи — кнопкой из чата (D-62): вывод модели — в чате, настройки и действия задачи — в листе.
    var taskSheet by remember { mutableStateOf<com.dpsmpx.dpai.core.ids.TaskId?>(null) }
    taskSheet?.let { t -> TaskSheet(model, t, onSession = { s -> taskSheet = null; selectedId = s }) { taskSheet = null } }
    val sessionScreen = if (selectedId != null) snapshot?.session else null
    val renameTask = sessionScreen?.task
    if (renaming && renameTask != null) {
        RenameSessionDialog(SessionNames.name(context, renameTask) ?: sessionScreen.title.orEmpty()) { name ->
            name?.let { SessionNames.set(context, renameTask, it) }
            renaming = false
        }
    }
    Scaffold(
        topBar = {
            // Узкая строка (D-61): больше места чату; в окне сессии — кружок состояния и имя (нажатие — переименовать).
            androidx.compose.material3.Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(48.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectedId != null) {
                        TextButton(onClick = { selectedId = null }) { Text("←") }
                        sessionScreen?.let { sc -> StatusDot(sc.status) }
                        Text(
                            sessionScreen?.let { sc -> SessionNames.name(context, sc.task) ?: sc.title } ?: "Сессия",
                            Modifier.weight(1f).padding(start = 8.dp).clickable(enabled = sessionScreen?.task != null) { renaming = true },
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        sessionScreen?.task?.let { t -> TextButton(onClick = { taskSheet = t }) { Text("⚙", style = MaterialTheme.typography.titleMedium) } }
                        TextButton(onClick = { sessionEvents = !sessionEvents }) { Text(if (sessionEvents) "Чат" else "События") }
                    } else {
                        Text(
                            if (tab == 2) "DP AI · targets" else if (tab == 3) "DP AI · запросы" else if (tab == 4) "DP AI · журнал" else "DP AI · ${snapshot?.mode ?: "…"}",
                            Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium,
                        )
                        // Глобальный поиск (ТЗ, §75) — с любой вкладки.
                        TextButton(onClick = { searching = true }) { Text("Найти") }
                    }
                }
            }
        },
        bottomBar = {
            // Панель — всегда: её исчезновение на экране сессии сдвигало всё содержимое. Без подписей, узкая (D-61).
            androidx.compose.material3.Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    fun go(t: Int) {
                        tab = t
                        selectedId = null
                    }
                    val waiting = snapshot?.requests?.size ?: 0
                    for ((i, icon) in listOf("◉", "▦", "⇄", "?", "≡").withIndex()) {
                        val selected = tab == i && selectedId == null
                        Box(
                            Modifier.weight(1f).fillMaxSize().clickable { go(i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            // Число — значком поверх: ширина пункта не меняется с числом запросов.
                            if (i == 3) {
                                BadgedBox(badge = { if (waiting > 0) Badge { Text("$waiting") } }) { Text(icon, color = color, style = MaterialTheme.typography.titleMedium) }
                            } else {
                                Text(icon, color = color, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        val s = snapshot
        // Клавиатура (D-64): содержимое — над ней; отступ панели вкладок уже учтён (consumeWindowInsets), не вдвое.
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            corrupted?.let { what ->
                // D-24: карантин хвоста — только явным решением; опасное — красным и вторым шагом.
                var armed by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        "Журнал контроллера испорчен: $what. Записи после испорченной — решения, ответы, итоги задач — " +
                            "можно отрезать в карантин (файл сохранится рядом), тогда контроллер откатится к последней целой записи. " +
                            "Без этого контроллер не запустится; журнал — файл live/live.journal в данных приложения.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        if (!armed) {
                            armed = true
                        } else {
                            scope.launch(Dispatchers.IO) { controller.confirmJournalQuarantine() }
                            corrupted = null
                            failure = null
                        }
                    }) { Text(if (armed) "Точно отрезать хвост в карантин" else "Отрезать хвост в карантин", color = MaterialTheme.colorScheme.error) }
                }
            }
            failure?.takeIf { corrupted == null }?.let {
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
                        screen == SESSION_SCREEN -> SessionPane(s.session, model, sessionEvents)
                        screen == 0 -> Column(Modifier.fillMaxSize()) {
                            QuickRow(s.quick, s.live, s.requests.size, model, onTask = { foundTask = it }) { selectedId = null; tab = 3 }
                            Box(Modifier.weight(1f)) { DashboardPane(s.dashboard, model) }
                        }
                        else -> BoardPane(s.board, s.live, model, s.archived) { selectedId = it }
                    }
                }
            }
        }
    }
}

@Composable
internal fun toneColor(t: Tone): Color {
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
private fun DashboardPane(screen: DashboardScreen, model: DemoModel) {
    // Загрузка окон — из `/usage` раз в минуту, пока сводка видна (D-48).
    if (screen.limits.isNotEmpty() && rememberVisible()) {
        LaunchedEffect(Unit) {
            while (true) {
                withContext(Dispatchers.IO) { model.refreshUsage() }
                kotlinx.coroutines.delay(60_000)
            }
        }
    }
    // Сводка — подписки (D-65): лимиты, порог и передача задач сверху, затем состояние, счётчики и «Внимание»;
    // задачи и их сессии — во вкладке «Задачи», оттуда — сразу чат (D-62).
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (screen.limits.isEmpty()) item { Text("Подписок пока нет: они появятся после первой пробы входа слотов.", style = MaterialTheme.typography.bodySmall) }
        items(screen.limits, key = { "limits:${it.subscription.value}" }) { LimitsCard(it, model) }
        item {
            Text("Состояние: ${screen.health}", color = toneColor(screen.healthTone), fontWeight = FontWeight.Bold)
            screen.reasons.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
        }
        item { Counters(screen) }
        if (screen.attention.isNotEmpty()) {
            item { Text("Внимание", style = MaterialTheme.typography.titleMedium) }
            items(screen.attention) { Text("· $it", color = toneColor(Tone.WARNING), style = MaterialTheme.typography.bodySmall) }
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
private fun SessionPane(screen: SessionScreen?, model: DemoModel, events: Boolean) {
    if (screen == null) {
        Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        return
    }
    // Чат сессии (D-59): все сообщения задачи в одной ленте, ввод внизу, управление ходом; имя и состояние — в заголовке (D-61).
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val shown = if (events) screen.timeline.size else screen.chat.size
    LaunchedEffect(screen.session, shown, events) {
        if (shown > 0) list.animateScrollToItem(shown)
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val inputMax = maxHeight - 56.dp
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Расход и строка состояния — мелко в начале ленты, а не над ней (D-61).
            item {
                // Сессия ревью (D-73) — с ролью: она только читает, её не продолжают запросом.
                screen.line?.let { l -> Text(listOfNotNull(l.review, l.state, l.next).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                screen.usage?.let { Text("Расход: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                SessionExtras(screen, model)
            }
            if (events) {
                items(screen.timeline) { e ->
                    Row {
                        Text(e.time, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(64.dp))
                        Text(e.text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                if (screen.chat.isEmpty()) item { Text("Сообщений пока нет: лента пишется с этой версии приложения.", style = MaterialTheme.typography.bodySmall) }
                items(screen.chat) { line -> ChatBubble(line) }
            }
            // Ход идёт (D-59): одной строкой — медленно крутящийся кружок и «работает N мин»; без инструментов и команд.
            if (screen.live != null || screen.workingFor != null) {
                item { WorkingLine(screen.workingFor) }
            }
        }
        HorizontalDivider()
        ChatInput(screen, model, inputMax)
    }
    }
}

/**
 * Мелкие кнопки в начале ленты (D-61 — место чату): что видел Claude в
 * последнем ходе (окно «Контекст», ТЗ, §74) и «Перенести» (D-77): ход
 * останавливается с handoff, следующая сессия — на другом слоте.
 */
@Composable
private fun SessionExtras(screen: SessionScreen, model: DemoModel) {
    val scope = rememberCoroutineScope()
    val keys = remember { com.dpsmpx.dpai.core.ids.IdGenerator(com.dpsmpx.dpai.core.clock.Clock.SYSTEM, com.dpsmpx.dpai.core.clock.RandomSource.secure()) }
    var context by remember(screen.session) { mutableStateOf(false) }
    var status by remember(screen.session) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val reassign = com.dpsmpx.dpai.ui.SessionAction.REASSIGN in screen.actions
    screen.task?.let { t -> if (context) ContextDialog(model, t) { context = false } }
    if (screen.task == null && !reassign) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (screen.task != null) TextButton(onClick = { context = true }) { Text("Контекст", style = MaterialTheme.typography.labelSmall) }
        if (reassign) {
            TextButton(enabled = !busy, onClick = {
                busy = true
                val key = keys.next(com.dpsmpx.dpai.core.ids.IdKinds.OPERATION).value
                scope.launch {
                    status = when (val r = withContext(Dispatchers.IO) { model.command(com.dpsmpx.dpai.orchestrator.commands.Command.Reassign(screen.session, key)) }) {
                        is Outcome.Success -> null
                        is Outcome.Failure -> "Не принято: ${r.diagnostics.first().message}"
                    }
                    busy = false
                }
            }) { Text(com.dpsmpx.dpai.ui.SessionAction.REASSIGN.label, style = MaterialTheme.typography.labelSmall) }
        }
    }
    status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
}

/** «работает N мин» с медленно крутящимся кружком — ровно одна строка. */
@Composable
private fun WorkingLine(duration: String?) {
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(2400, easing = androidx.compose.animation.core.LinearEasing)),
        label = "angle",
    )
    Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CircularProgressIndicator(
            progress = { 0.3f }, modifier = Modifier.size(12.dp).graphicsLayer { rotationZ = angle }, strokeWidth = 2.dp,
        )
        Text("работает" + (duration?.let { " $it" } ?: ""), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Состояние сессии кружком (D-61): идёт — пульсирует цветом акцента,
 * готово — зелёный, ошибка — красный, ждёт — серый.
 */
@Composable
private fun StatusDot(status: com.dpsmpx.dpai.ui.SessionStatus) {
    val base = when (status) {
        com.dpsmpx.dpai.ui.SessionStatus.RUNNING -> MaterialTheme.colorScheme.primary
        com.dpsmpx.dpai.ui.SessionStatus.DONE -> toneColor(Tone.DONE)
        com.dpsmpx.dpai.ui.SessionStatus.ERROR -> MaterialTheme.colorScheme.error
        com.dpsmpx.dpai.ui.SessionStatus.IDLE -> MaterialTheme.colorScheme.outline
    }
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "dot")
    val scale by pulse.animateFloat(
        initialValue = 0.7f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "scale",
    )
    val k = if (status == com.dpsmpx.dpai.ui.SessionStatus.RUNNING) scale else 1f
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(12.dp * k).background(base.copy(alpha = if (status == com.dpsmpx.dpai.ui.SessionStatus.RUNNING) 0.5f + 0.5f * k else 1f), androidx.compose.foundation.shape.CircleShape))
    }
}

/** Имя сессии (D-61): пусто — снова название задачи. */
@Composable
private fun RenameSessionDialog(current: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Имя сессии") },
        text = { OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text("Имя (пусто — название задачи)") }) },
        confirmButton = { TextButton(onClick = { onDone(name) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Отмена") } },
    )
}

/** Пузырь ленты (D-59): запрос человека — справа, текст модели — слева, рассуждение — свёрнуто, событие — по центру. */
@Composable
private fun ChatBubble(line: com.dpsmpx.dpai.ui.ChatLine) {
    val faded = if (line.here) 1f else 0.6f
    when {
        line.event -> Text(
            "${line.time} · ${line.text}",
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = faded),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        line.thinking -> {
            var open by remember(line) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 4.dp, end = 48.dp)) {
                Text("Рассуждение · ${line.time}" + if (open) "" else " ▸", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = faded))
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = faded),
                    maxLines = if (open) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.mine) Arrangement.End else Arrangement.Start) {
            Card(
                Modifier.padding(start = if (line.mine) 48.dp else 0.dp, end = if (line.mine) 0.dp else 24.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = (if (line.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant).copy(alpha = faded),
                ),
            ) {
                Column(Modifier.padding(10.dp)) {
                    // Ответ модели — с оформлением Markdown (заголовки, код, списки), как было в листе задачи (D-62).
                    val code = MaterialTheme.colorScheme.surface
                    val link = MaterialTheme.colorScheme.primary
                    val rendered = remember(line.text) { if (line.mine) null else com.dpsmpx.dpai.ui.Markdown.render(line.text) }
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        if (rendered != null) Text(annotated(rendered, code, link), style = MaterialTheme.typography.bodyMedium) else Text(line.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(line.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Ввод внизу чата (D-59, D-60): сообщение в сессию — сразу, если ход не
 * идёт; во время хода — в очередь задачи: ход, рассуждения и фоновые
 * процессы сессии не прерываются, сообщение уходит в ту же сессию после
 * хода. Остановка, свежая сессия, модель и усилие — здесь же.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChatInput(screen: SessionScreen, model: DemoModel, maxHeight: androidx.compose.ui.unit.Dp) {
    val scope = rememberCoroutineScope()
    val keys = remember { com.dpsmpx.dpai.core.ids.IdGenerator(com.dpsmpx.dpai.core.clock.Clock.SYSTEM, com.dpsmpx.dpai.core.clock.RandomSource.secure()) }
    var text by remember(screen.session) { mutableStateOf("") }
    var modelName by remember(screen.session) { mutableStateOf("") }
    var effort by remember(screen.session) { mutableStateOf("") }
    var params by remember(screen.session) { mutableStateOf(false) }
    var status by remember(screen.session) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val canSend = com.dpsmpx.dpai.ui.SessionAction.SEND in screen.actions
    val running = com.dpsmpx.dpai.ui.SessionAction.STOP in screen.actions
    val fresh = com.dpsmpx.dpai.ui.SessionAction.FRESH in screen.actions
    fun send(c: (String) -> com.dpsmpx.dpai.orchestrator.commands.Command, after: () -> Unit = {}) {
        busy = true
        val key = keys.next(com.dpsmpx.dpai.core.ids.IdKinds.OPERATION).value
        scope.launch {
            status = when (val r = withContext(Dispatchers.IO) { model.command(c(key)) }) {
                is Outcome.Success -> null.also { after() }
                is Outcome.Failure -> "Не принято: ${r.diagnostics.first().message}"
            }
            busy = false
        }
    }
    val typing = text.isNotEmpty()
    Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        // Пусто — одна строка на всю ширину и рядом «стоп» (или «свежая сессия»); начали писать — поле растёт
        // до высоты окна, под ним «Отправить» и «Модель и усилие» (D-61). Ход сообщением не прерывается (D-60).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                text, { text = it }, Modifier.weight(1f).heightIn(max = maxHeight),
                placeholder = { Text(if (canSend) "Сообщение Claude" else "Задача проверяется или завершена") },
                enabled = canSend, singleLine = !typing, maxLines = if (typing) Int.MAX_VALUE else 1,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            if (!typing) {
                when {
                    running -> androidx.compose.material3.FilledTonalIconButton(enabled = !busy, onClick = {
                        send({ k -> com.dpsmpx.dpai.orchestrator.commands.Command.StopSession(screen.session, k) })
                    }) { Text("■") }
                    fresh -> androidx.compose.material3.FilledTonalIconButton(enabled = !busy, onClick = {
                        send({ k -> com.dpsmpx.dpai.orchestrator.commands.Command.SendToSession(screen.session, null, modelName.ifEmpty { null }, effort.ifEmpty { null }, fresh = true, key = k) })
                    }) { Text("↻") }
                }
            }
        }
        if (typing) {
            if (params) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (m in listOf("", "haiku", "sonnet", "opus")) {
                        androidx.compose.material3.FilterChip(selected = modelName == m, onClick = { modelName = m }, label = { Text(m.ifEmpty { "модель задачи" }) })
                    }
                    for (e in listOf("", "low", "medium", "high")) {
                        androidx.compose.material3.FilterChip(selected = effort == e, onClick = { effort = e }, label = { Text(e.ifEmpty { "усилие задачи" }) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(enabled = canSend && !busy && text.isNotBlank(), onClick = {
                    val t = text
                    send({ k -> com.dpsmpx.dpai.orchestrator.commands.Command.SendToSession(screen.session, t, modelName.ifEmpty { null }, effort.ifEmpty { null }, key = k) }) { text = "" }
                }) { Text("Отправить") }
                TextButton(onClick = { params = !params }) { Text(if (params) "Скрыть параметры" else "Модель и усилие") }
                if (fresh) TextButton(enabled = !busy, onClick = {
                    val t = text
                    send({ k -> com.dpsmpx.dpai.orchestrator.commands.Command.SendToSession(screen.session, t, modelName.ifEmpty { null }, effort.ifEmpty { null }, fresh = true, key = k) }) { text = "" }
                }) { Text("Свежая") }
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
            // Нажатие — сразу чат задачи (D-62); сессий ещё не было — лист задачи.
            items(col.cards, key = { it.id.value }) { c ->
                TaskRow(c, Modifier.clickable { model.sessionOf(c.id)?.let { s -> onSession(s.value) } ?: run { opened = c.id } })
            }
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
            busy = false
            return@LaunchedEffect
        }
        delay(SEARCH_PAUSE_MS)
        busy = true
        try {
            val r = withContext(Dispatchers.IO) { model.search(query) }
            unavailable = r == null
            result = r
        } finally {
            // Отменённый поиск (запрос изменился) не снимает «Ищу…» у следующего — его снимет тот сам.
            if (coroutineContext.isActive) busy = false
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

/**
 * Новая задача контроллеру: название и цель — что Claude должен сделать
 * (ТЗ, §21); под «Дополнительно» — приоритет, класс, модель, усилие,
 * предел хода, ревью (D-73), область (ТЗ, §31) и зависимости (D-42, п. 1).
 */
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
    var reviews by remember { mutableStateOf(setOf<com.dpsmpx.dpai.tasks.ReviewRole>()) }
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
                            androidx.compose.material3.FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(Words.priority(p.name)) })
                        }
                    }
                    Text("Класс — маршрут модели (D-40)", style = MaterialTheme.typography.labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in com.dpsmpx.dpai.tasks.TaskClass.entries) {
                            androidx.compose.material3.FilterChip(selected = taskClass == c, onClick = { taskClass = c }, label = { Text(Words.taskClass(c.name)) })
                        }
                    }
                    OutlinedTextField(modelName, { modelName = it }, label = { Text("Модель (пусто — по классу)") }, singleLine = true)
                    OutlinedTextField(effort, { effort = it }, label = { Text("Усилие: low, medium, high (пусто — по классу)") }, singleLine = true)
                    OutlinedTextField(budget, { budget = it }, label = { Text("Предел стоимости хода, $ (пусто — без предела)") }, singleLine = true)
                    ReviewChips(reviews) { reviews = it }
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
                    val form = com.dpsmpx.dpai.ui.NewTaskForm.command(title, objective, key, priority, taskClass, modelName, effort, budget, paths, deps, reviews)
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
                        val r = withContext(Dispatchers.IO) { Answers.apply(context, card.id.value, uri, null, button.label, "app") }
                        // Принято в журнал (D-94) — тоже готово: итог придёт уведомлением.
                        if (r is Outcome.Success || Answers.queued(r)) onDone() else error = (r as Outcome.Failure).diagnostics.first().message
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
                // Имя, заданное человеком в окне сессии (D-61), — и на карточке.
                Text(SessionNames.name(androidx.compose.ui.platform.LocalContext.current, card.id) ?: card.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Слово, а не идентификатор: `COMPLETION_UNVERIFIED` человеку ничего не говорит (D-88, U-3).
                Text(Words.task(card.state) + if (card.held) " · пауза" else "", color = toneColor(card.tone), style = MaterialTheme.typography.labelMedium)
            }
            Text(
                "приоритет ${Words.priority(card.priority)}" + if (card.dependsOn.isEmpty()) "" else " · ждёт: ${card.dependsOn.joinToString()}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
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
            // Намерения (D-57): выбор виден сразу и применится, когда контур освободится.
            var tick by remember { mutableStateOf(0) }
            val wantNear = remember(tick, l) { model.desired(com.dpsmpx.dpai.orchestrator.commands.Command.SetNearPercent(l.subscription, l.nearPercent)) as? com.dpsmpx.dpai.orchestrator.commands.Command.SetNearPercent }
            val wantRotation = remember(tick, l) { model.desired(com.dpsmpx.dpai.orchestrator.commands.Command.SetRotation(l.subscription, l.rotation)) as? com.dpsmpx.dpai.orchestrator.commands.Command.SetRotation }
            Text("Порог «у предела»: выше — несрочные задачи ждут сброса окна, чужие задачи сюда не передаются" + if (wantNear != null) " · применится" else "", style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (p in listOf(50, 60, 70, 80, 90, 95, 100)) {
                    androidx.compose.material3.FilterChip(selected = (wantNear?.percent ?: l.nearPercent) == p, onClick = {
                        scope.launch {
                            status = when (val r = withContext(Dispatchers.IO) { model.command(com.dpsmpx.dpai.orchestrator.commands.Command.SetNearPercent(l.subscription, p)) }) {
                                is Outcome.Success -> null
                                is Outcome.Failure -> "Не принято: ${r.diagnostics.first().message}"
                            }
                            tick++
                        }
                    }, label = { Text("$p %") })
                }
            }
            // Передача задач при пределе (D-56): подписка берёт задачи других подписок, упёршихся в 5 ч или неделю.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Брать задачи подписок у предела (с handoff)" + if (wantRotation != null) " · применится" else "", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                androidx.compose.material3.Switch(checked = wantRotation?.allowed ?: l.rotation, onCheckedChange = { on ->
                    scope.launch {
                        status = when (val r = withContext(Dispatchers.IO) { model.command(com.dpsmpx.dpai.orchestrator.commands.Command.SetRotation(l.subscription, on)) }) {
                            is Outcome.Success -> null
                            is Outcome.Failure -> "Не принято: ${r.diagnostics.first().message}"
                        }
                        tick++
                    }
                })
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        }
    }
}
