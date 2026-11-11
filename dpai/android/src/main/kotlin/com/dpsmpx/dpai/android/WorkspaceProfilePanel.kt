package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.ManualProfile
import com.dpsmpx.dpai.ui.ManualProfileKind
import com.dpsmpx.dpai.ui.ProjectWorkspaceView
import com.dpsmpx.dpai.ui.WorkspaceLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Явная пользовательская команда, один профиль каждого вида на текущий проект (D-134). */
@Composable
internal fun WorkspaceProfilePanel(model: DemoModel, view: ProjectWorkspaceView, location: WorkspaceLocation?, kind: ManualProfileKind) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var command by rememberSaveable(view.project.project.value, kind.name) { mutableStateOf("") }
    var timeout by rememberSaveable(view.project.project.value, kind.name) { mutableStateOf("1800") }
    var loaded by remember(view.project, kind) { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var run by remember { mutableStateOf(model.manualProfileView()) }
    val jobs = LongJobs.get(context)
    val state by jobs.state.collectAsState()
    Poll(model, periodMs = 1500) { run = model.manualProfileView() }
    LaunchedEffect(view.project, kind) {
        when (val r = withContext(Dispatchers.IO) { model.manualProfile(view.project, kind) }) {
            is Outcome.Failure -> note = r.errors.first().message
            is Outcome.Success -> r.value?.let { p ->
                if (command.isEmpty()) command = p.command.joinToString(" ") { arg -> arg.split('\'').joinToString("\"'\"") { "'$it'" } }
                timeout = p.timeoutSeconds.toString()
            }
        }
        loaded = true
    }
    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
        Text(if (kind == ManualProfileKind.CHECK) "Ручная проверка" else "Ручная сборка", style = MaterialTheme.typography.titleSmall)
        Text("Команда запускается в выбранном дереве без shell. Для скрипта укажите интерпретатор явно. Секреты в команду не добавляйте.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = command, onValueChange = { command = it }, enabled = loaded && !run.blocked && !busy, label = { Text("Команда и аргументы") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = timeout, onValueChange = { timeout = it }, enabled = loaded && !run.blocked && !busy, label = { Text("Таймаут, секунд") })
        TextButton(enabled = loaded && !busy && !run.blocked, onClick = {
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { when (val p = ManualProfile.form(kind, command, timeout)) {
                    is Outcome.Failure -> p
                    is Outcome.Success -> model.saveManualProfile(view.project, p.value)
                } }
                note = if (r is Outcome.Failure) r.errors.first().message else "Профиль сохранён"
                busy = false
            }
        }) { Text("Сохранить профиль") }
        TextButton(enabled = loaded && !busy && !run.blocked && location != null && view.ready && state[LongJobs.WORKSPACE_PROFILE]?.running != true, onClick = {
            when (val p = ManualProfile.form(kind, command, timeout)) {
                is Outcome.Failure -> note = p.errors.first().message
                is Outcome.Success -> {
                    if (model.startManualProfile(view.project, location!!, p.value)) {
                        ControllerService.resume(context)
                        note = "Запрос запуска принят; результат появится ниже"
                    } else note = "Сначала завершите другую операцию Linux/DP-Verify"
                }
            }
        }) { Text(if (kind == ManualProfileKind.CHECK) "Запустить проверку" else "Запустить сборку") }
        note?.let { Text(it) }
        state[LongJobs.WORKSPACE_PROFILE]?.let { job -> if (!job.running) job.note?.let { Text(it) } }
        if (run.blocked) {
            Text("Весь контур проекта на паузе", color = MaterialTheme.colorScheme.error)
            run.active?.let { Text("${it.target.label}: ${it.path}\n${it.branch}", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { model.stopManualProfile(); ControllerService.resume(context) }) { Text("Безопасно остановить и снять паузу") }
        }
        if (run.note.isNotEmpty()) Text(run.note)
        if (run.output.isNotEmpty()) SelectionContainer { Text(run.output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        Text("Ручной итог не завершает задачу ИИ. Перед запуском сохраните или отмените правки; контур должен быть свободен.", style = MaterialTheme.typography.bodySmall)
    }
}
