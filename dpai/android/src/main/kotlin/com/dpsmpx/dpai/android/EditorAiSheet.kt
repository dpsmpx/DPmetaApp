package com.dpsmpx.dpai.android

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.lsp.Assist
import com.dpsmpx.dpai.lsp.DocumentUri
import com.dpsmpx.dpai.lsp.LanguageServers
import com.dpsmpx.dpai.lsp.LineIndex
import com.dpsmpx.dpai.lsp.LspSeverity
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.ui.EditorAiContext
import com.dpsmpx.dpai.ui.EditorAiRoute
import com.dpsmpx.dpai.ui.ProjectEntry
import com.dpsmpx.dpai.ui.WorkspaceTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Что открыто в редакторе в момент «В ИИ»: путь от корня дерева, путь на машине дерева, текст, выделение, черновик. */
internal data class EditorAiRequest(val path: String, val guestPath: String?, val text: String, val selection: IntRange?, val cursor: Int, val unsaved: Boolean)

/**
 * «В ИИ» из редактора (DPmetaApp:D-130; D-165): просьба автора и контекст места —
 * в сессию выбранной задачи проекта, новой задачей или во вкладку ИИ терминала.
 * Направление — настройка проекта (можно сменить на этот раз и запомнить). У
 * предела исполнителя — предложение другого подключённого ИИ; передаёт человек.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorAiSheet(model: DemoModel, project: ProjectEntry, task: WorkspaceTask?, request: EditorAiRequest, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf("") }
    var route by remember { mutableStateOf<EditorAiRoute?>(null) }
    var stored by remember { mutableStateOf<EditorAiRoute?>(null) }
    var keep by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf<EditorAiContext.Input?>(null) }
    var preview by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var executor by remember { mutableStateOf<Executor?>(null) }
    var offers by remember { mutableStateOf<List<Executor>>(emptyList()) }
    val session = task?.session
    val tabs = remember { TerminalHub.aiTabs() }

    // Направление проекта и структура кода: замечания и символ — у службы языка, если она идёт.
    LaunchedEffect(request) {
        val saved = withContext(Dispatchers.IO) { model.aiRoute(project) }
        stored = (saved as? Outcome.Success)?.value
        route = stored ?: if (session != null) EditorAiRoute.SESSION else EditorAiRoute.NEW_TASK
        input = gather(request)
    }

    // Исполнитель направления и предел (D-165): задача — её исполнитель; терминал — CLI вкладки.
    LaunchedEffect(route) {
        val o = withContext(Dispatchers.IO) { model.liveOrchestrator() }
        val x = when (route) {
            EditorAiRoute.SESSION -> task?.let { t -> o?.tasks?.tasks?.get(t.id)?.policy?.executor }
            EditorAiRoute.NEW_TASK -> Executor.CLAUDE
            EditorAiRoute.TERMINAL -> tabs.firstOrNull()?.kind?.let { Executor.byKey(it) }
            null -> null
        }
        executor = x
        offers = if (o != null && x != null) withContext(Dispatchers.IO) { o.limitOffers(x) } else emptyList()
    }

    fun send(chosen: EditorAiRoute, to: Executor?) {
        val i = input ?: return
        busy = true
        scope.launch {
            if (keep && chosen != stored) withContext(Dispatchers.IO) { model.saveAiRoute(project, chosen) }
            val result: Outcome<Any?> = withContext(Dispatchers.IO) {
                when (chosen) {
                    EditorAiRoute.SESSION -> {
                        val t = task ?: return@withContext Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.OPERATION_NOT_ALLOWED, "задача не выбрана — выберите её на странице «ИИ» или отправьте новой")
                        val s = t.session ?: return@withContext Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.OPERATION_NOT_ALLOWED, "у выбранной задачи нет сессии — отправьте новой задачей")
                        if (to != null) model.command(Command.SetExecutor(t.id, to)).let { if (it is Outcome.Failure) return@withContext it }
                        model.command(Command.SendToSession(s, EditorAiContext.render(i, ask)))
                    }
                    EditorAiRoute.NEW_TASK -> {
                        val draft = EditorAiContext.task(i, ask)
                        val key = com.dpsmpx.dpai.core.ids.IdGenerator(com.dpsmpx.dpai.core.clock.Clock.SYSTEM, com.dpsmpx.dpai.core.clock.RandomSource.secure()).next(com.dpsmpx.dpai.core.ids.IdKinds.OPERATION).value
                        when (val form = com.dpsmpx.dpai.ui.NewTaskForm.command(draft.title, draft.objective, key, scope = draft.scope.joinToString(" "), executor = to ?: Executor.CLAUDE)) {
                            is Outcome.Failure -> form
                            is Outcome.Success -> model.addTask(form.value)
                        }
                    }
                    EditorAiRoute.TERMINAL -> {
                        val tab = (to?.let { x -> tabs.firstOrNull { it.kind == x.key } } ?: tabs.firstOrNull())
                            ?: return@withContext Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.OPERATION_NOT_ALLOWED, "нет открытой вкладки ИИ: откройте Claude Code, Codex или Gemini в терминале и повторите")
                        TerminalHub.pasteToAi(tab, EditorAiContext.render(i, ask))
                    }
                }
            }
            busy = false
            when (result) {
                is Outcome.Failure -> if (result.diagnostics.first().code == com.dpsmpx.dpai.core.diagnostics.DiagCode.COMMAND_QUEUED) onClose() else note = result.diagnostics.first().message
                is Outcome.Success -> {
                    if (chosen == EditorAiRoute.TERMINAL) context.startActivity(Intent(context, TerminalActivity::class.java))
                    onClose()
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("В ИИ: ${request.path.substringAfterLast('/')}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(ask, { ask = it }, Modifier.fillMaxWidth(), label = { Text("Что сделать") }, minLines = 2)
                FlowRow {
                    for (r in EditorAiRoute.entries) FilterChip(selected = route == r, onClick = { route = r }, label = { Text(r.title) })
                }
                when (route) {
                    EditorAiRoute.SESSION -> Text(task?.let { t -> if (session != null) "Задача: ${t.title}" else "У задачи «${t.title}» нет сессии" } ?: "Задача не выбрана — выберите её на странице «ИИ» или отправьте новой", style = MaterialTheme.typography.bodySmall)
                    EditorAiRoute.TERMINAL -> Text(if (tabs.isEmpty()) "Нет открытой вкладки ИИ в терминале" else "Вкладка: ${tabs.first().label}; Enter нажмёте вы", style = MaterialTheme.typography.bodySmall)
                    else -> Unit
                }
                CheckRow(keep, { keep = it }, "Запомнить для проекта")
                if (offers.isNotEmpty() && executor != null) {
                    Text("У ${executor!!.title} предел всех слотов. Другой подключённый ИИ:", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        for (x in offers) TextButton(enabled = !busy && input != null, onClick = { route?.let { send(it, x) } }) {
                            Text(if (route == EditorAiRoute.SESSION) "Передать задачу ${x.title}" else "Через ${x.title}")
                        }
                    }
                }
                TextButton(onClick = { preview = !preview }) { Text(if (preview) "Скрыть, что уйдёт" else "Что уйдёт") }
                if (preview) input?.let { i ->
                    SelectionContainer { Text(EditorAiContext.block(i), Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
                if (input == null) Text("Собираю контекст…", style = MaterialTheme.typography.bodySmall)
                note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = !busy && input != null && route != null, onClick = { route?.let { send(it, null) } }) { Text("Отправить") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Отмена") } },
    )
}

@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Контекст места (D-165): замечания и символ — у службы языка файла, если она показана и
 * идёт; использования — её `references` от начала выделения. Пути службы — от корня дерева.
 */
private suspend fun gather(request: EditorAiRequest): EditorAiContext.Input {
    val service = request.guestPath?.let { CodeService.sessionOf(it) }
    val language = LanguageServers.languageId(request.path)
    val root = request.guestPath?.removeSuffix(request.path)?.trimEnd('/')
    val notes = service?.diagnostics?.get(service.uri).orEmpty().map { d ->
        val severity = when (d.severity) {
            LspSeverity.ERROR -> "ошибка"
            LspSeverity.WARNING -> "предупреждение"
            else -> "замечание"
        }
        EditorAiContext.Note(d.range.start.line + 1, severity, listOfNotNull(d.message, d.code?.let { c -> "${d.source.orEmpty()} $c".trim() }).joinToString(" — "))
    }
    val ready = service?.ready()
    var symbol: String? = null
    var usages = emptyList<EditorAiContext.Place>()
    if (ready != null) withContext(Dispatchers.IO) {
        val at = LineIndex(request.text).position((request.selection?.first ?: request.cursor).coerceIn(0, request.text.length))
        if (ready.open(service.uri, language ?: "plaintext", request.text) is Outcome.Success) {
            symbol = (ready.symbols(service.uri) as? Outcome.Success)?.value?.let { Assist.enclosing(it, at) }
            usages = (ready.references(service.uri, at) as? Outcome.Success)?.value.orEmpty().map { l ->
                val path = DocumentUri.toPath(l.uri) ?: l.uri
                EditorAiContext.Place(if (root != null && path.startsWith("$root/")) path.removePrefix("$root/") else path, l.range.start.line + 1)
            }
        }
    }
    return EditorAiContext.Input(request.path, language, request.text, request.selection, request.cursor, request.unsaved, notes, symbol, usages)
}
