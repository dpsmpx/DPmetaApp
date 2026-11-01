package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.notifications.RequestKind
import com.dpsmpx.dpai.ui.AnswerButton
import com.dpsmpx.dpai.ui.RequestCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes

/**
 * Вкладка «Запросы» (D-44): ждущие запросы с вариантами ответа и окно
 * поверх приложений (выключатель и разрешение системы); проверка канала —
 * в «Журнал» → «Отладка» ([ProbePanel]).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RequestsPane(model: DemoModel, snapshot: Snapshot?) {
    val context = LocalContext.current.applicationContext
    var overlayOn by remember { mutableStateOf(RequestOverlay.enabled(context)) }
    var permitted by remember { mutableStateOf(RequestOverlay.permitted(context)) }
    // Разрешение даётся в настройках системы: перечитать с каждым снимком (раз в 1–3 с).
    LaunchedEffect(snapshot) { permitted = RequestOverlay.permitted(context) }
    val requests = snapshot?.requests.orEmpty()
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(if (requests.isEmpty()) "Ждущих запросов нет" else "Ждут ответа: ${requests.size}", style = MaterialTheme.typography.titleMedium)
            if (snapshot?.live == false) Text("Демо: запросы живут до перезапуска приложения", style = MaterialTheme.typography.labelSmall)
        }
        items(requests, key = { it.id.value }) { RequestItem(model, it) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Окно поверх приложений", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Показывать запросы окном", Modifier.weight(1f))
                        Switch(checked = overlayOn, onCheckedChange = {
                            overlayOn = it
                            RequestOverlay.setEnabled(context, it)
                        })
                    }
                    Text(
                        when {
                            !overlayOn -> "Выключено: запросы приходят уведомлением."
                            permitted -> "Разрешение системы есть: окно появится при новом запросе."
                            else -> "Нет разрешения системы: запросы приходят уведомлением. Дайте разрешение «Поверх других приложений»."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (overlayOn && !permitted) {
                        OutlinedButton(onClick = { context.startActivity(RequestOverlay.permissionIntent(context)) }) { Text("Открыть настройку разрешения") }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RequestItem(model: DemoModel, card: RequestCard) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var text by remember(card.id) { mutableStateOf("") }
    var armed by remember(card.id) { mutableStateOf<String?>(null) }
    var status by remember(card.id) { mutableStateOf<String?>(null) }
    var busy by remember(card.id) { mutableStateOf(false) }
    LaunchedEffect(card.id) { withContext(Dispatchers.IO) { model.delivered(card.id, "app") } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${card.kind} · ${card.origin} · срок: ${card.left}", style = MaterialTheme.typography.labelSmall)
            Text(card.title, fontWeight = FontWeight.Bold)
            Text(card.body, style = MaterialTheme.typography.bodySmall)
            if (card.buttons.any { it.needsText }) OutlinedTextField(text, { text = it }, label = { Text("Ответ") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            // С переносом: у запроса бывает четыре-пять вариантов, в одну строку они не влезают (A-7).
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (b in card.buttons) {
                    AnswerChip(b, armed == b.uri, enabled = !busy) {
                        // Опасное — вторым нажатием (D-44).
                        if (b.confirm && armed != b.uri) {
                            armed = b.uri
                            status = "Опасное действие: нажмите «${b.label}» ещё раз"
                            return@AnswerChip
                        }
                        if (b.needsText && text.isBlank()) {
                            status = "Нужен текст ответа"
                            return@AnswerChip
                        }
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { Answers.apply(context, card.id.value, b.uri, text.takeIf { b.needsText }, b.label, "app") }
                            status = when (r) {
                                is Outcome.Success -> "Принято"
                                is Outcome.Failure -> "Не принято: ${r.diagnostics.first().message}"
                            }
                            busy = false
                        }
                    }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun AnswerChip(b: AnswerButton, armed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = if (b.confirm) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
    Button(onClick = onClick, enabled = enabled, colors = colors) { Text(if (armed) "Точно: ${b.label}?" else b.label) }
}

/**
 * Проверка канала запросов (D-44, `docs/DEVICE_CHECK.md`, §8.8): запрос
 * каждого вида, ответ только записывается в журнал. Проверено на Redmi
 * Note 13 — с экрана «Запросы» убрано, живёт в «Журнал» → «Отладка».
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProbePanel(model: DemoModel) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var shortTtl by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Проверка запросов", fontWeight = FontWeight.Bold)
            Text("Проверочный запрос: ответ только записывается в журнал контура.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Короткий срок (1 мин) — проверить истечение", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = shortTtl, onCheckedChange = { shortTtl = it })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in RequestKind.entries) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { model.probe(k, if (shortTtl) 1.minutes else null) }
                            note = when (r) {
                                is Outcome.Success -> "создан ${r.value.value}"
                                is Outcome.Failure -> "не создан: ${r.diagnostics.first().message}"
                            }
                            withContext(Dispatchers.IO) { runCatching { RequestPresenter.sync(context, model, model.pending()) } }
                        }
                    }) { Text(com.dpsmpx.dpai.ui.RequestScreens.kindLabel(k)) }
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

