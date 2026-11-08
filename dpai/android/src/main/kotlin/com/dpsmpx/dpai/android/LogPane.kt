package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.orchestrator.log.LogFilter
import com.dpsmpx.dpai.orchestrator.log.LogLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Журнал в «Настройках AI» (D-43, D-112): последние строки журнала приложения с
 * фильтром по уровню и выгрузка — начало и хвост файла — в буфер обмена
 * или файлом в «Загрузки/dpai-logs» (длинный журнал в буфер не влезает,
 * файл передаётся целиком). Выгрузка — только кнопкой человека: наружу
 * журнал сам не уходит.
 */
@Composable
fun LogPane(model: DemoModel) {
    val context = LocalContext.current.applicationContext
    val log = remember { AppLogs.get(context) }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }
    val undo = LocalUndo.current
    val scope = rememberCoroutineScope()
    // Файлы журнала — с диска, не в главном потоке (AI:A-16); только пока окно видно (AI:A-15).
    Poll(filter, periodMs = REFRESH_MS) {
        lines = withContext(Dispatchers.IO) { log.lines(filter = filter, max = MAX_LINES).reversed() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // Фильтр — на виду; путь файла, копирование, «В Загрузки» и отладка — в ⋮ (AI:A-26).
        var copying by remember { mutableStateOf(false) }
        var debug by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (f in listOf(LogFilter.ALL, LogFilter.INFO, LogFilter.WARN, LogFilter.ERROR)) {
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.title) })
                }
            }
            androidx.compose.foundation.layout.Box {
                androidx.compose.material3.IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Действия журнала" }) {
                    Text("⋮", style = MaterialTheme.typography.titleLarge)
                }
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    androidx.compose.material3.DropdownMenuItem(enabled = !copying, text = { Text(if (copying) "Копирую…" else "Копировать журнал") }, onClick = {
                        menu = false
                        // Файлы журнала (до 1 МиБ и прежний) — с диска не в главном потоке (AI:A-16).
                        copying = true
                        scope.launch {
                            val text = withContext(Dispatchers.IO) { log.export() }
                            copying = false
                            Clipboard.copy(context, "журнал dpai", text)
                            if (!Clipboard.systemConfirms) undo.tell("Скопировано знаков: ${text.length}") { note = it }
                        }
                    })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("В Загрузки") }, onClick = {
                        menu = false
                        scope.launch {
                            // Сохранено — снэкбаром (AI:A-19); отказ — строкой на месте.
                            when (val r = withContext(Dispatchers.IO) { saveLog(context, log.export(maxChars = Int.MAX_VALUE)) }) {
                                is Outcome.Success -> undo.tell("Сохранено: Загрузки/$LOG_DIR/${r.value}") { note = it }
                                is Outcome.Failure -> note = "Не сохранено: ${r.errors.first().message}"
                            }
                        }
                    })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Где файл журнала") }, onClick = {
                        menu = false
                        note = "Файл: ${log.location}"
                    })
                    // Отладка: проверочные запросы (D-44) — с экрана «Запросы» убраны, здесь — по требованию.
                    androidx.compose.material3.DropdownMenuItem(text = { Text(if (debug) "Скрыть отладку" else "Отладка") }, onClick = {
                        menu = false
                        debug = !debug
                    })
                }
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        if (debug) ProbePanel(model)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(lines, key = { it.seq }) { l ->
                Text(l.render(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Журнал целиком файлом в «Загрузки/[LOG_DIR]» (D-43, уточнено D-72): буфер
 * обмена берёт начало и хвост, файл — всё. Хранятся последние [LOG_KEEP]
 * выгрузок. Итог — имя файла.
 */
private fun saveLog(context: android.content.Context, text: String): Outcome<String> {
    val name = LOG_PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(Clock.SYSTEM.now().epochMillis)) + ".txt"
    return Downloads.write(context, LOG_DIR, name, "text/plain", text.toByteArray(Charsets.UTF_8), "журнал").map { name }
        .also { if (it is Outcome.Success) Downloads.prune(context, LOG_DIR, LOG_PREFIX, LOG_KEEP) }
}

private const val LOG_DIR = "dpai-logs"
private const val LOG_PREFIX = "dpai-log-"
private const val LOG_KEEP = 10
private const val MAX_LINES = 300
private const val REFRESH_MS = 2000L
