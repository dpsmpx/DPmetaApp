package com.dpsmpx.dpai.android

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskLifecycle
import com.dpsmpx.dpai.ecosystem.EcosystemProject
import com.dpsmpx.dpai.notifications.DeepLink
import com.dpsmpx.dpai.notifications.Notification
import com.dpsmpx.dpai.notifications.NotificationCategory
import com.dpsmpx.dpai.orchestrator.ecosystem.AutoEntry
import com.dpsmpx.dpai.orchestrator.ecosystem.AutoPolicy
import com.dpsmpx.dpai.orchestrator.log.AppLog
import com.dpsmpx.dpai.tasks.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days

/**
 * Задачи из экосистемы без подтверждения (D-106, T19): проход политики —
 * раз в час в такте службы контроллера, сразу после включения и кнопкой.
 * Манифест — файл, выбранный в окне «Экосистема» (разрешение на чтение
 * сохраняется), проект — проект контура. Каждая задача политики — строка
 * журнала приложения и уведомление: человек видит её, хотя не нажимал
 * «Поставить».
 */
object EcosystemAuto {
    private const val TAG = "экосистема"
    private const val EVERY_MS = 60L * 60 * 1000

    /** Монотонное время прошлого прохода такта; `null` — проход в первом такте службы. */
    @Volatile
    private var last: Long? = null

    /** Такт службы: проход раз в час. Политики нет — проход ничего не читает, кроме её документа. */
    fun tick(app: Context, model: DemoModel, notifier: Notifier, log: AppLog) {
        val now = Clock.SYSTEM.monotonicNanos()
        last?.let { if (now - it < TimeUnit.MILLISECONDS.toNanos(EVERY_MS)) return }
        last = now
        pass(app, model, notifier, log)
    }

    /** Проход сейчас; итог — строка для экрана. Блокирующий вызов — с фонового потока. */
    fun pass(context: Context, model: DemoModel, notifier: Notifier, log: AppLog): String {
        val auto = Controller.get(context.filesDir).ecosystemAuto
        val policy = when (val p = auto.policy()) {
            is Outcome.Failure -> return "Политика не читается: ${p.errors.firstOrNull()?.message}".also { log.error(TAG, it) }
            is Outcome.Success -> p.value ?: return "Политика выключена"
        }
        if (policy.expired(Clock.SYSTEM.now())) return "Срок политики истёк — задачи не ставятся"
        val project = EcosystemChoice.project(context) ?: return "Проект контура не выбран — задачи не ставятся".also { log.warn(TAG, it) }
        val uri = EcosystemChoice.manifest(context) ?: return "Манифест не выбран — задачи не ставятся".also { log.warn(TAG, it) }
        val projects = when (val m = readManifest(context, uri)) {
            is Outcome.Failure -> return "Манифест не читается: ${m.errors.firstOrNull()?.message}".also { log.warn(TAG, it) }
            is Outcome.Success -> m.value
        }
        // Без собранного контура о ключах ничего не узнать (брони сняло бы по сроку) — и задачу не поставить.
        val orchestrator = model.liveOrchestrator() ?: return "Контур не запущен — задачи не ставятся"
        // Неизвестная задача (архив, сброс) места не держит.
        val open: (com.dpsmpx.dpai.core.ids.TaskId) -> Boolean = { id ->
            orchestrator.tasks.tasks[id]?.state?.let { it !in TaskLifecycle.table.terminal } == true
        }
        // Что контур знает о ключе (D-42, D-94): итог исполнения или «ждёт в очереди» — команда политики или человека.
        val known: (String) -> Outcome<com.dpsmpx.dpai.core.ids.TaskId>? = { key ->
            // Сначала очередь, потом итоги: такт пишет итог раньше, чем снимает команду из очереди, —
            // в обратном порядке исполненная между чтениями команда не нашлась бы нигде.
            val waiting = orchestrator.accepted.pending.values.any { (it.command.fields["key"] as? com.dpsmpx.dpai.core.serialization.JsonString)?.value == key }
            orchestrator.operations.byKey[key]?.let { r ->
                if (r.status == com.dpsmpx.dpai.core.operation.OperationStatus.FAILED) {
                    Outcome.failure(r.code ?: com.dpsmpx.dpai.core.diagnostics.DiagCode.INTERNAL_ERROR, "контур отказал в задаче политики", key)
                } else {
                    r.value?.let { com.dpsmpx.dpai.core.ids.IdKinds.TASK.parse(it) }
                        ?: Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.INTERNAL_ERROR, "у команды нет id задачи", key)
                }
            } ?: if (waiting) Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.COMMAND_QUEUED, "команда ждёт очереди контура", key) else null
        }
        val r = when (val p = auto.pass(projects, setOf(project), open, model::addTask, known)) {
            is Outcome.Failure -> return "След политики не читается: ${p.errors.firstOrNull()?.message}".also { log.error(TAG, it) }
            is Outcome.Success -> p.value
        }
        val titles = projects.flatMap { p -> p.suggestions.map { s -> "${p.key}/${s.key}" to s.title } }.toMap()
        val at = Clock.SYSTEM.now()
        for (e in r.queued) log.info(TAG, "по политике ${e.policy}: ${e.project}/${e.suggestion} — ждёт очереди контура")
        for (e in r.placed) {
            val task = e.task ?: continue
            val title = titles["${e.project}/${e.suggestion}"] ?: e.suggestion
            log.info(TAG, "по политике ${e.policy}: ${e.project}/${e.suggestion} → ${task.value}")
            notifier.show(
                Notification(
                    "ecosystem-auto:${e.key}", NotificationCategory.WARNING, "Поставлена по политике",
                    "«$title» — ${e.project}/${e.suggestion}. Без подтверждения: по политике прогона.",
                    DeepLink.of(DeepLink.Kind.TASK, task), 1, at, at,
                ),
            )
        }
        r.failures.forEach { log.warn(TAG, "задача политики не поставлена: ${it.message}") }
        return buildString {
            append("Поставлено по политике: ${r.placed.size}")
            if (r.queued.isNotEmpty()) append("; ждут очереди контура: ${r.queued.size}")
            if (r.refused.isNotEmpty()) append("; политика не пропустила: ${r.refused.size}")
            if (r.failures.isNotEmpty()) append("; отказы: ${r.failures.joinToString("; ") { it.message }}")
        }
    }

    /** Политика словами — для экрана и окна включения. */
    fun describe(p: AutoPolicy): String =
        "проекты: ${p.projects.sorted().joinToString()}; виды работ: ${p.operations.sorted().joinToString()}; " +
            "приоритеты: ${p.priorities.sortedBy { it.ordinal }.joinToString { it.name }}; " +
            "не больше ${p.maxPerDay} за 24 часа и ${p.maxOpen} открытых сразу; " +
            (p.until?.let { "до ${stamp(it)}" } ?: "без срока")

    internal fun stamp(t: Timestamp): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t.epochMillis))
}

/**
 * Раздел политики в окне «Экосистема» (D-106): состояние, «Включить…» —
 * через окно с перечнем разрешённого, «Выключить», «Пройти сейчас», след.
 */
@Composable
internal fun AutoPolicySection(model: DemoModel, project: EcosystemProject?, manifestKept: Boolean) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var policy by remember { mutableStateOf<Outcome<AutoPolicy?>?>(null) }
    var entries by remember { mutableStateOf<List<AutoEntry>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(version) {
        val (p, e) = withContext(Dispatchers.IO) { Controller.get(context.filesDir).ecosystemAuto.let { it.policy() to it.entries() } }
        policy = p
        entries = e.valueOrNull().orEmpty()
    }

    fun run(job: () -> String) {
        busy = true
        scope.launch {
            status = withContext(Dispatchers.IO) { runCatching(job).getOrElse { "Сбой: ${AppLogs.describe(it)}" } }
            busy = false
            version++
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Без подтверждения — политика прогона", style = MaterialTheme.typography.labelMedium)
        val current = (policy as? Outcome.Success)?.value
        Text(
            when (val p = policy) {
                null -> "…"
                is Outcome.Failure -> "Политика не читается (${p.errors.firstOrNull()?.message}) — задачи не ставятся; «Включить…» заменит её."
                is Outcome.Success -> p.value?.let {
                    if (it.expired(Clock.SYSTEM.now())) "Срок истёк — задачи не ставятся: ${EcosystemAuto.describe(it)}." else "Включена: ${EcosystemAuto.describe(it)}."
                }
                    ?: "Выключена: каждая задача — после «Поставить задачу…»."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (!manifestKept) {
            Text("Проход без окна читает манифест, выбранный здесь: выберите его, чтобы включить политику.", style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = !busy && project != null && manifestKept, onClick = { editing = true }) { Text(if (current == null) "Включить…" else "Изменить…") }
            if (current != null) {
                TextButton(enabled = !busy, onClick = { run { EcosystemAuto.pass(context, model, Notifier(context), AppLogs.get(context)) } }) { Text("Пройти сейчас") }
                TextButton(enabled = !busy, onClick = {
                    run {
                        when (val r = Controller.get(context.filesDir).ecosystemAuto.disable()) {
                            is Outcome.Success -> "Политика выключена".also { AppLogs.get(context).info("экосистема", "политика прогона выключена человеком") }
                            is Outcome.Failure -> "Не выключилась: ${r.errors.firstOrNull()?.message}"
                        }
                    }
                }) { Text("Выключить") }
            }
        }
        for (e in entries.takeLast(5).asReversed()) {
            Text("${EcosystemAuto.stamp(e.at)} · ${e.project}/${e.suggestion} → ${e.task?.value ?: "в очереди контура"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (editing && project != null) {
        AutoPolicyDialog(project, (policy as? Outcome.Success)?.value, onDismiss = { editing = false }) { p ->
            editing = false
            run {
                when (val r = Controller.get(context.filesDir).ecosystemAuto.enable(p)) {
                    is Outcome.Failure -> "Не включилась: ${r.errors.firstOrNull()?.message}"
                    is Outcome.Success -> {
                        val log = AppLogs.get(context)
                        log.info("экосистема", "политика прогона включена человеком (${p.digest()}): ${EcosystemAuto.describe(p)}")
                        EcosystemAuto.pass(context, model, Notifier(context), log)
                    }
                }
            }
        }
    }
}

/**
 * Окно политики (D-106): рамки — выбором, затем перечень того, что она
 * разрешает, и «Включить». По умолчанию — только `docs`, обычные и низкие
 * приоритеты, 3 задачи за 24 часа, одна открытая, на неделю (T19).
 */
@Composable
private fun AutoPolicyDialog(project: EcosystemProject, current: AutoPolicy?, onDismiss: () -> Unit, onEnable: (AutoPolicy) -> Unit) {
    val operations = remember(project) { (project.suggestions.map { it.operation } + DOCS + current?.operations.orEmpty()).toSortedSet().toList() }
    var ops by remember { mutableStateOf(current?.operations ?: setOf(DOCS)) }
    var priorities by remember { mutableStateOf(current?.priorities ?: setOf(Priority.NORMAL, Priority.LOW, Priority.BACKGROUND)) }
    var perDay by remember { mutableIntStateOf(current?.maxPerDay ?: 3) }
    var open by remember { mutableIntStateOf(current?.maxOpen ?: 1) }
    // «Изменить…» не сбрасывает срок молча: по умолчанию — как сейчас (KEEP), если он ещё не прошёл.
    val keep = current?.until?.takeIf { it > Clock.SYSTEM.now() }
    var days by remember { mutableIntStateOf(if (current == null) 7 else if (current.until == null) 0 else if (keep != null) KEEP else 7) }
    var confirming by remember { mutableStateOf<AutoPolicy?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirming == null) "Политика прогона" else "Включить политику?") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val c = confirming
                if (c != null) {
                    Text(
                        "Контур будет сам, без «Поставить», ставить задачи из манифеста — ${EcosystemAuto.describe(c)}.\n\n" +
                            "Цели этих задач вы не прочитаете до постановки: о каждой — уведомление и запись здесь. " +
                            "Действия вне приложения (пуш, PR, слияние) по-прежнему спрашивают подтверждение.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("Проект: ${project.name} (${project.key})", style = MaterialTheme.typography.bodySmall)
                    Text("Виды работ", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (o in operations) FilterChip(selected = o in ops, onClick = { ops = if (o in ops) ops - o else ops + o }, label = { Text(o) })
                    }
                    Text("Приоритеты", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (p in Priority.entries) FilterChip(selected = p in priorities, onClick = { priorities = if (p in priorities) priorities - p else priorities + p }, label = { Text(p.name) })
                    }
                    Text("Задач за 24 часа", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (n in listOf(1, 3, 5, 10, AutoPolicy.MAX_PER_DAY)) FilterChip(selected = perDay == n, onClick = { perDay = n }, label = { Text("$n") })
                    }
                    Text("Открытых сразу", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (n in 1..AutoPolicy.MAX_OPEN) FilterChip(selected = open == n, onClick = { open = n }, label = { Text("$n") })
                    }
                    Text("Срок", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val choices = listOfNotNull(keep?.let { "Как сейчас (до ${EcosystemAuto.stamp(it)})" to KEEP }) + listOf("День" to 1, "Неделя" to 7, "Месяц" to 30, "Без срока" to 0)
                        for ((label, d) in choices) FilterChip(selected = days == d, onClick = { days = d }, label = { Text(label) })
                    }
                    val problem = draft(project, ops, priorities, perDay, open, days, keep).problem()
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            val c = confirming
            if (c != null) {
                TextButton(onClick = { onEnable(c) }) { Text("Включить") }
            } else {
                val d = draft(project, ops, priorities, perDay, open, days, keep)
                TextButton(enabled = d.problem() == null, onClick = { confirming = d }) { Text("Далее") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Выбор срока «как сейчас». */
private const val KEEP = -1

private fun draft(project: EcosystemProject, ops: Set<String>, priorities: Set<Priority>, perDay: Int, open: Int, days: Int, keep: Timestamp?): AutoPolicy =
    AutoPolicy(setOf(project.key), ops, priorities, perDay, open, if (days == KEEP) keep else if (days > 0) Clock.SYSTEM.now() + days.days else null)
