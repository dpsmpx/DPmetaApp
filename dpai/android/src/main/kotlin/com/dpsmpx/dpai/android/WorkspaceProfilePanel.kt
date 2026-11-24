package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.ui.ManualStep
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.ManualProfile
import com.dpsmpx.dpai.ui.ManualProfileKind
import com.dpsmpx.dpai.ui.ProjectWorkspaceView
import com.dpsmpx.dpai.ui.WorkspaceLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Строка шага на экране: поля как ввёл человек; проверка — `ManualProfile.form`. */
private data class StepRow(val name: String, val command: String, val timeout: String)

private val StepRowsSaver = listSaver<SnapshotStateList<StepRow>, String>(
    save = { list -> list.flatMap { listOf(it.name, it.command, it.timeout) } },
    restore = { flat -> flat.chunked(3).filter { it.size == 3 }.map { StepRow(it[0], it[1], it[2]) }.toMutableStateList() },
)

/** Явные пользовательские шаги, один профиль каждого вида на текущий проект (D-134, D-154). */
@Composable
internal fun WorkspaceProfilePanel(model: DemoModel, view: ProjectWorkspaceView, location: WorkspaceLocation?, kind: ManualProfileKind, onProblem: (path: String, line: Int) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Шаги профиля (D-154): поля экрана переживают поворот; сохранённый профиль подставляется, пока поля пусты.
    val rows = rememberSaveable(view.project.project.value, kind.name, saver = StepRowsSaver) { mutableStateListOf(StepRow("", "", "1800")) }
    // Шаблоны артефактов (D-157) — у «Сборки»: через пробел, от корня дерева.
    var artifactsText by rememberSaveable(view.project.project.value, kind.name) { mutableStateOf("") }
    // Параметры и окружение (D-183): текстом, по строке; выбор вариантов — к запуску, помнится по проекту.
    var paramsText by rememberSaveable(view.project.project.value, kind.name) { mutableStateOf("") }
    var envText by rememberSaveable(view.project.project.value, kind.name) { mutableStateOf("") }
    // Что профилю нужно на target (D-186): коды через запятую — переживают поворот.
    var needsText by rememberSaveable(view.project.project.value, kind.name) { mutableStateOf("") }
    val needs = needsText.split(',').mapNotNull { com.dpsmpx.dpai.ui.ManualNeed.of(it) }.toSet()
    var choices by remember(view.project, kind) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loaded by remember(view.project, kind) { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var run by remember { mutableStateOf(model.manualProfileView()) }
    val jobs = LongJobs.get(context)
    val state by jobs.state.collectAsState()
    Poll(model, periodMs = 1500) { run = model.manualProfileView() }
    LaunchedEffect(view.project, kind) {
        when (val r = withContext(Dispatchers.IO) { model.manualProfile(view.project, kind) }) {
            is Outcome.Failure -> note = r.errors.first().message
            is Outcome.Success -> r.value?.let { p ->
                if (rows.all { it.command.isBlank() }) {
                    rows.clear()
                    rows.addAll(p.steps.map { StepRow(it.name, ManualProfile.quote(it.command), it.timeoutSeconds.toString()) })
                    if (artifactsText.isBlank()) artifactsText = p.artifacts.joinToString(" ")
                    if (paramsText.isBlank()) paramsText = com.dpsmpx.dpai.ui.ManualVars.paramsText(p.params)
                    if (envText.isBlank()) envText = com.dpsmpx.dpai.ui.ManualVars.envText(p.env)
                    if (needsText.isBlank()) needsText = p.needs.joinToString(",") { it.code }
                }
            }
        }
        choices = withContext(Dispatchers.IO) { model.manualChoices(view.project, kind) }
        loaded = true
    }
    val editable = loaded && !run.blocked && !busy
    fun form(): Outcome<ManualProfile> {
        val globs = when (val g = com.dpsmpx.dpai.files.TreeArtifacts.patterns(artifactsText)) {
            is Outcome.Failure -> return g
            is Outcome.Success -> g.value
        }
        return ManualProfile.form(kind, rows.map { Triple(it.name, it.command, it.timeout) }, globs, paramsText, envText, needs)
    }
    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
        Text(if (kind == ManualProfileKind.CHECK) "Ручная проверка" else "Ручная сборка", style = MaterialTheme.typography.titleSmall)
        Text("Шаги выполняются по порядку в выбранном дереве, без shell; следующий — только после кода 0 предыдущего. Для скрипта укажите интерпретатор явно. Секреты в команду не добавляйте.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text("Шаблон:", style = MaterialTheme.typography.bodySmall)
            for (t in ManualProfile.templates(kind)) TextButton(enabled = editable, onClick = {
                rows.clear()
                rows.addAll(t.steps.map { (n, c, s) -> StepRow(n, c, s) })
                if (t.title == "APK Android") needsText = com.dpsmpx.dpai.ui.ManualNeed.ANDROID_SDK.code
                if (t.title == "DPmetaApp") {
                    needsText = com.dpsmpx.dpai.ui.ManualNeed.ANDROID_SDK.code
                    artifactsText = "shell/build/outputs/apk/release/*.apk"
                    envText = "DPAI_RELEASE_DEBUG_KEY=1"
                    note = "Самосборка DPmetaApp: APK с отладочной подписью. Для обновления установленной студии переподпишите своим прежним ключом."
                }
                else note = "Шаблон «${t.title}» подставлен — проверьте команды и сохраните"
            }) { Text(t.title) }
        }
        if (kind == ManualProfileKind.BUILD && location?.task == null && location != null) {
            Text("Последний PR: автоматически выбирается самый новый открытый PR origin, включая черновики. Совместимые локальные изменения и новые файлы сохраняются и входят в сборку; при пересечении Git укажет файлы. Свои коммиты не перезаписываются; вход gh — на target проекта.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(enabled = editable && view.ready && !run.blocked && state[LongJobs.WORKSPACE_PROFILE]?.running != true, onClick = {
                    if (model.startLatestPr(view.project, location)) {
                        ControllerService.resume(context)
                        note = "Подтягиваю последний PR; привязка обновится автоматически"
                    } else note = "Сначала завершите другую операцию"
                }) { Text("Подтянуть последний PR") }
                TextButton(enabled = editable && view.ready && !run.blocked && state[LongJobs.WORKSPACE_PROFILE]?.running != true, onClick = {
                    when (val p = form()) {
                        is Outcome.Failure -> note = p.errors.first().message
                        is Outcome.Success -> if (model.startLatestPr(view.project, location, p.value, choices)) {
                            ControllerService.resume(context)
                            note = "После подготовки последнего PR запущу выбранный профиль сборки"
                        } else note = "Сначала завершите другую операцию"
                    }
                }) { Text("Собрать последний PR") }
            }
        }
        rows.forEachIndexed { i, row ->
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Шаг ${i + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(enabled = editable && i > 0, onClick = { rows.add(i - 1, rows.removeAt(i)) }, modifier = Modifier.semantics { contentDescription = "Шаг ${i + 1} выше" }) { Text("↑") }
                    TextButton(enabled = editable && i < rows.size - 1, onClick = { rows.add(i + 1, rows.removeAt(i)) }, modifier = Modifier.semantics { contentDescription = "Шаг ${i + 1} ниже" }) { Text("↓") }
                    TextButton(enabled = editable && rows.size > 1, onClick = { rows.removeAt(i) }) { Text("Убрать") }
                }
                OutlinedTextField(value = row.name, onValueChange = { rows[i] = row.copy(name = it.take(ManualStep.MAX_NAME)) }, enabled = editable, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = row.command, onValueChange = { rows[i] = row.copy(command = it) }, enabled = editable, label = { Text("Команда и аргументы") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = row.timeout, onValueChange = { rows[i] = row.copy(timeout = it.filter(Char::isDigit).take(5)) }, enabled = editable, label = { Text("Таймаут, секунд") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }
        TextButton(enabled = editable && rows.size < ManualProfile.MAX_STEPS, onClick = { rows.add(StepRow("", "", "1800")) }) { Text("+ Шаг") }
        if (kind == ManualProfileKind.BUILD) OutlinedTextField(value = artifactsText, onValueChange = { artifactsText = it.take(4000) }, enabled = editable,
            label = { Text("Артефакты: шаблоны через пробел (пусто — **/*.apk **/*.aab)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = paramsText, onValueChange = { paramsText = it.take(4000) }, enabled = editable,
            label = { Text("Параметры: по строке ИМЯ | подпись | вариант, вариант") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = envText, onValueChange = { envText = it.take(8000) }, enabled = editable,
            label = { Text("Окружение шагов: по строке ИМЯ=значение, без секретов") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text("Нужно на target:", style = MaterialTheme.typography.bodySmall)
            for (n in com.dpsmpx.dpai.ui.ManualNeed.entries) androidx.compose.material3.FilterChip(
                selected = n in needs, enabled = editable,
                onClick = { needsText = (if (n in needs) needs - n else needs + n).joinToString(",") { it.code } },
                label = { Text(n.title) },
            )
        }
        Text("В Linux приложения нужное проверяется до запуска, ANDROID_HOME подставляется сам; на ПК — его SDK.", style = MaterialTheme.typography.bodySmall)
        if (view.target?.inApp == true && kind in setOf(ManualProfileKind.CHECK, ManualProfileKind.BUILD)) {
            val compatible = context.applicationInfo.targetSdkVersion <= 28
            Text("APK внутри приложения: ${if (compatible) "режим совместимости с Termux включён (targetSdk 28)" else "этот APK имеет targetSdk 29+ — Android может запретить запуск JVM"}. " +
                "В Linux откройте «Android SDK…»: приложение отдельно установит нативный Termux/Bionic JDK 21, SDK и инструменты ARM64 (aapt2/zipalign/aidl). Затем выберите шаблон «APK Android». " +
                "Alpine openjdk21-jdk не заменяет нативный JDK. Перед Gradle приложение проверяет java и javac. Проекты с C/C++ требуют также ARM64 NDK.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("В командах и окружении: \${PROJECT}, \${ROOT}, \${TREE}, \${BRANCH}, \${TARGET} и свои \${ИМЯ}; сам знак \${ — \$\${.",
            style = MaterialTheme.typography.bodySmall)
        Text("В sh -c и PowerShell подстановки не экранируются: используйте только доверенные значения. Пути и имена передавайте через окружение, обращения к переменным заключайте в кавычки.",
            style = MaterialTheme.typography.bodySmall)
        // Выбор вариантов к запуску (D-183): первый — по умолчанию, последний выбор помнится.
        val declared = remember(paramsText) { (com.dpsmpx.dpai.ui.ManualVars.parseParams(paramsText) as? Outcome.Success)?.value.orEmpty() }
        for (prm in declared) {
            val current = choices[prm.name]?.takeIf { it in prm.choices } ?: prm.choices.first()
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text("${prm.title}:", style = MaterialTheme.typography.bodySmall)
                for (c in prm.choices) androidx.compose.material3.FilterChip(selected = c == current, onClick = { choices = choices + (prm.name to c) },
                    label = { Text(c) }, modifier = Modifier.padding(start = 4.dp))
            }
        }
        TextButton(enabled = editable, onClick = {
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { when (val p = form()) {
                    is Outcome.Failure -> p
                    is Outcome.Success -> model.saveManualProfile(view.project, p.value)
                } }
                note = if (r is Outcome.Failure) r.errors.first().message else "Профиль сохранён"
                busy = false
            }
        }) { Text("Сохранить профиль") }
        TextButton(enabled = loaded && !busy && !run.blocked && location != null && view.ready && state[LongJobs.WORKSPACE_PROFILE]?.running != true, onClick = {
            when (val p = form()) {
                is Outcome.Failure -> note = p.errors.first().message
                is Outcome.Success -> {
                    val picked = p.value.params.associate { prm -> prm.name to (choices[prm.name]?.takeIf { it in prm.choices } ?: prm.choices.first()) }
                    if (model.startManualProfile(view.project, location!!, p.value, picked)) {
                        ControllerService.resume(context)
                        note = "Запрос запуска принят; результат появится ниже"
                    } else note = "Сначала завершите другую операцию Linux/DP-Verify"
                }
            }
        }) { Text(if (kind == ManualProfileKind.CHECK) "Запустить проверку" else "Запустить сборку") }
        // Ход по шагам (D-154): ✓ — код 0, ✗ — ошибка, ▶ — идёт, · — ещё не запускался.
        run.active?.profile?.steps?.takeIf { it.size > 1 }?.forEachIndexed { i, s ->
            val r = run.results.firstOrNull { it.step == i + 1 }
            val mark = when {
                r != null && r.ok -> "✓"
                r != null -> "✗"
                run.step == i + 1 -> "▶"
                else -> "·"
            }
            Text("$mark ${i + 1}. ${s.name}" + (r?.takeIf { !it.ok }?.let { " — ${it.reason}" }.orEmpty()), style = MaterialTheme.typography.bodyMedium)
        }
        note?.let { Text(it) }
        state[LongJobs.WORKSPACE_PROFILE]?.let { job -> if (!job.running) job.note?.let { Text(it) } }
        if (run.blocked) {
            Text("Весь контур проекта на паузе", color = MaterialTheme.colorScheme.error)
            run.active?.let { Text("${it.target.label}: ${it.path}\n${it.branch}", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { model.stopManualProfile(); ControllerService.resume(context) }) { Text("Безопасно остановить и снять паузу") }
        }
        if (run.note.isNotEmpty()) Text(run.note)
        // Места в коде из вывода (D-153): касание — файл дерева вкладкой на строке.
        var found by remember { mutableStateOf<com.dpsmpx.dpai.ui.BuildProblemList?>(null) }
        LaunchedEffect(run.output) { found = withContext(Dispatchers.Default) { com.dpsmpx.dpai.ui.BuildProblems.parse(run.output) }.takeIf { it.items.isNotEmpty() } }
        found?.let { list ->
            Text("Ошибки сборки — ${list.summary()}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            for (p in list.items.take(PROBLEMS_SHOWN)) {
                val rel = location?.let { com.dpsmpx.dpai.ui.BuildProblems.relative(p.path, it.path) }
                val mark = when (p.severity) {
                    com.dpsmpx.dpai.ui.ProblemSeverity.ERROR -> "✖"
                    com.dpsmpx.dpai.ui.ProblemSeverity.WARNING -> "▲"
                    com.dpsmpx.dpai.ui.ProblemSeverity.NOTE -> "·"
                }
                Column(Modifier.fillMaxWidth().then(if (rel != null) Modifier.clickable { onProblem(rel, p.line) } else Modifier).padding(vertical = 6.dp)) {
                    Text("$mark ${rel ?: p.path}:${p.line}${p.column?.let { ":$it" }.orEmpty()}" + if (rel == null) " — вне дерева" else "",
                        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                        color = if (p.severity == com.dpsmpx.dpai.ui.ProblemSeverity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(p.message, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
            }
            if (list.items.size > PROBLEMS_SHOWN) Text("…и ещё ${list.items.size - PROBLEMS_SHOWN}: исправьте первые и запустите снова", style = MaterialTheme.typography.bodySmall)
        }
        // Варианты без ИИ (D-188; DPmetaApp:D-168): правила по ошибке, выбор — человека; «Поручить ИИ» — последним.
        var askAi by remember { mutableStateOf(false) }
        if (!run.blocked && run.results.lastOrNull()?.ok == false) {
            val steps = (form() as? Outcome.Success)?.value?.steps.orEmpty()
            val problems = found ?: com.dpsmpx.dpai.ui.BuildProblemList(emptyList(), false)
            val options = com.dpsmpx.dpai.ui.FailureOptions.of(steps, run.results, run.output, problems) { p -> location?.let { com.dpsmpx.dpai.ui.BuildProblems.relative(p, it.path) } }
            Text("Что делать — без ИИ", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            for (o in options) TextButton(onClick = {
                when (o.kind) {
                    com.dpsmpx.dpai.ui.FailureOptions.Kind.OPEN_PROBLEM -> onProblem(o.path ?: return@TextButton, o.line ?: 1)
                    com.dpsmpx.dpai.ui.FailureOptions.Kind.ASK_AI -> askAi = true
                    else -> note = o.detail
                }
            }) { Text(o.title) }
            if (askAi) androidx.compose.material3.AlertDialog(
                onDismissRequest = { askAi = false },
                title = { Text("Поручить ИИ?") },
                text = { Text("Новая задача ИИ с выжимкой ошибки: шаг, команда, до 5 мест и 40 последних строк вывода. Это расход токенов. " +
                    "«Сначала снова без ИИ» запустит тот же профиль: прошло — задача не нужна, упало — задача со свежей выжимкой.", style = MaterialTheme.typography.bodySmall) },
                confirmButton = {
                    // Предполёт (D-190; DPmetaApp:D-168): тот же профиль без ИИ; ИИ — только если снова упало.
                    val profileNow = (form() as? Outcome.Success)?.value
                    if (profileNow != null && location != null) TextButton(onClick = {
                        askAi = false
                        val loc = location
                        note = "Предполёт: запускаю профиль снова, без ИИ…"
                        model.startManualProfile(view.project, loc, profileNow, null) { _, v ->
                            when (com.dpsmpx.dpai.ui.FailureOptions.preflight(v.complete, v.results)) {
                                com.dpsmpx.dpai.ui.FailureOptions.Preflight.PASSED -> scope.launch { note = "Прошло без ИИ — задача ИИ не нужна" }
                                com.dpsmpx.dpai.ui.FailureOptions.Preflight.UNCLEAR -> scope.launch { note = "Предполёт не дал итога (остановлен или не подтверждён) — решите сами" }
                                com.dpsmpx.dpai.ui.FailureOptions.Preflight.FAILED -> {
                                    val fresh = com.dpsmpx.dpai.ui.BuildProblems.parse(v.output)
                                    val (t, o) = com.dpsmpx.dpai.ui.FailureOptions.aiTask(kind, profileNow.steps, v.results, v.output, fresh, loc.branch)
                                    val key = com.dpsmpx.dpai.core.ids.IdGenerator(com.dpsmpx.dpai.core.clock.Clock.SYSTEM, com.dpsmpx.dpai.core.clock.RandomSource.secure())
                                        .next(com.dpsmpx.dpai.core.ids.IdKinds.OPERATION).value
                                    val r = when (val f = com.dpsmpx.dpai.ui.NewTaskForm.command(t, o, key)) {
                                        is Outcome.Failure -> f
                                        is Outcome.Success -> model.addTask(f.value)
                                    }
                                    scope.launch { note = if (r is Outcome.Success) "Снова упало — задача ИИ создана: «$t»" else (r as Outcome.Failure).diagnostics.first().message }
                                }
                            }
                        }.let { started -> if (!started) note = "Предполёт не запустился: занято другим долгим делом" }
                    }) { Text("Сначала снова без ИИ") }
                    TextButton(onClick = {
                        askAi = false
                        val (title, objective) = com.dpsmpx.dpai.ui.FailureOptions.aiTask(kind, steps, run.results, run.output, problems, location?.branch.orEmpty())
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                val key = com.dpsmpx.dpai.core.ids.IdGenerator(com.dpsmpx.dpai.core.clock.Clock.SYSTEM, com.dpsmpx.dpai.core.clock.RandomSource.secure())
                                    .next(com.dpsmpx.dpai.core.ids.IdKinds.OPERATION).value
                                when (val f = com.dpsmpx.dpai.ui.NewTaskForm.command(title, objective, key)) {
                                    is Outcome.Failure -> f
                                    is Outcome.Success -> model.addTask(f.value)
                                }
                            }
                            note = when (r) {
                                is Outcome.Success -> "Задача ИИ создана: «$title» — она на странице «ИИ»"
                                is Outcome.Failure -> r.diagnostics.first().message
                            }
                        }
                    }) { Text("Поручить сразу") }
                },
                dismissButton = { TextButton(onClick = { askAi = false }) { Text("Отмена") } },
            )
        }
        if (run.output.isNotEmpty()) SelectionContainer { Text(run.output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        if (kind == ManualProfileKind.BUILD) {
            val saved = remember(artifactsText) { (com.dpsmpx.dpai.files.TreeArtifacts.patterns(artifactsText) as? Outcome.Success)?.value.orEmpty() }
            // Последняя успешная сборка не старше суток — её новые APK подписываются сами (D-178).
            var built by remember(view.project.project) { mutableStateOf<BuildDone?>(null) }
            LaunchedEffect(view.project.project, run.finished) {
                val last = withContext(Dispatchers.IO) { model.manualHistory(view.project) }.valueOrNull()?.firstOrNull { it.kind == ManualProfileKind.BUILD }
                val now = com.dpsmpx.dpai.core.clock.Clock.SYSTEM.now().epochMillis
                built = last?.takeIf { it.outcome == com.dpsmpx.dpai.ui.ManualOutcome.OK && now - it.finishedAt.epochMillis < SIGN_AFTER_BUILD_MS }
                    ?.let { BuildDone(it.run.value, it.startedAt?.epochMillis, it.finishedAt.epochMillis) }
            }
            ArtifactsSection(model, view, location, saved, refresh = run.finished, built = built)
        }
        ManualHistorySection(model, view.project, kind, location, refresh = run.finished, onProblem = onProblem)
        Text("Ручной итог не завершает задачу ИИ. Перед запуском сохраните или отмените правки; контур должен быть свободен.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Подписывать сами APK сборки не старше суток (D-178): старые — только по кнопке. */
private const val SIGN_AFTER_BUILD_MS = 24L * 60 * 60 * 1000

/** Сколько мест показывать под выводом: дальше — шум от первой ошибки. */
private const val PROBLEMS_SHOWN = 100
