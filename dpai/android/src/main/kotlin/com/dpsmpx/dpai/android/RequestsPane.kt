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
import com.dpsmpx.dpai.ui.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes

/**
 * Вкладка «Запросы» (D-44): ждущие запросы с вариантами ответа. Окно поверх
 * приложений — в «Настройках AI» ([OverlayCard], AI:A-3); проверка канала —
 * там же, «Журнал» → «Отладка» ([ProbePanel]).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RequestsPane(model: DemoModel, snapshot: Snapshot?) {
    val requests = snapshot?.requests.orEmpty()
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(if (requests.isEmpty()) "Ждущих запросов нет" else "Ждут ответа: ${requests.size}", style = MaterialTheme.typography.titleMedium)
            if (snapshot?.live == false) Text("Демо: запросы живут до перезапуска приложения", style = MaterialTheme.typography.labelSmall)
        }
        items(requests, key = { it.id.value }) { RequestItem(model, it) }
    }
}

/** Окно поверх приложений (D-44): выключатель и разрешение системы — в «Настройках AI» (AI:A-3). */
@Composable
internal fun OverlayCard() {
    val context = LocalContext.current.applicationContext
    var overlayOn by remember { mutableStateOf(RequestOverlay.enabled(context)) }
    var permitted by remember { mutableStateOf(RequestOverlay.permitted(context)) }
    // Разрешение даётся в настройках системы: перечитать, когда человек вернулся (раз в 2 с, пока карточка видна).
    Poll(periodMs = 2_000) { permitted = RequestOverlay.permitted(context) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Окно поверх приложений", fontWeight = FontWeight.Bold)
            SwitchRow(overlayOn, onChange = {
                overlayOn = it
                RequestOverlay.setEnabled(context, it)
            }) {
                Text("Показывать запросы окном", Modifier.weight(1f))
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

/**
 * Карточка ждущего запроса (D-44): на «Запросах», а также над чатом сессии и
 * в листе задачи (AI:A-7) — касание уведомления сразу показывает все
 * варианты, а не только первые три.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun RequestItem(model: DemoModel, card: RequestCard) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var text by remember(card.id) { mutableStateOf("") }
    var confirming by remember(card.id) { mutableStateOf<AnswerButton?>(null) }
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
            fun answer(b: AnswerButton) {
                if (b.needsText && text.isBlank()) {
                    status = "Нужен текст ответа"
                    return
                }
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Answers.apply(context, card.id.value, b.uri, text.takeIf { b.needsText }, b.label, "app") }
                    status = when {
                        r is Outcome.Success -> "Принято"
                        Answers.queued(r) -> "Принято: применится после текущего шага контура"
                        r is Outcome.Failure -> "Не принято: ${r.diagnostics.first().message}"
                        else -> null
                    }
                    busy = false
                }
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (b in card.buttons) {
                    // Опасное (D-44) — окном с тем, на что отвечаем, а не вторым нажатием (AI:A-4).
                    // Нужен текст — сказать до окна: подтверждать «нельзя отменить», а потом узнать про текст — нелепо (ревью PR #170).
                    AnswerChip(b, enabled = !busy) {
                        when {
                            b.needsText && text.isBlank() -> status = "Нужен текст ответа"
                            b.confirm -> confirming = b
                            else -> answer(b)
                        }
                    }
                }
            }
            confirming?.let { b ->
                ConfirmDialog(
                    "«${b.label}»?",
                    "${card.title}\n\nОтвет нельзя отменить: контур выполнит его сразу.",
                    b.label,
                    onConfirm = { confirming = null; answer(b) },
                    onDismiss = { confirming = null },
                )
            }
            status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (it.startsWith("Принято")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * Вариант ответа — по роли (AI:A-8): «принять» — заливная кнопка, главное
 * действие; нейтральные — контурные; опасный — контурный цветом ошибки
 * (подтверждение — окном, AI:A-4).
 */
@Composable
private fun AnswerChip(b: AnswerButton, enabled: Boolean, onClick: () -> Unit) {
    when {
        b.confirm || b.tone == Tone.ERROR -> OutlinedButton(onClick = onClick, enabled = enabled, colors = ButtonDefaults.outlinedButtonColors(contentColor = toneColor(Tone.ERROR))) { Text(b.label) }
        b.tone == Tone.ACTIVE -> Button(onClick = onClick, enabled = enabled) { Text(b.label) }
        else -> OutlinedButton(onClick = onClick, enabled = enabled) { Text(b.label) }
    }
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
            SwitchRow(shortTtl, onChange = { shortTtl = it }) {
                Text("Короткий срок (1 мин) — проверить истечение", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
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

