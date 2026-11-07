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
import com.dpsmpx.dpai.providers.ChatEntry
import com.dpsmpx.dpai.providers.ChatRole
import com.dpsmpx.dpai.providers.Conversation
import com.dpsmpx.dpai.providers.EntryStatus
import com.dpsmpx.dpai.providers.ModelInfo
import com.dpsmpx.dpai.providers.ProviderHosts
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
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ModelsStore.init(context) } }
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
    var editing by remember { mutableStateOf<ApiProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    val conv = chats.firstOrNull { it.id.value == open }
    if (conv != null) {
        // Системный «назад» из разговора — к списку «Моделей» (AI:A-2).
        androidx.activity.compose.BackHandler { open = null }
        ChatPane(conv, profiles.firstOrNull { it.id == conv.profile }, running[conv.id]) { open = null }
        return
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Модели по ключу API — для чата. Задачи Claude Code идут по подписке, ключ в них не попадает.",
                style = MaterialTheme.typography.bodySmall,
            )
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { creating = true }) { Text("+ Профиль") }
        }
        if (profiles.isEmpty()) item { Text("Профилей нет: добавьте ключ Anthropic, OpenAI-совместимого провайдера или Gemini.", style = MaterialTheme.typography.bodySmall) }
        items(profiles, key = { "p:${it.id.value}" }) { p ->
            ProfileCard(p, onChat = { open = ModelsStore.newChat(p).id.value }, onEdit = { editing = p })
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
private fun ProfileCard(p: ApiProfile, onChat: () -> Unit, onEdit: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    val hasKey = remember(p) { ModelsStore.hasKey(p.id) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${providerName(p)} · ${p.model.ifEmpty { "модель не выбрана" }}" + if (!hasKey && Providers.keyRequired(p.kind)) " · нет ключа" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = onChat) { Text("Чат") }
                TextButton(onClick = onEdit) { Text("Изменить") }
                // Необратимое — окном (AI:A-4): ключ из Keystore и разговоры профиля не вернуть.
                TextButton(onClick = { asking = true }) { Text("Удалить…", color = MaterialTheme.colorScheme.error) }
            }
            if (asking) {
                ConfirmDialog(
                    "Удалить профиль «${p.name}»?",
                    "Ключ API сотрётся с телефона, разговоры профиля удалятся. Вернуть их нельзя; ключ у провайдера останется действующим.",
                    "Удалить",
                    onConfirm = { asking = false; ModelsStore.deleteProfile(p.id) },
                    onDismiss = { asking = false },
                )
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
    var model by remember { mutableStateOf(existing?.model ?: "") }
    var max by remember { mutableStateOf(existing?.maxOutputTokens?.toString() ?: "") }
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val id = remember { existing?.id ?: ModelsStore.newProfileId() }
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
                            val err = withContext(Dispatchers.IO) { ModelsStore.saveProfile(f.value, typed) }
                            busy = false
                            if (err == null) onDone() else status = err
                        }
                    }
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Отмена") } },
    )
}

private fun kindName(k: ProviderKind): String = when (k) {
    ProviderKind.ANTHROPIC -> "Anthropic (ключ Console)"
    ProviderKind.OPENAI_COMPATIBLE -> "OpenAI-совместимый"
    ProviderKind.GEMINI -> "Google Gemini"
}

/** Чат с моделью профиля: ответ идёт кусками; «Стоп» прерывает его. */
@Composable
private fun ChatPane(c: Conversation, p: ApiProfile?, live: ModelsStore.Running?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable(c.id.value) { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
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
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("←") }
            Column(Modifier.weight(1f)) {
                Text(c.title.ifBlank { "Новый чат" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p?.let { "${it.name} · ${it.model}" } ?: "профиль удалён", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (c.entries.isEmpty() && live == null) item { Text("Напишите вопрос — ответит ${p?.model ?: "модель"}.", style = MaterialTheme.typography.bodySmall) }
            items(c.entries.size) { i -> EntryView(c.entries[i]) }
            live?.let { r -> item { EntryView(ChatEntry(ChatRole.ASSISTANT, r.text.ifEmpty { "…" }, p?.model), streaming = true) } }
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
                Text(annotated(Markdown.render(e.text), code, link), style = MaterialTheme.typography.bodyMedium)
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
