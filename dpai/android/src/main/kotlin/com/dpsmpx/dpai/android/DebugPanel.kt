package com.dpsmpx.dpai.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.lsp.dap.DebugStatus

/**
 * Панель отладки (`DPmetaApp:D-124`; D-159): состояние словами, «Продолжить», «Шаг»,
 * «Войти», «Выйти», «Пауза», «Остановить»; стек (касание — кадр и его переменные),
 * переменные, вычисление выражения, вывод программы, точки останова. Пока отладка
 * не начиналась — не видна.
 */
@Composable
internal fun DebugPanel(modifier: Modifier = Modifier) {
    val state by Debugger.state.collectAsState()
    val marks by Debugger.breakpoints.collectAsState()
    if (state.phase == Debugger.Phase.IDLE) return
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(PAGE_STACK) }
    var expression by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    val view = state.view
    val status = view.status
    val stopped = status is DebugStatus.Stopped
    val running = state.phase == Debugger.Phase.ACTIVE && status is DebugStatus.Running
    Surface(modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Отладка · " + (state.target?.engine?.title ?: "") + " · " + statusText(state),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { Debugger.close() }) { Text(if (state.active) "Закрыть" else "Скрыть") }
            }
            state.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (state.missing != null) {
                OutlinedButton(enabled = !state.busy, onClick = { Debugger.install(context) }) { Text("Поставить ${state.missing?.title?.substringBefore(" ")}") }
            }
            if (state.active) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(enabled = stopped, onClick = Debugger::resume) { Text("Продолжить") }
                OutlinedButton(enabled = stopped, onClick = Debugger::next) { Text("Шаг") }
                OutlinedButton(enabled = stopped, onClick = Debugger::stepIn) { Text("Войти") }
                OutlinedButton(enabled = stopped, onClick = Debugger::stepOut) { Text("Выйти") }
                OutlinedButton(enabled = running, onClick = Debugger::pause) { Text("Пауза") }
                OutlinedButton(onClick = Debugger::stop) { Text("Остановить", color = MaterialTheme.colorScheme.error) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((key, title) in listOf(PAGE_STACK to "Стек", PAGE_VARS to "Переменные", PAGE_EVAL to "Выражение", PAGE_OUT to "Вывод", PAGE_MARKS to "Точки · ${marks.count}")) {
                    FilterChip(page == key, { page = key }, label = { Text(title) })
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                when (page) {
                    PAGE_STACK -> {
                        if (view.frames.isEmpty()) item { Hint(if (stopped) "Стек читается…" else "Стек — когда программа остановлена.") }
                        items(view.frames.size) { i ->
                            val f = view.frames[i]
                            val place = f.path?.let { "${it.substringAfterLast('/')}:${f.line}" } ?: "без исходника"
                            Text(
                                (if (f.id == view.frame || (view.frame == null && i == 0)) "▸ " else "  ") + f.name + " — " + place,
                                Modifier.fillMaxWidth().clickable(enabled = stopped) { Debugger.selectFrame(f.id); page = PAGE_VARS }.padding(vertical = 6.dp),
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    PAGE_VARS -> {
                        if (view.variables.isEmpty()) item { Hint(if (stopped) "Переменных нет или они читаются…" else "Переменные — когда программа остановлена.") }
                        items(view.variables.size) { i ->
                            val v = view.variables[i]
                            Text(
                                v.name + (v.type?.let { ": $it" } ?: "") + " = " + v.value,
                                Modifier.fillMaxWidth().padding(vertical = 4.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    PAGE_EVAL -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(expression, { if (it.length <= 1000) expression = it }, Modifier.weight(1f), label = { Text("Выражение языка программы") }, singleLine = true)
                                TextButton(enabled = stopped && expression.isNotBlank(), onClick = {
                                    Debugger.evaluate(expression) { r -> result = when (r) { is Outcome.Success -> r.value; is Outcome.Failure -> "Ошибка: " + r.diagnostics.first().message } }
                                }) { Text("Вычислить") }
                            }
                            result?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    PAGE_OUT -> {
                        val lines = if (view.output.isEmpty() && state.build.isNotEmpty()) state.build else view.output
                        if (lines.isEmpty()) item { Hint("Вывода программы пока нет.") }
                        items(lines.size) { i -> Text(lines[i], fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        if (state.problems.isNotEmpty()) item { Hint("Ошибки сборки: ${state.problems.size} — откройте файл и строку из списка ошибок запуска.") }
                    }
                    PAGE_MARKS -> {
                        if (marks.count == 0) item { Hint("Точек нет: коснитесь номера строки в файле C, C++ или Python.") }
                        for ((path, lines) in marks.byFile) {
                            val accepted = view.breakpoints[path].orEmpty()
                            items(lines.sorted().size) { i ->
                                val line = lines.sorted()[i]
                                val ok = accepted.firstOrNull { it.line == line }
                                val mark = when {
                                    ok == null -> ""
                                    ok.verified -> " ✓"
                                    else -> " — не встала" + (ok.message?.let { ": $it" } ?: "")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${path.substringAfterLast('/')}:$line$mark", Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = { Debugger.toggle(path, line) }, modifier = Modifier.semantics { contentDescription = "Убрать точку $line" }) { Text("Убрать") }
                                }
                            }
                        }
                        if (marks.count > 0) item { TextButton(onClick = Debugger::clearBreakpoints) { Text("Убрать все") } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun statusText(state: Debugger.State): String = when (state.phase) {
    Debugger.Phase.IDLE -> ""
    Debugger.Phase.CHECKING -> "проверка"
    Debugger.Phase.BUILDING -> "сборка"
    Debugger.Phase.STARTING -> "запуск"
    Debugger.Phase.ACTIVE, Debugger.Phase.ENDED -> when (val s = state.view.status) {
        DebugStatus.Starting -> if (state.phase == Debugger.Phase.ENDED) "не началась" else "запуск"
        DebugStatus.Running -> "идёт"
        is DebugStatus.Stopped -> "остановлена: " + reason(s.reason) + (state.view.location?.let { l -> l.path?.let { " · ${it.substringAfterLast('/')}:${l.line}" } } ?: "")
        is DebugStatus.Exited -> "программа завершилась" + (s.code?.let { ", код $it" } ?: "")
        is DebugStatus.Failed -> "сбой: ${s.message}"
    }
}

private fun reason(r: String): String = when (r) {
    "breakpoint" -> "точка останова"
    "step" -> "шаг"
    "pause" -> "пауза"
    "exception" -> "исключение"
    "entry" -> "начало"
    else -> r
}

private const val PAGE_STACK = "stack"
private const val PAGE_VARS = "vars"
private const val PAGE_EVAL = "eval"
private const val PAGE_OUT = "out"
private const val PAGE_MARKS = "marks"
