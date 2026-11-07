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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment

/**
 * Локальная модель на телефоне (D-105): поставить llama.cpp, выбрать файл
 * GGUF из своих каталогов, запустить и остановить сервер на `127.0.0.1`.
 * Запущенный сервер — профиль «Телефон: llama.cpp» во вкладке «Модели».
 */
@Composable
fun LocalModelSection() {
    val context = LocalContext.current
    val status by LocalModel.status.collectAsState()
    var path by remember { mutableStateOf(status.model ?: "") }
    // Долгое — вне экрана (AI:A-14): установка и поиск переживают уход с вкладки; повтор не запускается.
    val jobs = remember { LongJobs.get(context) }
    val state by jobs.state.collectAsState()
    val job = state[LongJobs.LLAMA]
    // Процессы гостя — по одному: пока экран Linux ставит пакеты, llama.cpp ждёт (база apk под замком).
    val busy = job?.takeIf { it.running }?.label ?: LongJobs.GUEST.firstNotNullOfOrNull { k -> state[k]?.takeIf { it.running }?.label }
    val note = job?.takeIf { !it.running }?.note
    // Найденные модели — вывод поиска: остаются и после ухода с экрана.
    val found = job?.takeIf { !it.running && it.label == FIND }?.output.orEmpty()

    fun work(label: String, job: () -> String?) {
        jobs.start(LongJobs.LLAMA, label, excludes = LongJobs.GUEST) { LongJobs.Done(job() ?: "$label: готово") }
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
            TextButton(enabled = busy == null, onClick = { work("Установка llama.cpp") { LocalModel.install(context) } }) { Text("Поставить llama.cpp") }
            TextButton(enabled = busy == null, onClick = {
                jobs.start(LongJobs.LLAMA, FIND, excludes = LongJobs.GUEST) {
                    val models = LocalModel.models(context)
                    LongJobs.Done(if (models.isEmpty()) "Файлов .gguf не нашлось" else "Найдено моделей: ${models.size}", models)
                }
            }) { Text("Найти модели") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (m in found) FilterChip(selected = path == m, onClick = { path = m }, label = { Text(m.substringAfterLast('/')) })
        }
        OutlinedTextField(path, { path = it }, label = { Text("Файл модели (путь в Linux)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = busy == null && path.isNotBlank(), onClick = { work("Запуск сервера") { LocalModel.start(context, path.trim()) ?: "Сервер запущен: профиль «Телефон: llama.cpp» во вкладке «Модели»" } }) {
                Text(if (status.running) "Перезапустить" else "Запустить")
            }
            if (status.running) TextButton(enabled = busy == null, onClick = { work("Остановка сервера") { LocalModel.stop(); "Сервер остановлен" } }) { Text("Остановить") }
        }
        val state = when {
            status.running -> "Идёт: ${status.model?.substringAfterLast('/')}, порт ${status.port}"
            else -> "Не запущен"
        }
        Text(state, style = MaterialTheme.typography.labelSmall)
        if (busy != null) {
            Text("$busy…", style = MaterialTheme.typography.labelSmall)
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        (note ?: status.problem)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        if (status.tail.isNotEmpty()) Text(status.tail.takeLast(12).joinToString("\n"), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }
}

/** Подпись поиска моделей: его вывод — найденные файлы. */
private const val FIND = "Поиск моделей"
