package com.dpsmpx.dpai.android

import android.os.CancellationSignal
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.tools.SharedToolsStatus
import com.dpsmpx.dpai.ui.TargetEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Настройка ПК/Termux: первая проба без аккаунта, синхронизация после явного входа. */
@Composable
internal fun RemoteSharedVerifyDialog(controller: Controller, target: TargetEntry, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val app = context as DpaiApplication
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val jobs by LongJobs.get(context).state.collectAsState()
    val job = jobs[RemoteSharedVerify.key(target)]
    var status by remember(target) { mutableStateOf<SharedToolsStatus?>(null) }
    var root by remember(target) { mutableStateOf("") }
    var python by remember(target) { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    var automatic by remember { mutableStateOf(RemoteSharedVerify.automatic(context, target)) }
    val needsAuthorization by RemoteSharedVerify.needsAuthorization.collectAsState()
    val busy = loading || signal != null || job?.running == true

    suspend fun refresh() {
        loading = true
        try {
            when (val r = withContext(Dispatchers.IO) { RemoteSharedVerify.probe(controller, target) }) {
                is Outcome.Failure -> { status = null; message = r.diagnostics.first().message }
                is Outcome.Success -> {
                    status = r.value
                    if (root.isEmpty() || r.value.configured != null) root = r.value.configured?.repository ?: r.value.defaultRepository
                    if (python.isEmpty() || r.value.configured != null) python = r.value.configured?.python ?: if (r.value.windows) "python" else "python3"
                    message = null
                }
            }
        } finally { loading = false }
    }

    LaunchedEffect(target, job?.running) { if (job?.running != true) { refresh(); automatic = RemoteSharedVerify.automatic(context, target) } }
    DisposableEffect(Unit) { onDispose { signal?.cancel() } }

    fun synchronize() {
        val owner = activity ?: return
        val pending = CancellationSignal()
        signal = pending
        app.authorizeSharedTools(owner, pending) { result ->
            signal = null
            when (result) {
                is Outcome.Failure -> message = result.diagnostics.first().message
                is Outcome.Success -> if (!RemoteSharedVerify.start(context, target, root.trim(), python.trim())) message = "Операция уже выполняется"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("DP-Verify: ${target.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Одна папка для всех исполнителей на этом target. Нужны обновлённый агент dpai, Git и Python 3.11+; на Windows также Git Bash. Зависимости на ПК автоматически не устанавливаются.", style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator()
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                job?.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                status?.let { current ->
                    Text(if (current.configured == null) "К новым ИИ-ходам не подключено" else "Подключено: ${current.configured?.repository}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(root, { root = it }, enabled = !busy && current.configured == null, label = { Text("Абсолютный путь папки на target") }, singleLine = true)
                    OutlinedTextField(python, { python = it }, enabled = !busy && current.configured == null, label = { Text("Программа Python на target") }, singleLine = true)
                    Text("После PIN/биометрии подключённый GitHub-аккаунт используется по SSH на ${target.host}:${target.port}. Токен не сохраняется на ПК и не передаётся проверкам или ИИ. Каждая публикуемая версия проходит полную проверку; занятые инструменты откладывают обновление.", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy && root.isNotBlank() && python.isNotBlank() && app.sharedToolsAccountAvailable, onClick = ::synchronize) {
                        Text(if (current.configured == null) "Установить или подключить…" else "Синхронизировать…")
                    }
                    if (!app.sharedToolsAccountAvailable) Text("Сначала подключите GitHub-аккаунт в DP_IDE", style = MaterialTheme.typography.bodySmall)
                    Row {
                        Checkbox(checked = automatic, enabled = !busy && RemoteSharedVerify.connected(context, target) && current.configured != null, onCheckedChange = {
                            automatic = it
                            RemoteSharedVerify.automatic(context, target, it)
                        })
                        Text("Синхронизировать в фоне раз в минуту", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Фон использует доступ только в памяти службы. После её перезапуска нужен новый вход. Смена адреса, ключа SSH или папки требует подключения заново.", style = MaterialTheme.typography.bodySmall)
                    if (automatic && needsAuthorization) Text("Фон ждёт входа. Для PIN/биометрии нажмите кнопку подключения или синхронизации выше.", style = MaterialTheme.typography.bodySmall)
                    if (current.configured != null) TextButton(enabled = !busy, onClick = {
                        if (!RemoteSharedVerify.disconnect(context, target)) message = "Операция уже выполняется"
                        root = ""; python = ""
                    }) { Text("Отключить, сохранив папку") }
                }
                TextButton(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text("Проверить агент и состояние") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
}
