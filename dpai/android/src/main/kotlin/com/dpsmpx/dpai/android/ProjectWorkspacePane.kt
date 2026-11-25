package com.dpsmpx.dpai.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.files.FileEntry
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.SubTree
import com.dpsmpx.dpai.ui.ProjectWorkspaceView
import com.dpsmpx.dpai.ui.WorkspaceLocation
import com.dpsmpx.dpai.ui.WorkspacePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class WorkspacePage(val title: String, val label: Int? = null) { FILES("Файлы"), AI("ИИ"), CHANGES("Изменения"), CHECKS("Проверки"), BUILD("Сборка"), RUN("Запуск"), DEPENDENCIES("", R.string.dependencies_title), AUTOMATIONS("", R.string.automation_title) }

/** Первый общий рабочий экран: представление одного настроенного проекта (D-132). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectWorkspacePane(model: DemoModel, onTargets: () -> Unit, onSession: (String) -> Unit, onClose: () -> Unit) {
    var view by remember { mutableStateOf<ProjectWorkspaceView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(model.manualProfileView()) }
    Poll(model, periodMs = 1500) { view = withContext(Dispatchers.IO) { model.workspace() }; manual = model.manualProfileView(); loaded = true }
    val current = view
    androidx.activity.compose.BackHandler(onBack = onClose)
    if (current == null) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            TextButton(onClick = onClose) { Text("← Файлы") }
            if (!loaded) CircularProgressIndicator() else {
                Text("Рабочий проект не настроен")
                // Новый — мастером, без GitHub (DPmetaApp:D-150); существующий репозиторий — в Targets.
                androidx.compose.material3.Button(onClick = { NewProjectRequests.request() }) { Text("Создать проект…") }
                TextButton(onClick = onTargets) { Text("Подключить существующий в Targets") }
            }
        }
        return
    }
    val project = current.project
    EnvironmentApprovalUi(AutomationUiCalls.key(project.project))
    val previous = remember(project.project.value) { IdeState.state.value.lastPlace?.split("\u0001") }
    var taskKey by rememberSaveable(project.project.value) { mutableStateOf(previous?.takeIf { it.size == 3 && it[0] == "workspace" && it[1] == project.project.value }?.get(2)?.ifEmpty { null }) }
    LaunchedEffect(project.project, taskKey) { IdeState.place("workspace\u0001${project.project.value}\u0001${taskKey.orEmpty()}") }
    var page by rememberSaveable(project.project.value) { mutableStateOf(WorkspacePage.FILES.name) }
    val simple = LocalUiLook.current.simple
    var operation by rememberSaveable(project.project.value) { mutableStateOf<String?>(null) }
    fun openPage(name: String) {
        if (simple && name in com.dpsmpx.dpai.ui.WorkspacePresentation.operations) operation = name
        else { operation = null; page = name }
    }
    LaunchedEffect(simple, page) {
        if (simple) {
            val restored = com.dpsmpx.dpai.ui.WorkspacePresentation.restore(page)
            if (restored.operation != null) { page = restored.primary; operation = restored.operation }
        } else operation?.let { page = it; operation = null }
    }

    // Переход к ошибке из терминала запуска (D-153, D-155): запрос для этого дерева — на «Файлы», там его выполнит панель вкладок.
    val jump by IdeState.jump.collectAsState()
    var choose by remember { mutableStateOf(false) }
    var taskSheet by remember(project.project, taskKey) { mutableStateOf(false) }
    var aiRequest by remember(project.project) { mutableStateOf<EditorAiRequest?>(null) }
    var draftsOpen by remember { mutableStateOf(false) }
    if (draftsOpen) FileDraftsDialog { draftsOpen = false }
    val selected = current.tasks.firstOrNull { it.id.value == taskKey }
    val location = if (taskKey == null) current.main else selected?.location
    LaunchedEffect(jump, location?.key) { if (jump != null && jump?.root == location?.key) openPage(WorkspacePage.FILES.name) }
    // Документ из пульта (DPmetaApp:D-177): основное дерево этого проекта, «Файлы», вкладка — через переход к месту.
    val fileRequest by IdeState.fileRequest.collectAsState()
    LaunchedEffect(fileRequest, project.project.value) {
        val r = fileRequest?.takeIf { it.project == project.project.value } ?: return@LaunchedEffect
        if (!IdeState.fileRequest.compareAndSet(r, null)) return@LaunchedEffect
        taskKey = null
        openPage(WorkspacePage.FILES.name)
        IdeState.jumpTo(current.main.key, r.path, 1)
    }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val pageStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    if (taskSheet && selected != null) TaskSheet(model, selected.id, onSession = onSession) { taskSheet = false }
    // «В ИИ» из редактора (D-165): выбранная задача — направление «в сессию».
    aiRequest?.let { req -> EditorAiSheet(model, current.project, selected, req) { aiRequest = null } }
    val zen = ideZen()
    val context = LocalContext.current
    val toolsTitle = androidx.compose.ui.res.stringResource(R.string.ui_tools)
    val contextTitle = androidx.compose.ui.res.stringResource(R.string.ui_context)
    val activeTitle = androidx.compose.ui.res.stringResource(R.string.ui_operation_active)
    val resultTitle = androidx.compose.ui.res.stringResource(R.string.ui_operation_open)
    var environment by rememberSaveable(project.project.value) { mutableStateOf(false) }
    if (environment) {
        androidx.activity.compose.BackHandler { environment = false }
        EnvironmentScreen(onBack = { environment = false }, controller = Controller.get(context.filesDir), model = model)
        return
    }
    val chrome = LocalIdeChrome.current
    androidx.compose.runtime.DisposableEffect(chrome) {
        chrome.workspace = true
        onDispose { chrome.workspace = false }
    }
    val menu = LocalAppMenu.current
    var tools by remember { mutableStateOf(false) }
    var contextOpen by rememberSaveable(project.project.value) { mutableStateOf(false) }
    fun title(name: String): Int = when (name) {
        "FILES" -> R.string.ui_code; "AI" -> R.string.ui_chat; "CHANGES" -> R.string.ui_changes
        "BUILD" -> R.string.ui_build; "CHECKS" -> R.string.ui_check; "RUN" -> R.string.ui_run
        "DEPENDENCIES" -> R.string.dependencies_title; else -> R.string.automation_title
    }
    @Composable fun androidx.compose.foundation.layout.ColumnScope.pageContent(targetPage: String) {
        pageStates.SaveableStateProvider("${location?.key ?: "${project.project.value}:$taskKey"}:$targetPage") {
            when (targetPage) {
                WorkspacePage.FILES.name -> if (location == null) Text("У задачи ещё нет доступного рабочего дерева", Modifier.padding(12.dp)) else {
                    // Файлы проекта: ИИ-дополнение берёт источник и согласие проекта (D-170).
                    androidx.compose.runtime.CompositionLocalProvider(LocalAiCompletion provides AiCompletionPlace(model, project)) {
                        WorkspaceFiles(model, current, location, readOnly = current.paused || current.pendingEdit || !current.ready || selected?.owner != null, modifier = Modifier.weight(1f), onAi = { aiRequest = it })
                    }
                }
                WorkspacePage.AI.name -> Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { ApiQuickStartRequests.request(project = project.project.value) }) { Text(androidx.compose.ui.res.stringResource(R.string.api_work_title)) }
                    if (selected == null) Text("Выберите задачу контура, чтобы открыть её чат и действия") else {
                        Text("${selected.title} · ${Words.task(selected.state)}")
                        selected.session?.let { s -> TextButton(onClick = { onSession(s.value) }) { Text("Открыть чат ИИ") } }
                        TextButton(onClick = { taskSheet = true }) { Text("Действия и история задачи") }
                    }
                }
                WorkspacePage.CHANGES.name -> Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                    location?.let { WorkspaceGitPanel(model, project, it) }
                    if (selected == null) Text("Выберите задачу: её изменения сравниваются с веткой интеграции") else ChangesSection(model, selected.id)
                }
                WorkspacePage.RUN.name -> WorkspaceRunPanel(model, current, location)
                WorkspacePage.AUTOMATIONS.name -> if (taskKey != null) Column(Modifier.padding(12.dp)) {
                    Text(androidx.compose.ui.res.stringResource(R.string.automation_main_tree))
                    TextButton(onClick = { taskKey = null }) { Text(androidx.compose.ui.res.stringResource(R.string.automation_select_main)) }
                } else AutomationCenter(project) { definition ->
                    when (definition.source) {
                        com.dpsmpx.dpai.automation.AutomationSource.PULT -> AutomationBridge.openPult?.invoke(context)
                        com.dpsmpx.dpai.automation.AutomationSource.ENVIRONMENT -> environment = true
                        com.dpsmpx.dpai.automation.AutomationSource.VERIFICATION -> { openPage(WorkspacePage.AI.name); if (selected != null) taskSheet = true }
                        com.dpsmpx.dpai.automation.AutomationSource.MANUAL -> openPage(when (definition.id) {
                            "manual.check" -> WorkspacePage.CHECKS.name
                            "manual.build" -> WorkspacePage.BUILD.name
                            "manual.dependencies" -> WorkspacePage.DEPENDENCIES.name
                            else -> WorkspacePage.RUN.name
                        })
                    }
                }
                WorkspacePage.DEPENDENCIES.name -> WorkspaceDependenciesPanel(model, current, location)
                WorkspacePage.CHECKS.name, WorkspacePage.BUILD.name -> {
                    val kind = if (targetPage == WorkspacePage.CHECKS.name) com.dpsmpx.dpai.ui.ManualProfileKind.CHECK else com.dpsmpx.dpai.ui.ManualProfileKind.BUILD
                    // Место из вывода — во вкладку «Файлов» этого дерева (D-153): запрос ждёт панель дерева, страница переключается.
                    WorkspaceProfilePanel(model, current, location, kind) { path, line ->
                        location?.let { IdeState.jumpTo(it.key, path, line); openPage(WorkspacePage.FILES.name) }
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (!zen) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onClick = menu) {
                    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_ai_menu), androidx.compose.ui.res.stringResource(R.string.ui_navigation))
                }
                Text(project.root.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\'), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                androidx.compose.foundation.layout.Box {
                    androidx.compose.material3.IconButton(onClick = { tools = true }, modifier = Modifier.semantics { contentDescription = toolsTitle }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                    DropdownMenu(tools, onDismissRequest = { tools = false }) {
                        for (name in com.dpsmpx.dpai.ui.WorkspacePresentation.operations) DropdownMenuItem(
                            text = { Text(androidx.compose.ui.res.stringResource(title(name))) }, onClick = { tools = false; openPage(name) })
                        current.target?.let { target -> DropdownMenuItem(text = { Text(androidx.compose.ui.res.stringResource(R.string.ui_terminal)) }, onClick = { tools = false; TerminalActivity.start(context, target.id.value) }) }
                        DropdownMenuItem(text = { Text(androidx.compose.ui.res.stringResource(R.string.ui_environment)) }, onClick = { tools = false; environment = true })
                        DropdownMenuItem(text = { Text("Черновики") }, onClick = { tools = false; draftsOpen = true })
                        DropdownMenuItem(text = { Text("← Файлы") }, onClick = { tools = false; onClose() })
                    }
                }
            }
            if (!keyboard) {
                TextButton(onClick = { contextOpen = !contextOpen }, modifier = Modifier.semantics { contentDescription = contextTitle }) {
                    Text("${current.target?.label ?: "Target?"} · ${location?.branch ?: "—"} ▾", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (current.paused) Text("Проект на паузе: ручной профиль", Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error)
                if (!current.ready) {
                    Text("Контроллер не готов для этой конфигурации", Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { environment = true }) { Text(androidx.compose.ui.res.stringResource(R.string.ui_environment)) }
                }
                if (selected?.owner != null) Text("Редактор: только чтение — дерево занято ИИ", Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error)
                if (contextOpen || !simple) {
                    val all = remember(project.project) { model.projects() }
                    val switchScope = androidx.compose.runtime.rememberCoroutineScope()
                    if (all.projects.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                        for (p in all.projects) FilterChip(selected = p.project == project.project, onClick = {
                            if (p.project != project.project) switchScope.launch { withContext(Dispatchers.IO) { model.selectProject(p.project) } }
                        }, label = { Text(p.root.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')) })
                    }
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(listOfNotNull(project.root, location?.path).distinct().joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { choose = true }) { Text(if (taskKey == null) "Основное дерево ▾" else "${selected?.title ?: "Задача больше не доступна"} ▾") }
                        DropdownMenu(expanded = choose, onDismissRequest = { choose = false }) {
                            DropdownMenuItem(text = { Text("Основное дерево") }, onClick = { taskKey = null; choose = false })
                            for (t in current.tasks.take(200)) DropdownMenuItem(text = { Text("${t.title} · ${Words.task(t.state)}") }, onClick = { taskKey = t.id.value; choose = false })
                            if (current.tasks.size > 200) DropdownMenuItem(text = { Text("Последние 200 задач; остальные — на доске") }, onClick = {}, enabled = false)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    val pages = if (simple) com.dpsmpx.dpai.ui.WorkspacePresentation.primary else WorkspacePage.entries.map { it.name }
                    for (name in pages) FilterChip(selected = page == name, onClick = { openPage(name) }, label = { Text(androidx.compose.ui.res.stringResource(title(name))) })
                }
                if (simple) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    for (name in listOf("BUILD", "CHECKS", "RUN")) TextButton(onClick = { openPage(name) }) { Text(androidx.compose.ui.res.stringResource(title(name))) }
                }
            }
        }
        pageContent(page)
        manual.active?.takeIf { it.project.project == project.project }?.let { active ->
            androidx.compose.material3.Surface(tonalElevation = 2.dp) {
                TextButton(onClick = { openPage(if (active.profile.kind == com.dpsmpx.dpai.ui.ManualProfileKind.CHECK) "CHECKS" else "BUILD") }) {
                    Text("${activeTitle} · ${active.branch} · ${resultTitle}", maxLines = 2)
                }
            }
        }
    }
    operation?.takeIf { simple }?.let { name ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { operation = null },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxSize(0.92f)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(androidx.compose.ui.res.stringResource(title(name)), Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { operation = null }) { Text(androidx.compose.ui.res.stringResource(R.string.ui_close)) }
                }
                pageContent(name)
            }
        }
    }
}

@Composable
private fun WorkspaceFiles(model: DemoModel, view: ProjectWorkspaceView, location: WorkspaceLocation, readOnly: Boolean, modifier: Modifier, onAi: ((EditorAiRequest) -> Unit)? = null) {
    val context = LocalContext.current
    val prefix = WorkspacePaths.homeRelative(view.target, location.path)?.takeIf { it.isEmpty() || FileNames.pathValid(it) }
    val target = view.target
    // Дерево на ПК — по SFTP с правкой (DPmetaApp:D-131; D-166): одно подключение на экран дерева.
    val pc = prefix == null && target != null && !target.inApp
    if (prefix == null && !pc) {
        Text("Файлы этого target пока открываются через терминал. Браузер поддерживает домашнюю папку Linux в приложении и деревья на ПК.", Modifier.padding(12.dp))
        return
    }
    val remote = remember(view.project, location) { if (pc) com.dpsmpx.dpai.jsch.ReopeningSftpTree { model.pcFiles(target, location.path, writable = true) } else null }
    androidx.compose.runtime.DisposableEffect(remote) { onDispose { remote?.let { r -> Thread({ r.close() }, "dpai-sftp-close").start() } } }
    val inner: FileTree = remember(view.project, location) { remote ?: SubTree(GuestPaths.homeTree(context), prefix!!) }
    val tree = remember(view.project, location) {
        object : FileTree {
            override fun list(dir: String) = model.workspaceAccess(view.project, location, false) { inner.list(dir) }
            override fun read(path: String, maxBytes: Long) = model.workspaceAccess(view.project, location, false) { inner.read(path, maxBytes) }
            override fun write(path: String, bytes: ByteArray) = model.workspaceAccess(view.project, location, true) { inner.write(path, bytes) }.also { r ->
                if (r is Outcome.Success && location.task == null)
                    AutomationEvents.fileChanged(context, view.project.project, path, com.dpsmpx.dpai.core.hashing.Hashing.sha256(bytes).hex)
                // Запись на ПК — действие вне приложения (D-07): строка журнала — путь и размер, без содержимого.
                if (remote != null && target != null) {
                    val what = if (r is Outcome.Success) "записан" else "не записан (${(r as Outcome.Failure).diagnostics.first().code})"
                    AppLogs.info(context, "файлы ПК", "${target.label}: $what ${location.path}/$path, ${bytes.size} байт")
                }
            }
            override fun delete(path: String) = Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "удаление из рабочего экрана пока недоступно")
        }
    }
    LaunchedEffect(location) {
        val reloaded = withContext(Dispatchers.IO) { Drafts.reloadWorkspace(location, tree, target?.inApp == true) }
        if (reloaded is Outcome.Failure) AppLogs.info(context, "файлы проекта", reloaded.diagnostics.first().message)
    }
    var dir by rememberSaveable(location.key) { mutableStateOf("") }
    val openFiles = rememberOpenEditorFiles(location.key)
    val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val file = openFiles.tabs.active
    var entries by remember(location, dir) { mutableStateOf<List<FileEntry>?>(null) }
    var error by remember(location, dir) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var searching by rememberSaveable(location.key) { mutableStateOf(false) }
    val editScope = androidx.compose.runtime.rememberCoroutineScope()
    var editCopy by remember(location) { mutableStateOf<com.dpsmpx.dpai.ui.WorkspaceChangeCopy?>(null) }
    var copyError by remember(location) { mutableStateOf<String?>(null) }
    var recovering by remember(location) { mutableStateOf(false) }
    var applyingChange by remember(location) { mutableStateOf(false) }
    fun completed() { refresh++ }
    val apiChanged by ApiProjectRuntime.changed.collectAsState()
    LaunchedEffect(apiChanged) { if (apiChanged > 0) refresh++ }
    val editPlace = remember(view.project, location, tree) {
        ProjectEditPlace(tree, location.path, { Drafts.dirtyIn(location, target?.inApp == true) }, { plan ->
            val result = model.workspaceChange(view.project, location) { changes -> changes.apply(view.project.project.value, location.key, inner, plan) }
            if (result is Outcome.Success && location.task == null && plan.files.isNotEmpty())
                AutomationEvents.fileChanged(context, view.project.project, location.path,
                    com.dpsmpx.dpai.core.hashing.Hashing.sha256(plan.files.joinToString("\n") {
                        it.path + ":" + com.dpsmpx.dpai.core.hashing.Hashing.sha256(it.after).hex
                    }).hex)
            val reloaded = Drafts.reloadWorkspace(location, tree, target?.inApp == true)
            if (result is Outcome.Failure) result else reloaded
        }, ::completed)
    }
    LaunchedEffect(location, refresh) {
        when (val result = withContext(Dispatchers.IO) { model.workspaceCopy(view.project) }) {
            is Outcome.Failure -> { copyError = result.diagnostics.first().message; editCopy = null }
            is Outcome.Success -> { editCopy = result.value; copyError = null }
        }
    }
    if (recovering) androidx.compose.material3.AlertDialog(onDismissRequest = { if (!applyingChange) recovering = false },
        title = { Text(androidx.compose.ui.res.stringResource(R.string.project_edit_recovery)) },
        text = { Text(copyError ?: androidx.compose.ui.res.stringResource(if (editCopy?.pending == true) R.string.project_edit_interrupted else R.string.project_edit_undo_hint)) },
        confirmButton = {
            fun run(after: Boolean?) {
                applyingChange = true
                editScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        val changed = model.workspaceChange(view.project, location, recovering = editCopy?.pending == true) { changes ->
                            if (after == null) changes.undo(view.project.project.value, location.key, inner)
                            else changes.recover(view.project.project.value, location.key, inner, after)
                        }
                        val reloaded = Drafts.reloadWorkspace(location, tree, target?.inApp == true)
                        if (changed is Outcome.Failure) changed else reloaded
                    }
                    applyingChange = false
                    if (result is Outcome.Success) { recovering = false; completed() }
                    else copyError = (result as Outcome.Failure).diagnostics.first().message
                }
            }
            Column {
                if (editCopy?.pending == true) {
                    TextButton(enabled = !applyingChange, onClick = { run(false) }) { Text(androidx.compose.ui.res.stringResource(R.string.project_edit_restore)) }
                    TextButton(enabled = !applyingChange, onClick = { run(true) }) { Text(androidx.compose.ui.res.stringResource(R.string.project_edit_complete)) }
                } else if (editCopy?.applied == true) TextButton(enabled = !applyingChange, onClick = { run(null) }) { Text(androidx.compose.ui.res.stringResource(R.string.project_edit_undo)) }
            }
        }, dismissButton = { TextButton(enabled = !applyingChange, onClick = { recovering = false }) { Text(androidx.compose.ui.res.stringResource(android.R.string.cancel)) } })
    LaunchedEffect(location, dir, refresh) {
        when (val r = withContext(Dispatchers.IO) { tree.list(dir) }) {
            is Outcome.Success -> { entries = r.value; error = null }
            is Outcome.Failure -> error = r.errors.first().message
        }
    }
    androidx.activity.compose.BackHandler(enabled = file == null && dir.isNotEmpty()) { dir = FileNames.parent(dir) }
    Column(modifier.fillMaxWidth()) {
        if (editCopy?.pending == true || copyError != null) TextButton(onClick = { recovering = true }) { Text(androidx.compose.ui.res.stringResource(R.string.project_edit_recovery)) }
        EditorTabsBar(openFiles) { path -> "file:workspace:${location.key}\u0001$path" }
        val path = file
        if (path != null) tabStates.SaveableStateProvider(path) {
            // У дерева ПК нет пути в Linux приложения: службы, запуск файла и отладка из редактора — у деревьев Linux (D-131).
            // Дерево на ПК — предпросмотр интерфейсов может рисовать на самом ПК (D-169).
            androidx.compose.runtime.CompositionLocalProvider(LocalProjectEdits provides if (pc) null else editPlace, LocalPcPreview provides if (pc) PcPreviewPlace(model, target, location.path, view.project, location) { model.workspaceAccess(view.project, location, true) { Outcome.success(Unit) } } else null) {
                androidx.compose.runtime.key(refresh) { TreeFileView(tree, "workspace:${location.key}", path, readOnly, guestPath = if (pc) null else "${location.path.trimEnd('/')}/$path",
                    onBrowse = { openFiles.finishedBrowse(path) }, onNext = openFiles::next, onActions = { openFiles.ready(path, it) }, guestRoot = if (pc) null else location.path.trimEnd('/').ifEmpty { "/" }, editorRootKey = location.key,
                    runGuard = { model.workspaceAccess(view.project, location, true) { Outcome.success(Unit) } }, onOpen = openFiles::openAt, onAi = onAi) { openFiles.close(path) } }
            }
        } else if (searching) {
            TreeSearchPanel(tree, dir, "workspace:${location.key}", onOpen = { searching = false; openFiles.open(it) }, onOpenAt = { found, line -> searching = false; openFiles.openAt(found, line) },
                replaceBlocked = when {
                    readOnly -> "дерево занято или контроллер не готов"
                    openFiles.tabs.paths.isNotEmpty() -> "закройте вкладки файлов проекта"
                    else -> null
                }) { searching = false }
        } else {
            if (editCopy?.applied == true && editCopy?.root == location.key) TextButton(onClick = { recovering = true }) { Text(androidx.compose.ui.res.stringResource(R.string.project_edit_undo)) }
            Row {
                TextButton(enabled = dir.isNotEmpty(), onClick = { dir = FileNames.parent(dir) }) { Text("Вверх") }
                Text(if (dir.isEmpty()) "/" else dir, Modifier.weight(1f).padding(12.dp))
                TextButton(onClick = { searching = true }) { Text("Поиск") }
                TextButton(onClick = { refresh++ }) { Text("Обновить") }
            }
            error?.let { DiagnosticText(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            if (entries == null && error == null) CircularProgressIndicator()
            LazyColumn {
                if (entries?.isEmpty() == true) item { Text("Пусто", Modifier.padding(12.dp)) }
                items(entries.orEmpty(), key = { it.name }) { entry -> Text((if (entry.dir) "▸ " else "") + entry.name,
                    Modifier.fillMaxWidth().clickable {
                        val next = FileNames.join(dir, entry.name)
                        if (entry.dir) dir = next else openFiles.open(next)
                    }.padding(12.dp)) }
            }
        }
    }
}
