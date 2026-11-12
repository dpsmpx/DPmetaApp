package com.dpsmpx.dpai.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.EditorOptions
import com.dpsmpx.dpai.ui.EditorRootSession
import com.dpsmpx.dpai.ui.EditorSessionBook
import com.dpsmpx.dpai.ui.EditorSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Метаданные без исходников: один IO-writer, изменения состояния сразу доступны экрану (D-138). */
internal object IdeState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean()
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private val flow = MutableStateFlow(EditorSessions())
    val state = flow.asStateFlow()
    val ready = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    fun install(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            val book = try {
                val dir = app.noBackupFilesDir.resolve("editor-ui").apply { mkdirs() }
                EditorSessionBook(DocumentStore(NioVfs(dir.toPath()), "ui"))
            } catch (_: java.io.IOException) {
                error.value = "Не удалось открыть настройки IDE; сохранённая копия оставлена"; ready.value = true; return@launch
            } catch (_: SecurityException) {
                error.value = "Нет доступа к настройкам IDE; сохранённая копия оставлена"; ready.value = true; return@launch
            }
            val loaded = book.load()
            when (loaded) {
                is Outcome.Success -> flow.value = loaded.value.copy(roots = loaded.value.roots.filterValues { it.tabs.paths.isNotEmpty() || it.runCommand.isNotBlank() || it.commands.isNotEmpty() })
                is Outcome.Failure -> error.value = loaded.errors.first().message
            }
            ready.value = true
            for (ignored in updates) {
                if (loaded is Outcome.Failure) continue // Не затирать недоступную копию пустым UI.
                error.value = when (val result = book.save(flow.value)) { is Outcome.Success -> null; is Outcome.Failure -> result.errors.first().message }
            }
        }
    }
    fun options(value: EditorOptions) { flow.update { it.copy(options = value) }; updates.trySend(Unit) }
    fun place(value: String?) { flow.update { it.copy(lastPlace = value) }; updates.trySend(Unit) }
    fun root(key: String, value: EditorRootSession) {
        val empty = value.tabs.paths.isEmpty() && value.runCommand.isBlank() && value.commands.isEmpty()
        if (!empty && key !in flow.value.roots && flow.value.roots.size >= EditorSessionBook.MAX_ROOTS) { error.value = "Настройки IDE: достигнут предел открытых папок"; return }
        flow.update { it.copy(roots = if (empty) it.roots - key else it.roots + (key to value)) }; updates.trySend(Unit)
    }
    fun flush() { updates.trySend(Unit) }
}

internal class IdeChrome {
    private var editors by mutableStateOf<Set<Any>>(emptySet())
    val active: Boolean get() = editors.isNotEmpty()
    fun enter(owner: Any) { editors = editors + owner }
    fun leave(owner: Any) { editors = editors - owner }
}
internal val LocalIdeChrome = staticCompositionLocalOf { IdeChrome() }
@Composable internal fun ideZen(): Boolean {
    val state by IdeState.state.collectAsState()
    return LocalIdeChrome.current.active && state.options.zen
}
