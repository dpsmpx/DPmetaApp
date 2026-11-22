package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.automation.AutomationDefinition
import com.dpsmpx.dpai.automation.AutomationInvocation
import com.dpsmpx.dpai.automation.AutomationSource
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.ui.ProjectEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Первый центр автоматизаций основного дерева; исполнение не принадлежит Compose (D-215). */
@Composable
internal fun AutomationCenter(project: ProjectEntry, onOpen: (AutomationDefinition) -> Unit) {
    EnvironmentApprovalUi(AutomationUiCalls.key(project.project))
    val context = LocalContext.current.applicationContext
    val jobs = remember { LongJobs.get(context) }
    val states by jobs.state.collectAsState()
    val work = states[AutomationUiCalls.key(project.project)]
    val running = work?.running == true
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var snapshot by remember(project.project) { mutableStateOf<ApiProjectRuntime.Session.Snapshot?>(null) }
    var problem by remember(project.project) { mutableStateOf<String?>(null) }
    var filter by rememberSaveable(project.project.value) { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable(project.project.value) { mutableStateOf<String?>(null) }
    LaunchedEffect(project, refresh, running) {
        if (running) return@LaunchedEffect
        loading = true
        val result = withContext(Dispatchers.IO) {
            try {
                when (val session = ApiProjectRuntime.open(context, project.project)) {
                    is Outcome.Failure -> session
                    is Outcome.Success -> session.value.use { it.catalog() }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.INTERNAL_ERROR, "сбой чтения каталога: ${e.javaClass.simpleName}")
            }
        }
        when (result) {
            is Outcome.Failure -> { snapshot = null; problem = result.diagnostics.joinToString("; ") { it.message } }
            is Outcome.Success -> { snapshot = result.value; problem = null }
        }
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(R.string.automation_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.automation_intro), style = MaterialTheme.typography.bodySmall)
            Text(project.root, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(enabled = !running && !loading, onClick = { refresh++ }) { Text(stringResource(R.string.automation_refresh)) }
                if (running) TextButton(onClick = { AutomationUiCalls.stop(project.project) }) { Text(stringResource(R.string.automation_stop)) }
            }
            if (loading || running) CircularProgressIndicator()
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            snapshot?.sourceIssues?.forEach { Text(it.message, color = MaterialTheme.colorScheme.error) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.automation_all)) })
                for (s in AutomationSource.entries) FilterChip(selected = filter == s.name, onClick = { filter = s.name }, label = { Text(sourceTitle(s)) })
            }
        }
        items(snapshot?.catalog?.entries.orEmpty().filter { filter == null || it.source.name == filter }, key = { it.id }) { d ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { expanded = if (expanded == d.id) null else d.id }) { Text(automationTitle(d)) }
                Text(d.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(if (d.available) R.string.automation_configured else R.string.automation_unavailable), style = MaterialTheme.typography.bodySmall)
                if (expanded == d.id) AutomationDetails(d, running,
                    onRun = { values -> AutomationUiCalls.start(context, project.project, d.id, d.revision, JsonObject(values.mapValues { JsonString(it.value) })) },
                    onOpen = { onOpen(d) })
                HorizontalDivider()
            }
        }
        work?.let { job -> item {
            Text(stringResource(R.string.automation_last_result), style = MaterialTheme.typography.titleSmall)
            job.note?.let { Text(it) }
            val result = job.output.firstOrNull()?.let { JsonParser().parse(it).valueOrNull() as? JsonObject }
            result?.let { r ->
                (r["status"] as? JsonString)?.value?.let { Text(it, fontFamily = FontFamily.Monospace) }
                val data = r["data"] as? JsonObject ?: r["evidence"] as? JsonObject
                (data?.get("note") as? JsonString)?.value?.let { Text(it) }
                (r["diagnostics"] as? JsonArray)?.items?.forEach { d -> ((d as? JsonObject)?.get("message") as? JsonString)?.value?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
                val output = (data?.get("output") as? JsonString)?.value ?: data?.let(CanonicalJson::write)
                output?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        } }
    }
}

@Composable
private fun AutomationDetails(d: AutomationDefinition, running: Boolean, onRun: (Map<String, String>) -> Unit, onOpen: () -> Unit) {
    var values by remember(d.revision) { mutableStateOf(d.parameters.associate { it.name to (it.defaultValue ?: it.choices.firstOrNull().orEmpty()) }) }
    Text(stringResource(R.string.automation_level, d.level.code), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(if (d.invocation == AutomationInvocation.DIRECT) R.string.automation_direct else R.string.automation_existing_ui), style = MaterialTheme.typography.bodySmall)
    d.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (d.source == AutomationSource.PULT) Text(d.purpose, style = MaterialTheme.typography.bodySmall)
    if (d.dependencies.isNotEmpty()) Text(stringResource(R.string.automation_dependencies, d.dependencies.joinToString()), style = MaterialTheme.typography.bodySmall)
    d.timeoutSeconds?.let { Text(stringResource(R.string.automation_timeout, it), style = MaterialTheme.typography.bodySmall) }
    for (p in d.parameters) {
        if (p.text) {
            OutlinedTextField(values[p.name].orEmpty(), { values = values + (p.name to it.take(p.maxLength)) },
                enabled = !running, label = { Text(p.title) }, modifier = Modifier.fillMaxWidth())
            continue
        }
        Text(p.title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (v in p.choices) FilterChip(selected = values[p.name] == v, enabled = !running, onClick = { values = values + (p.name to v) }, label = { Text(v) })
        }
    }
    if (d.invocation == AutomationInvocation.DIRECT) TextButton(enabled = !running && d.available && d.parameters.filter { it.required }.all { values[it.name]?.isNotBlank() == true }, onClick = { onRun(values) }) { Text(stringResource(R.string.automation_run)) }
    else TextButton(onClick = onOpen) { Text(stringResource(R.string.automation_open)) }
}

@Composable
private fun sourceTitle(source: AutomationSource): String = stringResource(when (source) {
    AutomationSource.PULT -> R.string.automation_pult
    AutomationSource.MANUAL -> R.string.automation_profiles
    AutomationSource.VERIFICATION -> R.string.automation_verification
    AutomationSource.ENVIRONMENT -> R.string.automation_environment
})

@Composable
private fun automationTitle(d: AutomationDefinition): String = when (d.id) {
    "manual.check" -> stringResource(R.string.automation_check)
    "manual.build" -> stringResource(R.string.automation_build)
    "manual.run" -> stringResource(R.string.automation_launch)
    "manual.dependencies" -> stringResource(R.string.dependencies_title)
    "environment.inspect" -> stringResource(R.string.automation_inspect)
    "environment.probe" -> stringResource(R.string.env_tools_probe)
    "environment.command" -> stringResource(R.string.env_linux_command)
    "environment.android_sdk" -> stringResource(R.string.env_sdk_install)
    else -> when (d.source) {
        AutomationSource.VERIFICATION -> stringResource(R.string.automation_verification_level, d.title)
        AutomationSource.ENVIRONMENT -> stringResource(R.string.automation_package_operation, d.title)
        else -> d.title
    }
}
