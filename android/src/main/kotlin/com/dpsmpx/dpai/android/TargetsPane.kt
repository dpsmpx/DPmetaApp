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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.jsch.ProbedHostKey
import com.dpsmpx.dpai.ui.CheckLine
import com.dpsmpx.dpai.ui.ProjectEntry
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
fun TargetsPane(controller: Controller, model: DemoModel) {
    val scope = rememberCoroutineScope()
    var targets by remember { mutableStateOf(controller.targets()) }
    var publicKey by remember { mutableStateOf<String?>(null) }
    val checks = remember { mutableStateMapOf<String, List<CheckLine>>() }
    var form by remember { mutableStateOf(false) }
    var project by remember { mutableStateOf(controller.project()) }
    var projectFor by remember { mutableStateOf<TargetEntry?>(null) }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (targets.any(TermuxLauncher::local)) item(key = "termux") { TermuxCard(targets.first(TermuxLauncher::local).port) }
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
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Проект", style = MaterialTheme.typography.titleMedium)
                    val p = project
                    if (p == null) {
                        Text("Нет: задачи идут в каталоге агента, без Git и проверки. Задайте у target с каталогом Claude.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("репозиторий ${p.root}; деревья ${p.worktreeRoot}; ветка ${p.integrationBranch}", style = MaterialTheme.typography.bodySmall)
                        Text(p.checks.firstOrNull()?.let { "проверка: ${it.command.joinToString(" ")}" } ?: "без проверки", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { controller.setProject(null) }; project = null } }) { Text("Убрать проект") }
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
                    Text(t.configDir?.let { "каталог Claude: $it" } ?: "без каталога Claude — только проверка агента", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = {
                            checks[t.id.value] = listOf(CheckLine("проверка", "идёт…", Tone.NEUTRAL))
                            scope.launch { checks[t.id.value] = withContext(Dispatchers.IO) { TargetScreens.doctor(controller.doctor(t)) } }
                        }) { Text("Проверить") }
                        if (t.configDir != null) {
                            TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { model.probe(t.id) } } }) { Text("Вход Claude") }
                            TextButton(onClick = { projectFor = t }) { Text("Проект") }
                        }
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

    projectFor?.let { t ->
        ProjectDialog(controller, t) { saved ->
            projectFor = null
            if (saved != null) project = saved
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
    var configDir by remember { mutableStateOf("") }
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
                    OutlinedTextField(configDir, { configDir = it }, label = { Text("Каталог Claude слота (для сессий)") }, singleLine = true)
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
                    when (val f = controller.form(label, host, port, user, windows, agent, configDir)) {
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

/** Проект на target: репозиторий, каталог деревьев задач, ветка интеграции, команда проверки. */
@Composable
private fun ProjectDialog(controller: Controller, target: TargetEntry, onDone: (ProjectEntry?) -> Unit) {
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf("") }
    var trees by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var check by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Проект на ${target.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(root, { root = it }, label = { Text("Каталог репозитория") }, singleLine = true)
                OutlinedTextField(trees, { trees = it }, label = { Text("Каталог деревьев задач") }, singleLine = true)
                OutlinedTextField(branch, { branch = it }, label = { Text("Ветка интеграции") }, singleLine = true)
                OutlinedTextField(check, { check = it }, label = { Text("Команда проверки (необязательно)") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val f = controller.projectForm(target, root, trees, branch, check)) {
                    is Outcome.Failure -> error = f.diagnostics.first().message
                    is Outcome.Success -> scope.launch {
                        when (val w = withContext(Dispatchers.IO) { controller.setProject(f.value) }) {
                            is Outcome.Failure -> error = w.diagnostics.first().message
                            is Outcome.Success -> onDone(f.value)
                        }
                    }
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Отмена") } },
    )
}

/**
 * Termux на этом телефоне (D-46): разрешение «Запуск команд в среде
 * Termux», слушает ли `sshd`, запуск `sshd` кнопкой. Служба контроллера
 * запускает его и сама, если порт закрыт.
 */
@Composable
private fun TermuxCard(port: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var permitted by remember { mutableStateOf(TermuxLauncher.permitted(context)) }
    var listening by remember { mutableStateOf<Boolean?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    // HyperOS отклоняет запрос стороннего разрешения молча, без окна: отказ — открыть страницу приложения в настройках.
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        permitted = ok
        if (!ok) {
            note = "Окно не появилось: в настройках приложения — «Разрешения» → «Запуск команд в среде Termux»"
            context.startActivity(TermuxLauncher.appSettings(context))
        }
    }
    LaunchedEffect(port) {
        while (true) {
            listening = withContext(Dispatchers.IO) { TermuxLauncher.listening(port) }
            permitted = TermuxLauncher.permitted(context)
            kotlinx.coroutines.delay(3000)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Termux на этом телефоне", style = MaterialTheme.typography.titleMedium)
            Text(
                "sshd на порту $port: " + when (listening) {
                    true -> "слушает"
                    false -> "не слушает"
                    null -> "проверяю…"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (!TermuxLauncher.installed(context)) {
                Text("Termux не найден", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else if (!permitted) {
                Text(
                    "Чтобы приложение само запускало sshd, разрешите «Запуск команд в среде Termux» и добавьте в Termux строку allow-external-apps = true в ~/.termux/termux.properties.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { ask.launch(TermuxLauncher.PERMISSION) }) { Text("Разрешить") }
            } else {
                Text("Разрешение есть: контроллер сам запустит sshd, если порт закрыт (не чаще раза в 5 минут).", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    scope.launch {
                        note = when (val r = withContext(Dispatchers.IO) { TermuxLauncher.startSshd(context) }) {
                            is com.dpsmpx.dpai.core.result.Outcome.Success -> "Команда отправлена в Termux"
                            is com.dpsmpx.dpai.core.result.Outcome.Failure -> "Не отправлена: ${r.diagnostics.first().message}"
                        }
                    }
                }) { Text("Запустить sshd сейчас") }
            }
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}
