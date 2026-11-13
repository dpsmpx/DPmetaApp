package com.dpsmpx.dpai.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Восстановление копированием: не требует существования исходного дерева и не переназначает путь (D-132). */
@Composable
internal fun FileDraftsDialog(onClose: () -> Unit) {
    val recovery by Drafts.recovery.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (confirm) AlertDialog(onDismissRequest = { if (!deleting) confirm = false },
        title = { Text("Удалить недоступные копии?") },
        text = { Text("Не удалось восстановить копий: ${recovery.unreadable}. Их удаление отменить нельзя. Сохранённые файлы и читаемые черновики останутся.") },
        confirmButton = { TextButton(enabled = !deleting, onClick = {
            deleting = true
            scope.launch {
                withContext(Dispatchers.IO) { Drafts.discardUnavailable() }
                deleting = false; confirm = false
            }
        }) { Text("Удалить копии", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(enabled = !deleting, onClick = { confirm = false }) { Text("Отмена") } })
    val drafts = Drafts.fileDrafts()
    val draft = selected?.let(Drafts::file)
    if (draft == null) FullScreenDialog(onClose, "Черновики файлов") {
        LazyColumn {
            item { Text("Записанные копии восстанавливаются после перезапуска. Здесь можно скопировать текст, даже если дерево задачи уже убрано.", style = MaterialTheme.typography.bodySmall) }
            if (recovery.note.isNotEmpty()) item { Text(recovery.note, Modifier.padding(12.dp)) }
            if (recovery.unreadable > 0) item { TextButton(onClick = { confirm = true }) { Text("Удалить недоступные копии (${recovery.unreadable})") } }
            if (recovery.ready && drafts.isEmpty()) item { Text("Несохранённых правок нет", Modifier.padding(12.dp)) }
            items(drafts, key = { it.first }) { (key, _) ->
                Text((if (Drafts.file(key)?.restored == true) "Восстановлен: " else "") + key.substringAfterLast('\u0001'), Modifier.fillMaxWidth().clickable { selected = key }.padding(12.dp))
            }
        }
    } else FullScreenDialog({ selected = null }, "Черновик: ${selected!!.substringAfterLast('\u0001')}") {
        Column(Modifier.fillMaxSize()) {
            Text("Просмотр и копирование. Автоматической записи в другое дерево нет.", style = MaterialTheme.typography.bodySmall)
            Clipboard.Sensitive {
                CodeEditor(draft.editor, selected!!.substringAfterLast('\u0001'), revision, onChange = { revision++ }, enabled = false, modifier = Modifier.weight(1f))
            }
        }
    }
}
