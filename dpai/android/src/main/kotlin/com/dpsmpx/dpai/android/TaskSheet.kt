package com.dpsmpx.dpai.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.orchestrator.review.Review
import com.dpsmpx.dpai.tasks.FindingSeverity
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.ReviewRole
import com.dpsmpx.dpai.tasks.TaskClass
import com.dpsmpx.dpai.ui.ReviewLine
import com.dpsmpx.dpai.ui.TaskAction
import com.dpsmpx.dpai.ui.TaskDetail
import com.dpsmpx.dpai.ui.Tone
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Лист задачи (D-42, п. 2): что с задачей, её модель и бюджет, действия.
 * Каждое действие — команда контура с ключом нажатия (повтор нажатия не
 * делает второго действия); опасное — после подтверждения (ТЗ, §68).
 *
 * [embedded] — панелью рядом с чатом на широком экране (AI:A-24,
 * `DPmetaApp:D-77`), иначе — во весь экран; [shownSession] — сессия, чат
 * которой уже открыт: «Открыть чат» к ней не ведёт.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TaskSheet(
    model: DemoModel,
    task: TaskId,
    onSession: (String) -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    shownSession: String? = null,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Сразу с данными (чтение неизменяемого состояния, без замка): лист не меняет размер после «Загружаю…».
    var detail by remember { mutableStateOf(model.taskDetail(task)) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<TaskAction?>(null) }
    var editor by remember { mutableStateOf<TaskAction?>(null) }
    val keys = remember { IdGenerator(Clock.SYSTEM, RandomSource.secure()) }
    // Кому передать задачу у предела её исполнителя (D-165): без автоматического перехода решает человек.
    var offers by remember { mutableStateOf<List<com.dpsmpx.dpai.core.ids.Executor>>(emptyList()) }
    Poll(task, periodMs = 1500) {
        detail = withContext(Dispatchers.IO) { model.taskDetail(task) }
        offers = withContext(Dispatchers.IO) { runCatching { model.liveOrchestrator()?.handoverCandidates(task) }.getOrNull().orEmpty() }
    }
    // Несколько команд — по очереди, до первого отказа (область, затем зависимости).
    fun sendAll(vararg cs: (String) -> Command) {
        busy = true
        val built = cs.map { c -> c(keys.next(IdKinds.OPERATION).value) }
        scope.launch {
            // «В очереди» — не отказ: следующая команда тоже идёт в очередь; настоящий отказ — стоп.
            val lines = mutableListOf<String>()
            for (c in built) {
                val r = withContext(Dispatchers.IO) { model.command(c) }
                val d = (r as? Outcome.Failure)?.diagnostics?.first()
                lines += when {
                    d == null -> "Принято"
                    d.code == com.dpsmpx.dpai.core.diagnostics.DiagCode.COMMAND_QUEUED -> "В очереди: ${d.message}"
                    else -> "Не принято: ${d.message}"
                }
                if (d != null && d.code != com.dpsmpx.dpai.core.diagnostics.DiagCode.COMMAND_QUEUED) break
            }
            status = lines.distinct().joinToString("\n")
            detail = withContext(Dispatchers.IO) { model.taskDetail(task) }
            busy = false
            editor = null
            confirm = null
        }
    }
    fun send(c: (String) -> Command) = sendAll(c)
    val d = detail
    // Действие листа: команда сразу, опасное — окном, с полями — своим окном редактора (AI:A-9).
    fun act(a: TaskAction) {
        when (a) {
            TaskAction.HOLD -> send { Command.Hold(task, it) }
            TaskAction.RESUME -> send { Command.Resume(task, it) }
            TaskAction.RUN_FIRST -> send { Command.RunFirst(task, it) }
            TaskAction.RETRY -> send { Command.Retry(task, null, it) }
            TaskAction.VERIFY -> send { Command.Verify(task, it) }
            TaskAction.ARCHIVE -> send { Command.Archive(task, true, it) }
            TaskAction.UNARCHIVE -> send { Command.Archive(task, false, it) }
            TaskAction.CANCEL, TaskAction.WAIVE -> confirm = a
            TaskAction.PRIORITY, TaskAction.POLICY, TaskAction.SCOPE, TaskAction.REPLY -> editor = a
        }
    }
    val primary = d?.let(com.dpsmpx.dpai.ui.TaskScreens::primary).orEmpty()
    // Во весь экран (AI:A-9): «×», название и ⋮ сверху, главное действие — заливной кнопкой, остальное — в ⋮.
    val title = d?.title ?: "Задача"
    val menu: FormMenu? = d?.actions?.filter { it !in primary }?.takeIf { it.isNotEmpty() }?.let { rest ->
        { close ->
            for (a in rest) {
                androidx.compose.material3.DropdownMenuItem(
                    enabled = !busy,
                    text = { Text(a.label + if (a == TaskAction.CANCEL || a == TaskAction.WAIVE) "…" else "", color = if (a.dangerous) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified) },
                    onClick = { close(); act(a) },
                )
            }
        }
    }
    val body: @Composable () -> Unit = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (d == null) {
                    Text("Загружаю…")
                    return@Column
                }
                // Вывод модели — только в чате сессии (D-62): переход туда — первым.
                d.lastSession?.takeIf { it.value != shownSession }?.let { s -> androidx.compose.material3.Button(onClick = { onSession(s.value) }, Modifier.fillMaxWidth()) { Text("Открыть чат") } }
                if (offers.isNotEmpty()) {
                    Text("У всех слотов исполнителя задачи предел. Передать другому подключённому ИИ:", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    androidx.compose.foundation.layout.Row {
                        for (x in offers) androidx.compose.material3.OutlinedButton(enabled = !busy, onClick = { send { k -> Command.SetExecutor(task, x, k) } }) { Text("Передать ${x.title}") }
                    }
                }
                // Слово, а не идентификатор (D-88, U-3): человек читает экран, а не журнал событий.
                Text("${Words.task(d.state)}${if (d.held) " · приостановлена" else ""} · приоритет ${Words.priority(d.priority)}", fontWeight = FontWeight.Bold)
                // Цель длиннее четырёх строк — с многоточием и раскрытием нажатием (A-20).
                var whole by remember(d.objective) { mutableStateOf(false) }
                Text(
                    d.objective, style = MaterialTheme.typography.bodySmall, maxLines = if (whole) Int.MAX_VALUE else 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.clickable { whole = !whole },
                )
                Text(
                    "класс «${Words.taskClass(d.taskClass.name)}» · модель ${d.model ?: "по классу"} · усилие ${d.effort ?: "по классу"} · бюджет хода ${d.budgetCents?.let { String.format(Locale.ROOT, "%.2f $", it / 100.0) } ?: "без предела"}",
                    style = MaterialTheme.typography.labelSmall,
                )
                d.usage?.let { Text("расход: $it", style = MaterialTheme.typography.labelSmall) }
                d.scope?.let { Text("область: ${it.sorted().joinToString(", ")}", style = MaterialTheme.typography.labelSmall) }
                if (d.dependencies.isNotEmpty()) {
                    Text("зависит от: ${d.dependencies.joinToString(" · ") { "${it.title} (${Words.task(it.state)})" }}", style = MaterialTheme.typography.labelSmall)
                }
                d.reason?.let { Text("причина: $it · повторов: ${d.retries}", style = MaterialTheme.typography.labelSmall) }
                // Кто выполняет и кому передавалась (D-56): слот — target и подписка.
                d.executor?.let { Text("Выполняет: $it", style = MaterialTheme.typography.bodySmall) }
                if (d.assignments.size > 1 || (d.assignments.isNotEmpty() && d.executor == null)) {
                    Text("Кому передавалась:", style = MaterialTheme.typography.labelSmall)
                    d.assignments.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall) }
                }
                if (d.reviews.isNotEmpty()) ReviewsSection(d.reviews)
                d.waiting?.let { Text("Ждёт: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                // Ждущий запрос задачи — с его вариантами здесь же (AI:A-7), а не только «Повторить».
                model.published?.requests.orEmpty().filter { it.task == task }.forEach { RequestItem(model, it) }
                // Одно-два действия по состоянию — заливными (AI:A-9); остальные — в ⋮ сверху.
                if (primary.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (a in primary) androidx.compose.material3.Button(enabled = !busy, onClick = { act(a) }) { Text(a.label) }
                    }
                }
                // Цвет ошибки — только у отказа (аудит удобства, A-19): «Принято» и «В очереди» — обычным.
                status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if ("Не принято" in it) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                // Редкое — свёрнутыми разделами (AI:A-9).
                var moving by rememberSaveable(task.value) { mutableStateOf(false) }
                Text(if (moving) "Слот следующего хода ▾" else "Слот следующего хода ▸", Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { moving = !moving }.padding(vertical = 12.dp), fontWeight = FontWeight.SemiBold)
                if (moving) MoveSection(model, task, d.executor) { c -> send(c) }
                ChangesSection(model, task)
                GitHubSendSection(model, task, title)
            }
    }
    if (embedded) SidePanel(onDone, title, modifier, menu = menu, content = body) else FullScreenDialog(onDone, title, menu = menu, content = body)
    // Редакторы — своим окном, а не вставкой между кнопками (AI:A-9).
    val dd = detail
    editor?.takeIf { dd != null }?.let { a ->
        AlertDialog(
            onDismissRequest = { editor = null },
            title = { Text(a.label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when (a) {
                        TaskAction.PRIORITY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (p in Priority.entries) FilterChip(selected = dd!!.priority == p.name, onClick = { send { k -> Command.SetPriority(task, p, k) } }, label = { Text(Words.priority(p.name)) })
                        }
                        TaskAction.POLICY -> PolicyEditor(dd!!, busy) { cls, m, e, b, r -> send { k -> Command.SetPolicy(task, cls, m, e, b, r, k) } }
                        TaskAction.REPLY -> ReplyEditor(busy) { text -> send { k -> Command.Retry(task, text, k) } }
                        TaskAction.SCOPE -> ScopeEditor(dd!!, remember(task) { model.dependencyChoices(except = task) }, busy) { paths, deps ->
                            // Неизменённое не шлётся: лишняя команда записала бы пустое изменение в журнал.
                            val cs = buildList<(String) -> Command> {
                                if (paths != dd.scope.orEmpty()) add { k -> Command.SetScope(task, paths, k) }
                                if (deps != dd.dependencies.map { it.id }.toSet()) add { k -> Command.SetDependencies(task, deps, k) }
                            }
                            if (cs.isEmpty()) editor = null else sendAll(*cs.toTypedArray())
                        }
                        else -> Unit
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editor = null }) { Text("Отмена") } },
        )
    }
    confirm?.let { a ->
        var reason by remember(a) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${a.label}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (a == TaskAction.CANCEL) "Идущий ход будет остановлен, несохранённая работа хода пропадёт." else "Задача станет выполненной без проверки — запишется исключение с причиной.",
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
private fun PolicyEditor(d: TaskDetail, busy: Boolean, onSave: (TaskClass, String?, String?, Long?, Set<ReviewRole>) -> Unit) {
    var cls by remember { mutableStateOf(d.taskClass) }
    var reviews by remember { mutableStateOf(d.reviews.map { it.role }.toSet()) }
    var modelName by remember { mutableStateOf(d.model.orEmpty()) }
    var effort by remember { mutableStateOf(d.effort.orEmpty()) }
    var budget by remember { mutableStateOf(d.budgetCents?.let { String.format(Locale.ROOT, "%.2f", it / 100.0) }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Варианты — исполнителя задачи (AI:A-10): класс — только у Claude, усилия у Gemini нет.
        val x = d.taskExecutor
        // Класс, заданный раньше (cli, прежний исполнитель), виден и у Codex и Gemini: «простая» запрещает ревью,
        // и без выбора на экране его не снять (ревью PR #170).
        if (com.dpsmpx.dpai.ui.ExecutorOptions.hasClass(x) || d.taskClass != TaskClass.NORMAL_ENGINEERING) {
            Text("Класс задачи — какую модель брать", style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in TaskClass.entries) FilterChip(selected = cls == c, onClick = { cls = c }, label = { Text(Words.taskClass(c.name)) })
            }
        }
        // Модель и усилие — выбором из вариантов исполнителя (AI:A-9, A-10); свободным текстом — только модель Codex и Gemini.
        val byClass = if (com.dpsmpx.dpai.ui.ExecutorOptions.hasClass(x)) "по классу" else "по умолчанию"
        val models = com.dpsmpx.dpai.ui.ExecutorOptions.models(x)
        if (models.isNotEmpty()) {
            Pick("Модель", modelName.ifEmpty { byClass }, listOf("" to byClass) + models.map { it to it }) { modelName = it }
        } else {
            OutlinedTextField(modelName, { modelName = it }, label = { Text(com.dpsmpx.dpai.ui.ExecutorOptions.modelHint(x)) }, singleLine = true)
        }
        if (com.dpsmpx.dpai.ui.ExecutorOptions.efforts(x).isNotEmpty()) {
            Pick("Усилие", effort.ifEmpty { byClass }, listOf("" to byClass) + com.dpsmpx.dpai.ui.ExecutorOptions.efforts(x).map { it to it }) { effort = it }
        }
        OutlinedTextField(budget, { budget = it }, label = { Text("Предел стоимости хода, $ (пусто — без предела)") }, singleLine = true)
        ReviewChips(reviews) { reviews = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        TextButton(enabled = !busy, onClick = {
            when (val c = com.dpsmpx.dpai.ui.NewTaskForm.budgetCents(budget)) {
                is Outcome.Failure -> error = c.diagnostics.first().message
                is Outcome.Success -> onSave(cls, modelName, effort, c.value, reviews)
            }
        }) { Text("Сохранить") }
    }
}

/** Область и зависимости задачи до запуска (D-42, п. 1): пути — текстом, зависимости — выбором из незавершённых задач. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ScopeEditor(d: TaskDetail, choices: List<com.dpsmpx.dpai.ui.DependencyLine>, busy: Boolean, onSave: (Set<String>, Set<TaskId>) -> Unit) {
    var paths by remember { mutableStateOf(d.scope?.sorted()?.joinToString("\n").orEmpty()) }
    var deps by remember { mutableStateOf(d.dependencies.map { it.id }.toSet()) }
    // Уже выбранная зависимость могла завершиться — её тоже видно, чтобы её можно было снять.
    val all = (choices + d.dependencies).distinctBy { it.id }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(paths, { paths = it }, label = { Text("Область: пути в репозитории (пусто — без области)") }, minLines = 2)
        if (all.isNotEmpty()) {
            Text("Зависит от", style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in all) FilterChip(selected = c.id in deps, onClick = { deps = if (c.id in deps) deps - c.id else deps + c.id }, label = { Text(c.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) })
            }
        }
        TextButton(enabled = !busy, onClick = { onSave(com.dpsmpx.dpai.ui.NewTaskForm.scopePaths(paths), deps) }) { Text("Сохранить") }
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
 * Изменения задачи (D-42, п. 3; D-72): от базы ветки интеграции до дерева
 * задачи — по файлам, новые файлы и текст. Загружаются по кнопке — это Git
 * на target, — и строки красятся по виду ([com.dpsmpx.dpai.ui.TaskChangesScreen]); длинные
 * строки — прокруткой вбок, без переноса.
 */
@Composable
internal fun ChangesSection(model: DemoModel, task: TaskId) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var view by remember(task) { mutableStateOf<com.dpsmpx.dpai.ui.ChangesView?>(null) }
    var failure by remember(task) { mutableStateOf<String?>(null) }
    var loading by remember(task) { mutableStateOf(false) }
    fun load() {
        loading = true
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { model.taskDiff(task) }) {
                is Outcome.Success -> {
                    view = withContext(Dispatchers.Default) { com.dpsmpx.dpai.ui.TaskChangesScreen.of(r.value) }
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
                // Изменения задачи — что угодно из дерева, в том числе случайный секрет: без превью буфера.
                Clipboard.copy(context, "изменения задачи", v.text, sensitive = true)
                // Своё «Скопировано» — только до Android 13: дальше его показывает система (AI:A-26).
                if (!Clipboard.systemConfirms) android.widget.Toast.makeText(context, "Скопировано", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("Копировать") }
        }
        TextButton(enabled = !loading, onClick = { load() }) { Text(if (loading) "Загружаю…" else if (view == null) "Показать" else "Обновить") }
    }
    failure?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    val v = view ?: return
    Text(v.summary, style = MaterialTheme.typography.bodySmall)
    if (v.lines.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    // Добавленные строки — смысловой «готово» под тему (AI:A-12): тёмно-зелёный на тёмной теме не читался.
    val added = toneColor(Tone.DONE)
    val text = remember(v, added) {
        val b = androidx.compose.ui.text.AnnotatedString.Builder()
        v.lines.forEachIndexed { i, l ->
            if (i > 0) b.append("\n")
            val style = when (l.kind) {
                com.dpsmpx.dpai.ui.DiffKind.SECTION -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = colors.primary)
                com.dpsmpx.dpai.ui.DiffKind.FILE -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)
                com.dpsmpx.dpai.ui.DiffKind.HUNK -> androidx.compose.ui.text.SpanStyle(color = colors.tertiary)
                com.dpsmpx.dpai.ui.DiffKind.ADDED -> androidx.compose.ui.text.SpanStyle(color = added, background = added.copy(alpha = 0.08f))
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
        // Изменения задачи — что угодно из дерева: копирование выделением — тоже «чувствительное».
        Clipboard.Sensitive {
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
}

/**
 * Ручной перенос задачи на слот (D-66): следующий ход — на выбранном слоте
 * свежей сессией с контекстом задачи (цель, переписка, фоновые процессы);
 * идущий ход не прерывается. Это опция: без неё задача сама переходит на
 * слот с запасом, когда подписка упирается в предел (D-56, D-57).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MoveSection(model: DemoModel, task: TaskId, executor: String?, send: ((String) -> Command) -> Unit) {
    val slots = remember { model.slotTargets() }
    if (slots.size < 2) return
    var pinned by remember { mutableStateOf(model.pinned(task)) }
    Poll(task, periodMs = 1500) { pinned = withContext(Dispatchers.IO) { model.pinned(task) } }
    androidx.compose.material3.HorizontalDivider()
    // Не «Перенести» (A-13): это выбор слота следующего хода, идущий ход не прерывается (D-66).
    Text("Где пойдёт следующий ход", style = MaterialTheme.typography.titleSmall)
    Text(
        (executor?.let { "Сейчас: $it. " } ?: "") + "Следующий ход пойдёт на выбранном слоте с контекстом задачи; идущий ход не прерывается.",
        style = MaterialTheme.typography.labelSmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((id, label) in slots) {
            FilterChip(selected = pinned == id, onClick = { send { k -> Command.MoveTask(task, id, k) }; pinned = id }, label = { Text(label) })
        }
    }
}

/** Роли ревью после проверки (D-73): каждая — отдельная сессия только для чтения. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReviewChips(selected: Set<ReviewRole>, onChange: (Set<ReviewRole>) -> Unit) {
    Text("Ревью после проверки — отдельной сессией", style = MaterialTheme.typography.labelSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in ReviewRole.entries) {
            FilterChip(selected = r in selected, onClick = { onChange(if (r in selected) selected - r else selected + r) }, label = { Text(Review.label(r)) })
        }
    }
}

/** Итоги ревью в листе задачи (D-73): роль, итог, находки — блокирующие первыми, с пояснением. */
@Composable
private fun ReviewsSection(reviews: List<ReviewLine>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (r in reviews) {
            Text("${r.label}: ${r.verdict}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = toneColor(r.tone))
            r.note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            for (f in r.findings) {
                Text(
                    "${Words.severity(f.severity.name)} · ${f.title}${f.where?.let { " ($it)" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (f.severity == FindingSeverity.BLOCKING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                f.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp)) }
            }
        }
    }
}
