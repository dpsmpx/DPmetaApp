package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Карточка «Linux в приложении» (D-75, шаг 5): Alpine под proot в данных
 * приложения — установка из APK, терминал к ней и пакеты. Claude Code
 * человек ставит сам и входит в каталоге слота (D-27, D-29); приложение
 * его не скачивает.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LinuxCard() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val available = remember { LinuxSetup.available(context) }
    var installed by remember { mutableStateOf(LinuxSetup.environment(context).installed()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf<List<String>>(emptyList()) }
    var packages by remember { mutableStateOf("") }

    fun apk(action: LinuxEnvironment.Apk, label: String) {
        busy = "$label…"
        note = null
        scope.launch {
            val names = if (action == LinuxEnvironment.Apk.UPGRADE) emptyList() else packages.split(' ', ',', '\n')
            val r = withContext(Dispatchers.IO) { LinuxSetup.apk(context, action, names) }
            when (r) {
                is Outcome.Success -> {
                    note = if (r.value.ok) "$label: готово" else "$label: apk завершился с кодом ${r.value.exitCode} — причина в выводе ниже"
                    output = (r.value.stdout + r.value.stderr).takeLast(MAX_OUTPUT)
                }
                is Outcome.Failure -> note = "$label: ${r.errors.first().message}"
            }
            busy = null
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Linux в приложении", style = MaterialTheme.typography.titleSmall)
            Text(
                "Alpine в данных приложения — без Termux. Claude Code ставите сами в терминале " +
                    "(apk add nodejs npm, затем npm i -g @anthropic-ai/claude-code) и входите в каталоге слота.",
                style = MaterialTheme.typography.bodySmall,
            )
            when {
                !available -> Text("Недоступно на этом телефоне: нужен процессор arm64.", style = MaterialTheme.typography.bodySmall)
                installed == null -> TextButton(enabled = busy == null, onClick = {
                    busy = "Устанавливаю Alpine…"
                    note = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { LinuxSetup.install(context) }
                        note = when (r) {
                            is Outcome.Success -> r.value?.let { "Установлено: файлов ${it.files}, ${it.bytes / 1_048_576} МБ" } ?: "Уже установлено"
                            is Outcome.Failure -> "Не установлено: ${r.errors.first().message}"
                        }
                        installed = LinuxSetup.environment(context).installed()
                        busy = null
                    }
                }) { Text(busy ?: "Установить Alpine") }
                else -> {
                    Text("Установлено: $installed", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { TerminalActivity.startLinux(context) }) { Text("Терминал") }
                    OutlinedTextField(packages, { packages = it }, label = { Text("Пакеты, например: git nodejs npm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = busy == null && packages.isNotBlank(), onClick = { apk(LinuxEnvironment.Apk.ADD, "Поставить") }) { Text("Поставить") }
                        TextButton(enabled = busy == null && packages.isNotBlank(), onClick = { apk(LinuxEnvironment.Apk.DEL, "Убрать") }) { Text("Убрать") }
                        TextButton(enabled = busy == null, onClick = { apk(LinuxEnvironment.Apk.UPGRADE, "Обновить всё") }) { Text("Обновить всё") }
                    }
                    // Target «в приложении» (D-75, шаг 5): задачи Claude — в этом Linux, без SSH; слот — каталог в госте (D-29).
                    Text(
                        "Задачи в этом Linux: «Добавить слот» — target «в приложении» на вкладке Targets; войти — его «Claude в терминале» → /login.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(enabled = busy == null, onClick = {
                        busy = "Добавляю слот…"
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                val id = com.dpsmpx.dpai.core.ids.IdGenerator(com.dpsmpx.dpai.core.clock.Clock.SYSTEM, com.dpsmpx.dpai.core.clock.RandomSource.secure())
                                    .next(com.dpsmpx.dpai.core.ids.IdKinds.TARGET)
                                Controller.get(context.filesDir, null).addInApp(id)
                            }
                            note = when (r) {
                                is Outcome.Success -> "Добавлен «${r.value.label}» (${r.value.configDir}): войдите в Claude в его терминале"
                                is Outcome.Failure -> "Слот не добавлен: ${r.errors.first().message}"
                            }
                            busy = null
                        }
                    }) { Text("Добавить слот «в приложении»") }
                }
            }
            busy?.takeIf { installed != null }?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            if (output.isNotEmpty()) Text(output.joinToString("\n"), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Строк вывода `apk` на карточке: хвост, где итог и ошибка. */
private const val MAX_OUTPUT = 20

/**
 * Карточка «Диск приложения» (D-96): переключатель — данные приложения
 * видны менеджерам файлов (Total Commander и др.) через системный выбор
 * папки; без секретов, запись — только в Linux /root. Пока открыт — в
 * шторке уведомление с «Закрыть».
 */
@Composable
fun AppDiskCard() {
    val context = LocalContext.current.applicationContext
    var open by remember { mutableStateOf(com.dpsmpx.dpai.ui.AppDisk.isOpen(context)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Диск приложения", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = open, onCheckedChange = { v ->
                    com.dpsmpx.dpai.ui.AppDisk.setOpen(context, v)
                    open = com.dpsmpx.dpai.ui.AppDisk.isOpen(context)
                })
            }
            Text(
                if (open) {
                    "Открыт: в менеджере файлов — «DPmetaApp» в системном выборе папки (в Total Commander — «Добавить место» → системный выбор). " +
                        "Только чтение; файлы можно класть в Linux /root. Токена dpai и входов Claude на диске нет."
                } else {
                    "Закрыт: файлы приложения не видны другим программам. Откройте, чтобы смотреть журналы, копии журнала и файлы Linux менеджером файлов."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
