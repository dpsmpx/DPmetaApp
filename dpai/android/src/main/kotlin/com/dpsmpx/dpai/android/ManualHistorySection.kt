package com.dpsmpx.dpai.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.BuildProblems
import com.dpsmpx.dpai.ui.ManualHistoryEntry
import com.dpsmpx.dpai.ui.ManualOutcome
import com.dpsmpx.dpai.ui.ManualProfileKind
import com.dpsmpx.dpai.ui.ProjectEntry
import com.dpsmpx.dpai.ui.WorkspaceLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * История ручных запусков вида [kind] (D-156): новые — сверху; касание раскрывает
 * шаги, итог, ошибки с переходом в код (D-153) и хвост вывода. [refresh] — признак
 * нового итога: после него список перечитывается.
 */
@Composable
internal fun ManualHistorySection(model: DemoModel, project: ProjectEntry, kind: ManualProfileKind, location: WorkspaceLocation?, refresh: Any?, onProblem: (String, Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var entries by remember(project) { mutableStateOf<List<ManualHistoryEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(project, refresh, version) {
        when (val r = withContext(Dispatchers.IO) { model.manualHistory(project) }) {
            is Outcome.Failure -> { error = r.errors.first().message; entries = emptyList() }
            is Outcome.Success -> { error = null; entries = r.value }
        }
    }
    val shown = entries.filter { it.kind == kind }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("История", Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            if (entries.isNotEmpty() || error != null) TextButton(onClick = { clearing = true }) { Text("Очистить") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (shown.isEmpty() && error == null) Text("Запусков ещё не было.", style = MaterialTheme.typography.bodySmall)
        for (e in shown) {
            val expanded = open == e.run.value
            Column(Modifier.fillMaxWidth().clickable { open = if (expanded) null else e.run.value }.padding(vertical = 6.dp)) {
                Text("${mark(e.outcome)} ${time(e)} · ${e.outcome.title}${e.duration?.let { " · ${span(it)}" }.orEmpty()}", style = MaterialTheme.typography.bodyMedium,
                    color = if (e.outcome == ManualOutcome.OK) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                Text("${e.target} · ${e.branch}${e.head?.let { "@${it.take(7)}" }.orEmpty()} · шагов ${e.steps.count { it.second?.ok == true }} из ${e.steps.size}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (expanded) HistoryDetails(e, location, onProblem)
            }
        }
    }
    if (clearing) AlertDialog(
        onDismissRequest = { clearing = false },
        title = { Text("Очистить историю запусков?") },
        text = { Text("Удалятся записи «Сборки» и «Проверок» этого проекта; профили и последний итог останутся.") },
        confirmButton = { TextButton(onClick = {
            clearing = false
            scope.launch {
                val r = withContext(Dispatchers.IO) { model.clearManualHistory(project) }
                error = (r as? Outcome.Failure)?.errors?.first()?.message
                version++
            }
        }) { Text("Очистить") } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("Отмена") } },
    )
}

@Composable
private fun HistoryDetails(e: ManualHistoryEntry, location: WorkspaceLocation?, onProblem: (String, Int) -> Unit) {
    Column(Modifier.padding(start = 12.dp, top = 4.dp)) {
        e.steps.forEachIndexed { i, (name, r) ->
            val m = when {
                r == null -> "·"
                r.ok -> "✓"
                else -> "✗"
            }
            Text("$m ${i + 1}. $name — ${r?.let { if (it.ok) "успешно" else it.reason } ?: "не запускался"}", style = MaterialTheme.typography.bodySmall)
        }
        val problems = remember(e.output) { BuildProblems.parse(e.output) }
        if (problems.items.isNotEmpty()) {
            Text("Ошибки — ${problems.summary()}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            for (p in problems.items.take(30)) {
                val rel = location?.let { BuildProblems.relative(p.path, it.path) }
                Text("${rel ?: p.path}:${p.line} — ${p.message}", style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    modifier = if (rel != null) Modifier.clickable { onProblem(rel, p.line) }.padding(vertical = 4.dp) else Modifier.padding(vertical = 4.dp))
            }
        }
        if (e.output.isNotEmpty()) SelectionContainer {
            Text(e.output.takeLast(4000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun mark(o: ManualOutcome): String = when (o) {
    ManualOutcome.OK -> "✓"
    ManualOutcome.FAILED -> "✗"
    ManualOutcome.STOPPED -> "■"
    ManualOutcome.UNCONFIRMED -> "?"
}

private val TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm")

private fun time(e: ManualHistoryEntry): String = TIME.format(Instant.ofEpochMilli(e.finishedAt.epochMillis).atZone(ZoneId.systemDefault()))

private fun span(d: kotlin.time.Duration): String {
    val s = d.inWholeSeconds
    return when {
        s < 60 -> "$s с"
        s < 3600 -> "${s / 60} мин ${s % 60} с"
        else -> "${s / 3600} ч ${s % 3600 / 60} мин"
    }
}
