package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dpsmpx.dpai.automation.AutomationInvocation
import com.dpsmpx.dpai.automation.Workflow
import com.dpsmpx.dpai.automation.WorkflowStep
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.ui.ProjectEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Редкий редактор сценария раскрывается на месте; импорт не включает фоновые действия. */
@Composable
internal fun WorkflowSection(project: ProjectEntry, snapshot: ApiProjectRuntime.Session.Snapshot, running: Boolean, changed: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var workflows by remember(project.project) { mutableStateOf<List<Workflow>>(emptyList()) }
    var note by remember(project.project) { mutableStateOf<String?>(null) }
    // Текст команды не помещается в Bundle; несохранённый импорт остаётся только в памяти.
    var editor by remember(project.project) { mutableStateOf<String?>(null) }
    var expected by remember(project.project) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var results by remember(project.project) { mutableStateOf<Map<String, JsonObject>>(emptyMap()) }
    val templateTitle = stringResource(R.string.workflow_template_title)
    LaunchedEffect(project, snapshot, refresh, running) {
        when (val r = withContext(Dispatchers.IO) { AutomationWorkflows.store(context, project.project).list() }) {
            is Outcome.Failure -> note = r.errors.joinToString("; ") { it.message }
            is Outcome.Success -> {
                workflows = r.value
                results = withContext(Dispatchers.IO) { r.value.mapNotNull { w ->
                    AutomationWorkflows.store(context, project.project).latestSummary(w.id).valueOrNull()?.let { w.id to it }
                }.toMap() }
            }
        }
    }
    DetailSection(stringResource(R.string.workflow_title), "workflows:" + project.project.value) { Column {
        Poll(project.project, workflows, periodMs = 1500) {
            val fresh = withContext(Dispatchers.IO) { workflows.mapNotNull { w ->
                AutomationWorkflows.store(context, project.project).latestSummary(w.id).valueOrNull()?.let { w.id to it }
            }.toMap() }
            results = fresh
        }
        Text(stringResource(R.string.workflow_intro), style = MaterialTheme.typography.bodySmall)
        for (w in workflows) androidx.compose.runtime.key(w.id) {
            Text(w.title, style = MaterialTheme.typography.titleSmall)
            Row {
                Text(stringResource(R.string.workflow_enabled))
                Switch(w.enabled, enabled = !busy && (!running || w.enabled), onCheckedChange = { enabled ->
                    scope.launch {
                        busy = true
                        val r = withContext(Dispatchers.IO) {
                            AutomationWorkflows.save(context, project.project, w.copy(enabled = enabled).json(), w.revision, imported = false)
                        }
                        note = r.errors.joinToString("; ") { it.message }.takeIf { it.isNotEmpty() }
                        busy = false; refresh++; changed()
                    }
                })
                TextButton(enabled = !running && !busy && w.enabled, onClick = {
                    AutomationUiCalls.startWorkflow(context, project.project, w.id, w.revision)
                }) { Text(stringResource(R.string.automation_run)) }
                TextButton(enabled = !running && !busy, onClick = { editor = CanonicalJson.write(w.json()); expected = w.revision }) {
                    Text(stringResource(R.string.workflow_edit))
                }
            }
            Text(stringResource(R.string.workflow_steps, w.steps.size), style = MaterialTheme.typography.bodySmall)
            results[w.id]?.let { summary ->
                WorkflowResult(project, w.id, summary)
            }
        }
        TextButton(enabled = !running && !busy, onClick = {
            val entries = listOf("manual.check", "manual.build").mapNotNull(snapshot.catalog::byId)
                .filter { it.available && it.invocation == AutomationInvocation.DIRECT }
            val fallback = snapshot.catalog.entries.firstOrNull { it.available && it.invocation == AutomationInvocation.DIRECT }
            val selected = entries.ifEmpty { listOfNotNull(fallback) }
            if (selected.isNotEmpty()) {
                val w = Workflow("project-flow", templateTitle, snapshot.contextRevision,
                    selected.map { d -> WorkflowStep(d.id, d.revision,
                        JsonObject(d.parameters.mapNotNull { p -> p.defaultValue?.let { p.name to com.dpsmpx.dpai.core.serialization.JsonString(it) } }.toMap())) })
                editor = CanonicalJson.write(w.json()); expected = null
            }
        }) { Text(stringResource(R.string.workflow_new)) }
        editor?.let { text ->
            Text(stringResource(R.string.workflow_editor_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { editor = it.take(256_000) }, enabled = !busy, minLines = 4, maxLines = 12,
                modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.workflow_description)) })
            Row {
                TextButton(enabled = !busy && !running, onClick = {
                    scope.launch {
                        busy = true
                        val value = JsonParser().parse(text).valueOrNull() as? JsonObject
                        val r = withContext(Dispatchers.IO) {
                            if (value == null) Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.VALUE_INVALID, "нужен объект сценария JSON")
                            else AutomationWorkflows.save(context, project.project, value, expected, imported = true)
                        }
                        note = r.errors.joinToString("; ") { it.message }.takeIf { it.isNotEmpty() }
                        if (r.isSuccess) { editor = null; refresh++; changed() }
                        busy = false
                    }
                }) { Text(stringResource(R.string.workflow_save_disabled)) }
                TextButton(enabled = !busy, onClick = { editor = null }) { Text(stringResource(R.string.workflow_close)) }
            }
        }
        note?.let { DiagnosticText(it, color = MaterialTheme.colorScheme.error) }
    } }
}

@Composable
private fun WorkflowResult(project: ProjectEntry, workflow: String, summary: JsonObject) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var details by remember(project.project, workflow) { mutableStateOf<String?>(null) }
    var loading by remember(project.project, workflow) { mutableStateOf(false) }
    val state = (summary["status"] as? com.dpsmpx.dpai.core.serialization.JsonString)?.value
    val label = when (state) {
        "RUNNING" -> R.string.workflow_running
        "PASSED" -> R.string.workflow_passed
        "FAILED" -> R.string.workflow_failed
        "BLOCKED" -> R.string.workflow_blocked
        "INCOMPLETE" -> R.string.workflow_incomplete
        "CANCELLED" -> R.string.workflow_cancelled
        else -> R.string.workflow_review
    }
    Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
    TextButton(enabled = !loading, onClick = {
        if (details != null) details = null else scope.launch {
            loading = true
            details = withContext(Dispatchers.IO) {
                when (val r = AutomationWorkflows.store(context, project.project).latest(workflow)) {
                    is Outcome.Failure -> r.errors.joinToString("; ") { it.message }
                    is Outcome.Success -> r.value?.let(CanonicalJson::write)
                }
            }
            loading = false
        }
    }) { Text(stringResource(R.string.automation_last_result)) }
    details?.let { DiagnosticText(it, style = MaterialTheme.typography.bodySmall) }
}
