package com.dpsmpx.dpai.android

import android.os.CancellationSignal
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Управление проверенными общими инструментами target «в приложении» (D-125). */
@Composable
fun SharedVerifyCard() {
    val context = LocalContext.current.applicationContext
    val activity = LocalActivity.current
    val application = context as DpaiApplication
    val jobs by LongJobs.get(context).state.collectAsState()
    val note by SharedVerify.state.collectAsState()
    var connected by remember { mutableStateOf(false) }
    var automatic by remember { mutableStateOf(SharedVerify.enabled(context)) }
    var authorizing by remember { mutableStateOf(false) }
    var authMessage by remember { mutableStateOf<String?>(null) }
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    val busy = authorizing || jobs.any { it.key in LongJobs.GUEST && it.value.running }
    LaunchedEffect(jobs) {
        connected = withContext(Dispatchers.IO) { SharedVerify.connected(context) }
        automatic = SharedVerify.enabled(context)
        if (note.isEmpty()) SharedVerify.refresh(context)
    }
    DisposableEffect(Unit) { onDispose { signal?.cancel() } }

    fun run(install: Boolean) {
        if (authorizing) return
        val owner = activity ?: return
        authorizing = true
        authMessage = null
        val pending = CancellationSignal()
        signal = pending
        application.authorizeSharedTools(owner, pending) { result ->
            authorizing = false
            signal = null
            when (result) {
                is Outcome.Success -> SharedVerify.start(context, install)
                is Outcome.Failure -> authMessage = result.errors.first().message
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Общие инструменты DP-Verify", style = MaterialTheme.typography.titleSmall)
            Text("Одна папка для Claude, Codex и Gemini на этом телефоне. ИИ может обновлять инструменты; каждая версия проверяется до отправки в main.", style = MaterialTheme.typography.bodySmall)
            DiagnosticText(note.ifEmpty { "Чтение состояния…" }, style = MaterialTheme.typography.bodySmall)
            authMessage?.let { DiagnosticText(it, style = MaterialTheme.typography.bodySmall) }
            jobs[LongJobs.SHARED_VERIFY]?.note?.takeIf { it != note }?.let {
                DiagnosticText("Последняя операция: $it", style = MaterialTheme.typography.bodySmall)
            }
            if (jobs[LongJobs.SHARED_VERIFY]?.running == true) {
                Text("Получение и полная проверка версии могут занять несколько минут", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            FlowRow {
                if (!connected) {
                    TextButton(enabled = !busy, onClick = { run(install = true) }) { Text("Установить и подключить…") }
                } else {
                    TextButton(enabled = !busy, onClick = { run(install = false) }) { Text("Синхронизировать…") }
                    TextButton(enabled = !busy, onClick = { SharedVerify.disconnect(context) }) { Text("Отключить") }
                }
            }
            if (connected) SwitchRow(automatic, onChange = {
                automatic = it
                SharedVerify.automatic(context, it)
            }) { Text("Синхронизировать в фоне", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
            Text("В фоне — раз в минуту, пока работает служба контроллера. Занятые инструменты ждут паузы. После входа доступ хранится только в памяти службы; её остановка, перезапуск или отключение фона требуют нового PIN или биометрии кнопкой выше.", style = MaterialTheme.typography.bodySmall)
            Text("Папка: /root/dp-verify. Подключение действует со следующего ИИ-хода. Отключение сохраняет файлы и историю.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
