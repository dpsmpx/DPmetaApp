package com.dpsmpx.dpai.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.ui.EditorTabs

/** Вкладки одного файлового корня; закрытие выполняет действие именно выбранного документа. */
internal class OpenEditorFiles(private val state: MutableState<EditorTabs>, private val pending: MutableState<String?>) {
    val tabs: EditorTabs get() = state.value
    var note: String? by mutableStateOf(null)
    var busy: Boolean by mutableStateOf(false)
    fun open(path: String) {
        if (busy) return
        val next = tabs.open(path)
        note = if (path !in next.paths) "Открыто ${EditorTabs.MAX_OPEN} файлов: закройте одну вкладку" else null
        state.value = next
    }
    fun browse() { if (!busy) state.value = tabs.browse() }
    fun close(path: String) { busy = false; state.value = tabs.close(path) }
    fun next(backwards: Boolean) { if (!busy) state.value = tabs.next(backwards) }
    fun requestClose(path: String) { if (!busy) { open(path); pending.value = path } }
    fun ready(path: String, actions: EditorCallbacks) {
        busy = actions.busy
        if (pending.value == path) { pending.value = null; actions.close() }
    }
}

private val tabsSaver = Saver<EditorTabs, ArrayList<String>>(
    save = { ArrayList(listOf(it.active.orEmpty()) + it.paths) },
    restore = { saved ->
        val paths = saved.drop(1).filter(FileNames::pathValid).distinct().take(EditorTabs.MAX_OPEN)
        EditorTabs(paths, saved.firstOrNull()?.takeIf { it in paths })
    },
)

@Composable
internal fun rememberOpenEditorFiles(key: String): OpenEditorFiles {
    val tabs = rememberSaveable(key, stateSaver = tabsSaver) { mutableStateOf(EditorTabs()) }
    val pending = remember(key) { mutableStateOf<String?>(null) }
    return remember(key) { OpenEditorFiles(tabs, pending) }
}

@Composable
internal fun EditorTabsBar(files: OpenEditorFiles, draftKey: (String) -> String) {
    // Изменения/восстановление черновиков обновляют звёздочки, тексты в state вкладок не хранятся.
    val recovery by Drafts.recovery.collectAsState()
    val tabs = files.tabs
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
        TextButton(enabled = !files.busy, onClick = files::browse) { Text("Файлы") }
        for (path in tabs.paths) {
            val dirty = Drafts.file(draftKey(path))?.editor?.dirty == true
            FilterChip(enabled = !files.busy, selected = tabs.active == path, onClick = { files.open(path) }, label = { Text(FileNames.name(path) + if (dirty) " *" else "") },
                modifier = Modifier.semantics { contentDescription = path + if (dirty) ", есть правки" else "" })
            TextButton(enabled = recovery.ready && !files.busy, onClick = { files.requestClose(path) }, modifier = Modifier.semantics { contentDescription = "Закрыть $path" }) { Text("×") }
        }
    }
    files.note?.let { Text(it, Modifier.padding(horizontal = 12.dp)) }
}
