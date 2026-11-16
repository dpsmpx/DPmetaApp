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
internal fun EnvironmentSettingsSection(release: String?, repositories: List<String>, running: Boolean, onApply: (String, String) -> Unit) {
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
            when (val r = withContext(Dispatchers.IO) { OwnVariablesStore.save(context, next) }) {
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
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        RepositoriesBlock(release, repositories, running, onApply)
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
    AlpineRepositories.warning(choice)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    val unchanged = current == choice
    TextButton(enabled = !running && !unchanged, onClick = {
        (AlpineRepositories.text(choice, release) as? Outcome.Success)?.value?.let { confirm = it }
    }) { Text(if (unchanged) "Выбрано то, что стоит" else "Записать и применить…") }
}

/** Записать репозитории и привести пакеты к выбранной ветке — одной долгой работой гостя. */
internal fun applyRepositories(context: Context, jobs: LongJobs, text: String, label: String) {
    jobs.start(LongJobs.LINUX_APK, label, excludes = LongJobs.GUEST) {
        when (val w = LinuxSetup.environment(context).writeRepositories(text)) {
            is Outcome.Failure -> return@start LongJobs.Done("$label: ${w.errors.first().message}", failed = true)
            is Outcome.Success -> Unit
        }
        when (val r = LinuxSetup.apk(context, LinuxEnvironment.Apk.UPGRADE_AVAILABLE, emptyList())) {
            is Outcome.Success -> LongJobs.Done(
                if (r.value.ok) "$label: записано, пакеты приведены к ветке" else "$label: записано; apk завершился с кодом ${r.value.exitCode} — причина в выводе ниже",
                (r.value.stdout + r.value.stderr).takeLast(20),
                failed = !r.value.ok,
            )
            is Outcome.Failure -> LongJobs.Done("$label: записано; apk: ${r.errors.first().message}", failed = true)
        }
    }
}
