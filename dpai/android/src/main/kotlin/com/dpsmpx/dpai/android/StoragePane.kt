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
            })
            note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun SecretRow(r: SecretGrants.Row, onGrant: (Boolean) -> Unit, onUnlock: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
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
