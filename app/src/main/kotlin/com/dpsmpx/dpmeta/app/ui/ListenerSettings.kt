package com.dpsmpx.dpmeta.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dpsmpx.dpmeta.app.PultListenerService
import com.dpsmpx.dpmeta.app.data.ListenerPrefs
import com.dpsmpx.dpmeta.notify.AppNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Уведомления других приложений» (F-57; D-155): выключено по умолчанию; доступ даёт
 * человек в настройках Android; берутся только пакеты белого списка (по умолчанию —
 * Claude), остальное отбрасывается сразу.
 */
@Composable
internal fun ListenerSettings() {
    val local = LocalContext.current
    val context = local.applicationContext
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(ListenerPrefs.enabled(context)) }
    var list by remember { mutableStateOf(ListenerPrefs.packages(context).joinToString("\n")) }
    var granted by remember { mutableStateOf(PultListenerService.granted(context)) }
    var note by remember { mutableStateOf<String?>(null) }

    Text("Уведомления других приложений", style = MaterialTheme.typography.titleSmall)
    Text(
        "Уведомления приложений из списка ниже (по умолчанию — Claude) идут в те же правила, что события пульта: " +
            "так «Claude закончил» приходит и из сессий без хуков. Android даёт доступ сразу ко всем уведомлениям " +
            "телефона; приложение берёт только пакеты из списка, остальное отбрасывает сразу, не сохраняя. " +
            "Чтобы не было двух уведомлений, отключите уведомления самого Claude в Android.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row {
        Checkbox(enabled, onCheckedChange = { on ->
            enabled = on
            scope.launch { withContext(Dispatchers.IO) { ListenerPrefs.setEnabled(context, on) } }
        })
        Text("Включено")
    }
    Text(
        if (granted) "Доступ к уведомлениям Android: дан" else "Доступ к уведомлениям Android: не дан — без него слушатель ничего не видит",
        color = if (granted || !enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = {
        runCatching { local.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }) { Text(if (granted) "Отозвать доступ в Android…" else "Дать доступ в Android…") }
    // Доступ меняют в настройках Android — после возвращения перечитать кнопкой.
    TextButton(onClick = { granted = PultListenerService.granted(context) }) { Text("Проверить доступ") }
    OutlinedTextField(list, { list = it.take(4000) }, label = { Text("Пакеты — по строке") }, minLines = 2)
    TextButton(onClick = {
        val p = AppNotifications.packages(list)
        if (p.bad.isNotEmpty()) {
            note = "Не имя пакета: ${p.bad.joinToString()}"
        } else {
            scope.launch {
                withContext(Dispatchers.IO) { ListenerPrefs.setPackages(context, p.ok) }
                list = p.ok.joinToString("\n")
                note = if (p.ok.isEmpty()) "Список пуст — уведомления не берутся" else "Список сохранён: ${p.ok.size}"
            }
        }
    }) { Text("Сохранить список") }
    TextButton(onClick = { list = AppNotifications.DEFAULT_PACKAGES.joinToString("\n") }) { Text("Как по умолчанию") }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
