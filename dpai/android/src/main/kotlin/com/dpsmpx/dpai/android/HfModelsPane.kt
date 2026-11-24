package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

/**
 * «Скачать с Hugging Face» в «Локальной модели» (D-110, D-113, D-119): модель
 * `владелец/имя` → файлы GGUF с размерами → скачивание в `/root/models` с
 * ходом и «Отменой»; оборвалось — «Докачать». Токен просится только у
 * закрытой модели — окном с защитой от снимков экрана.
 */
@Composable
fun HfDownloadSection() {
    val context = LocalContext.current
    val s by HfModels.state.collectAsState()
    var input by remember { mutableStateOf(s.input) }
    var asking by remember { mutableStateOf(false) }
    // Токен сохранён или стёрт — перечитать отметку.
    var tokenRound by remember { mutableIntStateOf(0) }
    val hasToken = remember(tokenRound) { HfModels.hasToken(context) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Скачать с Hugging Face", style = MaterialTheme.typography.titleSmall)
        Text(
            "Модель — «владелец/имя» или её адрес на huggingface.co. Файл сверяется с суммой Hugging Face; " +
                "токен нужен только закрытой модели — его спросим при скачивании.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(input, { input = it }, label = { Text("Модель на Hugging Face") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = s.busy == null && input.isNotBlank(), onClick = { HfModels.list(context, input.trim()) }) { Text("Показать файлы") }
            if (hasToken) {
                TextButton(enabled = s.busy == null, onClick = {
                    HfModels.forgetToken(context)
                    tokenRound++
                }) { Text("Стереть токен") }
            }
        }
        s.listing?.let { l ->
            Text(
                "Коммит ${l.revision.take(7)}" + (if (l.gated) " · условия доступа: примите их на странице модели" else "") + " · файлов: ${l.files.size}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        for (row in s.rows) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.file.path, style = MaterialTheme.typography.bodySmall)
                    val extra = when {
                        row.present -> " · скачан"
                        row.part > 0 -> " · есть ${HfModels.size(row.part)}"
                        else -> ""
                    }
                    Text(HfModels.size(row.file.size) + extra, style = MaterialTheme.typography.labelSmall)
                }
                if (!row.present) {
                    TextButton(enabled = s.busy == null, onClick = { HfModels.download(context, row.file) }) {
                        Text(if (row.part > 0) "Докачать" else "Скачать")
                    }
                }
            }
        }
        if (s.busy != null) {
            Text(s.busy.orEmpty(), style = MaterialTheme.typography.labelSmall)
            if (s.file != null && s.total > 0) {
                LinearProgressIndicator(progress = { (s.have.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${HfModels.size(s.have)} из ${HfModels.size(s.total)}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { HfModels.stop() }) { Text("Отмена") }
                }
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        s.note?.let { DiagnosticText(it, style = MaterialTheme.typography.labelSmall, color = if (s.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
        if (s.needToken && s.busy == null) {
            TextButton(onClick = { asking = true }) { Text(if (hasToken) "Заменить токен…" else "Ввести токен…") }
        }
    }
    if (asking) {
        HfTokenDialog(
            onSave = { token ->
                val ok = HfModels.saveToken(context, token)
                tokenRound++
                asking = false
                if (ok) HfModels.retry(context)
            },
            onDismiss = { asking = false },
        )
    }
}

/** Токен Hugging Face (только чтение) — окно с защитой от снимков: секрет на экране (T21). */
@Composable
private fun HfTokenDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Токен Hugging Face") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Модель закрыта: нужен токен с правом чтения (huggingface.co → Settings → Access Tokens → Read). " +
                        "Если у модели условия доступа — сначала примите их на её странице. " +
                        "Токен хранится под ключом Keystore и уходит только на huggingface.co — не на хранилище файлов и не в журнал.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    token, { token = it },
                    label = { Text("Токен") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        },
        confirmButton = { TextButton(enabled = token.isNotBlank(), onClick = { onSave(token) }) { Text("Сохранить и повторить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
