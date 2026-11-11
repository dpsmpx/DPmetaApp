package com.dpsmpx.dpai.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Восстановление копированием: не требует существования исходного дерева и не переназначает путь (D-132). */
@Composable
internal fun FileDraftsDialog(onClose: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }
    val drafts = Drafts.fileDrafts()
    val draft = selected?.let(Drafts::file)
    if (draft == null) FullScreenDialog(onClose, "Черновики файлов в памяти") {
        LazyColumn {
            item { Text("После смерти процесса черновики теряются. Здесь можно скопировать текст, даже если дерево задачи уже убрано.", style = MaterialTheme.typography.bodySmall) }
            if (drafts.isEmpty()) item { Text("Несохранённых правок нет", Modifier.padding(12.dp)) }
            items(drafts, key = { it.first }) { (key, _) ->
                Text(key.substringAfterLast('\u0001'), Modifier.fillMaxWidth().clickable { selected = key }.padding(12.dp))
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
