package com.dpsmpx.dpai.android

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.orchestrator.log.LogFilter
import com.dpsmpx.dpai.orchestrator.log.LogLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Вкладка «Журнал» (D-43): последние строки журнала приложения с
 * фильтром по уровню и выгрузка в буфер обмена — начало и хвост файла.
 * Выгрузка — только кнопкой человека: наружу журнал сам не уходит.
 */
@Composable
fun LogPane(model: DemoModel) {
    val context = LocalContext.current.applicationContext
    val log = remember { AppLogs.get(context) }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(filter) {
        while (true) {
            lines = log.lines(filter = filter, max = MAX_LINES).reversed()
            delay(REFRESH_MS)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("Файл: ${log.location}", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (f in listOf(LogFilter.ALL, LogFilter.INFO, LogFilter.WARN, LogFilter.ERROR)) {
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.title) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val text = log.export()
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("dpai.log", text))
                note = "скопировано знаков: ${text.length}"
            }) { Text("Копировать журнал") }
        }
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        // Отладка: проверочные запросы (D-44) — с экрана «Запросы» убраны, здесь — по требованию.
        var debug by remember { mutableStateOf(false) }
        androidx.compose.material3.TextButton(onClick = { debug = !debug }) { Text(if (debug) "Отладка ▾" else "Отладка ▸") }
        if (debug) ProbePanel(model)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(lines, key = { it.seq }) { l ->
                Text(l.render(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private const val MAX_LINES = 300
private const val REFRESH_MS = 2000L
