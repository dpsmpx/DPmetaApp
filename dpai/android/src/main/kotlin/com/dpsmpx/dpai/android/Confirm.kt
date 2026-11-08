package com.dpsmpx.dpai.android

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
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
 * Снэкбары оболочки «AI». Обратимое действие — со снэкбаром «Отменить» (AI:A-4): предмет скрыт сразу
 * ([hidden]), а само действие выполняется, когда снэкбар ушёл без
 * «Отменить». Окно закрыли раньше — действие не выполняется и предмет
 * остаётся: безопасная сторона (лучше не удалить, чем удалить без ведома).
 */
class Undo(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    /** Ключи предметов, ждущих своего действия: экран их не показывает. */
    val hidden = mutableStateMapOf<String, Boolean>()

    /**
     * Временный итог («Сохранено: …», «Токен отозван») — снэкбаром, который
     * уходит сам (AI:A-19), а не строкой до ухода с экрана. Отказ остаётся
     * строкой на месте: его надо прочесть и исправить.
     */
    fun say(text: String) {
        scope.launch { host.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Short) }
    }

    /**
     * Сделанное сразу — снэкбаром с «Отменить» (AI:A-26): нажато — [revert]
     * в фоне. Для обратимого, у которого итог (сколько) известен только после
     * действия: «Убрано 3 · Отменить».
     */
    fun revertible(message: String, revert: () -> Unit) {
        scope.launch {
            val r = host.showSnackbar(message, actionLabel = "Отменить", withDismissAction = true, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) withContext(Dispatchers.IO) { revert() }
        }
    }

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

/**
 * Временный итог (AI:A-19): снэкбаром, а строка на месте стирается — прежний
 * отказ не висит рядом с новым успехом; хоста снэкбаров нет — итог строкой.
 */
internal fun Undo?.tell(text: String, line: (String?) -> Unit) {
    if (this != null) {
        say(text)
        line(null)
    } else {
        line(text)
    }
}

/**
 * Рамка карточки — или без неё, когда карточка стала разделом другой
 * («Копии и восстановление» в «Настройках AI», AI:A-3).
 */
@Composable
internal fun Framed(framed: Boolean, content: @Composable () -> Unit) {
    if (framed) androidx.compose.material3.Card(Modifier.fillMaxWidth()) { content() } else content()
}

/**
 * Строка «подпись + переключатель» — одна цель касания (AI:A-5): вся
 * строка переключает, TalkBack читает «выключатель», подпись и состояние;
 * высота — не меньше 48 dp, при крупном шрифте растёт.
 */
@Composable
internal fun SwitchRow(checked: Boolean, onChange: (Boolean) -> Unit, label: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        label()
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Полноэкранная форма (AI:A-9): строка сверху — «×» (закрыть), название,
 * главное действие ([action]) и ⋮ ([menu], пункты получают «закрыть меню»);
 * содержимое — во всю высоту, прокрутку даёт само. Подтверждения поверх —
 * окнами, как прежде (AI:A-4).
 */
@Composable
internal fun FullScreenDialog(
    onDismissRequest: () -> Unit,
    title: String,
    action: (@Composable () -> Unit)? = null,
    menu: FormMenu? = null,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            // Системные панели и вырез сбоку — отступом (AI:A-24).
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)) {
                FormBar(onDismissRequest, title, action, menu)
                // На широком экране — не шире CONTENT_MAX по центру (AI:A-24).
                androidx.compose.foundation.layout.Box(
                    Modifier.weight(1f).fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = CONTENT_MAX).fillMaxWidth().padding(horizontal = 16.dp),
                ) { content() }
            }
        }
    }
}

/**
 * Та же форма панелью внутри экрана (AI:A-24, `DPmetaApp:D-77`): на широком
 * экране — рядом с основным содержимым, а не окном поверх него. Строка
 * сверху — как у [FullScreenDialog]; «×» прячет панель.
 */
@Composable
internal fun SidePanel(
    onClose: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    menu: FormMenu? = null,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Column(modifier.fillMaxSize()) {
        FormBar(onClose, title, action, menu, closeLabel = "Скрыть панель")
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) { content() }
    }
}

/** Пункты ⋮ формы: получают «закрыть меню». */
internal typealias FormMenu = @Composable androidx.compose.foundation.layout.ColumnScope.(close: () -> Unit) -> Unit

/** Строка формы: «×», название, главное действие и ⋮ (AI:A-9). */
@Composable
private fun FormBar(onClose: () -> Unit, title: String, action: (@Composable () -> Unit)?, menu: FormMenu?, closeLabel: String = "Закрыть") {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = closeLabel }) {
            Text("×", style = MaterialTheme.typography.titleLarge)
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        action?.invoke()
        if (menu != null) {
            var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.foundation.layout.Box {
                androidx.compose.material3.IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "Ещё действия" }) {
                    Text("⋮", style = MaterialTheme.typography.titleLarge)
                }
                androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
            }
        }
    }
}
