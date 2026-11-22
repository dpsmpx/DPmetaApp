package com.dpsmpx.dpai.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.dpsmpx.dpai.accounts.ApiPresets
import com.dpsmpx.dpai.accounts.ApiProfile
import com.dpsmpx.dpai.accounts.ApiProfiles
import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.providers.AdminReports
import com.dpsmpx.dpai.providers.ChatEntry
import com.dpsmpx.dpai.providers.ChatRole
import com.dpsmpx.dpai.providers.Conversation
import com.dpsmpx.dpai.providers.EntryStatus
import com.dpsmpx.dpai.providers.ModelInfo
import com.dpsmpx.dpai.providers.ProviderHosts
import com.dpsmpx.dpai.providers.UsageText
import com.dpsmpx.dpai.providers.Providers
import com.dpsmpx.dpai.ui.Markdown
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Вкладка «Модели» (D-98): API-профили по ключу и чат с моделью. Задачи
 * оркестратора по-прежнему исполняет Claude Code по подписке (D-29) —
 * профили для них не выбираются.
 */
@Composable
fun ModelsPane() {
    val context = LocalContext.current
    val ready by ModelsStore.ready.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ModelsStore.init(context)
            ProfileMarks.init(context)
        }
    }
    if (!ready) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator() }
        return
    }
    val profiles by ModelsStore.profiles.collectAsState()
    val chats by ModelsStore.chats.collectAsState()
    val undo = LocalUndo.current
    val running by ModelsStore.running.collectAsState()
    val problem by ModelsStore.problem.collectAsState()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val requestedChat by ApiQuickStartRequests.chat.collectAsState()
    LaunchedEffect(requestedChat) { requestedChat?.let { open = it; ApiQuickStartRequests.chat.value = null } }
    LaunchedEffect(Unit) { if (!ApiQuickStartRequests.seen(context)) ApiQuickStartRequests.request() }
    var usageOf by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ApiProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    val conv = chats.firstOrNull { it.id.value == open }
    if (conv != null) {
        // Системный «назад» из разговора — к списку «Моделей» (AI:A-2).
        androidx.activity.compose.BackHandler { open = null }
        ChatPane(conv, profiles.firstOrNull { it.id == conv.profile }, running[conv.id]) { open = null }
        return
    }
    profiles.firstOrNull { it.id.value == usageOf }?.let { p ->
        androidx.activity.compose.BackHandler { usageOf = null }
        UsagePane(p) { usageOf = null }
        return
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                androidx.compose.ui.res.stringResource(R.string.api_work_models_intro),
                style = MaterialTheme.typography.bodySmall,
            )
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            androidx.compose.material3.Button(onClick = { ApiQuickStartRequests.request() }) { Text(androidx.compose.ui.res.stringResource(R.string.api_work_title)) }
            // «Добавить профиль» — заметной кнопкой, а не текстом под пояснением (AI:A-26).
            androidx.compose.material3.FilledTonalButton(onClick = { creating = true }, modifier = Modifier.padding(top = 4.dp)) { Text("Добавить профиль") }
        }
        if (profiles.isEmpty()) item { Text("Профилей нет: добавьте ключ Anthropic, OpenAI-совместимого провайдера или Gemini.", style = MaterialTheme.typography.bodySmall) }
        items(profiles, key = { "p:${it.id.value}" }) { p ->
            ProfileCard(p, onChat = { open = ModelsStore.newChat(p).id.value }, onEdit = { editing = p }, onUsage = { usageOf = p.id.value })
        }
        // Локальная модель на телефоне (D-105) — здесь, рядом с профилем «Телефон: llama.cpp» (`DP_AI_Orcestrator:D-113`).
        item(key = "local-model") {
            androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    LocalModelSection()
                    Text("Нужен Linux в приложении: «Настройки AI» → «Linux в приложении».", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        // Локальный ИИ (D-105): какой профиль на этом устройстве или в частной сети спрашивать о подсказках.
        val local = LocalHints.candidates(profiles)
        if (local.isNotEmpty()) {
            item {
                var chosen by remember(profiles) { mutableStateOf<com.dpsmpx.dpai.core.ids.AccountId?>(null) }
                // Выбор читается из настроек в фоне, не на главном потоке.
                LaunchedEffect(profiles) { chosen = withContext(Dispatchers.IO) { LocalHints.chosen(context, profiles)?.id } }
                Text("Локальный ИИ — подсказки на сводке", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in local) FilterChip(selected = chosen == p.id, onClick = { LocalHints.choose(context, p); chosen = p.id }, label = { Text(p.name) })
                }
            }
        }
        if (chats.isNotEmpty()) item { Text("Разговоры", style = MaterialTheme.typography.titleMedium) }
        // Удалённый, но ещё не подтверждённый снэкбаром — не виден (AI:A-4).
        items(chats.reversed().filter { undo?.hidden?.containsKey("chat:${it.id.value}") != true }, key = { "c:${it.id.value}" }) { c ->
            val p = profiles.firstOrNull { it.id == c.profile }
            ChatRow(c, p, running[c.id] != null) { open = c.id.value }
        }
    }
    if (creating || editing != null) {
        ProfileDialog(editing) {
            creating = false
            editing = null
        }
    }
}

@Composable
private fun ProfileCard(p: ApiProfile, onChat: () -> Unit, onEdit: () -> Unit, onUsage: () -> Unit) {
    val hasKey = remember(p) { ModelsStore.hasKey(p.id) }
    val hasAdmin = remember(p) { p.kind == ProviderKind.ANTHROPIC && ModelsStore.hasAdminKey(p.id) }
    val marks by ProfileMarks.colors.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            // Цвет профиля — кружком у имени (`DPmetaApp:D-83`), не фоном и не текстом.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProfileDot(com.dpsmpx.dpai.ui.ProfileColors.of(marks[p.id.value]))
                Text(p.name, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "${providerName(p)} · ${p.model.ifEmpty { "модель не выбрана" }}" + if (!hasKey && Providers.keyRequired(p.kind)) " · нет ключа" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = { ApiQuickStartRequests.request(profile = p.id.value) }) { Text(androidx.compose.ui.res.stringResource(R.string.api_work_start)) }
                TextButton(onClick = onChat) { Text("Чат") }
                // «Удалить» — в окне «Изменить», а не рядом с «Чат» (AI:A-26).
                TextButton(onClick = onEdit) { Text("Изменить") }
                // Расход и лимиты — отчёты по Admin-ключу, только чтение (`DPmetaApp:D-44`).
                if (hasAdmin) TextButton(onClick = onUsage) { Text("Расход") }
            }
        }
    }
}

@Composable
private fun ChatRow(c: Conversation, p: ApiProfile?, live: Boolean, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.title.ifBlank { "Новый чат" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (p?.name ?: "профиль удалён") + " · реплик: ${c.entries.size}" + if (live) " · отвечает…" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Обратимое — сразу и со снэкбаром «Отменить» (AI:A-4); удаляется, когда снэкбар ушёл. Пустой — сразу:
        // терять нечего, а «Чат» профиля переиспользует пустой разговор и открыл бы ждущий удаления (ревью PR #170).
        val undo = LocalUndo.current
        TextButton(onClick = {
            if (undo != null && c.entries.isNotEmpty()) undo.run("chat:${c.id.value}", "Разговор удалён") { ModelsStore.deleteChat(c.id) } else ModelsStore.deleteChat(c.id)
        }, modifier = Modifier.semantics { contentDescription = "Удалить разговор" }) { Text("✕") }
    }
}

private fun providerName(p: ApiProfile): String = when (p.kind) {
    ProviderKind.ANTHROPIC -> "Anthropic"
    ProviderKind.GEMINI -> "Gemini"
    ProviderKind.OPENAI_COMPATIBLE -> ApiPresets.OPENAI_COMPATIBLE.firstOrNull { it.baseUrl == p.baseUrl }?.name ?: p.host
}

/**
 * Профиль: провайдер, адрес, ключ, модель. Окно с ключом закрыто от
 * снимков и превью «недавних» (D-86): у диалога своё окно — флаг ему.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProfileDialog(existing: ApiProfile?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(existing?.kind ?: ProviderKind.ANTHROPIC) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: ApiPresets.OPENAI_COMPATIBLE.first().baseUrl) }
    var custom by remember { mutableStateOf(existing?.customHostAllowed ?: false) }
    var key by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf("") }
    var adminStatus by remember { mutableStateOf<String?>(null) }
    var adminRemove by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(existing?.model ?: "") }
    var max by remember { mutableStateOf(existing?.maxOutputTokens?.toString() ?: "") }
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val id = remember { existing?.id ?: ModelsStore.newProfileId() }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Цвет — отметкой, не полем профиля (`DPmetaApp:D-83`): пишется вместе с сохранением.
    var color by remember { mutableStateOf(ProfileMarks.colors.value[id.value]) }
    var asking by remember { mutableStateOf(false) }
    if (asking && existing != null) {
        ConfirmDialog(
            "Удалить профиль «${existing.name}»?",
            "Ключ API сотрётся с телефона, разговоры профиля удалятся. Вернуть их нельзя; ключ у провайдера останется действующим.",
            "Удалить",
            onConfirm = {
                asking = false
                ModelsStore.deleteProfile(existing.id)
                onDone()
            },
            onDismiss = { asking = false },
        )
    }
    val host = runCatching { URI(baseUrl.trim()).host?.lowercase() }.getOrNull()
    val ownHost = kind == ProviderKind.OPENAI_COMPATIBLE && host != null && host !in ProviderHosts.BUILT_IN

    fun form(): Outcome<ApiProfile> {
        val m = max.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: -1 }
        return ApiProfiles.form(id, name.ifBlank { kindName(kind) }, kind, baseUrl, model, m, custom)
    }

    AlertDialog(
        onDismissRequest = onDone,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(if (existing == null) "Новый профиль" else "Профиль") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in ProviderKind.entries) {
                    Row(Modifier.fillMaxWidth().clickable { kind = k; models = emptyList() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = kind == k, onClick = null)
                        Text(kindName(k), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (kind == ProviderKind.OPENAI_COMPATIBLE) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (pr in ApiPresets.OPENAI_COMPATIBLE) {
                            FilterChip(selected = baseUrl == pr.baseUrl, onClick = { baseUrl = pr.baseUrl; models = emptyList() }, label = { Text(pr.name) })
                        }
                        // Локальные модели (D-105): адрес ПК в частной сети — заменить на свой.
                        for (pr in listOf(ApiPresets.phoneLlama(), ApiPresets.pcOllama(PC_HOST_HINT), ApiPresets.pcLlama(PC_HOST_HINT))) {
                            // Отметку своего сервера ставит человек (D-98): заготовка её не ставит.
                            FilterChip(selected = baseUrl == pr.baseUrl, onClick = { baseUrl = pr.baseUrl; models = emptyList() }, label = { Text(pr.name) })
                        }
                    }
                    OutlinedTextField(baseUrl, { baseUrl = it; models = emptyList() }, label = { Text("Адрес API (…/v1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (ownHost) {
                        Row(Modifier.fillMaxWidth().clickable { custom = !custom }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = custom, onCheckedChange = null)
                            Text(
                                "Разрешить этот адрес ($host): свой сервер. http — только в своей сети, ключ по http — только на этот телефон.",
                                Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Имя профиля") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Цвет — кружок у имени", style = MaterialTheme.typography.labelMedium)
                ProfileColorChips(color) { color = it }
                OutlinedTextField(
                    key, { key = it },
                    label = { Text(if (existing != null && ModelsStore.hasKey(id)) "Ключ API (пусто — прежний)" else if (kind == ProviderKind.OPENAI_COMPATIBLE) "Ключ API (свой сервер — можно без)" else "Ключ API") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(enabled = !busy, onClick = {
                    val f = form()
                    if (f is Outcome.Failure) {
                        status = f.errors.first().message
                        return@TextButton
                    }
                    busy = true
                    status = "проверяю…"
                    val typed = key.trim().takeIf { it.isNotEmpty() }?.let(::Secret)
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { ModelsStore.provider(f.valueOrNull()!!, typed).models() }
                        busy = false
                        when (r) {
                            is Outcome.Success -> {
                                models = r.value
                                status = "ключ принят: моделей — ${r.value.size}"
                                if (model.isBlank() || r.value.none { it.id == model }) ModelsStore.suggest(r.value)?.let { model = it }
                            }
                            is Outcome.Failure -> status = r.errors.joinToString("; ") { "${Words.reason(it.code.name)}: ${it.message}" }
                        }
                    }
                }) { Text("Проверить ключ") }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (models.isNotEmpty()) {
                    Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                        for (m in models) {
                            Row(Modifier.fillMaxWidth().clickable { model = m.id }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = model == m.id, onClick = null)
                                Text(m.name?.let { n -> "$n (${m.id})" } ?: m.id, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                OutlinedTextField(model, { model = it }, label = { Text("Модель") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    max, { max = it.filter(Char::isDigit) },
                    label = { Text("Предел ответа, токенов (пусто — по умолчанию)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Admin-ключ организации Console — только для отчётов расхода и лимитов (`DPmetaApp:D-44`): необязателен.
                if (kind == ProviderKind.ANTHROPIC) {
                    val stored = existing != null && ModelsStore.hasAdminKey(id)
                    OutlinedTextField(
                        admin, { admin = it; adminRemove = false },
                        label = { Text(if (stored) "Admin-ключ (пусто — прежний)" else "Admin-ключ — для расхода (можно без)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Только чтение отчётов: расход, стоимость, лимиты. Ключ организации Console; у личного аккаунта Admin API нет.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row {
                        TextButton(enabled = !busy && admin.isNotBlank(), onClick = {
                            busy = true
                            adminStatus = "проверяю…"
                            val typed = Secret(admin.trim())
                            scope.launch {
                                val err = withContext(Dispatchers.IO) { ModelsStore.checkAdminKey(typed) }
                                busy = false
                                adminStatus = err ?: "Admin-ключ принят: отчёт за сутки получен"
                            }
                        }) { Text("Проверить Admin-ключ") }
                        if (stored) {
                            TextButton(enabled = !busy, onClick = { adminRemove = !adminRemove; admin = "" }) {
                                Text(if (adminRemove) "Не стирать" else "Стереть Admin-ключ", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (adminRemove) Text("Admin-ключ сотрётся с телефона при сохранении.", style = MaterialTheme.typography.bodySmall)
                    adminStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                when (val f = form()) {
                    is Outcome.Failure -> status = f.errors.first().message
                    is Outcome.Success -> {
                        val typed = key.trim().takeIf { it.isNotEmpty() }?.let(::Secret)
                        busy = true
                        scope.launch {
                            val typedAdmin = admin.trim().takeIf { it.isNotEmpty() && kind == ProviderKind.ANTHROPIC }?.let(::Secret)
                            val err = withContext(Dispatchers.IO) {
                                // У иного провайдера Admin-ключа нет: сменили вид — прежний стирается.
                                ModelsStore.saveProfile(f.value, typed) ?: ModelsStore.saveAdminKey(f.value.id, typedAdmin, remove = adminRemove || kind != ProviderKind.ANTHROPIC)
                            }
                            busy = false
                            if (err == null) {
                                ProfileMarks.set(context, id.value, color)
                                onDone()
                            } else {
                                status = err
                            }
                        }
                    }
                }
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                // Необратимое — окном (AI:A-4): ключ из Keystore и разговоры профиля не вернуть.
                if (existing != null) TextButton(onClick = { asking = true }) { Text("Удалить…", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDone) { Text("Отмена") }
            }
        },
    )
}

/**
 * Расход и лимиты API-профиля Anthropic (`DPmetaApp:D-44`, ТЗ DPmetaApp, F-19): стоимость и
 * токены по дням и моделям за 7 или 30 полных суток по UTC и лимиты скорости. Только
 * чтение отчётов Admin API; неизвестное показывается «неизвестно», не нулём (D-13).
 */
@Composable
private fun UsagePane(p: ApiProfile, onBack: () -> Unit) {
    var days by rememberSaveable { mutableIntStateOf(7) }
    var view by remember(p.id) { mutableStateOf<Outcome<ModelsStore.AdminView>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(p.id, days, reload) {
        view = null
        view = withContext(Dispatchers.IO) { ModelsStore.adminReport(p.id, days) }
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onBack) { Text("← Модели") }
                Text("Расход и лимиты — ${p.name}", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in listOf(7, 30)) FilterChip(selected = days == d, onClick = { days = d }, label = { Text("$d дней") })
                }
                Text("Полные сутки по UTC, до вчера. Данные Anthropic приходят с задержкой до суток.", style = MaterialTheme.typography.labelSmall)
            }
        }
        when (val v = view) {
            null -> item(key = "wait") { CircularProgressIndicator() }
            is Outcome.Failure -> item(key = "fail") {
                Column {
                    Text(v.errors.first().let { "${Words.reason(it.code.name)}: ${it.message}" }, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reload++ }) { Text("Повторить") }
                }
            }
            is Outcome.Success -> {
                val r = v.value
                item(key = "total") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Стоимость за период: " + UsageText.usd(AdminReports.total(r.cost).takeIf { r.cost.isNotEmpty() }), style = MaterialTheme.typography.titleSmall)
                            Text(r.refreshedAt?.let { "Данные полны до ${UsageText.stamp(it)}" } ?: "Данных за период ещё нет", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                val totals = AdminReports.totals(r.usage)
                if (totals.isNotEmpty()) item(key = "models-title") { Text("По моделям", style = MaterialTheme.typography.titleSmall) }
                items(totals, key = { "m:${it.model}" }) { m ->
                    Column {
                        Text(m.model ?: "без модели", style = MaterialTheme.typography.bodyMedium)
                        Text(UsageText.tokens(m), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val byDay = AdminReports.days(r.usage, r.cost).reversed()
                if (byDay.isNotEmpty()) item(key = "days-title") { Text("По дням", style = MaterialTheme.typography.titleSmall) }
                items(byDay, key = { "d:${it.day}" }) { d ->
                    Column {
                        Text("${d.day} — ${UsageText.usd(d.usd)}", style = MaterialTheme.typography.bodyMedium)
                        for (m in d.models) {
                            Text("${m.model ?: "без модели"}: ${UsageText.tokens(m)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (r.limits.isNotEmpty()) item(key = "limits-title") { Text("Лимиты скорости", style = MaterialTheme.typography.titleSmall) }
                items(r.limits.size, key = { "l:$it" }) { i ->
                    val l = r.limits[i]
                    Column {
                        Text(l.group.ifEmpty { "группа" }, style = MaterialTheme.typography.bodyMedium)
                        Text(UsageText.limits(l), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun kindName(k: ProviderKind): String = when (k) {
    ProviderKind.ANTHROPIC -> "Anthropic (ключ Console)"
    ProviderKind.OPENAI_COMPATIBLE -> "OpenAI-совместимый"
    ProviderKind.GEMINI -> "Google Gemini"
}

/** Чат с моделью профиля: ответ идёт кусками; «Стоп» прерывает его. */
@Composable
private fun ChatPane(c: Conversation, p: ApiProfile?, live: ModelsStore.Running?, onBack: () -> Unit) {
    EnvironmentApprovalUi(EnvironmentApprovalBroker.chat(c.id.value))
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable(c.id.value) { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var fullAccess by remember(c.id.value) { mutableStateOf(ModelsStore.chatFullAccess(c.id)) }
    var showFullAccess by remember(c.id.value) { mutableStateOf(false) }
    var typedFullAccess by remember(c.id.value) { mutableStateOf("") }
    var authenticating by remember(c.id.value) { mutableStateOf(false) }
    val projectForAccess = c.project?.let { id -> Controller.get(context.filesDir).projects().projects.firstOrNull { it.project == id } }
    val fullAccessName = projectForAccess?.root?.trimEnd('/')?.substringAfterLast('/')
    val fullAccessTarget = projectForAccess?.let { project -> Controller.get(context.filesDir).targets().firstOrNull { it.id == project.target }?.label }
    val fullAccessTitle = androidx.compose.ui.res.stringResource(R.string.chat_full_access_title)
    LaunchedEffect(c.project) { fullAccess = ModelsStore.chatFullAccess(c.id) }
    // Файлы к вопросу (D-99): только выбором человека; текст уходит провайдеру вместе с вопросом.
    var files by remember(c.id.value) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val attach = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                when (val r = withContext(Dispatchers.IO) { readAttachment(context, uri) }) {
                    is Outcome.Success -> {
                        files = files + r.value
                        note = null
                    }
                    is Outcome.Failure -> note = "файл не приложен: ${r.errors.first().message}"
                }
            }
        }
    }
    val list = rememberLazyListState()
    val count = c.entries.size + if (live != null) 1 else 0
    LaunchedEffect(count, live?.text?.length) { if (count > 0) list.scrollToItem(count - 1) }
    if (showFullAccess && fullAccessName != null) AlertDialog(
        onDismissRequest = { if (!authenticating) showFullAccess = false },
        containerColor = MaterialTheme.colorScheme.errorContainer,
        title = { Text(fullAccessTitle) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text(androidx.compose.ui.res.stringResource(R.string.chat_full_access_warning))
                Text(androidx.compose.ui.res.stringResource(R.string.chat_full_access_project, fullAccessName),
                    style = MaterialTheme.typography.titleSmall)
                fullAccessTarget?.let { Text(it) }
                OutlinedTextField(typedFullAccess, { typedFullAccess = it }, singleLine = true,
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.chat_full_access_type, fullAccessName)) })
            }
        },
        confirmButton = {
            TextButton(enabled = !authenticating && typedFullAccess == fullAccessName, onClick = {
                authenticating = true
                DeviceConfirm.ask(context, fullAccessTitle, onOk = {
                    val ok = ModelsStore.enableChatFullAccess(c.id, typedFullAccess)
                    fullAccess = ModelsStore.chatFullAccess(c.id)
                    authenticating = false
                    if (ok && fullAccess) showFullAccess = false
                    else note = context.getString(R.string.chat_full_access_failed)
                }, onDenied = { error -> authenticating = false; note = error })
            }) { Text(androidx.compose.ui.res.stringResource(R.string.chat_full_access_confirm)) }
        },
        dismissButton = {
            TextButton(enabled = !authenticating, onClick = { showFullAccess = false }) {
                Text(androidx.compose.ui.res.stringResource(R.string.chat_full_access_cancel))
            }
        },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("←") }
            Column(Modifier.weight(1f)) {
                Text(c.title.ifBlank { "Новый чат" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p?.let { "${it.name} · ${it.model}" } ?: "профиль удалён", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (c.project == null) TextButton(onClick = { ApiQuickStartRequests.request(profile = c.profile.value) }) { Text(androidx.compose.ui.res.stringResource(R.string.api_work_connect_project)) }
        if (c.project != null) FlowRow(Modifier.fillMaxWidth()) {
            FilterChip(selected = fullAccess, enabled = fullAccess || fullAccessName != null, onClick = {
                if (fullAccess) {
                    ModelsStore.revokeChatFullAccess(c.id)
                    fullAccess = ModelsStore.chatFullAccess(c.id)
                    if (fullAccess) note = context.getString(R.string.chat_full_access_failed)
                } else {
                    typedFullAccess = ""
                    showFullAccess = true
                }
            }, label = { Text(androidx.compose.ui.res.stringResource(
                if (fullAccess) R.string.chat_full_access_on else R.string.chat_full_access_off)) })
            TextButton(enabled = live == null, onClick = { scope.launch {
                val result = withContext(Dispatchers.IO) {
                    val choice = ApiProjectRuntime.choices(context).valueOrNull()?.firstOrNull { it.project == c.project }
                    if (choice == null) Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.STATE_CONFLICT, "проект удалён")
                    else ApiProjectRuntime.prepare(context, choice)
                }
                when (result) {
                    is Outcome.Failure -> note = result.diagnostics.first().message
                    is Outcome.Success -> context.startActivity(MainActivity.workspace(context))
                }
            } }) { Text(androidx.compose.ui.res.stringResource(R.string.api_work_open_project)) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (c.entries.isEmpty() && live == null) item { Text("Напишите вопрос — ответит ${p?.model ?: "модель"}.", style = MaterialTheme.typography.bodySmall) }
            // Ответы только дописываются в конец: ключ — номер (AI:A-25).
            items(c.entries.size, key = { it }) { i -> EntryView(c.entries[i]) }
            live?.let { r -> item {
                if (r.actions.isNotEmpty()) Text(r.actions.takeLast(4).joinToString("\n"), style = MaterialTheme.typography.labelSmall)
                EntryView(ChatEntry(ChatRole.ASSISTANT, r.text.ifEmpty { "…" }, p?.model), streaming = true)
            } }
        }
        note?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (files.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in files) {
                    FilterChip(selected = true, onClick = { files = files - f }, label = { Text("${f.first} ✕", maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            TextButton(enabled = live == null, onClick = { attach.launch(arrayOf("text/*", "application/json", "application/xml", "application/x-sh", "*/*")) }, modifier = Modifier.semantics { contentDescription = "Приложить файл" }) { Text("📎") }
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Вопрос") }, maxLines = 6)
            if (live != null) {
                TextButton(onClick = { ModelsStore.stop(c.id) }) { Text("Стоп") }
            } else {
                TextButton(enabled = (input.isNotBlank() || files.isNotEmpty()) && p != null, onClick = {
                    note = ModelsStore.send(c.id, com.dpsmpx.dpai.files.ChatAttachments.message(input, files))
                    if (note == null) {
                        input = ""
                        files = emptyList()
                    }
                }) { Text("Отправить") }
            }
        }
    }
}

@Composable
private fun EntryView(e: ChatEntry, streaming: Boolean = false) {
    val context = LocalContext.current
    val code = MaterialTheme.colorScheme.surfaceVariant
    val link = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        Text(
            if (e.role == ChatRole.USER) "Вы" else (e.model ?: "модель"),
            style = MaterialTheme.typography.labelMedium,
            color = if (e.role == ChatRole.USER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            if (e.role == ChatRole.ASSISTANT && !streaming) {
                // Разбор Markdown — один раз на текст, а не на каждую перекомпозицию (AI:A-25).
                val tap = rememberLinkTap()
                val shown = remember(e.text, code, link, tap) { annotated(Markdown.render(e.text), code, link, tap) }
                Text(shown, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(e.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val meta = buildList {
            e.note?.let { add(it) }
            if (e.inputTokens != null || e.outputTokens != null) add("токены: ${e.inputTokens ?: "?"} → ${e.outputTokens ?: "?"}")
        }
        if (meta.isNotEmpty()) {
            Text(
                meta.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (e.status == EntryStatus.FAILED || e.status == EntryStatus.REFUSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (e.role == ChatRole.ASSISTANT && !streaming && e.text.isNotEmpty()) {
            TextButton(onClick = { Clipboard.copy(context, "ответ модели", e.text) }) { Text("Копировать", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/**
 * Текстовый файл к вопросу чата (D-99): имя — из провайдера документов,
 * текст — UTF-8 до [com.dpsmpx.dpai.files.ChatAttachments.MAX_BYTES];
 * картинка и двоичный файл — отказ с причиной.
 */
private fun readAttachment(context: android.content.Context, uri: android.net.Uri): Outcome<Pair<String, String>> {
    val max = com.dpsmpx.dpai.files.ChatAttachments.MAX_BYTES
    val name = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: SecurityException) {
        null
    } ?: "файл"
    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { SafTree.readLimited(it, max) }
    } catch (e: java.io.IOException) {
        return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.STORAGE_IO_FAILED, "не прочитан: ${e.javaClass.simpleName}")
    } catch (e: SecurityException) {
        return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.OPERATION_NOT_ALLOWED, "нет доступа: ${e.javaClass.simpleName}")
    } ?: return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.VALUE_INVALID, "больше ${max / 1024} КиБ")
    return com.dpsmpx.dpai.files.TextFile.decode(bytes, max).map { name to it }
}

/** Адрес ПК в заготовках Ollama и llama.cpp (D-105): образец частной сети — человек ставит адрес своего ПК. */
private const val PC_HOST_HINT = "192.168.1.2"
