package com.dpsmpx.dpai.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
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
import com.dpsmpx.dpai.ui.AndroidSdk
import com.dpsmpx.dpai.ui.AndroidNativeJdk
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
fun LinuxScreen(onBack: () -> Unit, controller: Controller? = null, model: DemoModel? = null) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val available = remember { LinuxSetup.available(context) }
    // Метка установки — файл на диске: читается не в главном потоке (AI:A-16); до ответа — «Проверяю…».
    var installed by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    // Долгое — вне экрана (AI:A-14): ход, вывод и итог переживают уход с экрана; повтор не запускается.
    val jobs = remember { LongJobs.get(context) }
    val state by jobs.state.collectAsState()
    val install = state[LongJobs.LINUX_INSTALL]
    val work = state[LongJobs.LINUX_APK]
    // Процессы гостя — по одному (база apk под замком): и llama.cpp во «Моделях» тоже.
    val running = LongJobs.GUEST.any { state[it]?.running == true }
    LaunchedEffect(install?.running) {
        if (install?.running != true) {
            installed = withContext(Dispatchers.IO) { LinuxSetup.environment(context).installed() }
            checked = true
        }
    }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var allOutput by remember { mutableStateOf(false) }
    var packages by rememberSaveable { mutableStateOf("") }
    var howto by rememberSaveable { mutableStateOf(false) }
    var slotMenu by remember { mutableStateOf(false) }
    var sdkOpen by remember { mutableStateOf(false) }
    var envOpen by rememberSaveable { mutableStateOf(false) }
    if (envOpen) {
        androidx.activity.compose.BackHandler { envOpen = false }
        EnvironmentScreen(onBack = { envOpen = false }, controller = controller, model = model)
        return
    }

    fun run(label: String, job: () -> Outcome<com.dpsmpx.dpai.execution.process.Completed>, done: (com.dpsmpx.dpai.execution.process.Completed) -> String) {
        allOutput = false
        jobs.start(LongJobs.LINUX_APK, label, excludes = LongJobs.GUEST) {
            when (val r = job()) {
                is Outcome.Success -> LongJobs.Done(done(r.value), (r.value.stdout + r.value.stderr).takeLast(MAX_OUTPUT), failed = !r.value.ok)
                is Outcome.Failure -> LongJobs.Done("$label: ${r.errors.first().message}", failed = true)
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
        Text("Alpine в данных приложения.", style = MaterialTheme.typography.bodySmall)
        when {
            !available -> Text("Недоступно на этом телефоне: нужен процессор arm64.", style = MaterialTheme.typography.bodySmall)
            !checked -> Text("Проверяю…", style = MaterialTheme.typography.bodySmall)
            installed == null -> Button(enabled = !running, onClick = { LinuxJobs.install(context) }) {
                Text(if (install?.running == true) "Устанавливаю Alpine…" else "Установить Alpine")
            }
            else -> {
                Text("Установлено: $installed", style = MaterialTheme.typography.bodySmall)
                // Терминал — первым и заметным (терминал §4, п. 9).
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { TerminalActivity.startLinux(context) }) { Text("Терминал") }
                    OutlinedButton(onClick = { envOpen = true }) { Text("Окружение: пакеты…") }
                }
                OutlinedTextField(packages, { packages = it }, label = { Text("Пакеты, например: git nodejs npm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !running && packages.isNotBlank(), onClick = { apk(LinuxEnvironment.Apk.ADD, "Поставить") }) { Text("Поставить") }
                    TextButton(enabled = !running && packages.isNotBlank(), onClick = { apk(LinuxEnvironment.Apk.DEL, "Убрать") }) { Text("Убрать") }
                    TextButton(enabled = !running, onClick = { apk(LinuxEnvironment.Apk.UPGRADE, "Обновить всё") }) { Text("Обновить всё") }
                    // Claude Code (D-75, шаг 5): Git, Node.js и npm — apk без /proc, затем npm; в конце — версия.
                    TextButton(enabled = !running, onClick = { allOutput = false; LinuxJobs.claude(context) }) { Text("Поставить Claude Code") }
                    // Codex CLI и Gemini CLI (D-100): Node.js и npm из Alpine, пакеты npm, в конце — версии.
                    TextButton(enabled = !running, onClick = {
                        run("Установка Codex и Gemini", { LinuxSetup.runSteps(context, LinuxEnvironment.executorsInstall()) }) {
                            if (it.ok) "Codex и Gemini: установлены" else "Установка завершилась с кодом ${it.exitCode} — причина в выводе ниже"
                        }
                    }) { Text("Установить Codex и Gemini") }
                    // tmux (D-111): вкладка слота «в приложении» держит сессию в нём, как на других targets.
                    TextButton(enabled = !running, onClick = {
                        run("Установка tmux", { LinuxSetup.apk(context, LinuxEnvironment.Apk.ADD, listOf("tmux")) }) {
                            if (it.ok) "tmux: установлен" else "apk завершился с кодом ${it.exitCode} — причина в выводе ниже"
                        }
                    }) { Text("Поставить tmux") }
                    // Android SDK по желанию (D-185): официальное по суммам, сборки сообщества — по сумме автора.
                    TextButton(enabled = !running, onClick = { sdkOpen = true }) { Text("Android SDK…") }
                }
                if (sdkOpen) AndroidSdkDialog(onCancel = { sdkOpen = false }) { licenses, ndk, aapt2, ndkCommunity ->
                    sdkOpen = false
                    run("Установка Android SDK", {
                        (StorageSpace.check(context, com.dpsmpx.dpai.storage.space.SpaceGuard.Kind.DOWNLOAD, AndroidSdk.spaceBytes(ndk), "Android SDK") as? Outcome.Failure)?.let { return@run it }
                        when (val first = LinuxEnvironment.apk(LinuxEnvironment.Apk.ADD, AndroidSdk.packages)) {
                            is Outcome.Failure -> first
                            is Outcome.Success -> LinuxSetup.runSteps(context, listOf(first.value,
                                com.dpsmpx.dpai.execution.process.ProcessSpec(AndroidNativeJdk.install()),
                                com.dpsmpx.dpai.execution.process.ProcessSpec(AndroidSdk.install(licenses, ndk, aapt2, ndkCommunity))))
                        }
                    }) {
                        if (it.ok && it.stdout.any { l -> "DPAI_ANDROID_SDK_OK" in l }) "Android SDK: ${AndroidSdk.HOME}; в профиль сборки — окружение ANDROID_HOME=${AndroidSdk.HOME}"
                        else "Установка завершилась с кодом ${it.exitCode} — причина в выводе ниже"
                    }
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
                        "1. «Поставить Claude Code» выше: Git, Node.js и npm из Alpine, затем Claude Code из npm (нужно около 1 ГБ свободного места).\n" +
                            "2. «Добавить слот ▾» → Claude: появится target «в приложении» на вкладке Targets.\n" +
                            "3. Войдите: его «Терминал ▾» → «Claude в терминале» → /login — по разу на слот.\n" +
                            "Пакеты Alpine — полем «Пакеты»: apk в терминале здесь не сработает.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (installed != null && SharedVerify.available(context)) SharedVerifyCard()
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
            SwitchRow(open, onChange = { v ->
                com.dpsmpx.dpai.ui.AppDisk.setOpen(context, v)
                open = com.dpsmpx.dpai.ui.AppDisk.isOpen(context)
            }) {
                Text("Диск приложения", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            Text(
                if (open) {
                    "Открыт: в менеджере файлов — «DP_IDE» в системном выборе папки (в Total Commander — «Добавить место» → системный выбор). " +
                        "Только чтение; файлы можно класть в Linux /root. Токена dpai и входов Claude на диске нет."
                } else {
                    "Закрыт: файлы приложения не видны другим программам. Откройте, чтобы смотреть журналы, копии журнала и файлы Linux менеджером файлов."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Android SDK по желанию (D-185): лицензия — галочкой человека; NDK — по желанию;
 * aapt2 и NDK для arm64 — сборкой сообщества по адресу и SHA-256, которые вводит автор.
 */
@Composable
private fun AndroidSdkDialog(onCancel: () -> Unit, onInstall: (Boolean, Boolean, AndroidSdk.Community?, AndroidSdk.Community?) -> Unit) {
    var license by remember { mutableStateOf(false) }
    var ndk by remember { mutableStateOf(false) }
    var aapt2Url by remember { mutableStateOf("") }
    var aapt2Sha by remember { mutableStateOf("") }
    var ndkUrl by remember { mutableStateOf("") }
    var ndkSha by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Android SDK") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "С официальных источников Google: cmdline-tools ${AndroidSdk.CMDLINE_VERSION} (по закреплённой сумме), " +
                        "android-36 и build-tools 36.1.0 — sdkmanager. Около ${AndroidSdk.spaceBytes(false) shr 20} МБ, с NDK — ${AndroidSdk.spaceBytes(true) shr 30} ГБ. " +
                        "Под arm64 Google не выпускает aapt2 для Gradle и NDK: по желанию — сборка сообщества по адресу и SHA-256, которые вы вводите.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(license, { license = it })
                    Text("Принимаю лицензию Android SDK (${AndroidSdk.LICENSE_URL})", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(ndk, { ndk = it })
                    Text("И NDK", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(aapt2Url, { aapt2Url = it.take(500) }, label = { Text("aapt2 arm64 (сообщество): адрес https") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(aapt2Sha, { aapt2Sha = it.take(64) }, label = { Text("aapt2: SHA-256") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (ndk) {
                    OutlinedTextField(ndkUrl, { ndkUrl = it.take(500) }, label = { Text("NDK arm64 (сообщество): адрес https") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ndkSha, { ndkSha = it.take(64) }, label = { Text("NDK: SHA-256") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = license, onClick = {
                val a = AndroidSdk.community(aapt2Url, aapt2Sha, "aapt2")
                val n = if (ndk) AndroidSdk.community(ndkUrl, ndkSha, "NDK") else Outcome.success(null)
                when {
                    a is Outcome.Failure -> problem = a.errors.first().message
                    n is Outcome.Failure -> problem = n.errors.first().message
                    else -> onInstall(license, ndk, (a as Outcome.Success).value, (n as Outcome.Success).value)
                }
            }) { Text("Установить") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}
