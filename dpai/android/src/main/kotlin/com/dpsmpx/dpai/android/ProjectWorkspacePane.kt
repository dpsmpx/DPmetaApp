package com.dpsmpx.dpai.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.files.FileEntry
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.SubTree
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.ui.ProjectWorkspaceView
import com.dpsmpx.dpai.ui.WorkspaceLocation
import com.dpsmpx.dpai.ui.WorkspacePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class WorkspacePage(val title: String) { FILES("Файлы"), AI("ИИ"), CHANGES("Изменения"), CHECKS("Проверки"), BUILD("Сборка") }

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
    var taskKey by rememberSaveable(project.project.value) { mutableStateOf<String?>(null) }
    var page by rememberSaveable(project.project.value) { mutableStateOf(WorkspacePage.FILES.name) }
    var choose by remember { mutableStateOf(false) }
    var taskSheet by remember { mutableStateOf(false) }
    val selected = current.tasks.firstOrNull { it.id.value == taskKey }
    val location = if (taskKey == null) current.main else selected?.location
    val context = LocalContext.current
    val pageStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    if (taskSheet && selected != null) TaskSheet(model, selected.id, onSession = onSession) { taskSheet = false }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onClose) { Text("← Файлы") }
            Text("Рабочий проект", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.padding(horizontal = 12.dp)) {
            Text(project.root, style = MaterialTheme.typography.bodySmall)
            Text("Target: ${current.target?.label ?: "не найден"}", style = MaterialTheme.typography.bodySmall)
            location?.let {
                Text("${if (it.task == null) "Ветка интеграции" else "Ветка дерева"}: ${it.branch}", style = MaterialTheme.typography.bodySmall)
                Text("Дерево: ${it.path}", style = MaterialTheme.typography.bodySmall)
            }
            if (!current.ready) Text("Контроллер не готов для этой конфигурации", color = MaterialTheme.colorScheme.error)
            if (selected?.owner != null) Text("Дерево занято ИИ — только чтение", color = MaterialTheme.colorScheme.error)
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
        pageStates.SaveableStateProvider("${location?.key ?: "${project.project.value}:$taskKey"}:$page") {
            when (page) {
                WorkspacePage.FILES.name -> if (location == null) Text("У задачи ещё нет доступного рабочего дерева", Modifier.padding(12.dp)) else {
                    WorkspaceFiles(model, current, location, readOnly = !current.ready || selected?.owner != null, modifier = Modifier.weight(1f))
                }
                WorkspacePage.AI.name -> Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                    if (selected == null) Text("Выберите задачу контура, чтобы открыть её чат и действия") else {
                        Text("${selected.title} · ${Words.task(selected.state)}")
                        selected.session?.let { s -> TextButton(onClick = { onSession(s.value) }) { Text("Открыть чат ИИ") } }
                        TextButton(onClick = { taskSheet = true }) { Text("Действия и история задачи") }
                    }
                }
                WorkspacePage.CHANGES.name -> Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                    if (selected == null) Text("Выберите задачу: её изменения сравниваются с веткой интеграции") else ChangesSection(model, selected.id)
                }
                WorkspacePage.CHECKS.name -> WorkspaceChecks(model, current, location, selected)
                WorkspacePage.BUILD.name -> Column(Modifier.padding(12.dp)) {
                    Text("Профили сборки и артефакты — следующий шаг. Сейчас доступен терминал target.")
                    TextButton(enabled = current.target != null && location != null, onClick = {
                        if (current.target?.inApp == true) TerminalActivity.startLinux(context, location!!.path)
                        else TerminalActivity.start(context, current.target!!.id.value)
                    }) { Text(if (current.target?.inApp == true) "Терминал в дереве" else "Терминал target") }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceChecks(model: DemoModel, view: ProjectWorkspaceView, location: WorkspaceLocation?, task: com.dpsmpx.dpai.ui.WorkspaceTask?) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val keys = remember { IdGenerator(Clock.SYSTEM, RandomSource.secure()) }
    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("Профиль проверки", style = MaterialTheme.typography.titleSmall)
        if (view.project.checks.isEmpty()) Text("Профиль не задан: успешная проверка не предполагается")
        view.project.checks.forEach { step -> Text("${step.name}: ${Redactor.STANDARD.redact(step.command.joinToString(" "))}", style = MaterialTheme.typography.bodySmall) }
        task?.verification?.let { v ->
            val verdict = if (v.waivedBy != null) "принято без проверки" else when (v.verdict) { "PASSED" -> "пройдено"; "FAILED" -> "не пройдено"; else -> "нет полного итога" }
            Text("Последний записанный итог: $verdict")
        }
        TextButton(enabled = !busy && task?.canVerify == true && location != null && view.ready, onClick = {
            busy = true
            val command = Command.Verify(task!!.id, keys.next(IdKinds.OPERATION).value)
            scope.launch {
                val r = withContext(Dispatchers.IO) { model.workspaceAccess(view.project, location!!, true) { model.command(command) } }
                note = when (r) {
                    is Outcome.Success -> "Проверка принята"
                    is Outcome.Failure -> if (r.errors.first().code == DiagCode.COMMAND_QUEUED) "Проверка в очереди" else r.errors.first().message
                }
                busy = false
            }
        }) { Text("Проверить снова") }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun WorkspaceFiles(model: DemoModel, view: ProjectWorkspaceView, location: WorkspaceLocation, readOnly: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val prefix = WorkspacePaths.homeRelative(view.target, location.path)?.takeIf { it.isEmpty() || FileNames.pathValid(it) }
    if (prefix == null) {
        Text("Файлы этого target пока открываются через терминал. Браузер поддерживает домашнюю папку Linux в приложении.", Modifier.padding(12.dp))
        return
    }
    val tree = remember(view.project, location) {
        val inner = SubTree(GuestPaths.homeTree(context), prefix)
        object : FileTree {
            override fun list(dir: String) = model.workspaceAccess(view.project, location, false) { inner.list(dir) }
            override fun read(path: String, maxBytes: Long) = model.workspaceAccess(view.project, location, false) { inner.read(path, maxBytes) }
            override fun write(path: String, bytes: ByteArray) = model.workspaceAccess(view.project, location, true) { inner.write(path, bytes) }
            override fun delete(path: String) = Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "удаление из рабочего экрана пока недоступно")
        }
    }
    var dir by rememberSaveable(location.key) { mutableStateOf("") }
    var file by rememberSaveable(location.key) { mutableStateOf<String?>(null) }
    var entries by remember(location, dir) { mutableStateOf<List<FileEntry>?>(null) }
    var error by remember(location, dir) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(location, dir, refresh) {
        when (val r = withContext(Dispatchers.IO) { tree.list(dir) }) {
            is Outcome.Success -> { entries = r.value; error = null }
            is Outcome.Failure -> error = r.errors.first().message
        }
    }
    androidx.activity.compose.BackHandler(enabled = file == null && dir.isNotEmpty()) { dir = FileNames.parent(dir) }
    Column(modifier.fillMaxWidth()) {
        val path = file
        if (path != null) TreeFileView(tree, "workspace:${location.key}", path, readOnly) { file = null } else {
            Row {
                TextButton(enabled = dir.isNotEmpty(), onClick = { dir = FileNames.parent(dir) }) { Text("Вверх") }
                Text(if (dir.isEmpty()) "/" else dir, Modifier.weight(1f).padding(12.dp))
                TextButton(onClick = { refresh++ }) { Text("Обновить") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            if (entries == null && error == null) CircularProgressIndicator()
            LazyColumn {
                if (entries?.isEmpty() == true) item { Text("Пусто", Modifier.padding(12.dp)) }
                items(entries.orEmpty(), key = { it.name }) { entry -> Text((if (entry.dir) "▸ " else "") + entry.name,
                    Modifier.fillMaxWidth().clickable {
                        val next = FileNames.join(dir, entry.name)
                        if (entry.dir) dir = next else file = next
                    }.padding(12.dp)) }
            }
        }
    }
}
