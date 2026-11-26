package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.contexts.ContextMode
import com.dpsmpx.dpai.contexts.ContextProfile
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.ContextSettings
import com.dpsmpx.dpai.ui.ProjectEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Настройки человека в существующем центре инструментов, без ещё одного экрана или гранта. */
@Composable
internal fun ContextSettingsSection(project: ProjectEntry, running: Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var refresh by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var expanded by remember(project.project) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember(project.project) { mutableStateOf<ContextSettings?>(null) }
    var problem by remember(project.project) { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(ContextMode.PROJECT_WIDE) }
    var roots by remember { mutableStateOf(".") }
    var pinned by remember { mutableStateOf("") }
    var rules by remember { mutableStateOf("AGENTS.md\nCLAUDE.md") }
    var chars by remember { mutableStateOf("64000") }
    var tokens by remember { mutableStateOf("16000") }
    var history by remember { mutableStateOf("20") }
    var expansion by remember { mutableStateOf(true) }
    fun assign(s: ContextSettings) {
        saved = s
        mode = s.profile.mode; roots = s.profile.roots.joinToString("\n"); pinned = s.profile.pinned.joinToString("\n")
        rules = s.profile.constraints.joinToString("\n"); chars = s.profile.maxChars.toString(); tokens = s.profile.maxTokens.toString()
        history = s.profile.historyMessages.toString(); expansion = s.profile.allowExpansion
    }
    LaunchedEffect(project, refresh) {
        val result = withContext(Dispatchers.IO) {
            val target = Controller.get(context.filesDir).targets().firstOrNull { it.id == project.target }
            if (target == null) Outcome.failure(DiagCode.STATE_CONFLICT, "target удалён") else ProjectContexts.store(context, project, target).load()
        }
        when (result) { is Outcome.Failure -> problem = result.errors.joinToString("; ") { it.message }; is Outcome.Success -> { assign(result.value); problem = null } }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.context_settings, saved?.profile?.mode?.let { stringResource(contextModeTitle(it)) } ?: "?")) }
        if (expanded) {
            TextButton(enabled = !busy, onClick = { refresh++ }) { Text(stringResource(R.string.context_reload)) }
            Text(stringResource(R.string.context_intro), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (m in ContextMode.entries) FilterChip(selected = mode == m, enabled = !running && !busy,
                    onClick = { mode = m; if (m == ContextMode.PROJECT_WIDE || m == ContextMode.FULL_ACCESS) roots = "." }, label = { Text(stringResource(contextModeTitle(m))) })
            }
            Text(stringResource(when (mode) {
                ContextMode.MINIMAL -> R.string.context_minimal; ContextMode.SCOPED -> R.string.context_scoped
                ContextMode.PROJECT_WIDE -> R.string.context_project; ContextMode.FULL_ACCESS -> R.string.context_full; ContextMode.CUSTOM -> R.string.context_custom
            }), style = MaterialTheme.typography.bodySmall)
            fun lines(value: String) = value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            OutlinedTextField(roots, { roots = it.take(32_000) }, enabled = !busy && mode !in setOf(ContextMode.PROJECT_WIDE, ContextMode.FULL_ACCESS),
                label = { Text(stringResource(R.string.context_roots)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pinned, { pinned = it.take(32_000) }, enabled = !busy, label = { Text(stringResource(R.string.context_pinned)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(rules, { rules = it.take(16_000) }, enabled = !busy, label = { Text(stringResource(R.string.context_rules)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(chars, { chars = it.take(7) }, enabled = !busy, label = { Text(stringResource(R.string.context_chars)) })
            OutlinedTextField(tokens, { tokens = it.take(6) }, enabled = !busy, label = { Text(stringResource(R.string.context_tokens)) })
            OutlinedTextField(history, { history = it.take(3) }, enabled = !busy, label = { Text(stringResource(R.string.context_history)) })
            FilterChip(selected = expansion, onClick = { expansion = !expansion }, enabled = !busy, label = { Text(stringResource(R.string.context_expansion)) })
            TextButton(enabled = saved != null && !busy && !running, onClick = {
                val profile = try { ContextProfile(mode, lines(roots), lines(pinned), lines(rules), chars.toInt(), tokens.toLong(), expansion, history.toInt()) }
                    catch (_: IllegalArgumentException) { problem = context.getString(R.string.context_invalid); return@TextButton }
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        val target = Controller.get(context.filesDir).targets().firstOrNull { it.id == project.target }
                            ?: return@withContext Outcome.failure(DiagCode.STATE_CONFLICT, "target удалён")
                        val model = DemoModel.get(context)
                        val view = when (val r = model.apiWorkspace(project)) { is Outcome.Failure -> return@withContext r; is Outcome.Success -> r.value }
                        model.workspaceAccess(project, view.main, write = true) {
                            val store = ProjectContexts.store(context, project, target)
                            when (val r = store.save(profile, saved?.revision)) { is Outcome.Failure -> r; is Outcome.Success -> store.load() }
                        }
                    }
                    when (result) {
                        is Outcome.Failure -> problem = result.errors.joinToString("; ") { it.message }
                        is Outcome.Success -> { assign(result.value); problem = context.getString(R.string.context_saved); onChanged() }
                    }
                    busy = false
                }
            }) { Text(stringResource(R.string.context_save)) }
            if (saved == null) TextButton(enabled = !busy && !running, onClick = {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        val target = Controller.get(context.filesDir).targets().firstOrNull { it.id == project.target }
                            ?: return@withContext Outcome.failure(DiagCode.STATE_CONFLICT, "target удалён")
                        val model = DemoModel.get(context)
                        val view = when (val r = model.apiWorkspace(project)) { is Outcome.Failure -> return@withContext r; is Outcome.Success -> r.value }
                        model.workspaceAccess(project, view.main, write = true) {
                            val store = ProjectContexts.store(context, project, target)
                            when (val r = store.reset()) { is Outcome.Failure -> r; is Outcome.Success -> store.load() }
                        }
                    }
                    when (result) { is Outcome.Failure -> problem = result.errors.joinToString("; ") { it.message }; is Outcome.Success -> { assign(result.value); problem = null; onChanged() } }
                    busy = false
                }
            }) { Text(stringResource(R.string.context_reset)) }
            problem?.let { DiagnosticText(it) }
        }
    }
}

private fun contextModeTitle(mode: ContextMode): Int = when (mode) {
    ContextMode.MINIMAL -> R.string.context_mode_minimal
    ContextMode.SCOPED -> R.string.context_mode_scoped
    ContextMode.PROJECT_WIDE -> R.string.context_mode_project
    ContextMode.FULL_ACCESS -> R.string.context_mode_full
    ContextMode.CUSTOM -> R.string.context_mode_custom
}
