package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.ui.ManualProfileKind
import com.dpsmpx.dpai.ui.ProjectWorkspaceView
import com.dpsmpx.dpai.ui.RunProfile
import com.dpsmpx.dpai.ui.WorkspaceLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Запуск программы проекта (D-155): команда в новой вкладке терминала на target
 * дерева — с вводом и выводом, кодом выхода и ошибками с переходом в код (D-153);
 * на Windows-target — PowerShell (D-163).
 * «Сначала собрать» запускает профиль «Сборка» (D-154) и открывает программу,
 * только если все шаги сборки прошли; ждёт этот экран — ушли с него, программа
 * сама не запустится, останется «▶ Запустить».
 */
@Composable
internal fun WorkspaceRunPanel(model: DemoModel, view: ProjectWorkspaceView, location: WorkspaceLocation?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projectKey = view.project.project.value
    var command by rememberSaveable(projectKey) { mutableStateOf("") }
    var dir by rememberSaveable(projectKey) { mutableStateOf("") }
    var buildFirst by rememberSaveable(projectKey) { mutableStateOf(true) }
    var loaded by remember(view.project) { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Ожидание сборки переживает поворот: id запуска сборки и признак «ждём».
    var awaiting by rememberSaveable(projectKey) { mutableStateOf(false) }
    var buildId by rememberSaveable(projectKey) { mutableStateOf<String?>(null) }
    var run by remember { mutableStateOf(model.manualProfileView()) }
    val jobs = LongJobs.get(context)
    val jobState by jobs.state.collectAsState()
    // Свой запуск сборки узнаётся по итогу, которого не было до нажатия, и по своей задаче LongJobs.
    var finishedBefore by rememberSaveable(projectKey) { mutableStateOf<String?>(null) }
    var jobStarted by remember { mutableStateOf<LongJobs.Job?>(null) }
    Poll(model, periodMs = 1000) { run = model.manualProfileView() }
    LaunchedEffect(view.project) {
        when (val r = withContext(Dispatchers.IO) { model.runProfile(view.project) }) {
            is Outcome.Failure -> note = r.errors.first().message
            is Outcome.Success -> r.value?.let { p -> if (command.isBlank()) { command = p.command; dir = p.dir; buildFirst = p.buildFirst } }
        }
        loaded = true
    }
    val target = view.target
    val windows = target != null && !target.inApp && target.platform == TargetPlatform.WINDOWS

    /** Проверки перед запуском — как у ▶ редактора: дерево доступно для записи, правки сохранены, ручной профиль не идёт. */
    suspend fun guard(loc: WorkspaceLocation): String? = withContext(Dispatchers.IO) {
        when {
            model.manualProfileView().blocked -> "идёт ручной профиль — дождитесь его итога"
            !Drafts.recovery.value.ready -> "дождитесь восстановления черновиков"
            Drafts.dirtyIn(loc, target?.inApp == true) -> "сначала сохраните или отмените правки редактора в этом дереве"
            else -> (model.workspaceAccess(view.project, loc, true) { Outcome.success(Unit) } as? Outcome.Failure)?.errors?.first()?.message
        }
    }

    fun open(profile: RunProfile, loc: WorkspaceLocation) {
        scope.launch {
            guard(loc)?.let { note = "Запуск не начат: $it"; return@launch }
            TerminalActivity.startRun(context, target, profile.workDir(loc.path), profile.command, loc.key, loc.path)
            note = "Программа открыта во вкладке терминала"
        }
    }

    // «Сначала собрать»: ждать итога своего запуска сборки; прошли все шаги — открыть программу.
    LaunchedEffect(awaiting, run, jobState[LongJobs.WORKSPACE_PROFILE]) {
        if (!awaiting) return@LaunchedEffect
        // Свежий снимок: опрос раз в секунду мог отстать от итога задачи.
        val now = model.manualProfileView()
        now.active?.let { if (buildId == null) buildId = it.id.value }
        val ours = now.finished?.value?.takeIf { it != finishedBefore && (buildId == null || it == buildId) }
        val job = jobState[LongJobs.WORKSPACE_PROFILE]
        val ended = jobStarted != null && job !== jobStarted && job?.running == false
        val loc = location
        when {
            now.active == null && ours != null -> {
                awaiting = false
                buildId = null
                if (now.complete && loc != null) {
                    val profile = RunProfile.form(command, dir, buildFirst).valueOrNull()
                    if (profile != null) open(profile, loc) else note = "Сборка прошла, но команда запуска неверна"
                } else {
                    note = "Сборка не прошла — программа не запущена. Итог и ошибки — на странице «Сборка»."
                }
            }
            // Сборка не началась (контур занят, нет агента, грязное дерево): задача кончилась, а своего запуска нет.
            ended && now.active == null && ours == null -> {
                awaiting = false
                buildId = null
                note = "Сборка не началась: ${job.note.orEmpty()}"
            }
        }
    }

    val editable = loaded && !busy && !awaiting
    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("Запуск программы", style = MaterialTheme.typography.titleSmall)
        Text("Команда выполняется в новой вкладке терминала на target дерева — с вводом с клавиатуры. По окончании — код выхода и «Ошибки» с переходом в код; вкладка остаётся оболочкой в той же папке.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(command, { command = it.take(RunProfile.MAX_COMMAND) }, enabled = editable, label = { Text("Команда, например ./build/app --level 1") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(dir, { dir = it.take(1024) }, enabled = editable, label = { Text("Папка от корня дерева (пусто — корень)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(buildFirst, { buildFirst = it }, enabled = editable)
            Text("Сначала собрать — профилем «Сборка»; программа откроется, только если все шаги прошли", style = MaterialTheme.typography.bodyMedium)
        }
        if (windows) Text("Windows: команда выполняется в PowerShell в папке запуска — одна строка до ${com.dpsmpx.dpai.ui.EditorTerminalRun.MAX_WINDOWS_COMMAND} знаков.", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(enabled = editable, onClick = {
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { when (val p = RunProfile.form(command, dir, buildFirst)) {
                        is Outcome.Failure -> p
                        is Outcome.Success -> model.saveRunProfile(view.project, p.value)
                    } }
                    note = if (r is Outcome.Failure) r.errors.first().message else "Профиль запуска сохранён"
                    busy = false
                }
            }) { Text("Сохранить") }
            TextButton(enabled = editable && location != null && view.ready && !run.blocked, onClick = {
                val loc = location ?: return@TextButton
                val profile = when (val p = RunProfile.form(command, dir, buildFirst)) {
                    is Outcome.Failure -> { note = p.errors.first().message; return@TextButton }
                    is Outcome.Success -> p.value
                }
                if (!profile.buildFirst) { open(profile, loc); return@TextButton }
                busy = true
                scope.launch {
                    val build = withContext(Dispatchers.IO) { model.manualProfile(view.project, ManualProfileKind.BUILD) }
                    busy = false
                    val saved = (build as? Outcome.Success)?.value
                    when {
                        build is Outcome.Failure -> note = build.errors.first().message
                        saved == null -> note = "Нет профиля «Сборка»: сохраните его на странице «Сборка» или снимите «Сначала собрать»"
                        else -> {
                            finishedBefore = model.manualProfileView().finished?.value
                            if (model.startManualProfile(view.project, loc, saved)) {
                                // Задача уже «идёт» сразу после start (LongJobs): её экземпляр — признак своей сборки.
                                jobStarted = jobs.state.value[LongJobs.WORKSPACE_PROFILE]
                                ControllerService.resume(context)
                                buildId = null
                                awaiting = true
                                note = "Сборка идёт (${saved.steps.size} шаг.) — программа откроется после неё"
                            } else {
                                note = "Сначала завершите другую операцию Linux/DP-Verify"
                            }
                        }
                    }
                }
            }) { Text(if (awaiting) "Жду сборку…" else if (buildFirst) "▶ Собрать и запустить" else "▶ Запустить") }
            if (awaiting) TextButton(onClick = { awaiting = false; buildId = null; note = "Ожидание снято: сборка идёт дальше, программа сама не откроется" }) { Text("Не запускать") }
            // Отладка программы проекта: одна программа с аргументами, собранная заранее (-g). Linux приложения — D-159;
            // ПК — адаптер на самом ПК по SSH (D-164).
            TextButton(enabled = editable && location != null && view.ready && target != null && !run.blocked, onClick = {
                val loc = location ?: return@TextButton
                val profile = when (val p = RunProfile.form(command, dir, buildFirst)) {
                    is Outcome.Failure -> { note = p.errors.first().message; return@TextButton }
                    is Outcome.Success -> p.value
                }
                scope.launch {
                    guard(loc)?.let { note = "Отладка не начата: $it"; return@launch }
                    val pc = target?.takeIf { !it.inApp }
                    val made = if (pc == null) {
                        com.dpsmpx.dpai.ui.DebugTargets.forCommand(loc.path, profile.workDir(loc.path), profile.command)
                    } else {
                        com.dpsmpx.dpai.ui.DebugTargets.forPcCommand(loc.path, profile.workDir(loc.path), profile.command, windows)
                    }
                    when (val t = made) {
                        is Outcome.Failure -> note = "Отладка не начата: " + t.diagnostics.first().message
                        is Outcome.Success -> {
                            Debugger.start(context, t.value, loc.key, loc.path, onPc = pc)
                            note = "Отладка — панель ниже; точки — касанием номера строки в «Файлах»"
                        }
                    }
                }
            }) { Text("Отладка") }
        }
        if (target != null && !target.inApp) {
            Text(
                if (windows) "Отладка на ПК: только Python — нужен debugpy в интерпретаторе команды (py -m pip install debugpy)."
                else "Отладка на ПК: C и C++ — gdb 14 или новее с Python, программа собрана с -g; Python — debugpy в интерпретаторе команды.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        run.active?.takeIf { awaiting }?.let { a ->
            Text("Сборка: шаг ${run.step ?: "?"} из ${a.profile.steps.size}", style = MaterialTheme.typography.bodyMedium)
        }
        note?.let { DiagnosticText(it, style = MaterialTheme.typography.bodyMedium) }
        DebugPanel()
    }
}
