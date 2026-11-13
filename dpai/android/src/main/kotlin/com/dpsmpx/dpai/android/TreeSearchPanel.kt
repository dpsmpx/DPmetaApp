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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.TreeSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Поиск по проекту в IDE (DPmetaApp:D-116, D-149): по именам файлов и по
 * коду, от папки [dir] вниз. Ищет после паузы ввода и вне потока
 * интерфейса; новый запрос отменяет прежний обход. Касание файла открывает
 * вкладку, касание строки кода — вкладку на этой строке.
 */
@Composable
internal fun TreeSearchPanel(tree: FileTree, dir: String, stateKey: String, onOpen: (String) -> Unit, onOpenAt: (String, Int) -> Unit, replaceBlocked: String? = null, onClose: () -> Unit) {
    var regex by rememberSaveable(stateKey) { mutableStateOf(false) }
    var replacement by rememberSaveable(stateKey) { mutableStateOf("") }
    var replacing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var query by rememberSaveable(stateKey) { mutableStateOf("") }
    var inCode by rememberSaveable(stateKey) { mutableStateOf(false) }
    var matchCase by rememberSaveable(stateKey) { mutableStateOf(false) }
    var names by remember { mutableStateOf<TreeSearch.Result<String>?>(null) }
    var lines by remember { mutableStateOf<TreeSearch.Result<TreeSearch.TextHit>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(onBack = onClose)
    LaunchedEffect(query, inCode, matchCase, regex, dir, round) {
        error = null
        if (query.isBlank() || (inCode && query.length < 2)) {
            names = null
            lines = null
            searching = false
            return@LaunchedEffect
        }
        delay(350)
        searching = true
        try {
            // Отмена сопрограммы (новый запрос, уход с экрана) останавливает обход дерева между файлами.
            val failure = withContext(Dispatchers.IO) {
                val cancelled = { !isActive }
                if (inCode) {
                    when (val r = TreeSearch.text(tree, query, ignoreCase = !matchCase, dir = dir, regex = regex, cancelled = cancelled)) {
                        is Outcome.Success -> { if (isActive) { lines = r.value; names = null }; null }
                        is Outcome.Failure -> r.diagnostics.first().message
                    }
                } else {
                    when (val r = TreeSearch.names(tree, query, dir = dir, cancelled = cancelled)) {
                        is Outcome.Success -> { if (isActive) { names = r.value; lines = null }; null }
                        is Outcome.Failure -> r.diagnostics.first().message
                    }
                }
            }
            error = failure
        } finally {
            searching = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            TextButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Закрыть поиск" }) { Text("←") }
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text(if (inCode) "Найти в коде" else "Найти файл") }, singleLine = true)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            FilterChip(!inCode, { inCode = false }, label = { Text("Файлы") })
            FilterChip(inCode, { inCode = true }, label = { Text("Код") }, modifier = Modifier.padding(start = 8.dp))
            if (inCode) FilterChip(matchCase, { matchCase = !matchCase }, label = { Text("Регистр") }, modifier = Modifier.padding(start = 8.dp))
            if (inCode) FilterChip(regex, { regex = !regex }, label = { Text("Выражение") }, modifier = Modifier.padding(start = 8.dp))
            Text(if (dir.isEmpty()) "во всём проекте" else "в папке $dir", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
        // Замена по проекту (DPmetaApp:D-116, D-150): только по найденному в коде и только после окна с числами.
        if (inCode && lines?.hits?.isNotEmpty() == true) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(replacement, { replacement = it.take(2000) }, Modifier.weight(1f), label = { Text(if (regex) "Заменить на ($1 — группа)" else "Заменить на") }, singleLine = true)
                TextButton(enabled = replaceBlocked == null && !replacing && !searching, onClick = { confirm = true }) { Text(if (replacing) "Заменяю…" else "Заменить все…") }
            }
            replaceBlocked?.let { Text("Замена недоступна: $it", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        }
        report?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        if (searching || replacing) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        val summary = names?.let { status(it.hits.size, it.limited, it.scanned, 0, "файлов") } ?: lines?.let { status(it.hits.size, it.limited, it.scanned, it.skipped, "строк") }
        summary?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
            names?.let { r ->
                items(r.hits, key = { it }) { path ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(path) }.padding(vertical = 8.dp)) {
                        Text(path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if ('/' in path) Text(path.substringBeforeLast('/'), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            lines?.let { r ->
                items(r.hits, key = { "${it.path}:${it.line}" }) { hit ->
                    Column(Modifier.fillMaxWidth().clickable { onOpenAt(hit.path, hit.line) }.padding(vertical = 8.dp)) {
                        Text("${hit.path}:${hit.line}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(hit.preview, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (confirm) ReplaceConfirm(query, replacement, regex, if (dir.isEmpty()) "весь проект" else "папка $dir", lines?.hits?.size ?: 0, lines?.limited == true, onCancel = { confirm = false }) {
        confirm = false
        replacing = true
        report = null
        scope.launch {
            val r = try {
                withContext(Dispatchers.IO) { TreeSearch.replace(tree, query, replacement, ignoreCase = !matchCase, regex = regex, dir = dir) }
            } finally {
                replacing = false
            }
            report = when (r) {
                is Outcome.Failure -> "Не заменено: ${r.diagnostics.first().message}"
                is Outcome.Success -> buildString {
                    append("Заменено: ${r.value.replacements} в файлах: ${r.value.files}")
                    if (r.value.failed.isNotEmpty()) append(" · не записано: ${r.value.failed.size} (${r.value.failed.first().let { "${it.first}: ${it.second}" }})")
                    if (r.value.skipped > 0) append(" · пропущено: ${r.value.skipped}")
                    if (r.value.limited) append(" · обход неполон — повторите поиск")
                }
            }
            round++
        }
    }
}

/** Окно перед заменой по проекту: что на что, где и чем вернуть. */
@Composable
private fun ReplaceConfirm(query: String, replacement: String, regex: Boolean, where: String, found: Int, limited: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Заменить во всех файлах?") },
        text = {
            Column {
                Text((if (regex) "Выражение" else "Текст") + ":", style = MaterialTheme.typography.labelSmall)
                Text(query, fontFamily = FontFamily.Monospace)
                Text("на:", style = MaterialTheme.typography.labelSmall)
                Text(replacement.ifEmpty { "(пусто — найденное удаляется)" }, fontFamily = FontFamily.Monospace)
                Text("Где: $where. Строк с находками: $found" + (if (limited) " — и это не все, заменено будет больше" else "") + ". Заменяются все находки в каждой строке.", Modifier.padding(top = 8.dp))
                Text("Файлы записываются сразу, отмены в редакторе нет: вернуть можно только средствами Git (git diff, git checkout).", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Заменить") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}

/** «Найдено: 12 строк в 40 файлах» и честные оговорки: показано не всё, часть файлов пропущена. */
private fun status(found: Int, limited: Boolean, scanned: Int, skipped: Int, what: String): String = buildString {
    append(if (found == 0) "Ничего не найдено" else "Найдено $what: $found")
    append(" · просмотрено файлов: $scanned")
    if (skipped > 0) append(" · пропущено (большие, не текст): $skipped")
    if (limited) append(" · показано не всё — уточните запрос")
}
