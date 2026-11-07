package com.dpsmpx.dpai.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
 * Экран «Linux в приложении» (D-75, шаг 5; AI:A-3, терминал §4, п. 9):
 * Alpine под proot в данных приложения — установка из APK, терминал первой
 * кнопкой, пакеты (с ними — Codex и Gemini), слот одной кнопкой «Добавить
 * слот ▾». Claude Code человек ставит сам и входит в каталоге слота (D-27,
 * D-29) — как, сказано в свёрнутом «Как поставить Claude Code»; приложение
 * его не скачивает. Локальная модель — во «Моделях» (`DP_AI_Orcestrator:D-113`).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LinuxScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val available = remember { LinuxSetup.available(context) }
    var installed by remember { mutableStateOf(LinuxSetup.environment(context).installed()) }
    // Долгое — вне экрана (AI:A-14): ход, вывод и итог переживают уход с экрана; повтор не запускается.
    val jobs = remember { LongJobs.get(context) }
    val state by jobs.state.collectAsState()
    val install = state[JOB_INSTALL]
    val work = state[JOB_APK]
    val running = install?.running == true || work?.running == true
    LaunchedEffect(install?.running) { if (install?.running == false) installed = LinuxSetup.environment(context).installed() }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var allOutput by remember { mutableStateOf(false) }
    var packages by rememberSaveable { mutableStateOf("") }
    var howto by rememberSaveable { mutableStateOf(false) }
    var slotMenu by remember { mutableStateOf(false) }

    fun run(label: String, job: () -> Outcome<com.dpsmpx.dpai.execution.process.Completed>, done: (com.dpsmpx.dpai.execution.process.Completed) -> String) {
        allOutput = false
        jobs.start(JOB_APK, label) {
            when (val r = job()) {
                is Outcome.Success -> LongJobs.Done(done(r.value), (r.value.stdout + r.value.stderr).takeLast(MAX_OUTPUT))
                is Outcome.Failure -> LongJobs.Done("$label: ${r.errors.first().message}")
            }
        }
    }
    fun apk(action: LinuxEnvironment.Apk, label: String) = run(label, {
        val names = if (action == LinuxEnvironment.Apk.UPGRADE) emptyList() else packages.split(' ', ',', '\n')
        LinuxSetup.apk(context, action, names)
    }) { if (it.ok) "$label: готово" else "$label: apk завершился с кодом ${it.exitCode} — причина в выводе ниже" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onBack) { Text("← Назад") }
        Text("Linux в приложении", style = MaterialTheme.typography.titleMedium)
        Text("Alpine в данных приложения — без Termux.", style = MaterialTheme.typography.bodySmall)
        when {
            !available -> Text("Недоступно на этом телефоне: нужен процессор arm64.", style = MaterialTheme.typography.bodySmall)
            installed == null -> Button(enabled = !running, onClick = {
                jobs.start(JOB_INSTALL, "Установка Alpine") {
                    when (val r = LinuxSetup.install(context)) {
                        is Outcome.Success -> LongJobs.Done(r.value?.let { "Установлено: файлов ${it.files}, ${it.bytes / 1_048_576} МБ" } ?: "Уже установлено")
                        is Outcome.Failure -> LongJobs.Done("Не установлено: ${r.errors.first().message}")
                    }
                }
            }) { Text(if (install?.running == true) "Устанавливаю Alpine…" else "Установить Alpine") }
            else -> {
                Text("Установлено: $installed", style = MaterialTheme.typography.bodySmall)
                // Терминал — первым и заметным (терминал §4, п. 9).
                Button(onClick = { TerminalActivity.startLinux(context) }) { Text("Терминал") }
                OutlinedTextField(packages, { packages = it }, label = { Text("Пакеты, например: git nodejs npm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !running && packages.isNotBlank(), onClick = { apk(LinuxEnvironment.Apk.ADD, "Поставить") }) { Text("Поставить") }
                    TextButton(enabled = !running && packages.isNotBlank(), onClick = { apk(LinuxEnvironment.Apk.DEL, "Убрать") }) { Text("Убрать") }
                    TextButton(enabled = !running, onClick = { apk(LinuxEnvironment.Apk.UPGRADE, "Обновить всё") }) { Text("Обновить всё") }
                    // Codex CLI и Gemini CLI (D-100): Node.js и npm из Alpine, пакеты npm, в конце — версии.
                    TextButton(enabled = !running, onClick = {
                        run("Установка Codex и Gemini", { LinuxSetup.run(context, LinuxEnvironment.executorsInstall()) }) {
                            if (it.ok) "Codex и Gemini: установлены" else "Установка завершилась с кодом ${it.exitCode} — причина в выводе ниже"
                        }
                    }) { Text("Установить Codex и Gemini") }
                }
                // Target «в приложении» (D-75, шаг 5): задачи — в этом Linux, без SSH; слот — каталог в госте (D-29).
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(enabled = busy == null && !running, onClick = { slotMenu = true }) { Text("Добавить слот ▾") }
                    androidx.compose.material3.DropdownMenu(expanded = slotMenu, onDismissRequest = { slotMenu = false }) {
                        for (x in com.dpsmpx.dpai.core.ids.Executor.entries) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text(x.title) }, onClick = {
                                slotMenu = false
                                busy = "Добавляю слот…"
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        val id = com.dpsmpx.dpai.core.ids.IdGenerator(com.dpsmpx.dpai.core.clock.Clock.SYSTEM, com.dpsmpx.dpai.core.clock.RandomSource.secure())
                                            .next(com.dpsmpx.dpai.core.ids.IdKinds.TARGET)
                                        Controller.get(context.filesDir).addInApp(id, x)
                                    }
                                    note = when (r) {
                                        is Outcome.Success -> "Добавлен «${r.value.label}» (${r.value.configDir}): войдите в ${x.title} на его карточке во вкладке Targets"
                                        is Outcome.Failure -> "Слот не добавлен: ${r.errors.first().message}"
                                    }
                                    busy = null
                                }
                            })
                        }
                    }
                }
                // Как поставить Claude Code (терминал:A-13): apk в интерактивной оболочке с /proc не работает (D-82) —
                // пакеты ставятся полем выше, npm — в терминале.
                Text(
                    if (howto) "Как поставить Claude Code ▾" else "Как поставить Claude Code ▸",
                    Modifier.fillMaxWidth().clickable { howto = !howto }.padding(vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (howto) {
                    Text(
                        "1. Поставьте nodejs npm полем «Пакеты» выше (apk в терминале здесь не сработает).\n" +
                            "2. В терминале — npm i -g @anthropic-ai/claude-code.\n" +
                            "3. «Добавить слот ▾» → Claude: появится target «в приложении» на вкладке Targets; войдите его «Терминал ▾» → «Claude в терминале» → /login.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        // Ход — полосой (AI:A-14); итог операции — строкой, пока его не убрали.
        for (j in listOfNotNull(install, work)) {
            if (j.running) {
                Text("${j.label}…", style = MaterialTheme.typography.labelSmall)
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                j.note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }
        busy?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        val output = work?.output.orEmpty()
        // Вывод — последней строкой с «показать всё» (терминал §4, п. 9): итог и ошибка — в хвосте.
        if (output.isNotEmpty()) {
            Text((if (allOutput) output else output.takeLast(1)).joinToString("\n"), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            if (output.size > 1) TextButton(onClick = { allOutput = !allOutput }) { Text(if (allOutput) "Свернуть вывод" else "Показать весь вывод (${output.size} строк)") }
        }
    }
}

/** Ключи долгих операций экрана Linux: установка среды и всё, что идёт через `apk` и npm гостя (один процесс за раз). */
private const val JOB_INSTALL = "linux-install"
private const val JOB_APK = "linux-apk"

/** Строк вывода `apk` на экране: хвост, где итог и ошибка. */
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
    // «Закрыть» в шторке — переключатель следом, пока экран открыт (A-26 аудита инструкции).
    DisposableEffect(Unit) {
        val listener: (Boolean) -> Unit = { open = it }
        AppDiskNotice.listen(listener)
        onDispose { AppDiskNotice.unlisten(listener) }
    }
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
