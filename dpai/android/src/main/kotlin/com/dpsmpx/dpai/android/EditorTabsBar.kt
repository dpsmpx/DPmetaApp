package com.dpsmpx.dpai.android

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.ui.EditorRootSession
import com.dpsmpx.dpai.ui.EditorTabs

/** Вкладки без зазоров/крестиков; действия относятся именно к выбранному документу (D-138). */
internal class OpenEditorFiles(private val key: String, private val state: MutableState<EditorTabs>) {
    val tabs: EditorTabs get() = state.value
    var note: String? by mutableStateOf(null)
    var busy: Boolean by mutableStateOf(false)
    var canSave: Boolean by mutableStateOf(false)
    private var actions = EditorCallbacks()
    private var pendingClose: String? = null
    private var pendingPreview: String? = null
    private fun change(next: EditorTabs) {
        state.value = next
        val root = IdeState.state.value.roots[key] ?: EditorRootSession()
        IdeState.root(key, root.copy(tabs = next))
    }
    fun open(path: String) {
        if (busy) return
        val next = tabs.open(path)
        note = if (path !in next.paths) "Открыто ${EditorTabs.MAX_OPEN} файлов: закройте одну вкладку" else null
        change(next)
    }
    fun preview(path: String) {
        if (busy) return
        if (tabs.active == path) actions.preview() else { pendingPreview = path; open(path) }
    }
    fun browse() { if (!busy) change(tabs.browse()) }
    fun close(path: String) { busy = false; change(tabs.close(path)) }
    fun next(backwards: Boolean) { if (!busy) { val next = tabs.next(backwards); pendingPreview = next.active; change(next) } }
    fun requestClose(path: String) {
        if (busy) return
        if (tabs.active == path) actions.close() else { pendingClose = path; open(path) }
    }
    fun ready(path: String, callbacks: EditorCallbacks) {
        if (path != tabs.active) return
        actions = callbacks; busy = callbacks.busy; canSave = callbacks.canSave
        if (pendingPreview == path) { pendingPreview = null; callbacks.preview() }
        if (pendingClose == path) { pendingClose = null; callbacks.close() }
    }
    fun exit() { if (!busy) actions.exit() }
    fun save() { if (!busy) actions.save() }
    fun run() { if (!busy) actions.run() }
    fun configureRun() { if (!busy) actions.configureRun() }
}

@Composable
internal fun rememberOpenEditorFiles(key: String): OpenEditorFiles {
    val state = remember(key) {
        val stored = IdeState.state.value.roots[key]?.tabs ?: EditorTabs()
        val paths = stored.paths.filter(FileNames::pathValid).distinct().take(EditorTabs.MAX_OPEN)
        mutableStateOf(EditorTabs(paths, stored.active?.takeIf { it in paths }))
    }
    return remember(key) { OpenEditorFiles(key, state) }
}

@Composable
internal fun EditorTabsBar(files: OpenEditorFiles, draftKey: (String) -> String) {
    val recovery by Drafts.recovery.collectAsState()
    val settings by IdeState.state.collectAsState()
    val zen = ideZen()
    val tabs = files.tabs
    Row(Modifier.fillMaxWidth().height(48.dp)) {
        if (tabs.active == null) IconButton(onClick = files::browse, enabled = !files.busy) { Text("≡") }
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            for (path in tabs.paths) {
                val dirty = Drafts.file(draftKey(path))?.editor?.dirty == true
                var menu by remember(path) { mutableStateOf(false) }
                Box {
                    Text(FileNames.name(path) + if (dirty) " *" else "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.background(if (tabs.active == path) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                            .combinedClickable(enabled = !files.busy, onClick = { files.preview(path) }, onLongClick = { menu = true })
                            .padding(horizontal = 8.dp, vertical = 14.dp).semantics { contentDescription = path + if (dirty) ", есть правки" else "" })
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(path) }, onClick = {}, enabled = false)
                        DropdownMenuItem(text = { Text("Закрыть вкладку") }, enabled = recovery.ready && !files.busy, onClick = { menu = false; files.requestClose(path) })
                        DropdownMenuItem(text = { Text("К списку файлов") }, enabled = !files.busy, onClick = { menu = false; files.exit() })
                        DropdownMenuItem(text = { Text("Команда запуска…") }, enabled = !files.busy && tabs.active == path, onClick = { menu = false; files.configureRun() })
                    }
                }
            }
        }
        if (tabs.active != null) {
            EditorIcon(R.drawable.ic_editor_exit, "Выйти из IDE", !files.busy, files::exit)
            EditorIcon(R.drawable.ic_editor_save, "Сохранить", !files.busy && files.canSave, files::save)
            EditorIcon(R.drawable.ic_editor_view, if (zen) "Полный интерфейс" else "Режим дзен", !files.busy) { IdeState.options(settings.options.copy(zen = !settings.options.zen)) }
            EditorIcon(R.drawable.ic_editor_run, "Запустить в терминале", !files.busy, files::run)
        }
    }
    if (!zen) files.note?.let { Text(it, Modifier.padding(horizontal = 8.dp)) }
}

@Composable
private fun EditorIcon(icon: Int, label: String, enabled: Boolean, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled) { Icon(painterResource(icon), label) }
}
