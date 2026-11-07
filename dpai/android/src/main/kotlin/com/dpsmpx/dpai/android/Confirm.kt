package com.dpsmpx.dpai.android

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Подтверждения одним приёмом на приложение (аудит UI/UX 5 ноября 2026,
 * AI:A-4; `DPmetaApp:docs/audits/2026-11-05-ui-ux.md`, §2.3): необратимое —
 * окном, где сказано, что пропадёт, с красной кнопкой справа; обратимое —
 * сразу и со снэкбаром «Отменить». «Вторым нажатием» — только в окне поверх
 * приложений (D-44), с паузой между касаниями и сбросом.
 */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, enabled: Boolean = true) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Обратимое действие со снэкбаром «Отменить» (AI:A-4): предмет скрыт сразу
 * ([hidden]), а само действие выполняется, когда снэкбар ушёл без
 * «Отменить». Окно закрыли раньше — действие не выполняется и предмет
 * остаётся: безопасная сторона (лучше не удалить, чем удалить без ведома).
 */
class Undo(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    /** Ключи предметов, ждущих своего действия: экран их не показывает. */
    val hidden = mutableStateMapOf<String, Boolean>()

    fun run(key: String, message: String, commit: () -> Unit) {
        hidden[key] = true
        scope.launch {
            try {
                val r = host.showSnackbar(message, actionLabel = "Отменить", withDismissAction = true, duration = SnackbarDuration.Long)
                if (r != SnackbarResult.ActionPerformed) withContext(Dispatchers.IO) { commit() }
            } finally {
                hidden.remove(key)
            }
        }
    }
}

/** Снэкбар «Отменить» оболочки «AI»; вне её — `null`, и обратимое делается сразу. */
val LocalUndo = staticCompositionLocalOf<Undo?> { null }
