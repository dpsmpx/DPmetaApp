package com.dpsmpx.dpai.android

import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.ui.Health
import com.dpsmpx.dpai.ui.HealthAction
import com.dpsmpx.dpai.ui.HealthFacts
import com.dpsmpx.dpai.ui.HealthItem
import com.dpsmpx.dpai.ui.HealthLevel
import com.dpsmpx.dpai.ui.SetupStepItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Переходы, которые нужны кнопкам проверки (DPmetaApp:D-123): вкладка «Targets»,
 * подэкраны «Настроек AI», документ настроек приложения.
 */
internal class HealthNav(
    val targets: () -> Unit,
    val log: () -> Unit,
    val guide: () -> Unit,
    val settings: () -> Unit,
)

/** Долгие операции гостя для кнопок проверки и мастера — те же, что у экрана Linux. */
internal object LinuxJobs {
    private const val MAX_OUTPUT = 40

    fun install(context: Context, onFinished: ((LongJobs.Done) -> Unit)? = null): Boolean = LongJobs.get(context).start(LongJobs.LINUX_INSTALL, "Установка Alpine", excludes = LongJobs.GUEST, onFinished = onFinished) {
        when (val r = LinuxSetup.install(context)) {
            is Outcome.Success -> LongJobs.Done(r.value?.let { "Установлено: файлов ${it.files}, ${it.bytes / 1_048_576} МБ" } ?: "Уже установлено")
            is Outcome.Failure -> LongJobs.Done("Не установлено: ${r.errors.first().message}", failed = true)
        }
    }

    /** Claude Code (D-75, шаг 5): Git, Node.js, npm и bash — apk без /proc, затем npm; в конце — версия. */
    fun claude(context: Context): Boolean = LongJobs.get(context).start(LongJobs.LINUX_APK, "Установка Claude Code", excludes = LongJobs.GUEST) {
        when (val r = LinuxSetup.runSteps(context, LinuxEnvironment.claudeInstall())) {
            is Outcome.Success -> LongJobs.Done(
                if (r.value.ok) "Claude Code: ${r.value.stdout.lastOrNull { l -> l.isNotBlank() } ?: "установлен"}"
                else "Установка завершилась с кодом ${r.value.exitCode} — причина в выводе ниже",
                (r.value.stdout + r.value.stderr).takeLast(MAX_OUTPUT), failed = !r.value.ok,
            )
            is Outcome.Failure -> LongJobs.Done("Установка Claude Code: ${r.errors.first().message}", failed = true)
        }
    }
}

/** Кнопка пункта проверки или шага мастера: то же действие в обоих местах. */
internal fun runHealthAction(context: Context, action: HealthAction, nav: HealthNav, askIdentity: () -> Unit) {
    val app = context.applicationContext as? DpaiApplication
    when (action) {
        HealthAction.INSTALL_LINUX -> LinuxJobs.install(context)
        HealthAction.REPAIR_PACKAGES -> HealthAuto.repair(context, force = true)
        HealthAction.INSTALL_CLAUDE -> LinuxJobs.claude(context)
        HealthAction.OPEN_TARGETS -> nav.targets()
        HealthAction.OPEN_LOG -> nav.log()
        HealthAction.GIT_IDENTITY -> askIdentity()
        HealthAction.APP_SETTINGS -> context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        // Секреты и хранилище — в документе настроек приложения (DPmetaApp:D-112); в отдельной сборке — в «Настройках AI».
        HealthAction.OPEN_SECRETS, HealthAction.OPEN_STORAGE ->
            app?.sectionIntent(com.dpsmpx.dpai.ui.AppSection.SETTINGS)?.let(context::startActivity) ?: nav.settings()
    }
}

/**
 * Экран «Проверка» (DPmetaApp:D-123, D-158): что не так — словами, решение — кнопкой;
 * шаги первой настройки; что приложение исправило само. Проверка — при открытии, после
 * каждой долгой операции гостя и кнопкой «Проверить снова».
 */
@Composable
internal fun HealthPane(model: DemoModel, nav: HealthNav, onBack: () -> Unit) {
    val context = LocalContext.current
    val jobs = remember { LongJobs.get(context) }
    val state by jobs.state.collectAsState()
    val guestBusy = LongJobs.GUEST.any { state[it]?.running == true }
    var facts by remember { mutableStateOf<HealthFacts?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var identity by rememberSaveable { mutableStateOf(false) }
    var wizard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(round, guestBusy) {
        if (guestBusy) return@LaunchedEffect
        facts = withContext(Dispatchers.IO) {
            HealthAuto.tick(context)
            HealthProbe.collect(context, model)
        }
    }
    val askIdentity = { identity = true }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text("← Настройки") }
        Text("Проверка приложения", style = MaterialTheme.typography.titleMedium)
        Text(
            "Что не так и что делать. Безопасное приложение исправляет само: повреждённые пакеты Linux, помощник входа Git, " +
                "DNS Linux при смене сети, догон чистых клонов пульта. Права и ваши данные — только кнопкой.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !guestBusy, onClick = { round++ }) { Text("Проверить снова") }
            OutlinedButton(onClick = { wizard = true }) { Text("Мастер настройки") }
        }
        // Идущая или законченная операция гостя — итог словами, как на экране Linux.
        for (key in listOf(LongJobs.LINUX_INSTALL, LongJobs.LINUX_APK)) {
            state[key]?.let { job ->
                DiagnosticText(if (job.running) "${job.label}…" else "${job.label}: ${job.note.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            }
        }
        val f = facts
        if (f == null) {
            Text("Проверяю…", style = MaterialTheme.typography.bodySmall)
        } else {
            val items = Health.items(f)
            if (items.isEmpty()) Text("Проблем не найдено.", style = MaterialTheme.typography.bodyMedium)
            for (level in HealthLevel.entries) {
                val of = items.filter { it.level == level }
                if (of.isEmpty()) continue
                Text(level.title, style = MaterialTheme.typography.titleSmall)
                for (item in of) HealthCard(item, enabled = !guestBusy) { runHealthAction(context, it, nav, askIdentity) }
            }
            if (!Health.setupDone(f)) {
                Text("Первая настройка", style = MaterialTheme.typography.titleSmall)
                for (step in Health.setup(f)) SetupStepRow(step, enabled = !guestBusy) { runHealthAction(context, it, nav, askIdentity) }
            }
        }
        val fixed = remember(facts) { HealthAuto.history(context) }
        if (fixed.isNotEmpty()) {
            Text("Исправлено само", style = MaterialTheme.typography.titleSmall)
            val fmt = remember { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.forLanguageTag("ru")) }
            for ((at, text) in fixed) Text("${fmt.format(java.util.Date(at))} — $text", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (identity) {
        GitIdentityDialog(onDone = { done ->
            identity = false
            if (done) round++
        })
    }
    if (wizard) {
        facts?.let { f -> SetupWizardDialog(f, enabled = !guestBusy, onAction = { runHealthAction(context, it, nav, askIdentity) }, onClose = { wizard = false; round++ }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HealthCard(item: HealthItem, enabled: Boolean, onAction: (HealthAction) -> Unit) {
    val colors = if (item.level == HealthLevel.ERROR) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    else CardDefaults.cardColors()
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall)
            DiagnosticText(item.detail, style = MaterialTheme.typography.bodySmall)
            if (item.actions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in item.actions) Button(enabled = enabled, onClick = { onAction(a) }) { Text(a.label) }
                }
            }
        }
    }
}

@Composable
private fun SetupStepRow(step: SetupStepItem, enabled: Boolean, onAction: (HealthAction) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (step.done) {
                true -> "✓"
                false -> "○"
                null -> "—"
            },
            Modifier.padding(end = 8.dp),
            color = if (step.done == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Column(Modifier.weight(1f)) {
            Text(step.title + if (step.optional && step.done != true) " · по желанию" else "", style = MaterialTheme.typography.bodyMedium)
            DiagnosticText(step.text, style = MaterialTheme.typography.bodySmall)
        }
        step.action?.let { a -> TextButton(enabled = enabled, onClick = { onAction(a) }) { Text(a.label) } }
    }
}

/**
 * Мастер первой настройки (DPmetaApp:D-123): те же шаги, что в «Проверке», по порядку;
 * открывается сам при первом открытии «AI», пока настройка не закончена; «Позже» —
 * больше сам не открывается, вернуть — кнопкой на экране «Проверка».
 */
@Composable
internal fun SetupWizardDialog(facts: HealthFacts, enabled: Boolean, onAction: (HealthAction) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Настройка приложения") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Шаги по порядку; сделанное отмечено ✓. Вернуться к мастеру можно из «Настройки AI» → «Проверка».", style = MaterialTheme.typography.bodySmall)
                for (step in Health.setup(facts)) SetupStepRow(step, enabled, onAction)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(if (Health.setupDone(facts)) "Готово" else "Позже") } },
    )
}

/** Автор коммитов Git в Linux (D-158): имя и почту вписывает человек; приложение их не подставляет. */
@Composable
private fun GitIdentityDialog(onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDone(false) },
        title = { Text("Автор коммитов Git") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Запишется в ~/.gitconfig Linux приложения: git commit в терминале и у ИИ будет подписан этим именем и почтой.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it; error = null }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it; error = null }, label = { Text("Почта") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { DiagnosticText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                val problem = Health.identityNameProblem(name) ?: Health.identityEmailProblem(email)
                if (problem != null) {
                    error = problem
                    return@TextButton
                }
                saving = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { LinuxSetup.setGitIdentity(context, name, email) }
                    saving = false
                    when (r) {
                        is Outcome.Success -> onDone(true)
                        is Outcome.Failure -> error = r.errors.first().message
                    }
                }
            }) { Text(if (saving) "Записываю…" else "Сохранить") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = { onDone(false) }) { Text("Отмена") } },
    )
}

/** Мастер первой настройки: открывался ли сам и закрыт «Позже» (тогда сам больше не открывается). */
internal object SetupWizardPrefs {
    private const val PREFS = "dpai.health"
    private const val LATER = "wizard-later"

    fun later(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(LATER, false)

    fun setLater(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(LATER, true) }
}
