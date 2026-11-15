package com.dpsmpx.dpai.android

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.sync.CloudCrypto
import com.dpsmpx.dpai.ui.AppBackup
import com.dpsmpx.dpai.ui.SecretRevoke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Резервная копия приложения» (`DPmetaApp:D-151`; F-47): сохранить разделы — с
 * секретами только под паролем; восстановить выбранные разделы; копия без секретов
 * сама раз в сутки. Пароль не сохраняется: забыт — копию не прочитать никому.
 */
@Composable
fun AppBackupCard() {
    val context = LocalContext.current
    val app = context.applicationContext
    val activity = androidx.activity.compose.LocalActivity.current
    val scope = rememberCoroutineScope()
    val version = BuildConfig.DPAI_VERSION
    var refresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saveOpen by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<SavePlan?>(null) }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var archive by remember { mutableStateOf<AppBackup.Archive?>(null) }
    var restarting by remember { mutableStateOf(false) }
    val lastAuto = rememberRead(refresh, initial = null) { AppBackups.lastAuto(app) }
    val applied = rememberRead(Unit, initial = null) { AppBackups.lastApplied(app, clear = true) }
    val pending = rememberRead(refresh, initial = false) { AppBackups.pending(app) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val p = plan ?: return@rememberLauncherForActivityResult
        plan = null
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "Сохраняю копию…"
        fun go() = scope.launch {
            val r = withContext(Dispatchers.IO) {
                when (val a = AppBackups.export(app, p.sections, p.password != null, version)) {
                    is Outcome.Failure -> a
                    is Outcome.Success -> AppBackups.save(app, uri, a.value, p.password)
                }
            }
            busy = false
            status = when (r) {
                is Outcome.Success -> "Копия сохранена (${r.value / 1024 + 1} КиБ)" + if (p.password != null) ", секреты — под паролем." else "."
                is Outcome.Failure -> "Копия не сохранена: ${r.errors.first().message}"
            }
        }
        // Токен GitHub пульта открывается только после входа (T-01): сначала вход, потом чтение.
        if (p.password != null && activity != null) AppSecrets.unlockAll(activity) { u -> if (u is Outcome.Failure) { busy = false; status = "Копия не сохранена: ${u.errors.first().message}" } else go() } else go()
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "Читаю копию…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { AppBackups.read(app, uri) }
            busy = false
            when (r) {
                is Outcome.Failure -> status = "Копия не прочитана: ${r.errors.first().message}"
                is Outcome.Success -> if (AppBackup.sealed(r.value)) {
                    status = null
                    restoreBytes = r.value
                } else {
                    when (val a = AppBackups.open(r.value, null)) {
                        is Outcome.Failure -> status = "Копия не прочитана: ${a.errors.first().message}"
                        is Outcome.Success -> { status = null; archive = a.value }
                    }
                }
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Резервная копия приложения", style = MaterialTheme.typography.titleSmall)
            Text(
                "Разделы: " + AppBackups.sections().joinToString { it.title } + " и секреты — только под паролем. Журналы, история, переписка и файлы проектов не входят.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                lastAuto?.let { (name, at) -> "Сама без секретов раз в сутки — «Загрузки/dpmeta-backup»: $name, ${dateText(at)}." }
                    ?: "Сама без секретов раз в сутки — в «Загрузки/dpmeta-backup»; первая — в течение суток.",
                style = MaterialTheme.typography.bodySmall,
            )
            applied?.let { Text("После перезапуска: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (pending) Text("Восстановление ждёт перезапуска приложения.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { saveOpen = true }) { Text("Сохранить копию…") }
                OutlinedButton(enabled = !busy, onClick = { pick.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) { Text("Восстановить…") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (saveOpen) SaveDialog(onCancel = { saveOpen = false }) { p ->
        saveOpen = false
        plan = p
        create.launch(AppBackups.suggestedName(p.password != null))
    }
    restoreBytes?.let { bytes ->
        PasswordDialog(onCancel = { restoreBytes = null }) { password, done ->
            scope.launch {
                val r = withContext(Dispatchers.IO) { AppBackups.open(bytes, password) }
                when (r) {
                    is Outcome.Failure -> done(r.errors.first().message)
                    is Outcome.Success -> { restoreBytes = null; archive = r.value; done(null) }
                }
            }
        }
    }
    archive?.let { a ->
        RestoreDialog(a, onCancel = { archive = null }) { ids ->
            archive = null
            busy = true
            status = "Восстанавливаю…"
            fun go() = scope.launch {
                val r = withContext(Dispatchers.IO) { AppBackups.restore(app, a, ids, version) }
                busy = false
                refresh++
                when (r) {
                    is Outcome.Success -> { status = r.value; restarting = AppBackups.pending(app) }
                    is Outcome.Failure -> status = "Не восстановлено: ${r.errors.first().message}"
                }
            }
            // Токен GitHub пульта пишется под ключом, которому нужен вход (T-01).
            if (AppBackup.SECRETS in ids && activity != null) AppSecrets.unlockAll(activity, includeAbsent = true) { u -> if (u is Outcome.Failure) { busy = false; status = "Не восстановлено: ${u.errors.first().message}" } else go() } else go()
        }
    }
    if (restarting) AlertDialog(
        onDismissRequest = {},
        title = { Text("Нужен перезапуск") },
        text = { Text("Выбранные разделы применятся при следующем запуске приложения — до чтения настроек. Откройте приложение снова после перезапуска.") },
        confirmButton = { TextButton(onClick = { android.os.Process.killProcess(android.os.Process.myPid()) }) { Text("Перезапустить") } },
        dismissButton = { TextButton(onClick = { restarting = false }) { Text("Позже") } },
    )
}

/** Дата и время копии для человека: «15.11.2026 14:05». */
private fun dateText(ms: Long): String = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.US).format(java.util.Date(ms))

/** Что сохранить: разделы и пароль (есть — с секретами). */
private class SavePlan(val sections: Set<String>, val password: Secret?)

@Composable
private fun SaveDialog(onCancel: () -> Unit, onSave: (SavePlan) -> Unit) {
    val all = remember { AppBackups.sections() }
    var chosen by remember { mutableStateOf(all.map { it.id }.toSet()) }
    var secrets by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val problem = when {
        chosen.isEmpty() && !secrets -> "выберите хотя бы один раздел"
        !secrets -> null
        else -> CloudCrypto.passphraseProblem(Secret(password)) ?: if (password != again) "пароли не совпадают" else null
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Сохранить копию") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in all) CheckRow(s.title, s.what, s.id in chosen) { on -> chosen = if (on) chosen + s.id else chosen - s.id }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(AppBackups.SECRETS_TITLE, style = MaterialTheme.typography.bodyLarge)
                        Text("токены, ключи API, свои секреты — только под паролем; ключ SSH и ключи подписи из Keystore не выходят", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(secrets, { secrets = it })
                }
                if (secrets) {
                    OutlinedTextField(password, { password = it }, label = { Text("Пароль копии (от ${CloudCrypto.MIN_PASSPHRASE} знаков)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(again, { again = it }, label = { Text("Пароль ещё раз") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    Text("Пароль приложение не хранит. Забыт — копию не прочитать никому.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = problem == null, onClick = { onSave(SavePlan(chosen, if (secrets) Secret(password) else null)) }) { Text("Выбрать место…") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}

@Composable
private fun PasswordDialog(onCancel: () -> Unit, onOpen: (Secret, (String?) -> Unit) -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Копия под паролем") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(password, { password = it; error = null }, label = { Text("Пароль копии") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                if (busy) Text("Проверяю пароль — это несколько секунд…", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && password.isNotEmpty(), onClick = {
                busy = true
                onOpen(Secret(password)) { e -> busy = false; error = e }
            }) { Text("Открыть") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text("Отмена") } },
    )
}

@Composable
private fun RestoreDialog(a: AppBackup.Archive, onCancel: () -> Unit, onRestore: (Set<String>) -> Unit) {
    val known = remember { AppBackups.sections().associateBy { it.id } }
    val present = a.sections.filter { !it.empty }
    // Неизвестный этой версии раздел не выбирается: его некуда разложить.
    var chosen by remember(a) { mutableStateOf(present.map { it.id }.filter { it == AppBackup.SECRETS || it in known }.toSet()) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Что восстановить") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Копия от ${dateText(a.createdAt)}" + (a.appVersion?.let { ", версия $it" } ?: "") + ".", style = MaterialTheme.typography.bodySmall)
                for (s in present) {
                    val title = if (s.id == AppBackup.SECRETS) "${AppBackups.SECRETS_TITLE} (${s.secrets.size})" else known[s.id]?.title ?: "${s.id} — неизвестен этой версии"
                    val what = if (s.id == AppBackup.SECRETS) "записываются сразу, поверх нынешних с тем же именем" else known[s.id]?.what.orEmpty()
                    CheckRow(title, what, s.id in chosen, enabled = s.id == AppBackup.SECRETS || s.id in known) { on -> chosen = if (on) chosen + s.id else chosen - s.id }
                }
                Text("Выбранные разделы заменят нынешние; нынешние перед этим сохранятся копией без секретов в «Загрузки». Разделы применятся после перезапуска.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (a.section(AppBackup.SECRETS) == null) Text("Секретов в этой копии нет — токены и ключи введите заново. Ключ SSH на новом телефоне — новый: добавьте его открытую строку на ПК.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { onRestore(chosen) }) { Text("Восстановить") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}

@Composable
private fun CheckRow(title: String, what: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked && enabled, onChange, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (what.isNotEmpty()) Text(what, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * «Аварийный отзыв» (F-46; `DPmetaApp:D-151`): все секреты устройства, где каждый
 * отозвать, галочки (по умолчанию — все) и «Стереть отмеченные…» с вводом слова.
 */
@Composable
fun EmergencyRevokeCard() {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val rows = rememberRead(refresh, initial = null) { AppSecrets.rows(app) }
    var unchecked by remember { mutableStateOf(emptySet<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Аварийный отзыв", style = MaterialTheme.typography.titleSmall)
            Text("Телефон потерян или секрет утёк: сначала отзовите секреты на страницах сервисов (с другого устройства), затем сотрите их здесь.",
                style = MaterialTheme.typography.bodySmall)
            when {
                rows == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                rows.isEmpty() -> Text("Секретов на устройстве нет.", style = MaterialTheme.typography.bodySmall)
                else -> for (r in rows) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(r.id !in unchecked, { on -> unchecked = if (on) unchecked - r.id else unchecked + r.id })
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.bodyLarge)
                            Text(r.hint, style = MaterialTheme.typography.bodySmall)
                        }
                        r.revoke?.let { url ->
                            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } }) { Text("Отозвать ↗") }
                        }
                    }
                }
            }
            val chosen = rows.orEmpty().filter { it.id !in unchecked }
            OutlinedButton(enabled = !busy && chosen.isNotEmpty(), onClick = { confirm = true }) {
                Text("Стереть отмеченные (${chosen.size})…", color = MaterialTheme.colorScheme.error)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (confirm) EraseDialog(chosen, onCancel = { confirm = false }) {
                confirm = false
                busy = true
                scope.launch {
                    val failed = withContext(Dispatchers.IO) { AppSecrets.erase(app, chosen.map { it.id }.toSet()) }
                    busy = false
                    status = if (failed.isEmpty()) "Стёрто: ${chosen.size}." else "Стёрто ${chosen.size - failed.size} из ${chosen.size}; не стёрты: " +
                        failed.entries.joinToString { (id, why) -> "${chosen.firstOrNull { it.id == id }?.name ?: id} ($why)" }
                    unchecked = emptySet()
                    refresh++
                }
            }
        }
    }
}

@Composable
private fun EraseDialog(rows: List<AppSecrets.Row>, onCancel: () -> Unit, onErase: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(rows) { typed = "" }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        title = { Text("Стереть секреты?", color = MaterialTheme.colorScheme.onErrorContainer) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Будет стёрто с этого устройства без возврата:", color = MaterialTheme.colorScheme.onErrorContainer)
                for (r in rows) Text("• ${r.name}", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                Text("Токены и ключи у сервисов этим не отзываются — отзовите их там. Ключ SSH: новый создастся при следующем подключении, его строку надо снова добавить на ПК.",
                    color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(typed, { typed = it }, label = { Text("Введите ${SecretRevoke.ERASE_WORD}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = SecretRevoke.eraseConfirmed(typed), onClick = onErase) { Text("Стереть", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}
