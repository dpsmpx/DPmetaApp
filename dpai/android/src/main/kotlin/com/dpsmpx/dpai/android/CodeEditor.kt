package com.dpsmpx.dpai.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.ui.CodeEditorBuffer
import com.dpsmpx.dpai.ui.CodeHighlight
import com.dpsmpx.dpai.ui.CodeSyntax
import com.dpsmpx.dpai.ui.CodeToken
import com.dpsmpx.dpai.ui.DiffKind
import com.dpsmpx.dpai.ui.DiffLine
import com.dpsmpx.dpai.ui.EditorChanges
import com.dpsmpx.dpai.ui.EditorMatches
import com.dpsmpx.dpai.ui.EditorSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Android рисует модель D-131; дорогой поиск, подсветка и просмотр правок — вне UI. */
@Composable
internal fun CodeEditor(buffer: CodeEditorBuffer, path: String, revision: Int, onChange: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    var value by remember(buffer) { mutableStateOf(TextFieldValue(buffer.text, TextRange(buffer.state.selection.start, buffer.state.selection.end))) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf(EditorMatches(emptyList(), false)) }
    var highlight by remember { mutableStateOf<Pair<String, CodeHighlight>?>(null) }
    var lineDialog by remember { mutableStateOf(false) }
    var changes by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    // Синхронизация после undo/поиска; composition IME при обычном вводе сохраняется.
    LaunchedEffect(revision) {
        val s = buffer.state.selection
        if (value.text != buffer.text || value.selection != TextRange(s.start, s.end)) value = TextFieldValue(buffer.text, TextRange(s.start, s.end))
    }
    LaunchedEffect(buffer.text, path) {
        val text = buffer.text
        delay(150)
        highlight = text to withContext(Dispatchers.Default) { CodeSyntax.highlight(path, text) }
    }
    LaunchedEffect(buffer.text, query, caseSensitive, searching) {
        val text = buffer.text
        delay(150)
        matches = withContext(Dispatchers.Default) { if (searching) CodeEditorBuffer.find(text, query, !caseSensitive) else EditorMatches(emptyList(), false) }
    }
    val colors = MaterialTheme.colorScheme
    val styled = remember(highlight, colors) {
        highlight?.let { (text, h) ->
            AnnotatedString.Builder(text).apply {
                for (span in h.spans) addStyle(SpanStyle(color = when (span.kind) {
                    CodeToken.KEYWORD -> colors.primary
                    CodeToken.STRING -> colors.tertiary
                    CodeToken.COMMENT -> colors.onSurfaceVariant
                    CodeToken.NUMBER -> colors.secondary
                }), span.start, span.end)
            }.toAnnotatedString()
        }
    }
    fun changed() {
        value = TextFieldValue(buffer.text, TextRange(buffer.state.selection.start, buffer.state.selection.end))
        onChange()
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(enabled = enabled && buffer.canUndo, onClick = { buffer.undo(); changed() }) { Text("Отменить") }
            TextButton(enabled = enabled && buffer.canRedo, onClick = { buffer.redo(); changed() }) { Text("Повторить") }
            TextButton(onClick = { searching = !searching }) { Text("Поиск") }
            TextButton(onClick = { lineDialog = true }) { Text("Строка") }
            TextButton(enabled = enabled, onClick = { buffer.replaceSelection("    "); changed() }) { Text("Отступ") }
            TextButton(onClick = { changes = true }) { Text("Изменения") }
        }
        if (searching) {
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
        Text("${CodeSyntax.language(path)} · ${buffer.line}:${buffer.column}${if (highlight?.second?.limited == true) " · подсветка сокращена" else ""}", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall)
        note?.let { Text(it, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(
            value, { next ->
                if (buffer.edit(next.text, EditorSelection(next.selection.start, next.selection.end), typing = true)) {
                    value = next
                    onChange()
                } else note = "Достигнут предел размера текста"
            }, Modifier.weight(1f).fillMaxWidth().padding(8.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            readOnly = !enabled,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            visualTransformation = VisualTransformation { source ->
                TransformedText(styled?.takeIf { it.text == source.text } ?: source, OffsetMapping.Identity)
            },
        )
    }
    if (lineDialog) {
        var line by remember { mutableStateOf("") }
        var invalid by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { lineDialog = false }, title = { Text("Перейти к строке") },
            text = { OutlinedTextField(line, { line = it; invalid = false }, label = { Text("Номер строки") }, singleLine = true, isError = invalid) },
            confirmButton = { TextButton(onClick = {
                if (line.toIntOrNull()?.let(buffer::goToLine) == true) { changed(); lineDialog = false } else invalid = true
            }) { Text("Перейти") } },
            dismissButton = { TextButton(onClick = { lineDialog = false }) { Text("Отмена") } },
        )
    }
    if (changes) EditorChangesDialog(buffer.original, buffer.text) { changes = false }
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
