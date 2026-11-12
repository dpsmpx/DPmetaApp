package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.ui.AI_SETTINGS_TITLE
import com.dpsmpx.dpai.ui.AiTab
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

/** Экран сессии в переходе между экранами (вкладки — номера `AiTab`, от 0). */
private const val SESSION_SCREEN = -1

/** Номер экрана «Настроек AI» в переходах (D-112): не вкладка. */
private const val SETTINGS_SCREEN = -2

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
    var tab by rememberSaveable { mutableIntStateOf(AiTab.SUMMARY.ordinal) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // «Настройки AI» — экран поверх вкладок (D-112): вход — значком в заголовке, выход — «←» и «Назад».
    var settings by rememberSaveable { mutableStateOf(false) }
    // Под-экран «Настроек AI» (инструкция, Linux, журнал) — здесь: «Журнал» у сбоя открывает именно журнал.
    var settingsSub by rememberSaveable { mutableStateOf<String?>(null) }
    /** Открыть вкладку: экран сессии и «Настройки AI» закрываются. */
    fun openTab(t: AiTab) {
        tab = t.ordinal
        selectedId = null
        settings = false
    }
    // Последняя вкладка раздела AI — пункт «AI» левой панели возвращает к ней из IDE (DPmetaApp:D-112).
    var lastAi by rememberSaveable { mutableIntStateOf(AiTab.SUMMARY.ordinal) }
    if (tab != AiTab.FILES.ordinal && lastAi != tab) lastAi = tab
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
                    runCatching { SettingsBackups.tick(context.applicationContext, Controller.get(context.filesDir)) }
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
                // Словами (A-17); имя класса — в журнале приложения.
                failure = Redactor.STANDARD.redact(com.dpsmpx.dpai.core.text.Words.failure(e))
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
    BackHandler(enabled = selectedId == null && settings) { settings = false }
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
                    if (session != null) selectedId = session.value else openTab(AiTab.TASKS)
                }
                com.dpsmpx.dpai.notifications.DeepLink.Kind.REQUEST -> openTab(AiTab.REQUESTS)
                else -> Unit
            }
        }
        incoming.confirm?.let { confirmUri = it }
        // Пункт левой панели приложения (DPmetaApp:D-112): вкладка — сверху, без открытой сессии и настроек.
        incoming.tab?.let { name -> AiTab.entries.firstOrNull { it.name == name }?.let { t -> selectedId = null; settings = false; openTab(t) } }
        if (incoming.settings) { selectedId = null; settings = true; settingsSub = null }
        if (incoming != Incoming()) onIncoming()
    }
    confirmUri?.let { uri -> ConfirmDialog(model, snapshot?.requests.orEmpty(), uri) { confirmUri = null } }
    // «Остановить» из уведомления службы (A-18): подтверждение — здесь, с тем, что встанет.
    var confirmStop by remember { mutableStateOf(false) }
    LaunchedEffect(incoming) { if (incoming.stop) confirmStop = true }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Остановить контроллер?") },
            text = { Text("Такты, уведомления и запросы встанут, пока вы не запустите его снова; идущий ход доиграет.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    scope.launch(Dispatchers.IO) { ControllerService.stop(context) }
                }) { Text("Остановить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Отмена") } },
        )
    }
    // Найденное ведёт к сущности: сессия — её экран, задача — её лист, слоты и проекты — вкладка Targets.
    if (searching) {
        SearchDialog(model, onDone = { searching = false }) { link ->
            searching = false
            when (link.kind) {
                com.dpsmpx.dpai.notifications.DeepLink.Kind.SESSION -> selectedId = link.id
                com.dpsmpx.dpai.notifications.DeepLink.Kind.TASK -> foundTask = com.dpsmpx.dpai.core.ids.IdKinds.TASK.parse(link.id).valueOrNull()
                com.dpsmpx.dpai.notifications.DeepLink.Kind.REQUEST -> openTab(AiTab.REQUESTS)
                com.dpsmpx.dpai.notifications.DeepLink.Kind.EVENT -> Unit
                // Подписки и их окна — на сводке (LimitsCard); слоты и проект — на вкладке Targets.
                com.dpsmpx.dpai.notifications.DeepLink.Kind.SUBSCRIPTION -> openTab(AiTab.SUMMARY)
                else -> openTab(AiTab.TARGETS)
            }
        }
    }
    foundTask?.let { t -> TaskSheet(model, t, onSession = { s -> foundTask = null; selectedId = s }) { foundTask = null } }

    // Окно сессии: чат или временная шкала (D-59) — переключатель в заголовке, а не в окне (D-61).
    var sessionEvents by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    // Лист задачи — кнопкой из чата (D-62): вывод модели — в чате, настройки и действия задачи — в листе.
    var taskSheet by remember { mutableStateOf<com.dpsmpx.dpai.core.ids.TaskId?>(null) }
    val sessionScreen = if (selectedId != null) snapshot?.session else null
    // Широкий экран (AI:A-24, DPmetaApp:D-77): лист задачи — панелью рядом с чатом; ⚙ прячет и показывает её.
    val windowWidth = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width
    val wide = with(androidx.compose.ui.platform.LocalDensity.current) { windowWidth.toDp() } >= TASK_PANEL_MIN
    var taskPanel by rememberSaveable { mutableStateOf(true) }
    val twoPane = wide && taskPanel && sessionScreen?.task != null
    // Окно листа поверх той же задачи, что уже в панели (поворот экрана), — не нужно.
    taskSheet?.takeIf { !(twoPane && it == sessionScreen.task) }?.let { t -> TaskSheet(model, t, onSession = { s -> taskSheet = null; selectedId = s }) { taskSheet = null } }
    val renameTask = sessionScreen?.task
    if (renaming && renameTask != null) {
        RenameSessionDialog(SessionNames.name(context, renameTask) ?: sessionScreen.title.orEmpty()) { name ->
            name?.let { SessionNames.set(context, renameTask, it) }
            renaming = false
        }
    }
    var creating by rememberSaveable { mutableStateOf(false) }
    var ecosystem by rememberSaveable { mutableStateOf(false) }
    if (creating) NewTaskDialog(model, onEcosystem = { creating = false; ecosystem = true }) { creating = false }
    if (ecosystem) EcosystemDialog(model) { ecosystem = false }
    // Обратимое — сразу и со снэкбаром «Отменить» (AI:A-4): хост — у оболочки, действия — во вкладках.
    val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // Пульт под «AI» есть в приложении DPmetaApp (D-73); в отдельной сборке «AI» — нет.
    val pultBelow = remember { (context.applicationContext as? DpaiApplication)?.parentIntent() != null }
    val snack = remember { androidx.compose.material3.SnackbarHostState() }
    val undo = remember(scope) { Undo(snack, scope) }
    val ideChrome = remember { IdeChrome() }
    val ideSettings by IdeState.state.collectAsState()
    val ideFullscreen = ideChrome.active && ideSettings.options.zen
    IdeWindow(ideFullscreen)
    val drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    val app = context.applicationContext as? DpaiApplication
    androidx.compose.runtime.CompositionLocalProvider(LocalUndo provides undo, LocalIdeChrome provides ideChrome) {
      // Левая панель — та же, что у пульта (DPmetaApp:D-112): Сводка, Проекты, IDE, AI, Настройки.
      androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = !ideFullscreen,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
                Text("DPmetaApp", Modifier.padding(horizontal = 28.dp, vertical = 16.dp), style = MaterialTheme.typography.titleMedium)
                val activity = androidx.activity.compose.LocalActivity.current
                fun section(sec: com.dpsmpx.dpai.ui.AppSection) {
                    // Разделы пульта: окно пульта под «AI» выходит вперёд на нужном разделе, «AI» закрывается.
                    app?.sectionIntent(sec)?.let { i -> context.startActivity(i); activity?.finish() }
                }
                val ide = tab == AiTab.FILES.ordinal && !settings
                @Composable
                fun item(title: String, icon: Int, selected: Boolean, onClick: () -> Unit) = androidx.compose.material3.NavigationDrawerItem(
                    label = { Text(title) },
                    icon = { androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(icon), contentDescription = null) },
                    selected = selected,
                    onClick = { scope.launch { drawer.close() }; onClick() },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                if (pultBelow) item(com.dpsmpx.dpai.ui.AppSection.SUMMARY.title, R.drawable.ic_ai_summary, false) { section(com.dpsmpx.dpai.ui.AppSection.SUMMARY) }
                if (pultBelow) item(com.dpsmpx.dpai.ui.AppSection.PROJECTS.title, R.drawable.ic_ai_tasks, false) { section(com.dpsmpx.dpai.ui.AppSection.PROJECTS) }
                item("IDE", R.drawable.ic_ai_files, ide) { openTab(AiTab.FILES) }
                item("AI", R.drawable.ic_ai_requests, !ide && !settings) { openTab(AiTab.of(lastAi)) }
                if (pultBelow) {
                    item(com.dpsmpx.dpai.ui.AppSection.SETTINGS.title, R.drawable.ic_ai_settings, false) { section(com.dpsmpx.dpai.ui.AppSection.SETTINGS) }
                } else {
                    item(AI_SETTINGS_TITLE, R.drawable.ic_ai_settings, settings) { settings = true; settingsSub = null; selectedId = null }
                }
            }
        },
      ) {
        Scaffold(
            contentWindowInsets = if (ideFullscreen) WindowInsets(0, 0, 0, 0) else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
            snackbarHost = { androidx.compose.material3.SnackbarHost(snack) },
            // «Новая задача» — плавающей кнопкой на «Сводке» и «Задачах» (AI:A-6); в демо задач не ставят.
            floatingActionButton = {
                val onBoard = tab == AiTab.SUMMARY.ordinal || tab == AiTab.TASKS.ordinal
                if (snapshot?.live == true && selectedId == null && !settings && onBoard) {
                    androidx.compose.material3.ExtendedFloatingActionButton(onClick = { creating = true }, text = { Text("Новая задача") }, icon = { Text("+", style = MaterialTheme.typography.titleLarge) })
                }
            },
            topBar = top@ {
                if (ideFullscreen) return@top
                // Узкая строка (D-61): больше места чату; в окне сессии — кружок состояния и имя (нажатие — переименовать).
                androidx.compose.material3.Surface(tonalElevation = 3.dp) {
                    Row(
                        // Сверху — строка состояния, сбоку — вырез в альбомной ориентации (AI:A-24).
                        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).heightIn(min = 48.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectedId != null) {
                            TextButton(onClick = { selectedId = null }, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("←") }
                            sessionScreen?.let { sc -> StatusDot(sc.status) }
                            Text(
                                sessionScreen?.let { sc -> SessionNames.name(context, sc.task) ?: sc.title } ?: "Сессия",
                                Modifier.weight(1f).padding(start = 8.dp).clickable(enabled = sessionScreen?.task != null) { renaming = true },
                                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            sessionScreen?.task?.let { t ->
                                TextButton(
                                    onClick = { if (wide) taskPanel = !taskPanel else taskSheet = t },
                                    modifier = Modifier.semantics { contentDescription = if (twoPane) "Скрыть лист задачи" else "Лист задачи" },
                                ) { Text("⚙", style = MaterialTheme.typography.titleMedium) }
                            }
                            TextButton(onClick = { sessionEvents = !sessionEvents }) { Text(if (sessionEvents) "Чат" else "События") }
                        } else if (settings) {
                            TextButton(onClick = { settings = false }, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("←") }
                            Text(AI_SETTINGS_TITLE, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            // Левая панель приложения (DPmetaApp:D-112) — вместо «← Пульт» и нижней панели вкладок.
                            androidx.compose.material3.IconButton(onClick = { scope.launch { drawer.open() } }) {
                                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_ai_menu), contentDescription = "Разделы")
                            }
                            if (tab == AiTab.FILES.ordinal) {
                                Text("IDE", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            } else {
                                // Вкладки раздела AI — в верхней панели (DPmetaApp:D-113): подписи, у «Запросов» — число.
                                AiSectionTabs(AiTab.of(tab), snapshot?.requests?.size ?: 0, snapshot?.mode == "демо", Modifier.weight(1f)) { openTab(it) }
                            }
                            // Глобальный поиск (ТЗ, §75) — с любой вкладки.
                            TextButton(onClick = { searching = true }) { Text("Найти") }
                            // «Настройки AI» (D-112): журнал, позже — настройка из Targets (AI:A-3).
                            Labeled(AI_SETTINGS_TITLE) {
                                androidx.compose.material3.IconButton(onClick = { settings = true; settingsSub = null }) {
                                    androidx.compose.material3.Icon(
                                        androidx.compose.ui.res.painterResource(R.drawable.ic_ai_settings),
                                        contentDescription = AI_SETTINGS_TITLE,
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { padding ->
            val s = snapshot
            // Клавиатура (D-64): содержимое — над ней; отступ панели вкладок уже учтён (consumeWindowInsets), не вдвое.
            // Широкий экран (AI:A-24): содержимое — не шире CONTENT_MAX по центру, вырез сбоку — отступом.
            Column(
                Modifier.padding(padding).consumeWindowInsets(padding).imePadding()
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                    .fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = if (ideFullscreen) androidx.compose.ui.unit.Dp.Infinity else if (twoPane) TWO_PANE_MAX else CONTENT_MAX).fillMaxWidth(),
            ) {
                if (!ideFullscreen) {
                corrupted?.let { what ->
                    // D-24: карантин хвоста — только явным решением; необратимое — окном (AI:A-4).
                    var asking by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            "Журнал контроллера испорчен: $what. Записи после испорченной — решения, ответы, итоги задач — " +
                                "можно отрезать в карантин (файл сохранится рядом), тогда контроллер откатится к последней целой записи. " +
                                "Без этого контроллер не запустится; журнал — файл live/live.journal в данных приложения.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { asking = true }) { Text("Отрезать хвост в карантин…", color = MaterialTheme.colorScheme.error) }
                        if (asking) {
                            ConfirmDialog(
                                "Отрезать хвост журнала?",
                                "Записи после испорченной — решения, ответы, итоги задач — уйдут в файл карантина рядом с журналом, " +
                                    "а контроллер откатится к последней целой записи. В работу они не вернутся.",
                                "Отрезать",
                                onConfirm = {
                                    asking = false
                                    scope.launch(Dispatchers.IO) { controller.confirmJournalQuarantine() }
                                    corrupted = null
                                    failure = null
                                },
                                onDismiss = { asking = false },
                            )
                        }
                    }
                }
                failure?.takeIf { corrupted == null }?.let {
                    // Пустое состояние ведёт дальше (AI:A-17): к targets и к журналу.
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("Контроллер не работает: $it.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { openTab(AiTab.TARGETS) }) { Text("Открыть Targets") }
                            TextButton(onClick = { selectedId = null; settings = true; settingsSub = SETTINGS_LOG }) { Text("Журнал") }
                        }
                    }
                }
                // Демо (AI:A-17): данные ненастоящие — сказано над каждой вкладкой, с путём к настоящей работе.
                if (snapshot?.live == false && corrupted == null) {
                    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Демо: данные ненастоящие. Чтобы работать — добавьте target или Linux в приложении.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            if (tab != AiTab.TARGETS.ordinal || settings || selectedId != null) TextButton(onClick = { openTab(AiTab.TARGETS) }) { Text("Начать") }
                        }
                    }
                }
                if (stopped) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Контроллер остановлен: такты не идут, уведомлений нет.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { scope.launch(Dispatchers.IO) { ControllerService.resume(context) }; stopped = false }) { Text("Запустить") }
                    }
                }
                }
                // Смена вкладки или экрана — плавно, без скачка содержимого; кадр перехода рисует свой экран.
                // Состояние вкладки (место в «Файлах», открытый разговор, прокрутка) — своё у каждой и переживает
                // смену вкладки (AI:A-2, AI:A-21): rememberSaveable внутри держит хранитель по номеру экрана.
                val target = when {
                    selectedId != null -> SESSION_SCREEN
                    settings -> SETTINGS_SCREEN
                    else -> tab
                }
                Crossfade(targetState = target, label = "screen", modifier = Modifier.weight(1f).fillMaxWidth()) { screen ->
                    tabStates.SaveableStateProvider(screen) {
                        Box(Modifier.fillMaxSize()) {
                            when {
                                screen == SETTINGS_SCREEN -> AiSettingsPane(controller, model, settingsSub) { settingsSub = it }
                                screen == AiTab.TARGETS.ordinal -> TargetsPane(controller, model)
                                screen == AiTab.REQUESTS.ordinal -> RequestsPane(model, s)
                                screen == AiTab.MODELS.ordinal -> ModelsPane()
                                screen == AiTab.FILES.ordinal -> FilesPane(onLinux = { openTab(AiTab.TARGETS) }, model = model, onSession = { selectedId = it })
                                s == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                                screen == SESSION_SCREEN -> {
                                    val t = s.session?.task
                                    if (wide && taskPanel && t != null) {
                                        Row(Modifier.fillMaxSize()) {
                                            Box(Modifier.weight(3f).fillMaxHeight()) { SessionPane(s.session, model, sessionEvents, s.requests) }
                                            androidx.compose.material3.VerticalDivider()
                                            // Своё состояние у листа каждой задачи: другая сессия из уведомления — не со строкой итога прежней.
                                            androidx.compose.runtime.key(t) {
                                                TaskSheet(model, t, onSession = { selectedId = it }, embedded = true, shownSession = selectedId, modifier = Modifier.weight(2f)) { taskPanel = false }
                                            }
                                        }
                                    } else {
                                        SessionPane(s.session, model, sessionEvents, s.requests)
                                    }
                                }
                                screen == AiTab.SUMMARY.ordinal -> Column(Modifier.fillMaxSize()) {
                                    QuickRow(s.quick, model)
                                    Box(Modifier.weight(1f)) { DashboardPane(s.dashboard, s.quick, model, onTargets = { openTab(AiTab.TARGETS) }) { foundTask = it } }
                                }
                                else -> BoardPane(s.board, model, s.archived, onEcosystem = if (s.live) ({ ecosystem = true }) else null) { selectedId = it }
                            }
                        }
                    }
                }
            }
        }
      }
    }
}

/**
 * Вкладки раздела AI в верхней панели (`DPmetaApp:D-113`): подписи-«пилюли» вместо
 * значков нижней панели; ряд листается, если не помещается. У «Запросов» — число
 * ждущих, для TalkBack — в описании вкладки.
 */
@Composable
private fun AiSectionTabs(current: AiTab, waiting: Int, demo: Boolean, modifier: Modifier = Modifier, onPick: (AiTab) -> Unit) {
    Row(
        modifier.then(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (t in com.dpsmpx.dpai.ui.AI_SECTION_TABS) {
            val selected = t == current
            androidx.compose.material3.FilterChip(
                selected = selected,
                onClick = { onPick(t) },
                label = { Text(t.title + if (demo && selected) " · демо" else "", maxLines = 1) },
                trailingIcon = if (t == AiTab.REQUESTS && waiting > 0) ({ Badge(Modifier.clearAndSetSemantics { }) { Text("$waiting") } }) else null,
                modifier = Modifier.semantics { contentDescription = t.spoken(waiting); this.role = Role.Tab },
            )
        }
    }
}


/**
 * Цвет состояния — один на все экраны «AI» и пульт (DPmetaApp:D-74, AI:A-12):
 * «готово», «предупреждение» и «ошибка» — смысловые цвета `AppPalette` с
 * контрастом не ниже 4,5 : 1 на обеих темах, а не оттенок обоев.
 */
@Composable
internal fun toneColor(t: Tone): Color {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    fun of(role: com.dpsmpx.dpai.ui.AppPalette.Role) = Color(com.dpsmpx.dpai.ui.AppPalette.semantic(role, dark))
    return when (t) {
        Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        // «В работе» — смысловой синий, а не акцент обоев (D-74): не тревога и не «готово».
        Tone.ACTIVE -> of(com.dpsmpx.dpai.ui.AppPalette.Role.NEUTRAL)
        Tone.WARNING -> of(com.dpsmpx.dpai.ui.AppPalette.Role.WARNING)
        Tone.ERROR -> of(com.dpsmpx.dpai.ui.AppPalette.Role.ERROR)
        Tone.DONE -> of(com.dpsmpx.dpai.ui.AppPalette.Role.DONE)
    }
}

@Composable
private fun DashboardPane(screen: DashboardScreen, quick: com.dpsmpx.dpai.ui.QuickActions, model: DemoModel, onTargets: () -> Unit, onTask: (com.dpsmpx.dpai.core.ids.TaskId) -> Unit) {
    // Загрузка окон — из `/usage` раз в минуту, пока сводка видна (D-48).
    if (screen.limits.isNotEmpty() && rememberVisible()) {
        LaunchedEffect(Unit) {
            while (true) {
                withContext(Dispatchers.IO) { model.refreshUsage() }
                kotlinx.coroutines.delay(60_000)
            }
        }
    }
    var usageOpen by rememberSaveable { mutableStateOf(false) }
    // Сводка — подписки (D-65): лимиты сверху, затем состояние, «Ждёт вас» одним списком и ненулевые счётчики (AI:A-6);
    // задачи и их сессии — во вкладке «Задачи», оттуда — сразу чат (D-62).
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = FAB_ROOM), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (screen.limits.isEmpty()) {
            item {
                // Пустое состояние — с действием (AI:A-17): войти в слот на Targets.
                Column {
                    Text("Подписок пока нет: они появятся после первой пробы входа слотов.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onTargets) { Text("Войти в слот ▸") }
                }
            }
        }
        items(screen.limits, key = { "limits:${it.subscription.value}" }) { LimitsCard(it, model) }
        item {
            Text("Состояние: ${screen.health}", color = toneColor(screen.healthTone), fontWeight = FontWeight.Bold)
            screen.reasons.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
        }
        // «Ждут человека» и «Внимание» — один список (AI:A-6): задача — касанием к её листу.
        if (quick.waiting.isNotEmpty() || screen.attention.isNotEmpty()) {
            item { Text("Ждёт вас", style = MaterialTheme.typography.titleMedium) }
            items(quick.waiting, key = { "w:${it.id.value}" }) { c ->
                Column(Modifier.fillMaxWidth().clickable { onTask(c.id) }.padding(vertical = 4.dp)) {
                    Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(Words.task(c.state) + (if (c.held) " · пауза" else "") + " ▸", style = MaterialTheme.typography.labelSmall, color = toneColor(Tone.WARNING))
                }
            }
            val keys = contentKeys(screen.attention) { it }
            items(screen.attention.size, key = { "attention:${keys[it]}" }) { Text("· ${screen.attention[it]}", color = toneColor(Tone.WARNING), style = MaterialTheme.typography.bodySmall) }
        }
        if (screen.shownCounters.isNotEmpty()) item { Counters(screen.shownCounters) }
        if (screen.usage.isNotEmpty()) {
            item { TextButton(onClick = { usageOpen = !usageOpen }) { Text(if (usageOpen) "Расход ▾" else "Расход ▸") } }
            if (usageOpen) item { Counters(screen.usage) }
        }
        // Подсказки локальной модели (D-105): карточкой, только если модель выбрана (AI:A-6).
        item { LocalHintsCard(screen) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Counters(counters: List<com.dpsmpx.dpai.ui.Counter>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        counters.forEach { c ->
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
private fun SessionPane(screen: SessionScreen?, model: DemoModel, events: Boolean, requests: List<RequestCard> = emptyList()) {
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
        // Ждущий запрос задачи — закреплён над лентой (AI:A-7): все варианты сразу, без поиска вкладки «Запросы».
        val waiting = requests.filter { it.task != null && it.task == screen.task }
        if (waiting.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp)) {
                for (r in waiting) RequestItem(model, r)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Расход и строка состояния — мелко в начале ленты, а не над ней (D-61).
            item {
                // Сессия ревью (D-73) — с ролью: она только читает, её не продолжают запросом.
                screen.line?.let { l -> Text(listOfNotNull(l.review, l.state, l.next).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                screen.usage?.let { Text("Расход: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                SessionExtras(screen, model)
            }
            if (events) {
                // Ключи по содержимому (AI:A-25): обрезка начала ленты не перерисовывает её целиком.
                val keys = contentKeys(screen.timeline) { "${it.time}|${it.text.hashCode()}" }
                items(screen.timeline.size, key = { "t:${keys[it]}" }) { i ->
                    val e = screen.timeline[i]
                    Row {
                        Text(e.time, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(64.dp))
                        Text(e.text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                if (screen.chat.isEmpty()) item { Text("Сообщений пока нет: лента пишется с этой версии приложения.", style = MaterialTheme.typography.bodySmall) }
                val keys = contentKeys(screen.chat) { "${it.time}|${it.mine}|${it.event}|${it.thinking}|${it.text.hashCode()}" }
                items(screen.chat.size, key = { "c:${keys[it]}" }) { i -> ChatBubble(screen.chat[i]) }
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
            // Прерывает идущий ход (D-77) — с подтверждением (аудит удобства, A-13).
            var confirm by remember(screen.session) { mutableStateOf(false) }
            TextButton(enabled = !busy, onClick = { confirm = true }) { Text(com.dpsmpx.dpai.ui.SessionAction.REASSIGN.label, style = MaterialTheme.typography.labelSmall) }
            if (confirm) {
                AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text("Прервать ход и перенести?") },
                    text = { Text("Идущий ход остановится с передачей контекста; следующая сессия задачи пойдёт на другом слоте. Выбрать слот следующего хода без прерывания — в листе задачи («⚙»).") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirm = false
                            busy = true
                            val key = keys.next(com.dpsmpx.dpai.core.ids.IdKinds.OPERATION).value
                            scope.launch {
                                status = when (val r = withContext(Dispatchers.IO) { model.command(com.dpsmpx.dpai.orchestrator.commands.Command.Reassign(screen.session, key)) }) {
                                    is Outcome.Success -> null
                                    is Outcome.Failure -> "Не принято: ${r.diagnostics.first().message}"
                                }
                                busy = false
                            }
                        }) { Text("Прервать и перенести", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
                )
            }
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
    Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
    // Состояние — и словом для TalkBack, а не только цветом (AI:A-5).
    val word = when (status) {
        com.dpsmpx.dpai.ui.SessionStatus.RUNNING -> "идёт"
        com.dpsmpx.dpai.ui.SessionStatus.DONE -> "готово"
        com.dpsmpx.dpai.ui.SessionStatus.ERROR -> "ошибка"
        com.dpsmpx.dpai.ui.SessionStatus.IDLE -> "ждёт"
    }
    Box(Modifier.size(14.dp).semantics { stateDescription = word }, contentAlignment = Alignment.Center) {
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
                    // Ссылки ответа — касанием по D-75 (AI:A-20): первое копирует, второе открывает https.
                    val tap = rememberLinkTap()
                    val shown = remember(rendered, code, link, tap) { rendered?.let { annotated(it, code, link, tap) } }
                    // Чат сессии несёт вывод инструментов и коды: копирование выделением — «чувствительное» (D-86).
                    Clipboard.Sensitive {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            if (shown != null) Text(shown, style = MaterialTheme.typography.bodyMedium) else Text(line.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(line.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        // Ответ целиком — одной кнопкой, как в «Моделях» (AI:A-20).
                        if (!line.mine && line.text.isNotEmpty()) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            val undo = LocalUndo.current
                            androidx.compose.material3.TextButton(onClick = {
                                Clipboard.copy(context, "ответ", line.text, sensitive = true)
                                if (!Clipboard.systemConfirms) undo?.say("Скопировано")
                            }) { Text("Копировать", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
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
    // Черновик — по сессии и вне окна: смена вкладки или сессии его не теряет (AI:A-13).
    var text by remember(screen.session) { mutableStateOf(Drafts.get(Drafts.chat(screen.session.value)).orEmpty()) }
    androidx.compose.runtime.SideEffect { Drafts.set(Drafts.chat(screen.session.value), text) }
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
    // «■» — с подтверждением и с тем, как продолжить (аудит удобства, A-13): одно касание ставило задачу на паузу.
    var confirmStop by remember(screen.session) { mutableStateOf(false) }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Остановить ход?") },
            text = { Text("Ход остановится, задача встанет на паузу. Продолжить — «Продолжить» в листе задачи («⚙») или сообщением сюда.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    send({ k -> com.dpsmpx.dpai.orchestrator.commands.Command.StopSession(screen.session, k) })
                }) { Text("Остановить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Отмена") } },
        )
    }
    Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        // Пусто — одна строка на всю ширину и рядом «стоп» (или «свежая сессия»); начали писать — поле растёт
        // до высоты окна, под ним «Отправить» и «Модель и усилие» (D-61). Ход сообщением не прерывается (D-60).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                text, { text = it }, Modifier.weight(1f).heightIn(max = maxHeight),
                placeholder = { Text(if (canSend) "Сообщение ${screen.executor.title}" else (screen.noSend ?: "сюда не пишут").replaceFirstChar { it.uppercase() }) },
                enabled = canSend, singleLine = !typing, maxLines = if (typing) Int.MAX_VALUE else 1,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            if (!typing) {
                when {
                    running -> androidx.compose.material3.FilledTonalIconButton(enabled = !busy, onClick = { confirmStop = true }, modifier = Modifier.semantics { contentDescription = "Остановить ход" }) { Text("■") }
                    fresh -> androidx.compose.material3.FilledTonalIconButton(enabled = !busy, onClick = {
                        send({ k -> com.dpsmpx.dpai.orchestrator.commands.Command.SendToSession(screen.session, null, modelName.ifEmpty { null }, effort.ifEmpty { null }, fresh = true, key = k) })
                    }, modifier = Modifier.semantics { contentDescription = "Свежая сессия" }) { Text("↻") }
                }
            }
        }
        if (typing) {
            if (params) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Варианты — исполнителя сессии: лестница Claude у Codex и Gemini не подействует (AI:A-10).
                    for (m in listOf("") + com.dpsmpx.dpai.ui.ExecutorOptions.models(screen.executor)) {
                        androidx.compose.material3.FilterChip(selected = modelName == m, onClick = { modelName = m }, label = { Text(m.ifEmpty { "модель задачи" }) })
                    }
                    for (e in com.dpsmpx.dpai.ui.ExecutorOptions.efforts(screen.executor).let { if (it.isEmpty()) it else listOf("") + it }) {
                        androidx.compose.material3.FilterChip(selected = effort == e, onClick = { effort = e }, label = { Text(e.ifEmpty { "усилие задачи" }) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(enabled = canSend && !busy && text.isNotBlank(), onClick = {
                    val t = text
                    send({ k -> com.dpsmpx.dpai.orchestrator.commands.Command.SendToSession(screen.session, t, modelName.ifEmpty { null }, effort.ifEmpty { null }, key = k) }) { text = "" }
                }) { Text("Отправить") }
                val tunable = com.dpsmpx.dpai.ui.ExecutorOptions.models(screen.executor).isNotEmpty() || com.dpsmpx.dpai.ui.ExecutorOptions.efforts(screen.executor).isNotEmpty()
                if (tunable) TextButton(onClick = { params = !params }) { Text(if (params) "Скрыть параметры" else "Модель и усилие") }
                if (fresh) TextButton(enabled = !busy, onClick = {
                    val t = text
                    send({ k -> com.dpsmpx.dpai.orchestrator.commands.Command.SendToSession(screen.session, t, modelName.ifEmpty { null }, effort.ifEmpty { null }, fresh = true, key = k) }) { text = "" }
                }) { Text("Свежая") }
            }
        }
    }
}

@Composable
private fun BoardPane(board: TaskBoard, model: DemoModel, archived: Int, onEcosystem: (() -> Unit)?, onSession: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var showArchived by remember { mutableStateOf(model.showArchived) }
    var opened by remember { mutableStateOf<com.dpsmpx.dpai.core.ids.TaskId?>(null) }
    // Снизу — место плавающей «Новой задаче» (AI:A-6): последняя карточка не прячется под ней.
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = FAB_ROOM), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Экосистема (ТЗ, §43–§46; политика авто-задач — D-106) — здесь и в окне «Новой задачи» (AI:A-6).
        onEcosystem?.let { open -> item(key = "ecosystem") { TextButton(onClick = open) { Text("Экосистема…") } } }
        // Архив (D-42): завершённые — с доски, история в журнале остаётся.
        item(key = "archive") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val undo = LocalUndo.current
                TextButton(onClick = {
                    // Отклик и «Отменить» (AI:A-26): сколько убрано — снэкбаром, отмена возвращает те же задачи.
                    scope.launch {
                        val (ids, r) = withContext(Dispatchers.IO) {
                            val before = model.liveOrchestrator()?.tasks?.tasks?.values?.filter { it.terminal && !it.archived }?.map { it.id }.orEmpty()
                            before to model.command(com.dpsmpx.dpai.orchestrator.commands.Command.ArchiveFinished())
                        }
                        val n = (r as? Outcome.Success)?.value as? Int
                        when {
                            Answers.queued(r) -> undo?.say("Уберу после текущего шага контура")
                            r is Outcome.Failure -> undo?.say("Не убрано: ${r.diagnostics.first().message}")
                            n == 0 -> undo?.say("Завершённых нет")
                            ids.isNotEmpty() && undo != null -> undo.revertible("Убрано: ${n ?: ids.size}") {
                                ids.forEach { model.command(com.dpsmpx.dpai.orchestrator.commands.Command.Archive(it, archived = false)) }
                            }
                            else -> undo?.say("Убрано: ${n ?: "?"}")
                        }
                    }
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
internal fun NewTaskDialog(model: DemoModel, onEcosystem: (() -> Unit)? = null, onDone: () -> Unit) {
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
    var executor by remember { mutableStateOf(com.dpsmpx.dpai.core.ids.Executor.CLAUDE) }
    var reviewer by remember { mutableStateOf<com.dpsmpx.dpai.core.ids.Executor?>(null) }
    val choices = remember { model.dependencyChoices() }
    // Ключ нажатия (D-42): повтор «Поставить» в этом диалоге — та же задача, а не вторая.
    val keys = remember { com.dpsmpx.dpai.core.ids.IdGenerator(com.dpsmpx.dpai.core.clock.Clock.SYSTEM, com.dpsmpx.dpai.core.clock.RandomSource.secure()) }
    // После настоящего отказа ключ новый: исправленная задача — новая команда, а не «уже исполнена: отказ».
    var key by remember { mutableStateOf(keys.next(com.dpsmpx.dpai.core.ids.IdKinds.OPERATION).value) }
    var error by remember { mutableStateOf<String?>(null) }
    // Второе нажатие, пока первое ставит задачу, не создаёт дубль (два хода Claude с одной целью).
    var busy by remember { mutableStateOf(false) }
    fun submit() {
        busy = true
        scope.launch {
            val form = com.dpsmpx.dpai.ui.NewTaskForm.command(
                title, objective, key, priority,
                // Скрытое не уходит в политику: класс — только у Claude, усилие — не у Gemini (AI:A-10).
                if (com.dpsmpx.dpai.ui.ExecutorOptions.hasClass(executor)) taskClass else com.dpsmpx.dpai.tasks.TaskClass.NORMAL_ENGINEERING,
                modelName, effort.takeIf { com.dpsmpx.dpai.ui.ExecutorOptions.efforts(executor).isNotEmpty() }.orEmpty(),
                budget, paths, deps, reviews, executor, reviewer,
            )
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
    }
    // Во весь экран (AI:A-9): «×», название и «Поставить» сверху; «Дополнительно» — как прежде.
    FullScreenDialog(
        onDismissRequest = onDone,
        title = "Новая задача",
        action = { TextButton(enabled = !busy, onClick = { submit() }) { Text(if (busy) "Ставлю…" else "Поставить") } },
    ) {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Задачи из манифеста экосистемы (ТЗ, §43–§46) — отсюда и с «Задач» (AI:A-6).
                onEcosystem?.let { TextButton(onClick = it) { Text("Из экосистемы…") } }
                OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(objective, { objective = it }, label = { Text("Что сделать") }, minLines = 3)
                TextButton(onClick = { more = !more }) { Text(if (more) "Дополнительно ▾" else "Дополнительно ▸") }
                if (more) {
                    Text("Приоритет", style = MaterialTheme.typography.labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (p in com.dpsmpx.dpai.tasks.Priority.entries) {
                            androidx.compose.material3.FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(Words.priority(p.name)) })
                        }
                    }
                    // Исполнитель задачи и ревью (D-100): у предела — следующий по кругу с контекстом.
                    // Он — первым: от него зависят класс, модель и усилие ниже (AI:A-10).
                    Text("Исполнитель", style = MaterialTheme.typography.labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (x in com.dpsmpx.dpai.core.ids.Executor.entries) {
                            androidx.compose.material3.FilterChip(selected = executor == x, onClick = { executor = x }, label = { Text(x.title) })
                        }
                    }
                    if (com.dpsmpx.dpai.ui.ExecutorOptions.hasClass(executor)) {
                        Text("Класс — какую модель брать", style = MaterialTheme.typography.labelSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (c in com.dpsmpx.dpai.tasks.TaskClass.entries) {
                                androidx.compose.material3.FilterChip(selected = taskClass == c, onClick = { taskClass = c }, label = { Text(Words.taskClass(c.name)) })
                            }
                        }
                    }
                    // Модель и усилие — выбором из вариантов исполнителя (AI:A-9, A-10); свободным текстом — только модель Codex и Gemini.
                    val models = com.dpsmpx.dpai.ui.ExecutorOptions.models(executor)
                    if (models.isNotEmpty()) {
                        Pick("Модель", modelName.ifEmpty { "по классу" }, listOf("" to "по классу") + models.map { it to it }) { modelName = it }
                    } else {
                        OutlinedTextField(modelName, { modelName = it }, label = { Text(com.dpsmpx.dpai.ui.ExecutorOptions.modelHint(executor)) }, singleLine = true)
                    }
                    if (com.dpsmpx.dpai.ui.ExecutorOptions.efforts(executor).isNotEmpty()) {
                        val byClass = if (com.dpsmpx.dpai.ui.ExecutorOptions.hasClass(executor)) "по классу" else "по умолчанию"
                        Pick("Усилие", effort.ifEmpty { byClass }, listOf("" to byClass) + com.dpsmpx.dpai.ui.ExecutorOptions.efforts(executor).map { it to it }) { effort = it }
                    }
                    OutlinedTextField(budget, { budget = it }, label = { Text("Предел стоимости хода, $ (пусто — без предела)") }, singleLine = true)
                    ReviewChips(reviews) { reviews = it }
                    if (reviews.isNotEmpty()) {
                        Text("Ревьюер", style = MaterialTheme.typography.labelSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            androidx.compose.material3.FilterChip(selected = reviewer == null, onClick = { reviewer = null }, label = { Text("как задача") })
                            for (x in com.dpsmpx.dpai.core.ids.Executor.entries) {
                                androidx.compose.material3.FilterChip(selected = reviewer == x, onClick = { reviewer = x }, label = { Text(x.title) })
                            }
                        }
                    }
                    OutlinedTextField(paths, { paths = it }, label = { Text("Область: пути в репозитории, через запятую или с новой строки") }, minLines = 2)
                    if (choices.isNotEmpty()) {
                        Text("Зависит от (начнётся после них)", style = MaterialTheme.typography.labelSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (c in choices) {
                                androidx.compose.material3.FilterChip(
                                    selected = c.id in deps,
                                    onClick = { deps = if (c.id in deps) deps - c.id else deps + c.id },
                                    label = { Text(c.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
                                )
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
    }
}

/** Выбор из вариантов (AI:A-9, A-10): «Подпись: выбранное ▾», меню — по касанию. */
@Composable
internal fun Pick(label: String, current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.OutlinedButton(onClick = { open = true }) { Text("$label: $current ▾", maxLines = 1) }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((value, title) in options) androidx.compose.material3.DropdownMenuItem(text = { Text(title) }, onClick = { open = false; onPick(value) })
        }
    }
}

/**
 * Подтверждение опасного ответа из уведомления и окна поверх приложений
 * (D-44, D-104): текст — из ждущего запроса оркестратора, а не из намерения;
 * запрос уже закрыт — сказать; вход приложения нужен — не применять.
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
                    // Запас к входу приложения (D-104): диалог — своё окно, экран входа мог его не закрыть.
                    if ((context as? DpaiApplication)?.loginNeeded() == true) {
                        error = "Нужен вход по PIN: войдите и подтвердите снова"
                        return@TextButton
                    }
                    error = null
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
            Poll(periodMs = 1000) { usage = model.usageState() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (refreshing, at) = usage
                Text(
                    when {
                        refreshing -> "Обновляю…"
                        at != null -> "Обновлено в ${java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(com.dpsmpx.dpai.core.clock.DisplayZone.current).format(java.time.Instant.ofEpochMilli(at.epochMillis))}"
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
            // Порог и передачу меняют редко — одной строкой с раскрытием (AI:A-6); D-65: они на сводке, у подписки.
            var setting by rememberSaveable(l.subscription.value) { mutableStateOf(false) }
            val near = wantNear?.percent ?: l.nearPercent
            val rotation = wantRotation?.allowed ?: l.rotation
            Text(
                "Порог $near %" + (if (rotation) " · берёт чужие задачи" else "") + (if (wantNear != null || wantRotation != null) " · применится" else "") + if (setting) " ▾" else " ▸",
                Modifier.fillMaxWidth().clickable { setting = !setting }.padding(vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
            )
            if (setting) Text("Порог «у предела»: выше — несрочные задачи ждут сброса окна, чужие задачи сюда не передаются", style = MaterialTheme.typography.labelSmall)
            if (setting) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
            if (setting) {
                SwitchRow(wantRotation?.allowed ?: l.rotation, onChange = { on ->
                    scope.launch {
                        status = when (val r = withContext(Dispatchers.IO) { model.command(com.dpsmpx.dpai.orchestrator.commands.Command.SetRotation(l.subscription, on)) }) {
                            is Outcome.Success -> null
                            is Outcome.Failure -> "Не принято: ${r.diagnostics.first().message}"
                        }
                        tick++
                    }
                }) {
                    Text("Брать задачи подписок у предела (с handoff)" + if (wantRotation != null) " · применится" else "", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                }
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** Место под плавающей кнопкой «Новая задача» внизу списков (AI:A-6). */
private val FAB_ROOM = 88.dp

/**
 * Подпись значка — долгим нажатием (D-112): подсказка Material 3 над значком;
 * касание проходит к самому значку.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Labeled(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider(androidx.compose.material3.TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = androidx.compose.material3.rememberTooltipState(),
        modifier = modifier,
        content = content,
    )
}

