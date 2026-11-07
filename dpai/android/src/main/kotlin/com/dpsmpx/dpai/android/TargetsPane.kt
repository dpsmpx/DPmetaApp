package com.dpsmpx.dpai.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.text.Words
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
 * Targets приложения (D-35): список targets первым (AI:A-3) — строка
 * «Targets (N) · Терминалы (N) · Добавить», вход в «Linux в приложении» и
 * карточки targets; новый target — с подтверждением отпечатка ключа хоста
 * (T2). Настройка (ключ, контур, копии, `dpai`, диск) — в «Настройках AI».
 * Всё сетевое — вне главного потока.
 */
@Composable
fun TargetsPane(controller: Controller, model: DemoModel) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var targets by remember { mutableStateOf(controller.targets()) }
    val checks = remember { mutableStateMapOf<String, List<CheckLine>>() }
    var form by remember { mutableStateOf(false) }
    var project by remember { mutableStateOf(controller.project()) }
    var projectFor by remember { mutableStateOf<TargetEntry?>(null) }
    var guide by rememberSaveable { mutableStateOf(false) }
    var linux by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<TargetEntry?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TargetEntry?>(null) }
    // Признак переустановки (AI:A-3): targets нет, а Termux есть — его агент и входы целы.
    val reinstalled = remember(targets) { targets.isEmpty() && TermuxLauncher.installed(context) }
    renaming?.let { r ->
        RenameDialog(r) { name ->
            if (name == null) {
                renaming = null
            } else {
                scope.launch {
                    when (val w = withContext(Dispatchers.IO) { controller.rename(r, name) }) {
                        is Outcome.Failure -> checks[r.id.value] = listOf(CheckLine("имя", w.diagnostics.first().message, Tone.ERROR))
                        is Outcome.Success -> targets = controller.targets()
                    }
                    renaming = null
                }
            }
        }
    }
    // Кто чем занят (D-56) — раз в 5 с, пока экран открыт.
    var work by remember { mutableStateOf(emptyMap<com.dpsmpx.dpai.core.ids.ExecutionTargetId, List<String>>()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            work = withContext(Dispatchers.IO) { model.work() }
            kotlinx.coroutines.delay(5_000)
        }
    }
    if (guide) {
        val close = {
            guide = false
            targets = controller.targets()
            project = controller.project()
        }
        // Системный «назад» из инструкции — к Targets, а не из «AI» (AI:A-2).
        androidx.activity.compose.BackHandler(onBack = close)
        SetupPane(controller, model, close)
        return
    }
    if (linux) {
        val close = {
            linux = false
            targets = controller.targets()
        }
        androidx.activity.compose.BackHandler(onBack = close)
        LinuxScreen(onBack = close)
        return
    }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Targets (${targets.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                // Открытые вкладки терминала живут, пока жив процесс (D-71): вернуться к ним.
                val terminals = TerminalHub.count.intValue
                if (terminals > 0) TextButton(onClick = { TerminalActivity.start(context, null) }) { Text("Терминалы ($terminals)") }
                Button(onClick = { form = true }) { Text("Добавить") }
            }
        }
        // Пустой список ведёт дальше (AI:A-17): по шагам или Linux без Termux.
        if (targets.isEmpty()) {
            item(key = "empty") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Ещё не настроено: Termux, агент, вход в Claude — по шагам, или Linux в приложении без Termux.", style = MaterialTheme.typography.bodySmall)
                        Row {
                            Button(onClick = { guide = true }) { Text("Инструкция") }
                            TextButton(onClick = { linux = true }) { Text("Linux в приложении") }
                        }
                    }
                }
            }
        }
        if (restoring || reinstalled) {
            item(key = "restore") {
                // После восстановления карточка остаётся с отчётом, пока её не скроют.
                RestoreCard(controller, onHide = { restoring = false }) {
                    targets = controller.targets()
                    project = controller.project()
                    restoring = true
                }
            }
        }
        // Вход в среду (AI:A-3, терминал §4, п. 9): её экран — отдельно, здесь — одна строка.
        if (targets.isNotEmpty()) item(key = "linux") { LinuxEntry { linux = true } }
        items(targets, key = { it.id.value }) { t ->
            TargetCard(
                t, model, controller, work[t.id].orEmpty(), checks[t.id.value],
                onChecks = { checks[t.id.value] = it },
                onProject = { projectFor = t },
                onRename = { renaming = t },
                onRemove = { removing = t },
            )
        }
        item(key = "project") { ProjectCard(controller, targets, project) { project = it } }
    }

    removing?.let { t ->
        RemoveDialog(t, model, onCancel = { removing = null }) {
            removing = null
            scope.launch {
                withContext(Dispatchers.IO) { controller.remove(t) }
                targets = controller.targets()
            }
        }
    }

    projectFor?.let { t ->
        ProjectDialog(controller, t, targets.filter { it.configDir != null }) { saved ->
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

/**
 * Карточка target (AI:A-3; терминал §4, п. 7, A-16): на виду — «Проверить» и
 * «Терминал ▾» (оболочка, исполнитель в терминале, вход Codex или Gemini);
 * в ⋮ — «Проверить вход», «Проект», «Имя», «Удалить». Переключатели, слоты,
 * лимиты и Termux — в раскрытии «Настройки target ▸». Адрес и каталог —
 * одной строкой.
 */
@Composable
private fun TargetCard(
    t: TargetEntry,
    model: DemoModel,
    controller: Controller,
    busy: List<String>,
    lines: List<CheckLine>?,
    onChecks: (List<CheckLine>) -> Unit,
    onProject: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var settings by rememberSaveable(t.id.value) { mutableStateOf(false) }
    var terminalMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val posixSlot = t.configDir != null && t.platform == com.dpsmpx.dpai.execution.target.TargetPlatform.POSIX
    // Проба ждёт такта с SSH — секунды: отклик сразу, повтор не встаёт в очередь, итог — словами (A-6).
    val login = "вход ${t.executor.title}"
    val probing = lines?.firstOrNull()?.name == login && lines.firstOrNull()?.detail == PROBING
    fun check() {
        onChecks(listOf(CheckLine("проверка", "идёт…", Tone.NEUTRAL)))
        scope.launch {
            onChecks(
                withContext(Dispatchers.IO) {
                    // «В приложении» — агентом процесса; Linux не установлен — причина, а не отказ SSH.
                    val inApp = if (t.inApp) com.dpsmpx.dpai.android.linux.LinuxSetup.agentHost(context, controller.version) else null
                    if (t.inApp && inApp == null) {
                        listOf(CheckLine("агент", "Linux в приложении не установлен — «Linux в приложении» на этой вкладке", Tone.WARNING))
                    } else {
                        TargetScreens.doctor(controller.doctor(t, inApp))
                    }
                },
            )
        }
    }
    fun probe() {
        onChecks(listOf(CheckLine(login, PROBING, Tone.NEUTRAL)))
        scope.launch {
            val slots = withContext(Dispatchers.IO) {
                model.probe(t.id)
                model.slotsOf(t.id)
            }
            onChecks(
                slots.map { sl ->
                    val ok = sl.state == com.dpsmpx.dpai.core.state.AccountState.AVAILABLE || sl.state == com.dpsmpx.dpai.core.state.AccountState.BUSY
                    CheckLine(login, "слот ${sl.configDir.substringAfterLast('/')}: ${Words.account(sl.state.name)}", if (ok) Tone.DONE else Tone.WARNING)
                }.ifEmpty {
                    val how = if (t.executor == com.dpsmpx.dpai.core.ids.Executor.CLAUDE) "«Терминал ▾» → «Claude в терминале» → /login" else "«Терминал ▾» → «Войти в ${t.executor.title}»"
                    listOf(CheckLine(login, "слота нет: войдите в каталоге слота ($how) и проверьте снова", Tone.WARNING))
                },
            )
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(t.label, style = MaterialTheme.typography.titleSmall)
            val place = if (t.inApp) "Alpine в приложении" else "${t.user}@${t.host}:${t.port}"
            val slot = t.configDir?.let { "${t.executor.title} · $it" } ?: "без слота — только проверка агента"
            Text("$place · ${Words.targetType(t.type.name)} · $slot", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            // Чем занят слот (D-56): идущие задачи с проектом; нет — свободен.
            if (t.configDir != null) Text(if (busy.isEmpty()) "Сейчас: свободен" else "Сейчас: " + busy.joinToString("; "), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = ::check) { Text("Проверить") }
                // Свой терминал (D-71): оболочка target или интерактивный исполнитель слота (вход, /usage).
                Box {
                    TextButton(onClick = { if (posixSlot) terminalMenu = true else TerminalActivity.start(context, t.id.value) }) { Text(if (posixSlot) "Терминал ▾" else "Терминал") }
                    androidx.compose.material3.DropdownMenu(expanded = terminalMenu, onDismissRequest = { terminalMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Оболочка") }, onClick = { terminalMenu = false; TerminalActivity.start(context, t.id.value) })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("${t.executor.title} в терминале") }, onClick = { terminalMenu = false; TerminalActivity.start(context, t.id.value, claude = true) })
                        // Вход Codex и Gemini — своей командой (D-100): код устройства или ссылка без браузера здесь.
                        if (t.executor != com.dpsmpx.dpai.core.ids.Executor.CLAUDE) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Войти в ${t.executor.title}") }, onClick = { terminalMenu = false; TerminalActivity.start(context, t.id.value, login = true) })
                        }
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Box {
                    androidx.compose.material3.IconButton(onClick = { more = true }, modifier = Modifier.semantics { contentDescription = "Ещё действия: ${t.label}" }) { Text("⋮", style = MaterialTheme.typography.titleMedium) }
                    androidx.compose.material3.DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        // «Проверить вход» — проверяет, а не входит; входят «Терминал ▾» (AI:A-11, терминал:A-16).
                        if (t.configDir != null) {
                            androidx.compose.material3.DropdownMenuItem(enabled = !probing, text = { Text(if (probing) "Проверяю вход…" else "Проверить вход") }, onClick = { more = false; probe() })
                        }
                        // Проект — у любого target: ход Claude может идти на другом, со своим входом (D-50); и «в приложении» (D-75, шаг 5).
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Проект…") }, onClick = { more = false; onProject() })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Имя…") }, onClick = { more = false; onRename() })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Удалить…", color = MaterialTheme.colorScheme.error) }, onClick = { more = false; onRemove() })
                    }
                }
            }
            lines?.forEach { c -> CheckRow(c) }
            val local = TermuxLauncher.local(t)
            if (t.configDir != null || local) {
                Text(
                    if (settings) "Настройки target ▾" else "Настройки target ▸",
                    Modifier.fillMaxWidth().clickable { settings = !settings }.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (settings) {
                    if (t.configDir != null) {
                        TargetSwitches(model, t)
                        UsageAutoSwitch(model, t)
                    }
                    // Termux на этом телефоне (D-46) — у его target, а не отдельной карточкой (AI:A-3).
                    if (local) TermuxCard(t.port, framed = false)
                }
            }
        }
    }
}

/** Строка входа в «Linux в приложении» (AI:A-3): среда — своим экраном. */
@Composable
private fun LinuxEntry(onOpen: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val installed = remember { com.dpsmpx.dpai.android.linux.LinuxSetup.environment(context).installed() }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Linux в приложении", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text((if (installed != null) "установлен" else "не установлен") + " ▸", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Проект контура (D-50): где репозиторий, кто ходит, какая проверка; убрать — окном (AI:A-4). */
@Composable
private fun ProjectCard(controller: Controller, targets: List<TargetEntry>, project: ProjectEntry?, onChanged: (ProjectEntry?) -> Unit) {
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Проект", style = MaterialTheme.typography.titleMedium)
            val p = project
            if (p == null) {
                Text("Нет: задачи идут в каталоге агента, без Git и проверки. Задайте у target с репозиторием: ⋮ → «Проект…».", style = MaterialTheme.typography.bodySmall)
            } else {
                val place = targets.firstOrNull { it.id == p.target }?.label ?: "target удалён"
                Text("$place: репозиторий ${p.root}; деревья ${p.worktreeRoot}; ветка ${p.integrationBranch}", style = MaterialTheme.typography.bodySmall)
                p.claude?.let { c ->
                    val on = targets.firstOrNull { it.id == c.target }?.label ?: "target удалён"
                    Text("Claude — на $on (вход его слота), дерево — по SSH ключом ${c.identityFile}", style = MaterialTheme.typography.bodySmall)
                }
                Text(p.checks.firstOrNull()?.let { "проверка: ${it.command.joinToString(" ")}" } ?: "без проверки", style = MaterialTheme.typography.bodySmall)
                // Окном (AI:A-4) и с проверкой итога (аудит удобства, A-18): поля проекта придётся вводить заново.
                var asking by remember { mutableStateOf(false) }
                var failed by remember { mutableStateOf<String?>(null) }
                TextButton(onClick = { asking = true }) { Text("Убрать проект…") }
                if (asking) {
                    ConfirmDialog(
                        "Убрать проект?",
                        "Задачи пойдут без Git и проверки. Поля проекта — репозиторий, деревья, ветку, проверки — придётся вводить заново.",
                        "Убрать",
                        onConfirm = {
                            asking = false
                            scope.launch {
                                when (val r = withContext(Dispatchers.IO) { controller.setProject(null) }) {
                                    is Outcome.Success -> onChanged(null)
                                    is Outcome.Failure -> failed = "Не убран: ${r.errors.first().message}"
                                }
                            }
                        },
                        onDismiss = { asking = false },
                    )
                }
                failed?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** Ключ приложения для `~/.ssh/authorized_keys` на target (D-35). */
@Composable
internal fun AppKeyCard(controller: Controller) {
    val scope = rememberCoroutineScope()
    var publicKey by remember { mutableStateOf<String?>(null) }
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

@Composable
private fun CheckRow(c: CheckLine) {
    // Состояние — и знаком, а не только цветом (AI:A-12): «✓», «!», «✕».
    val mark = when (c.tone) {
        Tone.DONE -> "✓ "
        Tone.WARNING -> "! "
        Tone.ERROR -> "✕ "
        Tone.ACTIVE, Tone.NEUTRAL -> ""
    }
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(mark + c.name, Modifier.weight(0.35f), color = toneColor(c.tone), style = MaterialTheme.typography.labelMedium)
        Text(c.detail, Modifier.weight(0.65f), style = MaterialTheme.typography.bodySmall)
    }
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

/**
 * Проект на target: репозиторий, каталог деревьев задач, ветка интеграции,
 * команда проверки и где идёт ход Claude — на этом target или на другом
 * с каталогом Claude (D-50: дерево — по SSH ключом того target).
 */
@Composable
private fun ProjectDialog(controller: Controller, target: TargetEntry, withClaude: List<TargetEntry>, onDone: (ProjectEntry?) -> Unit) {
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf("") }
    var trees by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var check by remember { mutableStateOf("") }
    var claudeOn by remember { mutableStateOf(withClaude.firstOrNull { it.id == target.id } ?: withClaude.firstOrNull { it.type == com.dpsmpx.dpai.execution.target.TargetType.TERMUX_LOCAL } ?: withClaude.firstOrNull()) }
    var key by remember { mutableStateOf(com.dpsmpx.dpai.ui.ProjectForm.DEFAULT_IDENTITY) }
    var known by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var linking by remember { mutableStateOf<String?>(null) }
    var linked by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Проект на ${target.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(root, { root = it }, label = { Text("Каталог репозитория") }, singleLine = true)
                OutlinedTextField(trees, { trees = it }, label = { Text("Каталог деревьев задач") }, singleLine = true)
                OutlinedTextField(branch, { branch = it }, label = { Text("Ветка интеграции") }, singleLine = true)
                OutlinedTextField(check, { check = it }, label = { Text("Команда проверки (необязательно)") }, singleLine = true)
                Text("Где работает Claude", style = MaterialTheme.typography.labelLarge)
                if (withClaude.isEmpty()) Text("Нет target с каталогом Claude", style = MaterialTheme.typography.bodySmall)
                withClaude.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = claudeOn?.id == c.id, onClick = { claudeOn = c })
                        Text(if (c.id == target.id) "${c.label} (здесь же)" else "${c.label} — дерево по SSH", style = MaterialTheme.typography.bodySmall)
                    }
                }
                val on = claudeOn
                if (on != null && on.id != target.id) {
                    // Связка D-53: приложение само кладёт ключ Claude на ${target.label} и ключ хоста — в Termux.
                    Text(
                        linking ?: if (linked) "✓ Связано: Claude на ${on.label} ходит на ${target.label} ключом $key" else "Claude на ${on.label} ходит на ${target.label} по SSH своим ключом — его можно настроить автоматически.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(enabled = linking == null, onClick = {
                        error = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { controller.linkWorkspace(on, target) { linking = "… $it" } }
                            linking = null
                            when (r) {
                                is Outcome.Failure -> error = r.diagnostics.first().let { d -> listOfNotNull(d.message, d.causes.firstOrNull()?.message).joinToString(": ") }
                                is Outcome.Success -> {
                                    key = com.dpsmpx.dpai.ui.WorkspaceLink.KEY
                                    known = com.dpsmpx.dpai.ui.WorkspaceLink.KNOWN_HOSTS
                                    linked = true
                                }
                            }
                        }
                    }) { Text(if (linked) "Связать заново" else "Связать автоматически") }
                    OutlinedTextField(key, { key = it }, label = { Text("Ключ SSH на ${on.label}") }, singleLine = true)
                    OutlinedTextField(known, { known = it }, label = { Text("known_hosts на нём (необязательно)") }, singleLine = true)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val f = controller.projectForm(target, root, trees, branch, check, claudeOn, key, known)) {
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
internal fun TermuxCard(port: Int, framed: Boolean = true) {
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
    Framed(framed) {
        Column(if (framed) Modifier.padding(12.dp) else Modifier) {
            Text("Termux на этом телефоне", style = MaterialTheme.typography.titleSmall)
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
            } else if (!TermuxLauncher.supportsRunCommand(context)) {
                Text(
                    "Этот Termux (из Google Play) не принимает команды от других приложений. sshd поднимается автозапуском при загрузке телефона " +
                        "(~/.termux/boot/10-dpai-sshd) или вашим виджетом.",
                    style = MaterialTheme.typography.bodySmall,
                )
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

/**
 * Контур (D-42, п. 6): общий предел одновременных сессий — сверх
 * предела каждого target; выше него новые задачи ждут.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ControlCard(model: DemoModel) {
    val scope = rememberCoroutineScope()
    var limit by remember { mutableStateOf(model.control()?.maxSessions) }
    var live by remember { mutableStateOf(model.control() != null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val c = withContext(Dispatchers.IO) { model.control() }
            live = c != null
            limit = c?.maxSessions
            kotlinx.coroutines.delay(3000)
        }
    }
    if (!live) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Контур", style = MaterialTheme.typography.titleMedium)
            Text("Сессий одновременно: ${limit ?: "без общего предела"}", style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (v in listOf<Int?>(null, 1, 2, 4, 8)) {
                    androidx.compose.material3.FilterChip(selected = limit == v, onClick = {
                        scope.launch {
                            note = when (val r = withContext(Dispatchers.IO) { model.command(com.dpsmpx.dpai.orchestrator.commands.Command.SetSessionLimit(v)) }) {
                                is com.dpsmpx.dpai.core.result.Outcome.Success -> null.also { limit = v }
                                is com.dpsmpx.dpai.core.result.Outcome.Failure -> "Не принято: ${r.diagnostics.first().message}"
                            }
                        }
                    }, label = { Text(v?.toString() ?: "∞") })
                }
            }
            note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/**
 * Target и его слоты (D-42, п. 5): выключенный target новых сессий не
 * получает (идущие доигрывают); выключенный слот не арендуется, после
 * включения — проба входа.
 */
@Composable
private fun TargetSwitches(model: DemoModel, t: TargetEntry) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(model.control()?.let { t.id !in it.disabledTargets }) }
    var slots by remember { mutableStateOf(model.slotsOf(t.id)) }
    // Намерения (D-57): переключатель показывает желаемое, пока контур занят, и не откатывается.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(t.id) {
        while (true) {
            enabled = withContext(Dispatchers.IO) { model.control()?.let { t.id !in it.disabledTargets } }
            slots = withContext(Dispatchers.IO) { model.slotsOf(t.id) }
            tick++
            kotlinx.coroutines.delay(3000)
        }
    }
    val on = enabled ?: return
    val wantTarget = remember(tick, on) { model.desired(com.dpsmpx.dpai.orchestrator.commands.Command.SetTargetEnabled(t.id, on)) as? com.dpsmpx.dpai.orchestrator.commands.Command.SetTargetEnabled }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Принимает новые задачи" + if (wantTarget != null) " · применится" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        androidx.compose.material3.Switch(checked = wantTarget?.enabled ?: on, onCheckedChange = { v ->
            scope.launch {
                withContext(Dispatchers.IO) { model.command(com.dpsmpx.dpai.orchestrator.commands.Command.SetTargetEnabled(t.id, v)) }
                tick++
            }
        })
    }
    for (slot in slots) {
        val want = remember(tick, slot) { model.desired(com.dpsmpx.dpai.orchestrator.commands.Command.SetSlotEnabled(slot.id, !slot.disabled)) as? com.dpsmpx.dpai.orchestrator.commands.Command.SetSlotEnabled }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("слот ${slot.configDir.substringAfterLast('/')} · ${Words.account(slot.state.name)}" + if (want != null) " · применится" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.Switch(checked = want?.enabled ?: !slot.disabled, onCheckedChange = { v ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        model.command(com.dpsmpx.dpai.orchestrator.commands.Command.SetSlotEnabled(slot.id, v))
                        // Включённый слот — вход неизвестен: проба, а не «как было» (D-13).
                        if (v) model.probe(t.id)
                    }
                    tick++
                    slots = withContext(Dispatchers.IO) { model.slotsOf(t.id) }
                }
            })
        }
        model.refusal(com.dpsmpx.dpai.orchestrator.commands.Command.SetSlotEnabled(slot.id, true))?.let { Text("Не применено: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
    }
}

/**
 * Командная строка (D-47): `dpai` в Termux на этом телефоне (или с ПК
 * через SSH-туннель) управляет контуром через канал на 127.0.0.1; код
 * сопряжения — здесь, на две минуты.
 */
@Composable
internal fun CliCard() {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var code by remember { mutableStateOf<String?>(null) }
    var until by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val undo = LocalUndo.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Командная строка dpai", style = MaterialTheme.typography.titleMedium)
            var running by remember { mutableStateOf(ControlServer.running) }
            var problem by remember { mutableStateOf(ControlServer.problem) }
            LaunchedEffect(Unit) {
                while (true) {
                    running = ControlServer.running
                    problem = ControlServer.problem
                    kotlinx.coroutines.delay(2000)
                }
            }
            Text(
                when {
                    running -> "Канал: 127.0.0.1:${ControlServer.PORT} — открыт"
                    problem != null -> "Канал не открылся: $problem"
                    else -> "Канал закрыт: он работает вместе со службой контроллера"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (running) androidx.compose.ui.graphics.Color.Unspecified else MaterialTheme.colorScheme.error,
            )
            if (!running) TextButton(onClick = { ControlServer.start(context) }) { Text("Открыть канал") }
            code?.let { c ->
                // Код — в своём окне с FLAG_SECURE (дубль D-86 аудита безопасности 1 ноября 2026): на главном
                // экране он попадал в превью «недавних» и в снимки; остальные экраны снимать можно (§5.8).
                AlertDialog(
                    onDismissRequest = { code = null },
                    properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
                    title = { Text("Код сопряжения dpai") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(c, style = MaterialTheme.typography.headlineMedium)
                            Text("В Termux: dpai control pair $c — код действует 2 минуты", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = { TextButton(onClick = { code = null }) { Text("Готово") } },
                )
            }
            Row {
                TextButton(onClick = {
                    val (c, u) = ControlServer.pairing(context).newCode()
                    code = c
                    until = u.epochMillis
                    note = null
                }) { Text("Показать код") }
                // Окном (AI:A-4): после отзыва каждый dpai нужно сопрячь заново.
                var asking by remember { mutableStateOf(false) }
                TextButton(onClick = { asking = true }) { Text("Отозвать токен…") }
                if (asking) {
                    ConfirmDialog(
                        "Отозвать токен командной строки?",
                        "Все сопряжённые dpai перестанут управлять приложением; каждый нужно будет сопрячь заново новым кодом.",
                        "Отозвать",
                        onConfirm = {
                            asking = false
                            ControlServer.pairing(context).revoke()
                            code = null
                            undo.tell("Токен отозван: dpai нужно сопрячь заново") { note = it }
                        },
                        onDismiss = { asking = false },
                    )
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/**
 * Удаление target — с подтверждением (ревью 8 октября 2026, A-32). Пока на
 * нём идут сессии, удалить нельзя: ход Claude на target удаление не
 * останавливает, а сессии и задачи без target зависли бы навсегда.
 * Сначала — выключить приём задач и дождаться конца сессий.
 */
@Composable
private fun RemoveDialog(t: TargetEntry, model: DemoModel, onCancel: () -> Unit, onRemove: () -> Unit) {
    val active by produceState<Int?>(null, t.id) { value = withContext(Dispatchers.IO) { model.activeSessionsOn(t.id) } ?: 0 }
    val busy = (active ?: 0) > 0
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Удалить target «${t.label}»?") },
        text = {
            Text(
                when {
                    active == null -> "Проверка сессий на target…"
                    busy -> "На target идут сессии: $active. Удаление их не остановит — ход Claude продолжится без контроля, " +
                        "а задачи зависнут. Сначала выключите «Принимает новые задачи» и дождитесь конца сессий."
                    else -> "Target уйдёт из списка; ключ хоста останется в known_hosts. Задачи на нём больше не запустятся."
                },
            )
        },
        confirmButton = { TextButton(onClick = onRemove, enabled = active != null && !busy) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}

/**
 * Копия настроек (D-51): в «Загрузки/dpai-backup» — сама после изменения и
 * раз в час, и по кнопке; загрузка — системным выбором файла. Ключ SSH
 * приложения в копию не входит: после восстановления на новом ключе его
 * строку надо снова добавить в authorized_keys targets.
 */
@Composable
internal fun BackupCard(controller: Controller, framed: Boolean = true, onRestoreAccess: () -> Unit, onRestored: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var last by remember { mutableStateOf(SettingsBackups.last(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    val undo = LocalUndo.current
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val r = withContext(Dispatchers.IO) { SettingsBackups.restore(context, uri, controller) }
                message = when (r) {
                    is com.dpsmpx.dpai.core.result.Outcome.Success ->
                        "Восстановлено: targets — ${r.value.targets.size}, проект — ${if (r.value.project != null) "есть" else "нет"}. " +
                            "Ключ приложения мог смениться: «Показать открытый ключ» и добавьте строку в authorized_keys каждого target."
                    is com.dpsmpx.dpai.core.result.Outcome.Failure -> "Не восстановлено: ${r.errors.firstOrNull()?.message}"
                }
                last = SettingsBackups.last(context)
                onRestored()
            }
        }
    }
    Framed(framed) {
        Column(Modifier.padding(12.dp)) {
            Text("Копия настроек", style = MaterialTheme.typography.titleMedium)
            Text(
                "Загрузки/dpai-backup — сама после изменений и раз в час. " +
                    (last?.let { "Последняя: ${it.first}" } ?: "Копий ещё нет."),
                style = MaterialTheme.typography.bodySmall,
            )
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row {
                TextButton(onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { SettingsBackups.save(context, controller.snapshot(), controller) }
                        // Сохранено — снэкбаром (AI:A-19); отказ — строкой на месте.
                        val text = when (r) {
                            is com.dpsmpx.dpai.core.result.Outcome.Success -> "Сохранено: Загрузки/dpai-backup/${r.value}"
                            is com.dpsmpx.dpai.core.result.Outcome.Failure -> "Не сохранено: ${r.errors.firstOrNull()?.message}"
                        }
                        if (r is com.dpsmpx.dpai.core.result.Outcome.Success) undo.tell(text) { message = it } else message = text
                        last = SettingsBackups.last(context)
                    }
                }) { Text("Сохранить сейчас") }
                TextButton(onClick = { pick.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) }) { Text("Загрузить…") }
            }
            TextButton(onClick = onRestoreAccess) { Text("Восстановить доступ после переустановки") }
        }
    }
}

/**
 * Восстановление после переустановки (D-54): одна команда в Termux — и
 * приложение само возвращает доступ по SSH, настройки из копии в Termux,
 * targets по слотам Claude и ключ приложения на ПК. После копирования
 * команды карточка сама ждёт возвращения из Termux и пробует каждые 5 с.
 */
@Composable
internal fun RestoreCard(controller: Controller, framed: Boolean = true, onHide: () -> Unit, onRestored: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val nonce = remember { SettingsBackups.restoreNonce(context) }
    val user = remember { SettingsBackups.termuxUser(context) }
    var command by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<List<String>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        command = withContext(Dispatchers.IO) { com.dpsmpx.dpai.ui.TermuxRestore.command(controller.publicKeyLine(), nonce) }
    }
    suspend fun attempt(quiet: Boolean): Boolean {
        val u = user ?: run { message = "Termux не установлен"; return true }
        busy = "…"
        val r = withContext(Dispatchers.IO) { controller.restoreAccess(nonce, u) { busy = "… $it" } }
        busy = null
        return when (r) {
            is Outcome.Success -> {
                SettingsBackups.clearRestoreNonce(context)
                report = r.value
                message = "Готово. Проверьте targets кнопкой «Проверить»; вход — ⋮ → «Проверить вход»."
                onRestored()
                true
            }
            is Outcome.Failure -> {
                val d = r.diagnostics.first()
                if (!quiet || d.code == com.dpsmpx.dpai.core.diagnostics.DiagCode.SSH_HOST_KEY_REJECTED) message = d.message
                d.code == com.dpsmpx.dpai.core.diagnostics.DiagCode.SSH_HOST_KEY_REJECTED
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(waiting) {
        var left = 180
        while (waiting && left-- > 0) {
            kotlinx.coroutines.delay(5_000)
            if (busy == null && withContext(Dispatchers.IO) { TermuxLauncher.listening(com.dpsmpx.dpai.ui.TermuxRestore.PORT) } && attempt(quiet = true)) waiting = false
        }
        waiting = false
    }
    Framed(framed) {
        Column(Modifier.padding(12.dp)) {
            Text("Восстановление после переустановки", style = MaterialTheme.typography.titleMedium)
            Text(
                "Удаление приложения стёрло его настройки и ключ, но Termux цел: агент, входы Claude, копия настроек. " +
                    "Скопируйте команду, вставьте в Termux (долгое нажатие → Paste) и вернитесь — остальное приложение сделает само.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(enabled = command != null, onClick = {
                    val c = command ?: return@TextButton
                    Clipboard.copy(context, "dpai", c, sensitive = true)
                    context.packageManager.getLaunchIntentForPackage("com.termux")?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
                    message = "Команда скопирована. Вставьте её в Termux и вернитесь сюда."
                    waiting = true
                }) { Text("Копировать и открыть Termux") }
                TextButton(enabled = busy == null, onClick = { scope.launch { attempt(quiet = false) } }) { Text("Восстановить") }
            }
            (busy ?: if (waiting) "жду команду в Termux…" else null)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            report.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onHide) { Text("Скрыть") }
        }
    }
}

/** Новое имя слота (target): от 1 до 60 знаков; `null` — отмена. */
@Composable
private fun RenameDialog(t: TargetEntry, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(t.label) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Имя target") },
        text = { OutlinedTextField(name, { name = it.take(60) }, label = { Text("Имя") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onDone(name) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Отмена") } },
    )
}

/** Автообновление лимитов слота: включили — `/usage` сразу, дальше раз в минуту в фоне ([UsageAuto]). */
@Composable
private fun UsageAutoSwitch(model: DemoModel, t: TargetEntry) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(t.id in UsageAuto.enabled(context)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Лимиты: обновлять раз в минуту", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        androidx.compose.material3.Switch(checked = on, onCheckedChange = { v ->
            on = v
            UsageAuto.set(context, t.id, v)
            if (v) scope.launch { withContext(Dispatchers.IO) { model.refreshUsage(setOf(t.id)) } }
        })
    }
}

private const val PROBING = "проверяю вход…"
