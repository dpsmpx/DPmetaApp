package com.dpsmpx.dpai.android

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.edit
import com.dpsmpx.dpai.accounts.ApiPresets
import com.dpsmpx.dpai.accounts.ApiProfile
import com.dpsmpx.dpai.accounts.ApiProfiles
import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.providers.Conversation
import com.dpsmpx.dpai.providers.ModelInfo
import com.dpsmpx.dpai.providers.Providers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object ApiQuickStartRequests {
    val open = MutableStateFlow(false)
    val chat = MutableStateFlow<String?>(null)
    var preferredProfile: String? = null
    var preferredProject: String? = null
    fun request(profile: String? = null, project: String? = null) { preferredProfile = profile; preferredProject = project; open.value = true }
    fun seen(context: Context) = context.getSharedPreferences("dpai.quick-start", Context.MODE_PRIVATE).getBoolean("seen", false)
    fun dismiss(context: Context) {
        context.getSharedPreferences("dpai.quick-start", Context.MODE_PRIVATE).edit { putBoolean("seen", true) }
        open.value = false
    }
}

/** Первый путь настройки API-разработки: ключ вводится, проект/провайдер/модель выбираются (D-214). */
@Composable
internal fun ApiQuickStartWizard(onOpen: (Conversation) -> Unit, onAdvanced: () -> Unit, onFiles: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by ModelsStore.profiles.collectAsState()
    var choices by remember { mutableStateOf<List<ApiProjectRuntime.Choice>>(emptyList()) }
    var selectedProject by remember { mutableStateOf<String?>(null) }
    var selectedProfile by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf("") } // Секрет не входит в saved state.
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var selectedModel by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf<ApiProfile?>(null) }
    var probeCancel by remember { mutableStateOf<com.dpsmpx.dpai.providers.Cancel?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { probeCancel?.cancel() } }
    val presets = remember {
        listOf(Triple("Anthropic", ProviderKind.ANTHROPIC, ProviderKind.ANTHROPIC.fixedBaseUrl!!), Triple("Gemini", ProviderKind.GEMINI, ProviderKind.GEMINI.fixedBaseUrl!!)) +
            ApiPresets.OPENAI_COMPATIBLE.map { Triple(it.name, ProviderKind.OPENAI_COMPATIBLE, it.baseUrl) }
    }
    var preset by remember { mutableStateOf(presets.first()) }
    val newId = remember { ModelsStore.newProfileId() }
    val existing = profiles.firstOrNull { it.id.value == selectedProfile }
    val selected = choices.firstOrNull { it.key == selectedProject }
    val localStopped = stringResource(R.string.api_work_local_stopped)
    val noModels = stringResource(R.string.api_work_no_models)
    val preparing = stringResource(R.string.api_work_preparing)
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            ModelsStore.init(context)
            ApiProjectRuntime.choices(context)
        }
        when (loaded) {
            is Outcome.Success -> { choices = loaded.value; selectedProject = choices.firstOrNull { it.project?.value == ApiQuickStartRequests.preferredProject }?.key ?: choices.firstOrNull()?.key; selectedProfile = ModelsStore.profiles.value.firstOrNull { it.id.value == ApiQuickStartRequests.preferredProfile }?.id?.value ?: ModelsStore.profiles.value.firstOrNull()?.id?.value }
            is Outcome.Failure -> note = loaded.diagnostics.first().message
        }
        busy = false
    }
    fun changedProfile() { checked = null; models = emptyList(); selectedModel = ""; note = null; key = "" }
    fun connect() {
        busy = true; note = null; checked = null
        val cancel = com.dpsmpx.dpai.providers.Cancel().also { probeCancel = it }
        val capturedKey = key.trim().takeIf { it.isNotEmpty() }?.let(::Secret)
        scope.launch {
            val result = withContext(Dispatchers.IO) { try {
                val base = existing ?: ApiProfiles.form(newId, preset.first, preset.second, preset.third, "", null, false).valueOrNull()!!
                // Старый loopback-порт мог занять другой процесс: не отдавать ему ключ остановленного llama.cpp (T18).
                if (LocalModel.isOwn(context, base) && !LocalModel.usable(base)) return@withContext Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.STATE_CONFLICT, localStopped)
                val provider = if (capturedKey == null && existing != null) ModelsStore.provider(base) else Providers.of(base, capturedKey)
                when (val r = provider.models(cancel)) {
                    is Outcome.Failure -> r
                    is Outcome.Success -> Outcome.success(base to com.dpsmpx.dpai.providers.DevelopmentModels.eligible(r.value))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.INTERNAL_ERROR, "сбой проверки API: ${e.javaClass.simpleName}")
            } }
            probeCancel = null
            when (result) {
                is Outcome.Failure -> note = result.diagnostics.first().message
                is Outcome.Success -> {
                    checked = result.value.first
                    models = result.value.second
                    selectedModel = com.dpsmpx.dpai.providers.DevelopmentModels.preferred(models, existing?.model).orEmpty()
                    if (models.isEmpty()) note = noModels
                }
            }
            busy = false
        }
    }
    LaunchedEffect(selectedProfile) { if (!busy && existing != null) connect() }
    fun start() {
        val choice = selected ?: return
        val base = checked ?: existing ?: return
        val selectedId = selectedModel.ifBlank { base.model }
        busy = true; note = preparing
        val capturedKey = key.trim().takeIf { it.isNotEmpty() }?.let(::Secret)
        scope.launch {
            val result = withContext(Dispatchers.IO) { try {
                if (existing != null && ModelsStore.profiles.value.firstOrNull { it.id == existing.id } != existing) return@withContext Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.STATE_CONFLICT, "профиль изменился; выберите его заново")
                val profile = when (val r = ApiProfiles.form(base.id, base.name, base.kind, base.baseUrl, selectedId, base.maxOutputTokens, base.customHostAllowed)) {
                    is Outcome.Failure -> return@withContext r
                    is Outcome.Success -> r.value
                }
                ModelsStore.saveProfile(profile, capturedKey)?.let { return@withContext Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.STORAGE_IO_FAILED, it) }
                when (val r = ApiProjectRuntime.prepare(context, choice)) {
                    is Outcome.Failure -> r
                    is Outcome.Success -> Outcome.success(ModelsStore.newProjectChat(profile, r.value.project))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.INTERNAL_ERROR, "сбой подготовки проекта: ${e.javaClass.simpleName}")
            } }
            when (result) {
                is Outcome.Failure -> note = result.diagnostics.first().message
                is Outcome.Success -> { key = ""; SetupWizardPrefs.setLater(context); ApiQuickStartRequests.dismiss(context); onOpen(result.value) }
            }
            busy = false
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) ApiQuickStartRequests.dismiss(context) }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.api_work_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.api_work_intro))
            Text(stringResource(R.string.api_work_project), style = MaterialTheme.typography.titleSmall)
            if (choices.isEmpty() && !busy) TextButton(onClick = { ApiQuickStartRequests.dismiss(context); onFiles() }) { Text(stringResource(R.string.api_work_no_projects)) }
            if (choices.isNotEmpty()) LazyColumn(Modifier.height(130.dp)) { items(choices, key = { it.key }) { c ->
                FilterChip(selected = c.key == selectedProject, onClick = { if (!busy) selectedProject = c.key }, label = { Text("${c.title} · ${c.branch}", maxLines = 1) })
            } }
            Text(stringResource(R.string.api_work_provider), style = MaterialTheme.typography.titleSmall)
            if (profiles.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (p in profiles) FilterChip(selected = p.id.value == selectedProfile, onClick = { if (!busy) { selectedProfile = p.id.value; changedProfile() } }, label = { Text(p.name) })
                FilterChip(selected = selectedProfile == null, onClick = { if (!busy) { selectedProfile = null; changedProfile() } }, label = { Text(stringResource(R.string.api_work_new_profile)) })
            }
            if (existing == null) Row(Modifier.horizontalScroll(rememberScrollState())) { for (p in presets) FilterChip(selected = preset == p, onClick = { if (!busy) { preset = p; changedProfile() } }, label = { Text(p.first) }) }
            val keyOptional = existing?.let { it.kind == ProviderKind.OPENAI_COMPATIBLE && it.customHostAllowed } == true
            val needsKey = existing == null || !ModelsStore.hasKey(existing.id) && !keyOptional
            OutlinedTextField(key, { key = it; checked = null; models = emptyList() }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(if (needsKey) R.string.api_work_key else R.string.api_work_key_saved)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
            TextButton(enabled = !busy && (!needsKey || key.isNotBlank()), onClick = ::connect) { Text(stringResource(R.string.api_work_check_key)) }
            if (models.isNotEmpty()) {
                Text(stringResource(R.string.api_work_model))
                LazyColumn(Modifier.height(150.dp)) { items(models, key = { it.id }) { m -> FilterChip(selected = m.id == selectedModel, onClick = { if (!busy) selectedModel = m.id }, label = { Text(m.name ?: m.id, maxLines = 1) }) } }
            } else existing?.model?.takeIf { it.isNotBlank() }?.let { Text(it) }
            Text(stringResource(R.string.api_work_consent), style = MaterialTheme.typography.bodySmall)
            note?.let { DiagnosticText(it, color = if (busy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) }
            if (busy) CircularProgressIndicator()
        } },
        confirmButton = { TextButton(enabled = !busy && selected != null && checked != null && selectedModel.isNotBlank(), onClick = ::start) { Text(stringResource(R.string.api_work_start)) } },
        dismissButton = { Row { if (busy && probeCancel != null) TextButton(onClick = { probeCancel?.cancel() }) { Text(stringResource(R.string.api_work_cancel)) }; TextButton(enabled = !busy, onClick = { ApiQuickStartRequests.dismiss(context); onAdvanced() }) { Text(stringResource(R.string.api_work_advanced)) }; TextButton(enabled = !busy, onClick = { ApiQuickStartRequests.dismiss(context) }) { Text(stringResource(R.string.api_work_later)) } } })
}
