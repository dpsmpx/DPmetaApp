package com.dpsmpx.dpai.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
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
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.ui.ContextView
import com.dpsmpx.dpai.ui.QuickActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Быстрые действия сводки (ТЗ, §73): пауза и продолжение всех — только когда
 * есть что приостановить или продолжить (AI:A-6): «0» не показывается.
 * «Новая задача» — плавающей кнопкой,
 * ждущее человека — списком «Ждёт вас» на сводке, запросы — значком
 * вкладки. Каждое действие — команды задач с ключами нажатия, как в листе
 * задачи (D-42).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun QuickRow(quick: QuickActions, model: DemoModel) {
    val scope = rememberCoroutineScope()
    val keys = remember { IdGenerator(Clock.SYSTEM, RandomSource.secure()) }
    var confirmHold by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    fun run(what: String, cs: List<Command>) {
        busy = true
        scope.launch {
            val rs = withContext(Dispatchers.IO) { model.commandAll(cs) }
            val queued = rs.count { r -> r is Outcome.Failure && r.diagnostics.all { it.code == DiagCode.COMMAND_QUEUED } }
            val refused = rs.filterIsInstance<Outcome.Failure>().filter { r -> r.diagnostics.any { it.code != DiagCode.COMMAND_QUEUED } }
            status = "$what: принято ${rs.size - queued - refused.size} из ${rs.size}" +
                (if (queued > 0) ", в очереди $queued" else "") +
                (refused.firstOrNull()?.let { ", не принято ${refused.size}: ${it.diagnostics.first().message}" } ?: "")
            busy = false
        }
    }
    // Приостанавливать стало нечего — окно подтверждения закрывается и само уже не всплывёт (проверка PR #172).
    val nothingToHold = quick.holdable.isEmpty()
    LaunchedEffect(nothingToHold) { if (nothingToHold) confirmHold = false }
    if (quick.holdable.isNotEmpty() || quick.resumable.isNotEmpty() || status != null) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (quick.holdable.isNotEmpty()) {
                AssistChip(enabled = !busy, onClick = { confirmHold = true }, label = { Text("Пауза всем (${quick.holdable.size})") })
            }
            if (quick.resumable.isNotEmpty()) {
                AssistChip(enabled = !busy, onClick = { run("Продолжить", (model.quickNow() ?: quick).resumeAll(keys.next(IdKinds.OPERATION).value)) }, label = {
                    Text("Продолжить все (${quick.resumable.size})")
                })
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
    if (confirmHold && !nothingToHold) {
        // Пауза останавливает идущие ходы с handoff (D-42) — после подтверждения; ключ серии — один на нажатие «Да».
        AlertDialog(
            onDismissRequest = { confirmHold = false },
            title = { Text("Приостановить задачи: ${quick.holdable.size}?") },
            text = { Text("Идущие ходы остановятся с handoff, новые не начнутся, пока не нажать «Продолжить все» или «Продолжить» у задачи.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmHold = false
                    // Набор задач — в миг нажатия: снимок мог отстать на такт, и новая задача осталась бы без паузы.
                    run("Пауза", (model.quickNow() ?: quick).holdAll(keys.next(IdKinds.OPERATION).value))
                }) { Text("Приостановить") }
            },
            dismissButton = { TextButton(onClick = { confirmHold = false }) { Text("Отмена") } },
        )
    }
}

/** Сколько раз окно «Контекст» пробует прочитать версию, пока идёт такт, и пауза между попытками. */
private const val CONTEXT_TRIES = 10
private const val CONTEXT_RETRY_MS = 1_500L

/**
 * Окно «Контекст» сессии (ТЗ, §74, View context): что видел Claude в
 * последнем ходе задачи — цель, следующее действие, источники и что не
 * вошло. Текст секретных источников не показывается (ТЗ, §65).
 */
@Composable
fun ContextDialog(model: DemoModel, task: TaskId, onDone: () -> Unit) {
    var view by remember { mutableStateOf<ContextView?>(null) }
    var why by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var opened by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(task) {
        // Такт держит замок, пока ходит к target: окно повторяет попытку само, а не просит закрыть и открыть.
        repeat(CONTEXT_TRIES) { attempt ->
            val (v, reason) = withContext(Dispatchers.IO) { model.context(task) }
            view = v
            why = reason
            if (reason != DemoModel.CONTEXT_BUSY || attempt == CONTEXT_TRIES - 1) {
                loading = false
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(CONTEXT_RETRY_MS)
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(view?.title ?: "Контекст") },
        text = {
            val v = view
            when {
                loading -> Text("Загружаю…", style = MaterialTheme.typography.bodySmall)
                v == null -> Text(why ?: "контекста нет", style = MaterialTheme.typography.bodySmall)
                else -> LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item(key = "head") {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Цель", style = MaterialTheme.typography.labelSmall)
                            Text(v.objective, style = MaterialTheme.typography.bodySmall)
                            v.nextAction?.let {
                                Text("Следующее действие", style = MaterialTheme.typography.labelSmall)
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            // Хеш контекста — не для чтения человеком (AI:A-26): под «Подробнее».
                            var hashShown by remember { mutableStateOf(false) }
                            if (hashShown) {
                                Text(v.hash, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            } else {
                                TextButton(onClick = { hashShown = true }) { Text("Подробнее", style = MaterialTheme.typography.labelSmall) }
                            }
                            Text("Источники (${v.sources.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    // Без ключей строк: список не меняется, пока окно открыто.
                    items(v.sources) { s ->
                        Column(
                            Modifier.fillMaxWidth().clickable(enabled = s.text != null) { opened = if (opened == s.key) null else s.key }.padding(vertical = 2.dp),
                        ) {
                            Text(s.key, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 2)
                            Text("${s.level} · ${s.chars} знаков" + (s.hidden?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (opened == s.key) s.text?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
                        }
                    }
                    if (v.omitted.isNotEmpty()) {
                        item(key = "omitted") { Text("Не вошли (${v.omitted.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
                        items(v.omitted) { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Закрыть") } },
    )
}
