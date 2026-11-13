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
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.ui.CodeEditorBuffer
import com.dpsmpx.dpai.ui.CodeSyntax
import com.dpsmpx.dpai.ui.DiffKind
import com.dpsmpx.dpai.ui.DiffLine
import com.dpsmpx.dpai.ui.EditorActions
import com.dpsmpx.dpai.ui.EditorChanges
import com.dpsmpx.dpai.ui.EditorMatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Инструменты IDE над нативным полем; снимки, история и восстановление остаются общими (D-136). */
@Composable
internal fun CodeEditor(buffer: CodeEditorBuffer, path: String, revision: Int, onChange: () -> Unit, enabled: Boolean = true,
    modifier: Modifier = Modifier, callbacks: EditorCallbacks = EditorCallbacks(), guestPath: String? = null, guestRoot: String? = null, presentation: Boolean = false, editing: Boolean = enabled, onActivate: (() -> Unit)? = null) {
    val settings by IdeState.state.collectAsState()
    val zen = presentation && settings.options.zen
    val syntax = if (editing) settings.options.editorSyntax else settings.options.previewSyntax
    val chrome = LocalIdeChrome.current
    val owner = remember { Any() }
    androidx.compose.runtime.DisposableEffect(owner, presentation) {
        if (presentation) chrome.enter(owner)
        onDispose { if (presentation) chrome.leave(owner) }
    }
    var marks by remember { mutableStateOf<List<com.dpsmpx.dpai.lsp.Assist.Mark>>(emptyList()) }
    var searching by rememberSaveable(path) { mutableStateOf(false) }
    var query by rememberSaveable(path) { mutableStateOf("") }
    var replacement by rememberSaveable(path) { mutableStateOf("") }
    var caseSensitive by rememberSaveable(path) { mutableStateOf(false) }
    var wrap by rememberSaveable(path) { mutableStateOf(false) }
    var symbols by rememberSaveable(path) { mutableStateOf(true) }
    // Начальный размер — «Шрифт редактора» из «Оформления» (DPmetaApp:D-115); A− и A+ меняют его для файла.
    val startFont = LocalUiLook.current.editorFont
    var fontSize by rememberSaveable(path) { mutableStateOf(startFont) }
    var width by rememberSaveable(path) { mutableStateOf(4) }
    var tabs by rememberSaveable(path) { mutableStateOf(false) }
    var view by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf(EditorMatches(emptyList(), false)) }
    var lineDialog by remember { mutableStateOf(false) }
    var changes by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableStateOf(1 to 1) }
    val context = LocalContext.current
    LaunchedEffect(buffer.text, query, caseSensitive, searching) {
        val text = buffer.text
        delay(150)
        matches = withContext(Dispatchers.Default) { if (searching) CodeEditorBuffer.find(text, query, !caseSensitive) else EditorMatches(emptyList(), false) }
    }
    fun changed() { onChange() }
    fun command(action: EditorCommand) {
        when (action) {
            EditorCommand.UNDO -> if (enabled) { buffer.undo(); changed() }
            EditorCommand.REDO -> if (enabled) { buffer.redo(); changed() }
            EditorCommand.SAVE -> if (enabled) callbacks.save()
            EditorCommand.FIND, EditorCommand.REPLACE -> searching = true
            EditorCommand.GOTO -> lineDialog = true
            EditorCommand.FILES -> callbacks.browse()
            EditorCommand.NEXT_FILE -> callbacks.next(false)
            EditorCommand.PREV_FILE -> callbacks.next(true)
            EditorCommand.CLOSE_FILE -> callbacks.close()
            EditorCommand.EXIT -> callbacks.exit()
            EditorCommand.RUN -> callbacks.run()
            else -> if (enabled) {
                val result = when (action) {
                    EditorCommand.INDENT -> EditorActions.indent(buffer, width, tabs)
                    EditorCommand.UNINDENT -> EditorActions.indent(buffer, width, tabs, backwards = true)
                    EditorCommand.COMMENT -> EditorActions.comment(buffer, path)
                    EditorCommand.DUPLICATE -> EditorActions.duplicate(buffer)
                    EditorCommand.DELETE_LINE -> EditorActions.deleteLines(buffer)
                    EditorCommand.MOVE_UP -> EditorActions.moveLines(buffer, true)
                    EditorCommand.MOVE_DOWN -> EditorActions.moveLines(buffer, false)
                }
                if (result) { note = null; changed() }
            }
        }
    }
    Column(modifier) {
        if (!zen) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(enabled = enabled && buffer.canUndo, onClick = { command(EditorCommand.UNDO) }) { Text("↶") }
            TextButton(enabled = enabled && buffer.canRedo, onClick = { command(EditorCommand.REDO) }) { Text("↷") }
            TextButton(onClick = { searching = !searching }) { Text("Поиск") }
            TextButton(onClick = { lineDialog = true }) { Text("Строка") }
            TextButton(onClick = { view = !view }) { Text("Вид / команды") }
            TextButton(onClick = { changes = true }) { Text("Правки") }
            callbacks.debug?.let { d -> TextButton(enabled = !callbacks.busy, onClick = d) { Text("Отладка") } }
            callbacks.ai?.let { a -> TextButton(enabled = !callbacks.busy, onClick = a) { Text("В ИИ") } }
            callbacks.uiPreview?.let { p -> TextButton(enabled = !callbacks.busy, onClick = p) { Text("Предпросмотр") } }
        }
        if (view && !zen) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                FilterChip(settings.options.previewSyntax, { IdeState.options(settings.options.copy(previewSyntax = !settings.options.previewSyntax)) }, label = { Text("Подсветка просмотра") })
                FilterChip(settings.options.editorSyntax, { IdeState.options(settings.options.copy(editorSyntax = !settings.options.editorSyntax)) }, label = { Text("Подсветка редактора") })
                FilterChip(wrap, { wrap = !wrap }, label = { Text("Перенос") })
                FilterChip(symbols, { symbols = !symbols }, label = { Text("Клавиши") })
                TextButton(enabled = fontSize > 10, onClick = { fontSize-- }) { Text("A−") }
                Text("$fontSize", Modifier.padding(12.dp))
                TextButton(enabled = fontSize < 32, onClick = { fontSize++ }) { Text("A+") }
                TextButton(onClick = { width = when (width) { 2 -> 4; 4 -> 8; else -> 2 } }) { Text("Отступ: $width") }
                FilterChip(tabs, { tabs = !tabs }, label = { Text("Tab") })
                TextButton(onClick = { licenses = true }) { Text("Лицензии") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                for ((action, label) in listOf(EditorCommand.COMMENT to "Комментарий", EditorCommand.DUPLICATE to "Дублировать", EditorCommand.DELETE_LINE to "Удалить строки", EditorCommand.MOVE_UP to "Выше", EditorCommand.MOVE_DOWN to "Ниже")) {
                    TextButton(enabled = enabled, onClick = { command(action) }) { Text(label) }
                }
            }
            Text("Ctrl+S — сохранить · Ctrl+F/H — поиск/замена · Ctrl+G — строка · Ctrl+/ — комментарий · Ctrl+D — дубль · Alt+↑/↓ — строки · Ctrl+Tab — вкладка · Ctrl+W — закрыть · Ctrl+P — файлы", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (symbols && editing && !zen) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(enabled = enabled, onClick = { command(EditorCommand.INDENT) }) { Text("Tab →") }
            TextButton(enabled = enabled, onClick = { command(EditorCommand.UNINDENT) }) { Text("← Tab") }
            for (pair in listOf("()", "[]", "{}", "\"\"", "''")) TextButton(enabled = enabled, onClick = {
                EditorActions.surround(buffer, pair.take(1), pair.takeLast(1)); changed()
            }) { Text(pair) }
            for (symbol in listOf(";", ":", "=", "/", "_")) TextButton(enabled = enabled, onClick = { buffer.replaceSelection(symbol); changed() }) { Text(symbol) }
        }
        if (searching && !zen) {
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Найти") }, singleLine = true)
                OutlinedTextField(replacement, { replacement = it }, Modifier.weight(1f), label = { Text("Заменить на") }, singleLine = true)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                FilterChip(caseSensitive, { caseSensitive = !caseSensitive }, label = { Text("Регистр") })
                TextButton(onClick = { buffer.findNext(query, backwards = true, ignoreCase = !caseSensitive); changed() }) { Text("Назад") }
                TextButton(onClick = { buffer.findNext(query, ignoreCase = !caseSensitive); changed() }) { Text("Далее") }
                TextButton(enabled = enabled, onClick = { buffer.replaceNext(query, replacement, !caseSensitive); changed() }) { Text("Заменить") }
                TextButton(enabled = enabled, onClick = {
                    val count = buffer.replaceAll(query, replacement, !caseSensitive)
                    note = if (count > 0) "Заменено: $count" else "Нет замен: проверьте совпадения и размер текста"
                    changed()
                }) { Text("Все") }
                Text("${matches.ranges.size}${if (matches.limited) "+" else ""}", Modifier.padding(12.dp))
            }
        }
        if (!zen) Text("${CodeSyntax.language(path)} · ${position.first}:${position.second} · ${if (EditorActions.separator(buffer.text) == "\r\n") "CRLF" else "LF"}", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall)
        if (!zen) note?.let { Text(it, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall) }
        if (guestPath != null && guestRoot != null) CodeAssist(buffer, guestPath, guestRoot, revision, enabled, width, tabs, onChange = { changed() }, onMarks = { marks = it }, compact = zen, onOpen = callbacks.open)
        // Отладка (D-159): точки — касанием номера строки у файлов C, C++, Python в Linux; строка остановки — фоном.
        val debuggable = guestPath != null && com.dpsmpx.dpai.ui.DebugTargets.engineOf(guestPath) != null
        val breakpoints by Debugger.breakpoints.collectAsState()
        val debugState by Debugger.state.collectAsState()
        val debugLines = if (debuggable) breakpoints.lines(guestPath) else emptySet()
        val debugAt = if (debuggable && debugState.view.status is com.dpsmpx.dpai.lsp.dap.DebugStatus.Stopped) debugState.view.location?.takeIf { it.path == guestPath }?.line else null
        NativeCodeField(buffer, path, revision, enabled, wrap, fontSize, width, tabs, marks, syntax, onActivate, presentation, editing, onChange, ::command,
            onPosition = { line, column -> position = line to column }, onIssue = { note = it; if (zen) android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }, onZoom = { fontSize = it }, modifier = Modifier.weight(1f).fillMaxWidth(),
            debugLines = debugLines, debugAt = debugAt, onGutter = if (debuggable) ({ line -> Debugger.toggle(guestPath, line) }) else null)
        if (!zen) DebugPanel()
    }
    if (lineDialog) {
        var line by remember { mutableStateOf("") }
        var invalid by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { lineDialog = false }, title = { Text("Перейти к строке") },
            text = { OutlinedTextField(line, { line = it; invalid = false }, label = { Text("Номер строки") }, singleLine = true, isError = invalid) },
            confirmButton = { TextButton(onClick = {
                if (line.toIntOrNull()?.let(buffer::goToLine) == true) { changed(); lineDialog = false } else invalid = true
            }) { Text("Перейти") } }, dismissButton = { TextButton(onClick = { lineDialog = false }) { Text("Отмена") } })
    }
    if (changes) EditorChangesDialog(buffer.original, buffer.text) { changes = false }
    if (licenses) {
        var license by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { license = withContext(Dispatchers.IO) { listOf("NOTICES.txt", "SORA-LICENSE.txt", "EPL-2.0.txt").joinToString("\n\n") { name -> context.assets.open("editor/$name").bufferedReader().use { it.readText() } } } }
        FullScreenDialog({ licenses = false }, "Sora Editor 0.24.7 · лицензия") {
            SelectionContainer { Text(license, Modifier.verticalScroll(rememberScrollState()).padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun EditorChangesDialog(before: String, after: String, onClose: () -> Unit) {
    var lines by remember { mutableStateOf<List<DiffLine>>(emptyList()) }
    LaunchedEffect(before, after) { lines = withContext(Dispatchers.Default) { EditorChanges.preview(before, after) } }
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Правки относительно открытого файла") },
        text = {
            Clipboard.Sensitive {
                SelectionContainer {
                    LazyColumn {
                        items(lines) { line ->
                            Text(line.text, fontFamily = FontFamily.Monospace, color = when (line.kind) {
                                DiffKind.ADDED -> toneColor(com.dpsmpx.dpai.ui.Tone.DONE)
                                DiffKind.REMOVED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
}
