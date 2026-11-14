package com.dpsmpx.dpmeta.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.DisplayZone
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.data.PultAlerts
import com.dpsmpx.dpmeta.app.data.UserAlertRules
import com.dpsmpx.dpmeta.app.screens.AlertActions
import com.dpsmpx.dpmeta.formats.AlertFormat
import com.dpsmpx.dpmeta.metrics.FeedEvent
import com.dpsmpx.dpmeta.model.AlertAction
import com.dpsmpx.dpmeta.model.AlertRule
import com.dpsmpx.dpmeta.model.EventCatalog
import java.time.DayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Управление движком уведомлений; проба правил не отправляет настоящих уведомлений (D-140). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun NotificationSettings(feed: List<FeedEvent>, onFeed: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val initial = remember { PultAlerts.settings(context) }
    var privacy by remember { mutableStateOf(initial.privacy) }
    var privacySaving by remember { mutableStateOf(false) }
    var quiet by remember { mutableStateOf(initial.quiet != null) }
    var from by remember { mutableStateOf(AlertActions.quietName(initial.quiet ?: AlertActions.DEFAULT_QUIET).substringBefore('–')) }
    var to by remember { mutableStateOf(AlertActions.quietName(initial.quiet ?: AlertActions.DEFAULT_QUIET).substringAfter('–')) }
    var days by remember { mutableStateOf(initial.quiet?.days.orEmpty()) }
    var note by remember { mutableStateOf<String?>(null) }
    var rules by remember { mutableStateOf(false) }
    val issue by PultAlerts.issue.collectAsState()
    Text("Уведомления разработки", style = MaterialTheme.typography.titleMedium)
    if (issue.isNotEmpty()) Text("Последняя проблема: $issue", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Row {
        Checkbox(privacy, enabled = !privacySaving, onCheckedChange = { value ->
            if (!privacySaving) {
                privacySaving = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { PultAlerts.setPrivacy(context, value) }
                        privacy = value
                    }
                    finally { privacySaving = false }
                }
            }
        })
        Text("Скрывать проекты и тексты на экране блокировки")
    }
    if (privacySaving) Text("Применяю защиту уведомлений…", style = MaterialTheme.typography.bodySmall)
    Text("Включение защиты убирает прежние уведомления пульта. Следующие показывают на блокировке только вид события.", style = MaterialTheme.typography.bodySmall)
    Row { Checkbox(quiet, onCheckedChange = { quiet = it }); Text("Тихие часы") }
    if (quiet) {
        OutlinedTextField(from, { from = it.take(5) }, label = { Text("С ЧЧ:ММ") }, singleLine = true)
        OutlinedTextField(to, { to = it.take(5) }, label = { Text("До ЧЧ:ММ") }, singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DayOfWeek.values().forEachIndexed { i, day ->
                FilterChip(selected = day in days, onClick = { days = if (day in days) days - day else days + day }, label = { Text(listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[i]) })
            }
        }
        Text("Пустой выбор — каждый день; день относится к началу ночи. Пояс — ${DisplayZone.current.id}. Срочные правила могут обходить тишину.", style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = {
        val parsed = if (quiet) AlertActions.quiet(from, to, days) else Outcome.success(null)
        when (parsed) {
            is Outcome.Failure -> note = parsed.diagnostics.first().message
            is Outcome.Success -> scope.launch {
                withContext(Dispatchers.IO) { PultAlerts.setQuiet(context, parsed.value) }
                note = "Тихие часы сохранены"
            }
        }
    }) { Text("Сохранить тихие часы") }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = { rules = true }) { Text("Правила и проба по ленте…") }
    TextButton(onClick = onFeed) { Text("Лента уведомлений ИИ и проектов…") }
    Text("Правила действуют на обычные сообщения ИИ. Запросы на подтверждение и безопасность сохраняют обязательный показ. История ИИ — последние 200 кратких сообщений до 7 дней.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }) { Text("Разрешение и каналы Android…") }
    if (rules) AlertRulesDialog(feed) { rules = false }
}

@Composable
private fun AlertRulesDialog(feed: List<FeedEvent>, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<EventCatalog?>(null) }
    var text by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var closing by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf("") }
    val parsed = remember(text, catalog) { catalog?.let { AlertFormat.parse(text, it, "правила") } }
    val current = (parsed as? Outcome.Success)?.value

    fun change(value: String) { text = value; resetting = false; preview = emptyMap(); note = null }
    fun update(id: String, transform: (AlertRule) -> AlertRule) {
        current?.let { rules ->
            when (val result = AlertFormat.write(rules.copy(rules = rules.rules.map { r -> if (r.id == id) transform(r) else r }))) {
                is Outcome.Failure -> note = result.diagnostics.first().message
                is Outcome.Success -> change(result.value)
            }
        }
    }
    fun close() { if (text != original || resetting) closing = true else onClose() }
    fun save() {
        busy = true
        val snapshot = text
        scope.launch {
            val result = withContext(Dispatchers.IO) { if (resetting) PultAlerts.resetRules(context) else PultAlerts.saveRules(context, snapshot) }
            busy = false
            when (result) {
                is Outcome.Failure -> { closing = false; note = result.diagnostics.first().message }
                is Outcome.Success -> onClose()
            }
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            note = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(exportText) } ?: error("нет доступа") }
                    .fold({ "Правила сохранены в файл" }, { "Не удалось записать файл" })
            }
        }
    }
    val importRules = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        java.io.InputStreamReader(input, Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)).use { UserAlertRules.importText(it) }
                    }
                }.getOrNull()
            }
            busy = false
            when (result) {
                is Outcome.Success -> { change(result.value); advanced = true }
                is Outcome.Failure -> note = result.diagnostics.first().message
                null -> note = "Не удалось прочитать файл UTF-8"
            }
        }
    }
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { PultAlerts.catalog(context) to PultAlerts.rulesText(context) }
        catalog = (result.first as? Outcome.Success)?.value
        when (val loaded = result.second) {
            is Outcome.Success -> { text = loaded.value; original = loaded.value }
            is Outcome.Failure -> note = loaded.diagnostics.first().message
        }
        if (result.first is Outcome.Failure) note = (result.first as Outcome.Failure).diagnostics.first().message
        busy = false
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Правила уведомлений") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Решает первое подходящее включённое правило. Проба использует только загруженную ленту последних 7 дней и ничего не отправляет.", style = MaterialTheme.typography.bodySmall)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (text.length > UserAlertRules.MAX_TEXT) Text("Дополнение правил превысило предел текста; сократите комментарии или правила. Исходная настройка сохранена.", color = MaterialTheme.colorScheme.error)
            if (advanced) OutlinedTextField(text, { if (it.length <= UserAlertRules.MAX_TEXT || it.length < text.length) change(it) }, enabled = !busy, label = { Text("Правила TOML") }, minLines = 8, maxLines = 15, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) else {
                current?.rules?.forEach { rule ->
                    Row { Checkbox(rule.enabled, enabled = !busy, onCheckedChange = { value -> update(rule.id) { it.copy(enabled = value) } }); Text(rule.title, style = MaterialTheme.typography.bodySmall) }
                    TextButton(enabled = !busy, onClick = {
                        val actions = listOf(AlertAction.NOTIFY, AlertAction.FEED, AlertAction.SILENT)
                        update(rule.id) { it.copy(action = actions[(actions.indexOf(it.action) + 1) % actions.size]) }
                    }) { Text("${AlertActions.actionName(rule.action)} · ${AlertActions.onName(rule, catalog!!)}" + (preview[rule.id]?.let { " · сработало бы: $it" } ?: "")) }
                }
            }
            (parsed as? Outcome.Failure)?.let { Text(it.diagnostics.first().message, color = MaterialTheme.colorScheme.error) }
            if (current?.rules?.any { it.action == AlertAction.DIGEST } == true) Text("Дайджест по расписанию ещё в плане; выберите «уведомить» для немедленной доставки.", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy, onClick = { advanced = !advanced }) { Text(if (advanced) "Переключатели правил" else "Условия, порядок и новые правила (TOML)") }
            if (advanced) {
                Text("Regex: синтаксис RE2, без обратных ссылок и lookaround. Пороги — целые числа.", style = MaterialTheme.typography.bodySmall)
                catalog?.types?.forEach { Text("${it.type} — ${it.title}" + if (it.numbers.isEmpty()) "" else "; числа: ${it.numbers.joinToString()}", style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(enabled = !busy && current != null, onClick = {
                val rules = current ?: return@TextButton
                val known = catalog ?: return@TextButton
                busy = true
                scope.launch {
                    val history = withContext(Dispatchers.IO) { PultAlerts.refreshAi(context) }
                    if (history is Outcome.Failure) {
                        note = history.diagnostics.first().message
                        busy = false
                        return@launch
                    }
                    val notices = (history as Outcome.Success).value
                    preview = withContext(Dispatchers.Default) { AlertActions.rows(rules, known, feed, Clock.SYSTEM.now(), DisplayZone.current, PultAlerts.settings(context).quiet, notices.map { it.event }).associate { it.id to it.tried } }
                    note = "Каждое правило проверено отдельно по загруженной ленте; при доставке действует первое подходящее. Разрешения Android и доставка здесь не проверяются" + if (notices.any { it.truncated }) "; длинные сообщения ИИ представлены выдержками" else ""
                    busy = false
                    advanced = false
                }
            }) { Text("Проверить на ленте за 7 дней") }
            Row {
                TextButton(enabled = !busy, onClick = { importRules.launch(arrayOf("text/*", "application/toml", "application/octet-stream")) }) { Text("Импорт…") }
                TextButton(enabled = !busy && current != null, onClick = { exportText = text; export.launch("dpmeta-notification-rules.toml") }) { Text("Экспорт…") }
            }
            TextButton(enabled = !busy && catalog != null, onClick = { busy = true; scope.launch {
                when (val result = withContext(Dispatchers.IO) { PultAlerts.defaultsText(context) }) {
                    is Outcome.Failure -> note = result.diagnostics.first().message
                    is Outcome.Success -> { change(result.value); resetting = true; note = "Шаблон загружен; сохраните, чтобы сбросить личные правила" }
                }
                busy = false
            } }) { Text("По умолчанию") }
        }
    }, confirmButton = { TextButton(enabled = !busy && current != null, onClick = ::save) { Text("Сохранить") } },
        dismissButton = { TextButton(enabled = !busy, onClick = ::close) { Text("Закрыть") } })
    if (closing) AlertDialog(onDismissRequest = { closing = false }, title = { Text("Правила не сохранены") }, text = { Text("Сохранить изменения перед закрытием?") },
        confirmButton = { TextButton(enabled = !busy && current != null, onClick = ::save) { Text("Сохранить") } },
        dismissButton = { Row { TextButton(onClick = onClose) { Text("Не сохранять") }; TextButton(onClick = { closing = false }) { Text("Продолжить") } } })
}
