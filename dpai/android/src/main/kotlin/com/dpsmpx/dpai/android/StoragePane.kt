package com.dpsmpx.dpai.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.space.SpaceGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Хранилище и секреты (`DPmetaApp:D-111`, `DPmetaApp:D-113`, `DPmetaApp:D-114`):
 * предел объёма, место, карта `~/STORAGE.md`; секреты — списком без значений,
 * «давать ИИ» — у токена GitHub, ключей API-профилей и токена Hugging Face.
 * До документа настроек (D-112) — карточкой в настройках «AI».
 */
@Composable
fun StorageSecretsCard() {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var space by remember { mutableStateOf<SpaceGuard.Facts?>(null) }
    var rows by remember { mutableStateOf<List<SecretGrants.Row>>(emptyList()) }
    var limit by remember { mutableStateOf(StorageSpace.limitGb(app).toString()) }
    var note by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SecretGrants.Row?>(null) }
    if (adding) OwnSecretDialog(onClose = { adding = false }) { version++ }
    removing?.let { r ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Удалить «${r.name}»?") },
            text = { Text("Значение сотрётся с телефона, ИИ и терминал перестанут получать ${r.variable.orEmpty()}. Вернуть — только ввести заново.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    removing = null
                    scope.launch {
                        withContext(Dispatchers.IO) { SecretGrants.removeCustom(app, r.id) }
                        version++
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { removing = null }) { Text("Отмена") } },
        )
    }
    LaunchedEffect(version) {
        withContext(Dispatchers.IO) {
            space = runCatching { StorageSpace.facts(app) }.getOrNull()
            rows = runCatching { SecretGrants.rows(app) }.getOrDefault(emptyList())
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Хранилище", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = limit,
                    onValueChange = { t ->
                        val v = t.filter(Char::isDigit).take(4)
                        limit = v
                        v.toIntOrNull()?.let { StorageSpace.setLimitGb(app, it); version++ }
                    },
                    label = { Text("Предел, ГБ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(140.dp),
                )
                Text("0 — всё свободное место телефона", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            space?.let { f ->
                Text(
                    "Свободно: ${SpaceGuard.size(f.free)}; занято приложением: ${f.used?.let(SpaceGuard::size) ?: "неизвестно"}. " +
                        "Загрузка, установка, клон и сборка не начнутся, если после них останется меньше 1 ГБ.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text("Карта для ИИ и терминала — ~/STORAGE.md: разделы ~/projects, ~/tools, ~/ai.", style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Секреты", style = MaterialTheme.typography.titleSmall)
            Text(
                "Значения не показываются. «Давать ИИ» — переменная окружения в Linux приложения (ходы ИИ и терминал); на ПК-target не уходит.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (rows.isEmpty()) Text("Секретов пока нет.", style = MaterialTheme.typography.bodyMedium)
            for (r in rows) SecretRow(r, onGrant = { on ->
                scope.launch {
                    withContext(Dispatchers.IO) { SecretGrants.grant(app, r.id, on) }
                    version++
                }
            }, onUnlock = {
                val activity = context.activity() ?: return@SecretRow
                SecretGrants.unlock(activity, r.id) { res ->
                    note = (res as? Outcome.Failure)?.errors?.firstOrNull()?.message
                    version++
                }
            }, onRemove = if (SecretGrants.isCustom(r.id)) ({ removing = r }) else null)
            OutlinedButton(onClick = { adding = true }) { Text("Добавить свой секрет") }
            note?.let { DiagnosticText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/**
 * Свой секрет автора (DPmetaApp:D-117): имя — оно же переменная окружения, значение
 * скрыто при вводе и после сохранения не показывается нигде. Окно — без снимков экрана.
 */
@Composable
private fun OwnSecretDialog(onClose: () -> Unit, onSaved: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Свой секрет") },
        text = {
            // У диалога своё окно: запрет снимков ставится ему, а не активности.
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.DisposableEffect(view) {
                val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                window?.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
                onDispose { }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Имя — переменная окружения в Linux приложения: латиница в верхнем регистре, цифры и «_». ИИ секрет получит, только когда включите «давать ИИ».", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(name, { name = it.uppercase().take(64); error = null }, label = { Text("Имя, например MY_SERVICE_TOKEN") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
                OutlinedTextField(value, { value = it.take(8192); error = null }, label = { Text("Значение") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                error?.let { DiagnosticText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    val secret = com.dpsmpx.dpai.core.diagnostics.Secret(value)
                    val r = withContext(Dispatchers.IO) { SecretGrants.addCustom(app, name, secret) }
                    saving = false
                    when (r) {
                        is Outcome.Failure -> error = r.errors.first().message
                        is Outcome.Success -> { value = ""; onSaved(); onClose() }
                    }
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

@Composable
private fun SecretRow(r: SecretGrants.Row, onGrant: (Boolean) -> Unit, onUnlock: () -> Unit, onRemove: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        onRemove?.let { remove -> Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { androidx.compose.material3.TextButton(onClick = remove) { Text("Удалить «${r.variable.orEmpty()}»") } } }
        if (r.eligible) {
            SwitchRow(r.granted, onChange = onGrant) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.bodyLarge)
                    Text(r.variable.orEmpty() + (r.alias?.let { " · слотам — $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            }
            if (r.locked) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Не выдаётся до входа в этом запуске", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onUnlock) { Text("Войти") }
                }
            }
        } else {
            Text(r.name, style = MaterialTheme.typography.bodyLarge)
            Text("только в списке: ИИ не выдаётся", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
