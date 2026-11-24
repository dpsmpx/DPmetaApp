package com.dpsmpx.dpai.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.dpsmpx.dpai.accounts.Routine
import com.dpsmpx.dpai.accounts.Routines
import com.dpsmpx.dpai.accounts.SubscriptionState
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.ids.SubscriptionId
import com.dpsmpx.dpai.core.result.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Маршруты Claude Code (D-116; `DPmetaApp:D-08`, F-22): имя, адрес `/fire`,
 * токен, подписка, проект и шаблон по умолчанию. Здесь — только завести и
 * удалить: запускает маршрут пульт, действием с ценой и записью в журнал
 * действий (`DPmetaApp:D-07`). Токен — в Keystore, на экране не виден.
 */
@Composable
fun RoutinesPane(model: DemoModel, onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ModelsStore.init(context)
            ProfileMarks.init(context)
        }
    }
    val routines by ModelsStore.routines.collectAsState()
    val marks by ProfileMarks.colors.collectAsState()
    val problem by ModelsStore.problem.collectAsState()
    val subs = remember { model.subscriptions() }
    var editing by remember { mutableStateOf<Routine?>(null) }
    var adding by remember { mutableStateOf(false) }
    if (adding || editing != null) {
        RoutineDialog(editing, subs) { adding = false; editing = null }
    }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) { Text("← Настройки") }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Маршруты Claude Code", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Маршрут открывает облачную сессию Claude по своему токену. Запуск — из пульта: поручение по шаблону, " +
                            "с показом текста до отправки и записью в журнал действий.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    problem?.let { DiagnosticText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { adding = true }) { Text("Добавить маршрут") }
                }
            }
            // Цвет профиля подписки — кружком у её маршрутов (`DPmetaApp:D-83`; ТЗ DPmetaApp, F-17).
            if (subs.isNotEmpty()) {
                item(key = "subs") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Цвет подписок", style = MaterialTheme.typography.titleSmall)
                        for (s in subs) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ProfileDot(com.dpsmpx.dpai.ui.ProfileColors.of(marks[s.id.value]))
                                Text(s.plan?.let { "${s.label} ($it)" } ?: s.label, style = MaterialTheme.typography.bodySmall)
                            }
                            ProfileColorChips(marks[s.id.value]) { ProfileMarks.set(context, s.id.value, it) }
                        }
                    }
                }
            }
            items(routines, key = { it.id.value }) { r ->
                Card(Modifier.fillMaxWidth().clickable { editing = r }) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ProfileDot(com.dpsmpx.dpai.ui.ProfileColors.of(r.subscription?.let { marks[it.value] }))
                            Text(r.name, style = MaterialTheme.typography.titleSmall)
                        }
                        Text(r.trigger, style = MaterialTheme.typography.bodySmall)
                        Text(
                            listOfNotNull(
                                r.project?.let { "проект $it" },
                                r.subscription?.let { id -> subs.firstOrNull { it.id == id }?.let { "подписка ${it.plan ?: it.label}" } ?: "подписка ${id.value}" },
                                r.brief?.let { "шаблон $it" },
                                if (ModelsStore.hasKey(r.id)) null else "нет токена",
                            ).joinToString(" · ").ifEmpty { "без проекта" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineDialog(existing: Routine?, subs: List<SubscriptionState>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.fireUrl ?: "") }
    var token by remember { mutableStateOf("") }
    var project by remember { mutableStateOf(existing?.project ?: "") }
    var brief by remember { mutableStateOf(existing?.brief ?: "") }
    var sub by remember { mutableStateOf<SubscriptionId?>(existing?.subscription) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val id = remember { existing?.id ?: ModelsStore.newProfileId() }
    if (asking && existing != null) {
        ConfirmDialog(
            "Удалить маршрут «${existing.name}»?",
            "Токен сотрётся с телефона. Сам маршрут и его токен в Claude останутся: отозвать токен — в настройках маршрута на claude.ai.",
            "Удалить",
            onConfirm = {
                asking = false
                ModelsStore.deleteRoutine(existing.id)
                onDone()
            },
            onDismiss = { asking = false },
        )
    }
    AlertDialog(
        onDismissRequest = onDone,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(if (existing == null) "Новый маршрут" else "Маршрут") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "На claude.ai: маршрут → новый триггер API → адрес и токен (токен показывается один раз). " +
                        "Промпт маршрута должен явно разрешать выполнять поручение из поля text запуска " +
                        "(оно приходит блоком routine-fire-payload): иначе Claude прочтёт его как данные и не станет действовать.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text("Адрес /fire или id trig_…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    token, { token = it },
                    label = { Text(if (existing != null && ModelsStore.hasKey(id)) "Токен (пусто — прежний)" else "Токен маршрута") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(project, { project = it }, label = { Text("Проект (ключ из реестра, можно пусто)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(brief, { brief = it }, label = { Text("Шаблон по умолчанию (можно пусто)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (subs.isNotEmpty()) {
                    Text("Подписка", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().clickable { sub = null }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = sub == null, onClick = null)
                        Text("не указана", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    for (s in subs) {
                        Row(Modifier.fillMaxWidth().clickable { sub = s.id }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = sub == s.id, onClick = null)
                            Text(s.plan?.let { "${s.label} ($it)" } ?: s.label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                status?.let { DiagnosticText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                when (val f = Routines.form(id, name, url, sub, project, brief)) {
                    is Outcome.Failure -> status = f.errors.first().message
                    is Outcome.Success -> {
                        val typed = token.trim().takeIf { it.isNotEmpty() }?.let(::Secret)
                        if (typed == null && !ModelsStore.hasKey(id)) {
                            status = "нужен токен маршрута"
                            return@TextButton
                        }
                        busy = true
                        scope.launch {
                            val err = withContext(Dispatchers.IO) { ModelsStore.saveRoutine(f.value, typed) }
                            busy = false
                            if (err == null) onDone() else status = err
                        }
                    }
                }
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { asking = true }) { Text("Удалить…", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDone) { Text("Отмена") }
            }
        },
    )
}
