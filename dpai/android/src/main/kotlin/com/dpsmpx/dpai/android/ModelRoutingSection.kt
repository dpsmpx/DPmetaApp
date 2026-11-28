package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.accounts.AiAdmission
import com.dpsmpx.dpai.accounts.AiBackend
import com.dpsmpx.dpai.accounts.AiMode
import com.dpsmpx.dpai.accounts.AiModePolicy
import com.dpsmpx.dpai.accounts.AiModelRef
import com.dpsmpx.dpai.accounts.AiReservation
import com.dpsmpx.dpai.accounts.ApiProfile
import com.dpsmpx.dpai.core.result.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Первый путь настройки: разрешённые API-профили; ограничение применяется ко всей студии. */
@Composable
internal fun ModelRoutingSection(profiles: List<ApiProfile>, model: DemoModel) {
    val gate = AiAdmission.global
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(gate.snapshot()) }
    var active by remember { mutableStateOf(gate.active()) }
    var problem by remember { mutableStateOf(gate.failure()) }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var mode by remember(saved.policy) { mutableStateOf(saved.policy?.mode ?: AiMode.SINGLE) }
    var selected by remember(saved.policy) { mutableStateOf(saved.policy?.allowed.orEmpty().toSet()) }
    var confirming by remember { mutableStateOf<AiReservation?>(null) }
    Poll(periodMs = 2_000) { saved = gate.snapshot(); active = gate.active() }
    val candidates = profiles.filter { it.model.isNotBlank() }.map { it to AiModelRef.api(it) }
    fun configure(policy: AiModePolicy?) {
        val revision = saved.revision
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { model.configureAiMode(revision, policy) }
            problem = (result as? Outcome.Failure)?.errors?.joinToString("; ") { it.message }
            saved = gate.snapshot(); active = gate.active(); busy = false
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.routing_title)) }
            Text(saved.policy?.mode?.name ?: stringResource(R.string.routing_unconfigured), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.routing_active, active), style = MaterialTheme.typography.labelSmall)
            problem?.let { DiagnosticText(it, color = MaterialTheme.colorScheme.error) }
            if (expanded) {
                Text(stringResource(R.string.routing_intro), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (option in AiMode.entries) FilterChip(selected = mode == option, enabled = !busy,
                        onClick = { mode = option; if (mode == AiMode.SINGLE) selected = selected.take(1).toSet() }, label = { Text(option.name) })
                }
                Text(stringResource(R.string.routing_api_profiles), style = MaterialTheme.typography.titleSmall)
                for ((p, ref) in candidates) FilterChip(selected = ref in selected, enabled = !busy,
                    onClick = { selected = if (mode == AiMode.SINGLE) setOf(ref) else if (ref in selected) selected - ref else selected + ref },
                    label = { Text("${p.name} · ${p.model}") })
                if (candidates.isEmpty()) Text(stringResource(R.string.routing_no_profiles))
                if (selected.any { ref -> candidates.none { it.second == ref } }) Text(stringResource(R.string.routing_stale), color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(enabled = !busy, onClick = { selected = emptySet() }) { Text(stringResource(R.string.routing_clear_selection)) }
                    TextButton(enabled = !busy && active == 0 && selected.isNotEmpty() && selected.all { ref -> candidates.any { it.second == ref } },
                        onClick = { configure(AiModePolicy(mode, selected.toList())) }) { Text(stringResource(R.string.routing_apply)) }
                    TextButton(enabled = !busy && active == 0 && saved.policy != null, onClick = { configure(null) }) { Text(stringResource(R.string.routing_disable)) }
                }
                for (pending in saved.pending) {
                    Text(pending.reference ?: pending.owner, style = MaterialTheme.typography.labelSmall)
                    if (pending.model.backend == AiBackend.CLOUD_ROUTINE) TextButton(enabled = !busy, onClick = { confirming = pending }) {
                        Text(stringResource(R.string.routing_cloud_confirm))
                    } else Text(stringResource(R.string.routing_native_pending), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    confirming?.let { pending ->
        AlertDialog(onDismissRequest = { confirming = null }, title = { Text(stringResource(R.string.routing_cloud_confirm)) },
            text = { Text(stringResource(R.string.routing_cloud_warning)) },
            confirmButton = { TextButton(onClick = {
                confirming = null
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { gate.release(pending.owner) }
                    problem = (result as? Outcome.Failure)?.errors?.joinToString("; ") { it.message }
                    saved = gate.snapshot(); active = gate.active(); busy = false
                }
            }) { Text(stringResource(R.string.routing_cloud_finished)) } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.routing_cancel)) } })
    }
}
