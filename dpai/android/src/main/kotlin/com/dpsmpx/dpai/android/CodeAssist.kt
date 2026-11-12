package com.dpsmpx.dpai.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.lsp.Assist
import com.dpsmpx.dpai.lsp.Clangd
import com.dpsmpx.dpai.lsp.CompletionItem
import com.dpsmpx.dpai.lsp.DocumentUri
import com.dpsmpx.dpai.lsp.LineIndex
import com.dpsmpx.dpai.lsp.LspClient
import com.dpsmpx.dpai.lsp.LspDiagnostic
import com.dpsmpx.dpai.lsp.LspSeverity
import com.dpsmpx.dpai.ui.CodeEditorBuffer
import com.dpsmpx.dpai.ui.EditorSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Семантика C и C++ над полем редактора (DPmetaApp:D-105, D-137): строка
 * службы, полоска вариантов дополнения, замечания, сведения, переход,
 * форматирование. Служба включается здесь же кнопкой. Все правки идут в
 * буфер редактора одним шагом отмены; файл меняет только «Сохранить».
 *
 * [onMarks] получает отрезки для подчёркивания. Они посчитаны для текста,
 * который видела служба: после нового ввода обновятся со следующей
 * диагностикой (доли секунды).
 */
@Composable
internal fun CodeAssist(buffer: CodeEditorBuffer, guestPath: String, guestRoot: String, revision: Int, enabled: Boolean, tabSize: Int, tabs: Boolean, onChange: () -> Unit, onMarks: (List<Assist.Mark>) -> Unit) {
    val language = Clangd.languageId(guestPath) ?: return
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val session = remember(guestPath) { CodeService.session(guestPath) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<CompletionItem>>(emptyList()) }
    var details by remember { mutableStateOf<String?>(null) }
    var problems by remember { mutableStateOf(false) }
    val state = session.state
    val diagnostics = session.diagnostics[session.uri].orEmpty()

    DisposableEffect(session) {
        onDispose {
            onMarks(emptyList())
            kotlin.concurrent.thread(name = "dpai-code-service-release") { CodeService.release(session) }
        }
    }
    LaunchedEffect(diagnostics) { onMarks(withContext(Dispatchers.Default) { Assist.marks(buffer.text, diagnostics) }) }

    /** Текст буфера — службе; затем [ask] с позицией курсора. Вне потока интерфейса. */
    suspend fun <T> withClient(ask: (LspClient, String, Int) -> Outcome<T>): Outcome<T>? {
        val client = session.ready() ?: return null
        val text = buffer.text
        val cursor = buffer.state.selection.end.coerceIn(0, text.length)
        return withContext(Dispatchers.IO) {
            when (val o = client.open(session.uri, language, text)) {
                is Outcome.Failure -> o
                is Outcome.Success -> ask(client, text, cursor)
            }
        }
    }

    // Синхронизация текста и дополнение по мере ввода: после паузы, чтобы не спрашивать службу на каждый знак.
    LaunchedEffect(revision, state) {
        if (state !is CodeService.State.Ready) { suggestions = emptyList(); return@LaunchedEffect }
        val text = buffer.text
        val selection = buffer.state.selection
        val cursor = selection.end.coerceIn(0, text.length)
        val prefix = Assist.typedPrefix(text, cursor)
        val before = text.substring(0, cursor - prefix.length)
        val trigger = when {
            prefix.isNotEmpty() -> null
            before.endsWith(".") -> '.'
            before.endsWith("->") -> '>'
            before.endsWith("::") -> ':'
            else -> null
        }
        val wanted = enabled && selection.start == selection.end && (prefix.length >= 2 || trigger != null || (prefix.isNotEmpty() && (before.endsWith(".") || before.endsWith("->") || before.endsWith("::"))))
        delay(if (wanted) 250 else 400)
        val result = withClient { client, _, _ -> if (wanted) client.completion(session.uri, LineIndex(text).position(cursor), trigger) else Outcome.success(null) }
        suggestions = ((result as? Outcome.Success)?.value)?.let { Assist.filter(it.items, prefix, 24) }.orEmpty()
    }

    fun apply(applied: Outcome<Assist.Applied>, done: String?) {
        when (applied) {
            is Outcome.Failure -> note = applied.diagnostics.first().message
            is Outcome.Success -> if (buffer.edit(applied.value.text, EditorSelection(applied.value.cursor, applied.value.cursor))) {
                note = done
                onChange()
            } else {
                note = "Текст превышает предел редактора; правка не применена"
            }
        }
    }

    fun launch(step: String, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            note = step
            try { block() } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            when (state) {
                CodeService.State.Off -> {
                    Text("C++: семантика выключена", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { launch("…") { withContext(Dispatchers.IO) { CodeService.start(context, session, guestRoot) }; note = null } }) { Text("Включить") }
                }
                is CodeService.State.Starting -> Text("C++: ${state.step}…", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                CodeService.State.Missing -> {
                    Text("clangd не установлен в Linux приложения", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = {
                        launch("Ставлю пакет ${CodeService.PACKAGE}: нужна сеть и около 400 МБ на диске, это несколько минут…") {
                            when (val r = withContext(Dispatchers.IO) { CodeService.install(context) }) {
                                is Outcome.Failure -> note = "Не установлен: ${r.diagnostics.first().message}"
                                is Outcome.Success -> { note = null; withContext(Dispatchers.IO) { CodeService.start(context, session, guestRoot) } }
                            }
                        }
                    }) { Text("Поставить clangd") }
                }
                is CodeService.State.Failed -> {
                    Text("C++: ${state.message}", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { launch("…") { withContext(Dispatchers.IO) { CodeService.start(context, session, guestRoot) }; note = null } }) { Text("Повторить") }
                }
                is CodeService.State.Ready -> {
                    TextButton(onClick = { problems = true }) { Text(Assist.summary(diagnostics)) }
                    TextButton(enabled = !busy, onClick = {
                        launch("…") {
                            val r = withClient { c, t, cursor -> c.hover(session.uri, LineIndex(t).position(cursor)) }
                            note = null
                            when (r) {
                                is Outcome.Success -> details = r.value?.text ?: "Об этом месте службе нечего сказать"
                                is Outcome.Failure -> note = r.diagnostics.first().message
                                null -> Unit
                            }
                        }
                    }) { Text("Сведения") }
                    TextButton(enabled = !busy, onClick = {
                        launch("…") {
                            val r = withClient { c, t, cursor -> c.definition(session.uri, LineIndex(t).position(cursor)) }
                            note = when (r) {
                                is Outcome.Failure -> r.diagnostics.first().message
                                null -> null
                                is Outcome.Success -> {
                                    val target = r.value.firstOrNull()
                                    val offset = target?.takeIf { it.uri == session.uri }?.let { LineIndex(buffer.text).offset(it.range.start) }
                                    when {
                                        target == null -> "Определение не найдено"
                                        offset != null -> { buffer.select(offset); onChange(); null }
                                        else -> "Определение: ${DocumentUri.toPath(target.uri) ?: target.uri}, строка ${target.range.start.line + 1}"
                                    }
                                }
                            }
                        }
                    }) { Text("Определение") }
                    TextButton(enabled = enabled && !busy, onClick = {
                        launch("…") {
                            val cursor = buffer.state.selection.end
                            val before = buffer.text
                            when (val r = withClient { c, _, _ -> c.format(session.uri, tabSize, spaces = !tabs) }) {
                                is Outcome.Failure -> note = r.diagnostics.first().message
                                is Outcome.Success -> if (buffer.text != before) note = "Текст изменился, пока служба форматировала; повторите" else if (r.value.isEmpty()) note = "Уже отформатировано" else apply(Assist.applyEdits(before, cursor, r.value), "Отформатировано; отмена — ↶")
                                null -> note = null
                            }
                        }
                    }) { Text("Формат") }
                    TextButton(onClick = { kotlin.concurrent.thread(name = "dpai-code-service-release") { CodeService.release(session) } }) { Text("Выкл") }
                }
            }
        }
        if (state is CodeService.State.Ready && state.compileCommands == null) {
            Text("Нет compile_commands.json: служба не знает флагов сборки и заголовков проекта — подсказки неполны (CMake: -DCMAKE_EXPORT_COMPILE_COMMANDS=ON)", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
        note?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        if (suggestions.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            for (item in suggestions) TextButton(enabled = enabled, onClick = {
                val cursor = buffer.state.selection.end
                suggestions = emptyList()
                apply(Assist.complete(buffer.text, cursor, item), null)
            }) { Text(item.label.trim().take(40), fontFamily = FontFamily.Monospace) }
        }
    }
    details?.let { text ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text("Сведения") },
            text = { Clipboard.Sensitive { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } } },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Закрыть") } })
    }
    if (problems) ProblemsDialog(diagnostics, onGo = { d -> problems = false; if (buffer.goToLine(d.range.start.line + 1)) onChange() }) { problems = false }
}

@Composable
private fun ProblemsDialog(items: List<LspDiagnostic>, onGo: (LspDiagnostic) -> Unit, onClose: () -> Unit) {
    val sorted = remember(items) { items.sortedWith(compareBy({ it.severity.ordinal }, { it.range.start })) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Замечания службы") },
        text = {
            if (sorted.isEmpty()) Text("Замечаний нет") else LazyColumn {
                items(sorted) { d ->
                    TextButton(onClick = { onGo(d) }) {
                        Text("${d.range.start.line + 1}:${d.range.start.character + 1} · ${d.message}",
                            color = if (d.severity == LspSeverity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } })
}
