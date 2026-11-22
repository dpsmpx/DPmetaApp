package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.ui.ProjectEntry
import com.dpsmpx.dpai.ui.TargetEntry

/** Подключение к проекту прямо из чата (D-219): существующий target или штатные мастера создания. */
@Composable
internal fun ChatTargetPicker(controller: Controller, selected: ProjectEntry?, onPick: (ProjectEntry) -> Unit, onDismiss: () -> Unit) {
    var targets by remember { mutableStateOf(controller.targets()) }
    var projects by remember { mutableStateOf(controller.projects().projects) }
    var target by remember { mutableStateOf(selected?.target ?: targets.firstOrNull()?.id) }
    var newTarget by remember { mutableStateOf(false) }
    var newProject by remember { mutableStateOf(false) }
    val chosen = targets.firstOrNull { it.id == target }

    if (newTarget) {
        AddTargetDialog(controller) { created ->
            newTarget = false
            targets = controller.targets()
            if (created != null) target = created.id
        }
    } else if (newProject && chosen != null) {
        ProjectDialog(controller, chosen, targets.filter { it.configDir != null }) { created ->
            newProject = false
            projects = controller.projects().projects
            if (created != null) onPick(created)
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Проект и target чата") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Выберите место, где находится код проекта. API-ключи не передаются на target; инструменты доступны только после явной привязки.", style = MaterialTheme.typography.bodySmall)
                    if (targets.isEmpty()) Text("Подключённых targets пока нет.", style = MaterialTheme.typography.bodySmall)
                    for (t in targets) {
                        FilterChip(selected = target == t.id, onClick = { target = t.id }, label = { Text(t.label + if (t.inApp) " · Linux" else " · SSH") })
                    }
                    val matches = projects.filter { it.target == target }
                    if (matches.isEmpty() && chosen != null) Text("На этом target ещё нет рабочего проекта.", style = MaterialTheme.typography.bodySmall)
                    for (p in matches) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(p.root.substringAfterLast('/').substringAfterLast('\\'), style = MaterialTheme.typography.bodyMedium)
                                Text(p.root, style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { onPick(p) }) { Text(if (selected?.project == p.project) "Выбран" else "Подключить") }
                        }
                    }
                    Text("При смене проекта полный доступ будет отозван. История чата сохранится; новому исполнителю она передаётся только по вашему действию.", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { newTarget = true }) { Text("Новый target") }
                    TextButton(enabled = chosen != null, onClick = { newProject = true }) { Text("Новый проект") }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        )
    }
}
