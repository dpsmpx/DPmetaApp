package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.jsch.ProbedHostKey
import com.dpsmpx.dpai.ui.CheckLine
import com.dpsmpx.dpai.ui.TargetEntry
import com.dpsmpx.dpai.ui.TargetScreens
import com.dpsmpx.dpai.ui.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Targets приложения (D-35): ключ приложения для `authorized_keys`,
 * список, новый target с подтверждением отпечатка ключа хоста (T2) и
 * проверка агента. Всё сетевое — вне главного потока.
 */
@Composable
fun TargetsPane(controller: Controller) {
    val scope = rememberCoroutineScope()
    var targets by remember { mutableStateOf(controller.targets()) }
    var publicKey by remember { mutableStateOf<String?>(null) }
    val checks = remember { mutableStateMapOf<String, List<CheckLine>>() }
    var form by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Ключ приложения", style = MaterialTheme.typography.titleMedium)
                    Text("Добавьте строку в ~/.ssh/authorized_keys на target.", style = MaterialTheme.typography.bodySmall)
                    val key = publicKey
                    if (key == null) {
                        TextButton(onClick = { scope.launch { publicKey = withContext(Dispatchers.IO) { controller.publicKeyLine() } } }) {
                            Text("Показать открытый ключ")
                        }
                    } else {
                        SelectionContainer { Text(key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Targets (${targets.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button(onClick = { form = true }) { Text("Добавить") }
            }
        }
        items(targets, key = { it.id.value }) { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(t.label, style = MaterialTheme.typography.titleSmall)
                    Text("${t.user}@${t.host}:${t.port} · ${t.type.name}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = {
                            checks[t.id.value] = listOf(CheckLine("проверка", "идёт…", Tone.NEUTRAL))
                            scope.launch { checks[t.id.value] = withContext(Dispatchers.IO) { TargetScreens.doctor(controller.doctor(t)) } }
                        }) { Text("Проверить") }
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { controller.remove(t) }
                                targets = controller.targets()
                            }
                        }) { Text("Удалить") }
                    }
                    checks[t.id.value]?.forEach { c -> CheckRow(c) }
                }
            }
        }
    }

    if (form) {
        AddTargetDialog(controller, onDone = {
            form = false
            targets = controller.targets()
        })
    }
}

@Composable
private fun CheckRow(c: CheckLine) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(c.name, Modifier.weight(0.35f), color = toneOf(c.tone), style = MaterialTheme.typography.labelMedium)
        Text(c.detail, Modifier.weight(0.65f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun toneOf(t: Tone) = when (t) {
    Tone.ERROR -> MaterialTheme.colorScheme.error
    Tone.WARNING -> MaterialTheme.colorScheme.tertiary
    Tone.DONE, Tone.ACTIVE -> MaterialTheme.colorScheme.primary
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Новый target: форма → ключ хоста → человек сверяет отпечаток с тем, что
 * показывает target (`ssh-keygen -l -f <ключ хоста>.pub`) → сохранение.
 */
@Composable
private fun AddTargetDialog(controller: Controller, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("8022") }
    var user by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf("") }
    var windows by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<TargetEntry, ProbedHostKey>?>(null) }
    var busy by remember { mutableStateOf(false) }

    val probed = pending
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (probed == null) "Новый target" else "Ключ хоста") },
        text = {
            if (probed == null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(label, { label = it }, label = { Text("Имя") }, singleLine = true)
                    OutlinedTextField(host, { host = it }, label = { Text("Хост") }, singleLine = true)
                    OutlinedTextField(port, { port = it }, label = { Text("Порт") }, singleLine = true)
                    OutlinedTextField(user, { user = it }, label = { Text("Пользователь") }, singleLine = true)
                    OutlinedTextField(agent, { agent = it }, label = { Text("Команда агента dpai") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(windows, { windows = it })
                        Text("Windows")
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            } else {
                Column {
                    Text("Сверьте отпечаток с тем, что показывает сам target:")
                    Text("ssh-keygen -l -f <каталог ssh>/ssh_host_*_key.pub", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(probed.second.type, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    SelectionContainer { Text(probed.second.fingerprint, fontFamily = FontFamily.Monospace) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            if (probed == null) {
                TextButton(enabled = !busy, onClick = {
                    when (val f = controller.form(label, host, port, user, windows, agent)) {
                        is Outcome.Failure -> error = f.diagnostics.first().message
                        is Outcome.Success -> {
                            busy = true
                            scope.launch {
                                when (val p = withContext(Dispatchers.IO) { controller.probe(f.value) }) {
                                    is Outcome.Failure -> error = p.diagnostics.first().message
                                    is Outcome.Success -> {
                                        error = null
                                        pending = f.value to p.value
                                    }
                                }
                                busy = false
                            }
                        }
                    }
                }) { Text(if (busy) "Связь…" else "Получить ключ хоста") }
            } else {
                TextButton(onClick = {
                    scope.launch {
                        when (val a = withContext(Dispatchers.IO) { controller.add(probed.first, probed.second) }) {
                            is Outcome.Failure -> error = a.diagnostics.first().message
                            is Outcome.Success -> onDone()
                        }
                    }
                }) { Text("Совпадает — сохранить") }
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Отмена") } },
    )
}
