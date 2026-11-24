package com.dpsmpx.dpai.android

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.linux.AlpineRepositories
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.OwnVariables
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Свои переменные окружения (D-193; `DPmetaApp:D-171`, шаг 3): файл `env-vars.json` в
 * данных приложения — вне гостя, черновиком и переименованием. Читается на каждый запуск
 * процесса гостя: терминал, ход ИИ, шаг профиля.
 */
internal object OwnVariablesStore {
    private const val FILE = "env-vars.json"
    private const val DRAFT = "env-vars.json.draft"

    fun load(context: Context): List<OwnVariables.Var> {
        val vfs = NioVfs(context.applicationContext.filesDir.toPath())
        val text = runCatching { vfs.read(FILE)?.toString(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        return (OwnVariables.decode(text) as? Outcome.Success)?.value.orEmpty()
    }

    fun save(context: Context, vars: List<OwnVariables.Var>): Outcome<Unit> {
        val ok = when (val c = OwnVariables.check(vars)) {
            is Outcome.Failure -> return c
            is Outcome.Success -> c.value
        }
        val vfs = NioVfs(context.applicationContext.filesDir.toPath())
        return try {
            vfs.write(DRAFT, OwnVariables.encode(ok).toByteArray())
            vfs.rename(DRAFT, FILE)
            Outcome.success(Unit)
        } catch (e: IOException) {
            Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.STORAGE_IO_FAILED, "свои переменные не сохранены: ${e.message}")
        }
    }

    /** Свои переменные под окружением [base]: окружение гостя и профиля сильнее. */
    fun under(context: Context, base: Map<String, String>): Map<String, String> = OwnVariables.merge(load(context), base)

    /** Только свои — для ходов ИИ, поверх окружения гостя. */
    fun map(context: Context): Map<String, String> = load(context).associate { it.name to it.value }
}

/** Раздел «Настройки» экрана «Окружение»: свои переменные и репозитории Alpine. */
@Composable
internal fun EnvironmentSettingsSection(release: String?, repositories: List<String>, running: Boolean, onApply: (String, String) -> Unit, onReset: (Boolean) -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var vars by remember { mutableStateOf<List<OwnVariables.Var>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(version) { vars = withContext(Dispatchers.IO) { OwnVariablesStore.load(context) } }

    fun save(next: List<OwnVariables.Var>, done: () -> Unit = {}) {
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { EnvSnapshotStore.take(context, "Свои переменные", prune = false); OwnVariablesStore.save(context, next) }) {
                is Outcome.Failure -> note = r.errors.first().message
                is Outcome.Success -> { note = null; done(); version++ }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Свои переменные", style = MaterialTheme.typography.titleSmall)
        Text(
            "Для терминала, ходов ИИ и шагов профилей в Linux приложения. Не секреты: ключи и пароли — в «Секретах» с «давать ИИ». " +
                "Окружение профиля сильнее своих переменных.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (vars.isEmpty()) Text("Своих переменных нет.", style = MaterialTheme.typography.bodySmall)
        for (v in vars) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${v.name}=${v.value}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                TextButton(onClick = { save(vars.filter { it.name != v.name }) }) { Text("Удалить") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(64) }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.width(140.dp))
            OutlinedTextField(value, { value = it.replace("\n", "").take(640) }, label = { Text("Значение") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        TextButton(enabled = name.isNotBlank(), onClick = {
            val problem = OwnVariables.problem(name, value)
            if (problem != null) note = problem else save(vars.filter { it.name != name } + OwnVariables.Var(name, value)) { name = ""; value = "" }
        }) { Text("Добавить или заменить") }
        note?.let { DiagnosticText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        RepositoriesBlock(release, repositories, running, onApply)
        HorizontalDivider()
        UpdatesBlock(running)
        HorizontalDivider()
        var resetting by remember { mutableStateOf(false) }
        if (resetting) ResetAlpineDialog(onCancel = { resetting = false }) { whole -> resetting = false; onReset(whole) }
        Text("Сброс Alpine", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        Text("Если окружение сломано так, что не помогают ни починка, ни откат. Закройте вкладки терминала и дождитесь конца ходов ИИ.", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !running, onClick = { resetting = true }) { Text("Сбросить Alpine…", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun RepositoriesBlock(release: String?, repositories: List<String>, running: Boolean, onApply: (String, String) -> Unit) {
    val current = remember(release, repositories) { AlpineRepositories.parse(repositories, release) }
    var edge by remember(current) { mutableStateOf(current?.edge ?: false) }
    var testing by remember(current) { mutableStateOf(current?.testing ?: false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val branch = AlpineRepositories.branch(release)
    val choice = AlpineRepositories.Choice(edge, edge && testing)

    confirm?.let { text ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Сменить репозитории Alpine?") },
            text = {
                Text(
                    "Будет записано в /etc/apk/repositories:\n$text\n" + (AlpineRepositories.warning(choice)?.let { "$it\n" } ?: "") +
                        "Затем — apk upgrade --available: пакеты станут версиями выбранной ветки (в том числе ниже).",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; onApply(text, "Репозитории: ${if (edge) "edge" else branch}") }) { Text("Записать и применить") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }

    Text("Репозитории Alpine", style = MaterialTheme.typography.titleSmall)
    Text(
        when {
            repositories.isEmpty() -> "Файла репозиториев нет."
            current == null -> "Свой список (правили вручную): " + repositories.joinToString()
            current.edge -> "Сейчас: edge" + if (current.testing) " и testing" else ""
            else -> "Сейчас: выпуск $branch"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    Text("Зеркало — ${AlpineRepositories.MIRROR}: своих адресов нет, сеть гостя ходит туда же, что и раньше.", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = !edge, enabled = branch != null, onClick = { edge = false; testing = false }, label = { Text("Выпуск ${branch ?: "?"}") })
        FilterChip(selected = edge, onClick = { edge = true }, label = { Text("edge") })
    }
    if (edge) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(testing, { testing = it })
            Text("и testing (пакеты на пробе)", style = MaterialTheme.typography.bodySmall)
        }
    }
    AlpineRepositories.warning(choice)?.let { DiagnosticText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    val unchanged = current == choice
    TextButton(enabled = !running && !unchanged, onClick = {
        (AlpineRepositories.text(choice, release) as? Outcome.Success)?.value?.let { confirm = it }
    }) { Text(if (unchanged) "Выбрано то, что стоит" else "Записать и применить…") }
}

/** Записать репозитории и привести пакеты к выбранной ветке — одной долгой работой гостя. */
internal fun applyRepositories(context: Context, jobs: LongJobs, text: String, label: String) {
    jobs.start(LongJobs.LINUX_APK, label, excludes = LongJobs.GUEST) {
        EnvSnapshotStore.take(context, label)
        when (val w = LinuxSetup.environment(context).writeRepositories(text)) {
            is Outcome.Failure -> return@start LongJobs.Done("$label: ${w.errors.first().message}", failed = true)
            is Outcome.Success -> Unit
        }
        when (val r = LinuxSetup.apk(context, LinuxEnvironment.Apk.UPGRADE_AVAILABLE, emptyList(), snapshot = false)) {
            is Outcome.Success -> LongJobs.Done(
                if (r.value.ok) "$label: записано, пакеты приведены к ветке" else "$label: записано; apk завершился с кодом ${r.value.exitCode} — причина в выводе ниже",
                (r.value.stdout + r.value.stderr).takeLast(20),
                failed = !r.value.ok,
            )
            is Outcome.Failure -> LongJobs.Done("$label: записано; apk: ${r.errors.first().message}", failed = true)
        }
    }
}

/**
 * Сброс Alpine целиком (D-196; `DPmetaApp:D-171`, шаг 6) одной долгой работой: снимок (после
 * сброса откат вернёт прежний набор пакетов), запись в журнал, [RootfsReset.begin], распаковка
 * тем же путём, что установка, возврат сохранённого.
 */
internal fun resetAlpine(context: Context, jobs: LongJobs, wholeHome: Boolean) {
    val app = context.applicationContext
    val label = "Сброс Alpine"
    jobs.start(LongJobs.LINUX_APK, label, excludes = LongJobs.GUEST) {
        val reset = com.dpsmpx.dpai.orchestrator.linux.RootfsReset(NioVfs(app.filesDir.toPath()), app.filesDir.toPath())
        EnvSnapshotStore.take(app, "Перед сбросом Alpine")
        AppLogs.info(app, "окружение", "сброс Alpine начат; сохраняется: ${reset.keeps(wholeHome).joinToString()}")
        when (val b = reset.begin(wholeHome)) {
            is Outcome.Failure -> return@start LongJobs.Done("$label: ${b.errors.first().message}", failed = true).also { AppLogs.info(app, "окружение", "сброс Alpine остановлен: ${b.errors.first().message}") }
            is Outcome.Success -> Unit
        }
        // Установка сама возвращает сохранённое (доделка оборванного сброса); не вернула — здесь.
        val installed = LinuxSetup.install(app)
        val back: Outcome<List<String>> = if (reset.pending()) reset.restore() else Outcome.success(reset.keeps(wholeHome))
        val note = when {
            installed is Outcome.Failure -> "$label: Alpine не распакована — ${installed.errors.first().message}; дом цел, «Установить» доделает"
            back is Outcome.Failure -> "$label: ${back.errors.first().message}"
            else -> "$label: готово; сохранено ${(back as Outcome.Success).value.joinToString().ifEmpty { "—" }}. Пакеты — заново: «Снимки» → снимок «перед сбросом» вернёт прежний набор"
        }
        AppLogs.info(app, "окружение", note)
        LongJobs.Done(note, failed = installed is Outcome.Failure || back is Outcome.Failure)
    }
}

/** Окно сброса (D-37): красное предупреждение, выбор, что сохранить, и ввод имени. */
@Composable
internal fun ResetAlpineDialog(onCancel: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var whole by remember { mutableStateOf(true) }
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Сбросить Alpine целиком?", color = MaterialTheme.colorScheme.error) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Корневая система будет распакована из APK заново. Пропадут все пакеты Alpine, настройки в /etc, Claude Code, Codex и Gemini (пакеты npm). " +
                        "~/projects и ~/ai сохраняются всегда.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(whole, { whole = it })
                    Text("Сохранить всю домашнюю папку: ~/tools (службы IDE, модели), ~/android-sdk, свои настройки", style = MaterialTheme.typography.bodySmall)
                }
                Text("Перед сбросом — снимок окружения: «Откатить» к нему поставит прежний набор пакетов.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(typed, { typed = it.take(20) }, label = { Text("Введите ${com.dpsmpx.dpai.orchestrator.linux.RootfsReset.CONFIRM}") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = typed == com.dpsmpx.dpai.orchestrator.linux.RootfsReset.CONFIRM, onClick = { onConfirm(whole) }) {
                Text("Сбросить", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}

/** Проверка обновлений (D-197): раз в сутки в фоне по настройке и «Проверить сейчас». */
@Composable
private fun UpdatesBlock(running: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(true) }
    var last by remember { mutableStateOf<com.dpsmpx.dpai.ui.EnvUpdates.Result?>(null) }
    LaunchedEffect(running) {
        if (running) return@LaunchedEffect
        withContext(Dispatchers.IO) { on = EnvUpdatesJob.on(context); last = EnvUpdatesJob.last(context) }
    }
    Text("Обновления", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Проверять раз в сутки", style = MaterialTheme.typography.bodyMedium)
            Text("В фоне при Wi-Fi и зарядке: индекс Alpine и версии компонентов; есть новое — уведомление. Ставите вы.", style = MaterialTheme.typography.bodySmall)
        }
        androidx.compose.material3.Switch(on, onCheckedChange = { v ->
            on = v
            scope.launch { withContext(Dispatchers.IO) { EnvUpdatesJob.setOn(context, v) } }
        })
    }
    Text(
        last?.let { r -> "Проверено ${EnvSnapshotStore.time(r.at)}: " + (com.dpsmpx.dpai.ui.EnvUpdates.notice(r) ?: "всё новое") } ?: "Ещё не проверялось.",
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(enabled = !running, onClick = {
        LongJobs.get(context).start(LongJobs.LINUX_APK, "Проверка обновлений", excludes = LongJobs.GUEST) {
            val r = EnvUpdatesJob.check(context, notify = false)
            LongJobs.Done(r?.let { "Проверка обновлений: " + (com.dpsmpx.dpai.ui.EnvUpdates.notice(it) ?: "всё новое") } ?: "Проверка обновлений: не вышло — нет сети или Linux не установлен", failed = r == null)
        }
    }) { Text("Проверить сейчас") }
}
