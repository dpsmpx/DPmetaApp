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
import kotlinx.coroutines.withContext

/**
 * Поиск по проекту в IDE (DPmetaApp:D-116, D-149): по именам файлов и по
 * коду, от папки [dir] вниз. Ищет после паузы ввода и вне потока
 * интерфейса; новый запрос отменяет прежний обход. Касание файла открывает
 * вкладку, касание строки кода — вкладку на этой строке.
 */
@Composable
internal fun TreeSearchPanel(tree: FileTree, dir: String, stateKey: String, onOpen: (String) -> Unit, onOpenAt: (String, Int) -> Unit, onClose: () -> Unit) {
    var query by rememberSaveable(stateKey) { mutableStateOf("") }
    var inCode by rememberSaveable(stateKey) { mutableStateOf(false) }
    var matchCase by rememberSaveable(stateKey) { mutableStateOf(false) }
    var names by remember { mutableStateOf<TreeSearch.Result<String>?>(null) }
    var lines by remember { mutableStateOf<TreeSearch.Result<TreeSearch.TextHit>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(onBack = onClose)
    LaunchedEffect(query, inCode, matchCase, dir) {
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
                    when (val r = TreeSearch.text(tree, query, ignoreCase = !matchCase, dir = dir, cancelled = cancelled)) {
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
            Text(if (dir.isEmpty()) "во всём проекте" else "в папке $dir", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
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
}

/** «Найдено: 12 строк в 40 файлах» и честные оговорки: показано не всё, часть файлов пропущена. */
private fun status(found: Int, limited: Boolean, scanned: Int, skipped: Int, what: String): String = buildString {
    append(if (found == 0) "Ничего не найдено" else "Найдено $what: $found")
    append(" · просмотрено файлов: $scanned")
    if (skipped > 0) append(" · пропущено (большие, не текст): $skipped")
    if (limited) append(" · показано не всё — уточните запрос")
}
