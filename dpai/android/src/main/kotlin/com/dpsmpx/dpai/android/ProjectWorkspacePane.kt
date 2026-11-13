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
import kotlinx.coroutines.withContext

private enum class WorkspacePage(val title: String) { FILES("Файлы"), AI("ИИ"), CHANGES("Изменения"), CHECKS("Проверки"), BUILD("Сборка"), RUN("Запуск") }

/** Первый общий рабочий экран: представление одного настроенного проекта (D-132). */
@Composable
internal fun ProjectWorkspacePane(model: DemoModel, onTargets: () -> Unit, onSession: (String) -> Unit, onClose: () -> Unit) {
    var view by remember { mutableStateOf<ProjectWorkspaceView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    Poll(model, periodMs = 1500) { view = withContext(Dispatchers.IO) { model.workspace() }; loaded = true }
    val current = view
    androidx.activity.compose.BackHandler(onBack = onClose)
    if (current == null) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            TextButton(onClick = onClose) { Text("← Файлы") }
            if (!loaded) CircularProgressIndicator() else {
                Text("Рабочий проект не настроен")
                TextButton(onClick = onTargets) { Text("Настроить проект в Targets") }
            }
        }
        return
    }
    val project = current.project
    val previous = remember(project.project.value) { IdeState.state.value.lastPlace?.split("\u0001") }
    var taskKey by rememberSaveable(project.project.value) { mutableStateOf(previous?.takeIf { it.size == 3 && it[0] == "workspace" && it[1] == project.project.value }?.get(2)?.ifEmpty { null }) }
    LaunchedEffect(project.project, taskKey) { IdeState.place("workspace\u0001${project.project.value}\u0001${taskKey.orEmpty()}") }
    var page by rememberSaveable(project.project.value) { mutableStateOf(WorkspacePage.FILES.name) }
    // Переход к ошибке из терминала запуска (D-153, D-155): запрос для этого дерева — на «Файлы», там его выполнит панель вкладок.
    val jump by IdeState.jump.collectAsState()
    var choose by remember { mutableStateOf(false) }
    var taskSheet by remember(project.project, taskKey) { mutableStateOf(false) }
    var aiRequest by remember(project.project) { mutableStateOf<EditorAiRequest?>(null) }
    var draftsOpen by remember { mutableStateOf(false) }
    if (draftsOpen) FileDraftsDialog { draftsOpen = false }
    val selected = current.tasks.firstOrNull { it.id.value == taskKey }
    val location = if (taskKey == null) current.main else selected?.location
    LaunchedEffect(jump, location?.key) { if (jump != null && jump?.root == location?.key) page = WorkspacePage.FILES.name }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val pageStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    if (taskSheet && selected != null) TaskSheet(model, selected.id, onSession = onSession) { taskSheet = false }
    // «В ИИ» из редактора (D-165): выбранная задача — направление «в сессию».
    aiRequest?.let { req -> EditorAiSheet(model, current.project, selected, req) { aiRequest = null } }
    val zen = ideZen()
    Column(Modifier.fillMaxSize()) {
        if (!zen) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onClose) { Text("← Файлы") }
            Text("Рабочий проект", Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            TextButton(onClick = { draftsOpen = true }) { Text("Черновики") }
        }
        if (!keyboard) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Text(project.root, style = MaterialTheme.typography.bodySmall)
            Text("Target: ${current.target?.label ?: "не найден"}", style = MaterialTheme.typography.bodySmall)
            location?.let {
                Text("${if (it.task == null) "Ветка интеграции" else "Ветка дерева"}: ${it.branch}", style = MaterialTheme.typography.bodySmall)
                Text("Дерево: ${it.path}", style = MaterialTheme.typography.bodySmall)
            }
            if (current.paused) Text("Проект на паузе: ручной профиль", color = MaterialTheme.colorScheme.error)
            if (!current.ready) Text("Контроллер не готов для этой конфигурации", color = MaterialTheme.colorScheme.error)
            if (selected?.owner != null) Text("Редактор: только чтение — дерево занято ИИ", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { choose = true }) { Text(if (taskKey == null) "Основное дерево ▾" else "${selected?.title ?: "Задача больше не доступна"} ▾") }
            DropdownMenu(expanded = choose, onDismissRequest = { choose = false }) {
                DropdownMenuItem(text = { Text("Основное дерево") }, onClick = { taskKey = null; choose = false })
                for (t in current.tasks.take(200)) DropdownMenuItem(text = { Text("${t.title} · ${Words.task(t.state)}") }, onClick = { taskKey = t.id.value; choose = false })
                if (current.tasks.size > 200) DropdownMenuItem(text = { Text("Последние 200 задач; остальные — на доске") }, onClick = {}, enabled = false)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            for (p in WorkspacePage.entries) FilterChip(selected = page == p.name, onClick = { page = p.name }, label = { Text(p.title) })
        }
        }
        }
        pageStates.SaveableStateProvider("${location?.key ?: "${project.project.value}:$taskKey"}:$page") {
            when (page) {
                WorkspacePage.FILES.name -> if (location == null) Text("У задачи ещё нет доступного рабочего дерева", Modifier.padding(12.dp)) else {
                    WorkspaceFiles(model, current, location, readOnly = current.paused || !current.ready || selected?.owner != null, modifier = Modifier.weight(1f), onAi = { aiRequest = it })
                }
                WorkspacePage.AI.name -> Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
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
                WorkspacePage.CHECKS.name, WorkspacePage.BUILD.name -> {
                    val kind = if (page == WorkspacePage.CHECKS.name) com.dpsmpx.dpai.ui.ManualProfileKind.CHECK else com.dpsmpx.dpai.ui.ManualProfileKind.BUILD
                    // Место из вывода — во вкладку «Файлов» этого дерева (D-153): запрос ждёт панель дерева, страница переключается.
                    WorkspaceProfilePanel(model, current, location, kind) { path, line ->
                        location?.let { IdeState.jumpTo(it.key, path, line); page = WorkspacePage.FILES.name }
                    }
                }
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
    val tree = remember(view.project, location) {
        val inner: FileTree = remote ?: SubTree(GuestPaths.homeTree(context), prefix!!)
        object : FileTree {
            override fun list(dir: String) = model.workspaceAccess(view.project, location, false) { inner.list(dir) }
            override fun read(path: String, maxBytes: Long) = model.workspaceAccess(view.project, location, false) { inner.read(path, maxBytes) }
            override fun write(path: String, bytes: ByteArray) = model.workspaceAccess(view.project, location, true) { inner.write(path, bytes) }
            override fun delete(path: String) = Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "удаление из рабочего экрана пока недоступно")
        }
    }
    var dir by rememberSaveable(location.key) { mutableStateOf("") }
    val openFiles = rememberOpenEditorFiles(location.key)
    val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val file = openFiles.tabs.active
    var entries by remember(location, dir) { mutableStateOf<List<FileEntry>?>(null) }
    var error by remember(location, dir) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var searching by rememberSaveable(location.key) { mutableStateOf(false) }
    LaunchedEffect(location, dir, refresh) {
        when (val r = withContext(Dispatchers.IO) { tree.list(dir) }) {
            is Outcome.Success -> { entries = r.value; error = null }
            is Outcome.Failure -> error = r.errors.first().message
        }
    }
    androidx.activity.compose.BackHandler(enabled = file == null && dir.isNotEmpty()) { dir = FileNames.parent(dir) }
    Column(modifier.fillMaxWidth()) {
        EditorTabsBar(openFiles) { path -> "file:workspace:${location.key}\u0001$path" }
        val path = file
        if (path != null) tabStates.SaveableStateProvider(path) {
            // У дерева ПК нет пути в Linux приложения: службы, запуск файла и отладка из редактора — у деревьев Linux (D-131).
            TreeFileView(tree, "workspace:${location.key}", path, readOnly, guestPath = if (pc) null else "${location.path.trimEnd('/')}/$path",
                onBrowse = { openFiles.finishedBrowse(path) }, onNext = openFiles::next, onActions = { openFiles.ready(path, it) }, guestRoot = if (pc) null else location.path.trimEnd('/').ifEmpty { "/" }, editorRootKey = location.key,
                runGuard = { model.workspaceAccess(view.project, location, true) { Outcome.success(Unit) } }, onOpen = openFiles::openAt, onAi = onAi) { openFiles.close(path) }
        } else if (searching) {
            TreeSearchPanel(tree, dir, "workspace:${location.key}", onOpen = { searching = false; openFiles.open(it) }, onOpenAt = { found, line -> searching = false; openFiles.openAt(found, line) },
                replaceBlocked = when {
                    readOnly -> "дерево занято или контроллер не готов"
                    openFiles.tabs.paths.isNotEmpty() -> "закройте вкладки файлов проекта"
                    else -> null
                }) { searching = false }
        } else {
            Row {
                TextButton(enabled = dir.isNotEmpty(), onClick = { dir = FileNames.parent(dir) }) { Text("Вверх") }
                Text(if (dir.isEmpty()) "/" else dir, Modifier.weight(1f).padding(12.dp))
                TextButton(onClick = { searching = true }) { Text("Поиск") }
                TextButton(onClick = { refresh++ }) { Text("Обновить") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
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
