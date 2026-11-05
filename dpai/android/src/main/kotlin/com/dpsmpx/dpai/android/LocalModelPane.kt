package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Локальная модель на телефоне (D-105): поставить llama.cpp, выбрать файл
 * GGUF из своих каталогов, запустить и остановить сервер на `127.0.0.1`.
 * Запущенный сервер — профиль «Телефон: llama.cpp» во вкладке «Модели».
 */
@Composable
fun LocalModelSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by LocalModel.status.collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf(status.model ?: "") }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }

    fun work(label: String, job: () -> String?) {
        busy = label
        note = null
        scope.launch {
            note = withContext(Dispatchers.IO) { job() }
            busy = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Локальная модель (llama.cpp)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Модель — файл GGUF: в /root/models (скачать терминалом) или в общем хранилище при «Доступе ко всем файлам» " +
                "(например, Download). Связанная папка копирует файлы до 32 МиБ — модели в неё не входят. " +
                "Сервер — на этом телефоне, с ключом этого запуска; модель берёт память — начинайте с маленькой (1–3 млрд параметров, Q4).",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = busy == null, onClick = { work("Ставлю llama.cpp…") { LocalModel.install(context) } }) { Text("Поставить llama.cpp") }
            TextButton(enabled = busy == null, onClick = {
                work("Ищу модели…") {
                    found = LocalModel.models(context)
                    if (found.isEmpty()) "Файлов .gguf не нашлось" else null
                }
            }) { Text("Найти модели") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (m in found) FilterChip(selected = path == m, onClick = { path = m }, label = { Text(m.substringAfterLast('/')) })
        }
        OutlinedTextField(path, { path = it }, label = { Text("Файл модели (путь в Linux)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = busy == null && path.isNotBlank(), onClick = { work("Запускаю сервер…") { LocalModel.start(context, path.trim()) ?: "Сервер запущен: профиль «Телефон: llama.cpp» во вкладке «Модели»" } }) {
                Text(if (status.running) "Перезапустить" else "Запустить")
            }
            if (status.running) TextButton(enabled = busy == null, onClick = { work("Останавливаю…") { LocalModel.stop(); "Сервер остановлен" } }) { Text("Остановить") }
        }
        val state = when {
            status.running -> "Идёт: ${status.model?.substringAfterLast('/')}, порт ${status.port}"
            else -> "Не запущен"
        }
        Text(state, style = MaterialTheme.typography.labelSmall)
        (busy ?: note ?: status.problem)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        if (status.tail.isNotEmpty()) Text(status.tail.takeLast(12).joinToString("\n"), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }
}
