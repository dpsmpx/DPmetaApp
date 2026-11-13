package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.git.GitStatus
import com.dpsmpx.dpai.ui.ProjectEntry
import com.dpsmpx.dpai.ui.WorkspaceLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Наблюдение Git отдельно от назначенной ветки; усечение не становится успешным снимком (D-133). */
@Composable
internal fun WorkspaceGitPanel(model: DemoModel, project: ProjectEntry, location: WorkspaceLocation) {
    var status by remember(project, location) { mutableStateOf<GitStatus?>(null) }
    var error by remember(project, location) { mutableStateOf<String?>(null) }
    var busy by remember(project, location) { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load() {
        busy = true
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { model.workspaceGit(project, location) }) {
                is Outcome.Success -> { status = r.value; error = null }
                is Outcome.Failure -> error = r.errors.first().message
            }
            busy = false
        }
    }
    LaunchedEffect(project, location) { load() }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row {
            Text("Снимок Git", Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            TextButton(enabled = !busy, onClick = ::load) { Text(if (busy) "Обновляю…" else "Обновить Git") }
        }
        error?.let { Text("Не обновлён: $it", color = MaterialTheme.colorScheme.error) }
        status?.let { s ->
            Text("Ветка на диске: ${s.branch ?: if (s.head != null) "detached HEAD" else "неизвестна"}")
            Text("HEAD: ${s.head ?: "коммитов ещё нет"}", style = MaterialTheme.typography.bodySmall)
            if (s.branch != location.branch) Text("Ветка отличается от назначенной: ${location.branch}", color = MaterialTheme.colorScheme.error)
            Text(if (s.clean) "Рабочее дерево чистое" else "Изменено: ${s.changed.size} · новые: ${s.untracked.size} · конфликты: ${s.conflicted.size}")
            if (!s.clean) {
                TextButton(onClick = { details = !details }) { Text(if (details) "Скрыть файлы" else "Показать файлы") }
                if (details) Clipboard.Sensitive {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Column {
                            for ((label, files) in listOf("Изменён" to s.changed, "Не отслеживается" to s.untracked, "Конфликт" to s.conflicted)) {
                                files.take(100).forEach { Text("$label: $it", style = MaterialTheme.typography.bodySmall) }
                                if (files.size > 100) Text("Ещё ${files.size - 100}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
