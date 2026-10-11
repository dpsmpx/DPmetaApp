package com.dpsmpx.dpai.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskClass
import com.dpsmpx.dpai.ui.TaskAction
import com.dpsmpx.dpai.ui.TaskDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Лист задачи (D-42, п. 2): что с задачей, её модель и бюджет, действия.
 * Каждое действие — команда контура с ключом нажатия (повтор нажатия не
 * делает второго действия); опасное — после подтверждения (ТЗ, §68).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TaskSheet(model: DemoModel, task: TaskId, onSession: (String) -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Сразу с данными (чтение неизменяемого состояния, без замка): лист не меняет размер после «Загружаю…».
    var detail by remember { mutableStateOf(model.taskDetail(task)) }
    var messages by remember { mutableStateOf(model.taskMessages(task)) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<TaskAction?>(null) }
    var editor by remember { mutableStateOf<TaskAction?>(null) }
    val keys = remember { IdGenerator(Clock.SYSTEM, RandomSource.secure()) }
    LaunchedEffect(task) {
        while (true) {
            detail = withContext(Dispatchers.IO) { model.taskDetail(task) }
            messages = withContext(Dispatchers.IO) { model.taskMessages(task) }
            delay(1500)
        }
    }
    fun send(c: (String) -> Command) {
        busy = true
        val key = keys.next(IdKinds.OPERATION).value
        scope.launch {
            val r = withContext(Dispatchers.IO) { model.command(c(key)) }
            status = when (r) {
                is Outcome.Success -> "Принято"
                is Outcome.Failure -> "Не принято: ${r.diagnostics.first().message}"
            }
            detail = withContext(Dispatchers.IO) { model.taskDetail(task) }
            busy = false
            editor = null
            confirm = null
        }
    }
    val d = detail
    AlertDialog(
        onDismissRequest = onDone,
        // Почти во весь экран: итоги ходов бывают длинными (D-42).
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.96f),
        title = { Text(d?.title ?: "Задача") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (d == null) {
                    Text("Загружаю…")
                    return@Column
                }
                Text("${d.state}${if (d.held) " · приостановлена" else ""} · приоритет ${d.priority}", fontWeight = FontWeight.Bold)
                Text(d.objective, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                Text(
                    "класс ${d.taskClass} · модель ${d.model ?: "по классу"} · усилие ${d.effort ?: "по классу"} · бюджет хода ${d.budgetCents?.let { "%.2f $".format(it / 100.0) } ?: "без предела"}",
                    style = MaterialTheme.typography.labelSmall,
                )
                d.reason?.let { Text("причина: $it · повторов: ${d.retries}", style = MaterialTheme.typography.labelSmall) }
                d.usage?.let { Text("Расход: $it", style = MaterialTheme.typography.labelSmall) }
                d.waiting?.let { Text("Ждёт: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                d.lastSession?.let { s -> TextButton(onClick = { onSession(s.value) }) { Text("Открыть последнюю сессию") } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (a in d.actions) {
                        OutlinedButton(
                            enabled = !busy,
                            colors = if (a.dangerous) ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.outlinedButtonColors(),
                            onClick = {
                                when (a) {
                                    TaskAction.HOLD -> send { Command.Hold(task, it) }
                                    TaskAction.RESUME -> send { Command.Resume(task, it) }
                                    TaskAction.RETRY -> send { Command.Retry(task, null, it) }
                                    TaskAction.VERIFY -> send { Command.Verify(task, it) }
                                    TaskAction.ARCHIVE -> send { Command.Archive(task, true, it) }
                                    TaskAction.UNARCHIVE -> send { Command.Archive(task, false, it) }
                                    TaskAction.CANCEL, TaskAction.WAIVE -> confirm = a
                                    TaskAction.PRIORITY, TaskAction.POLICY, TaskAction.REPLY -> editor = a
                                }
                            },
                        ) { Text(a.label) }
                    }
                }
                when (editor) {
                    TaskAction.PRIORITY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (p in Priority.entries) FilterChip(selected = d.priority == p.name, onClick = { send { k -> Command.SetPriority(task, p, k) } }, label = { Text(p.name) })
                    }
                    TaskAction.POLICY -> PolicyEditor(d, busy) { cls, m, e, b -> send { k -> Command.SetPolicy(task, cls, m, e, b, k) } }
                    TaskAction.REPLY -> ReplyEditor(busy) { text -> send { k -> Command.Retry(task, text, k) } }
                    else -> Unit
                }
                status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                ChangesSection(model, task)
                TurnMessagesSection(messages)
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Закрыть") } },
    )
    confirm?.let { a ->
        var reason by remember(a) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${a.label}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (a == TaskAction.CANCEL) "Идущий ход будет остановлен, несохранённая работа хода пропадёт." else "Задача станет DONE без проверки — запишется исключение с причиной.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (a == TaskAction.WAIVE) OutlinedTextField(reason, { reason = it }, label = { Text("Почему") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && (a != TaskAction.WAIVE || reason.isNotBlank()), onClick = {
                    if (a == TaskAction.CANCEL) send { Command.Cancel(task, it) } else send { Command.Waive(task, reason, DemoModel.ACTOR, it) }
                }) { Text(a.label, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PolicyEditor(d: TaskDetail, busy: Boolean, onSave: (TaskClass, String?, String?, Long?) -> Unit) {
    var cls by remember { mutableStateOf(d.taskClass) }
    var modelName by remember { mutableStateOf(d.model.orEmpty()) }
    var effort by remember { mutableStateOf(d.effort.orEmpty()) }
    var budget by remember { mutableStateOf(d.budgetCents?.let { "%.2f".format(it / 100.0) }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Класс задачи — маршрут модели (D-40)", style = MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in TaskClass.entries) FilterChip(selected = cls == c, onClick = { cls = c }, label = { Text(c.name.lowercase()) })
        }
        OutlinedTextField(modelName, { modelName = it }, label = { Text("Модель (пусто — по классу)") }, singleLine = true)
        OutlinedTextField(effort, { effort = it }, label = { Text("Усилие: low, medium, high (пусто — по классу)") }, singleLine = true)
        OutlinedTextField(budget, { budget = it }, label = { Text("Предел стоимости хода, $ (пусто — без предела)") }, singleLine = true)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        TextButton(enabled = !busy, onClick = {
            val cents = budget.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.let { v -> v.toBigDecimalOrNull()?.movePointRight(2)?.toLong() ?: -1L }
            if (cents != null && cents <= 0) error = "бюджет — положительное число долларов" else onSave(cls, modelName, effort, cents)
        }) { Text("Сохранить") }
    }
}

@Composable
private fun ReplyEditor(busy: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(text, { text = it }, label = { Text("Ответ к следующему ходу") }, minLines = 2)
        TextButton(enabled = !busy && text.isNotBlank(), onClick = { onSend(text) }) { Text("Отправить и повторить") }
    }
}

/**
 * Изменения задачи (D-42, п. 3): что принесёт слияние, что не закоммичено,
 * новые файлы. Загружаются по кнопке — это Git на target, — и строки
 * красятся по виду ([com.dpsmpx.dpai.ui.TaskChangesScreen]); длинные
 * строки — прокруткой вбок, без переноса.
 */
@Composable
private fun ChangesSection(model: DemoModel, task: TaskId) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var view by remember(task) { mutableStateOf<com.dpsmpx.dpai.ui.ChangesView?>(null) }
    var failure by remember(task) { mutableStateOf<String?>(null) }
    var loading by remember(task) { mutableStateOf(false) }
    fun load() {
        loading = true
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { model.taskChanges(task) }) {
                is Outcome.Success -> {
                    view = withContext(Dispatchers.Default) { com.dpsmpx.dpai.ui.TaskChangesScreen.of(r.value, r.warnings.map { it.message }) }
                    failure = null
                }
                is Outcome.Failure -> failure = r.errors.first().message
            }
            loading = false
        }
    }
    androidx.compose.material3.HorizontalDivider()
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Изменения", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        view?.let { v ->
            TextButton(onClick = {
                context.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("dpai", v.text))
            }) { Text("Копировать") }
        }
        TextButton(enabled = !loading, onClick = { load() }) { Text(if (loading) "Загружаю…" else if (view == null) "Показать" else "Обновить") }
    }
    failure?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    val v = view ?: return
    Text(v.summary, style = MaterialTheme.typography.bodySmall)
    if (v.lines.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val text = remember(v) {
        val b = androidx.compose.ui.text.AnnotatedString.Builder()
        v.lines.forEachIndexed { i, l ->
            if (i > 0) b.append("\n")
            val style = when (l.kind) {
                com.dpsmpx.dpai.ui.DiffKind.SECTION -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = colors.primary)
                com.dpsmpx.dpai.ui.DiffKind.FILE -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)
                com.dpsmpx.dpai.ui.DiffKind.HUNK -> androidx.compose.ui.text.SpanStyle(color = colors.tertiary)
                com.dpsmpx.dpai.ui.DiffKind.ADDED -> androidx.compose.ui.text.SpanStyle(color = ADDED, background = ADDED.copy(alpha = 0.08f))
                com.dpsmpx.dpai.ui.DiffKind.REMOVED -> androidx.compose.ui.text.SpanStyle(color = colors.error, background = colors.error.copy(alpha = 0.08f))
                com.dpsmpx.dpai.ui.DiffKind.NOTE -> androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = colors.outline)
                com.dpsmpx.dpai.ui.DiffKind.STAT, com.dpsmpx.dpai.ui.DiffKind.CONTEXT -> null
            }
            val start = b.length
            b.append(l.text)
            style?.let { b.addStyle(it, start, b.length) }
        }
        b.toAnnotatedString()
    }
    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
            )
        }
    }
}

/** Цвет добавленных строк: зелёный, различимый на светлой и тёмной теме. */
private val ADDED = androidx.compose.ui.graphics.Color(0xFF2E7D32)

/**
 * Итоговые сообщения ходов (D-42): на чём модель остановилась и ждёт
 * ввода — целиком, по порядку. Переключатель: оформление (Markdown без
 * символов разметки) или исходный текст; «Копировать» берёт то, что
 * видно, — оформленное копируется чисто, без `#`, `**` и `` ` ``.
 */
@Composable
private fun TurnMessagesSection(messages: List<com.dpsmpx.dpai.orchestrator.TurnMessage>) {
    if (messages.isEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val zone = remember { java.time.ZoneId.systemDefault() }
    var formatted by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    androidx.compose.material3.HorizontalDivider()
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Сообщения модели (${messages.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        FilterChip(selected = formatted, onClick = { formatted = true }, label = { Text("md") })
        androidx.compose.foundation.layout.Spacer(Modifier.padding(2.dp))
        FilterChip(selected = !formatted, onClick = { formatted = false }, label = { Text("txt") })
    }
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    for (m in messages) {
        val t = java.time.Instant.ofEpochMilli(m.at.epochMillis).atZone(zone)
        val rendered = remember(m.text) { com.dpsmpx.dpai.ui.Markdown.render(m.text) }
        androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        "ход ${m.turn} · %02d.%02d %02d:%02d · ${m.status}${m.model?.let { " · $it" } ?: ""}".format(t.dayOfMonth, t.monthValue, t.hour, t.minute),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val text = if (formatted) rendered.text else m.text
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            .setPrimaryClip(android.content.ClipData.newPlainText("dpai", text))
                    }) { Text("Копировать") }
                }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    if (formatted) {
                        Text(annotated(rendered, codeBackground, linkColor), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(m.text, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

/** Оформленный текст ([com.dpsmpx.dpai.ui.Markdown]) → стили Compose; символов разметки в нём уже нет. */
private fun annotated(r: com.dpsmpx.dpai.ui.Rendered, code: androidx.compose.ui.graphics.Color, link: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.AnnotatedString {
    val b = androidx.compose.ui.text.AnnotatedString.Builder(r.text)
    for (m in r.marks) {
        val style = when (m.style) {
            com.dpsmpx.dpai.ui.MarkStyle.BOLD -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)
            com.dpsmpx.dpai.ui.MarkStyle.ITALIC -> androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            com.dpsmpx.dpai.ui.MarkStyle.STRIKE -> androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
            com.dpsmpx.dpai.ui.MarkStyle.CODE, com.dpsmpx.dpai.ui.MarkStyle.CODE_BLOCK ->
                androidx.compose.ui.text.SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, background = code)
            com.dpsmpx.dpai.ui.MarkStyle.HEADING_1 -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, fontSize = androidx.compose.ui.unit.TextUnit(1.35f, androidx.compose.ui.unit.TextUnitType.Em))
            com.dpsmpx.dpai.ui.MarkStyle.HEADING_2 -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, fontSize = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Em))
            com.dpsmpx.dpai.ui.MarkStyle.HEADING_3 -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, fontSize = androidx.compose.ui.unit.TextUnit(1.08f, androidx.compose.ui.unit.TextUnitType.Em))
            com.dpsmpx.dpai.ui.MarkStyle.QUOTE -> androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = link.copy(alpha = 0.8f))
            com.dpsmpx.dpai.ui.MarkStyle.LINK -> androidx.compose.ui.text.SpanStyle(color = link, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
            com.dpsmpx.dpai.ui.MarkStyle.TABLE -> androidx.compose.ui.text.SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        b.addStyle(style, m.start, m.end)
    }
    return b.toAnnotatedString()
}
