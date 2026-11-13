package com.dpsmpx.dpai.android

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.accounts.ApiProfile
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.lsp.LanguageServers
import com.dpsmpx.dpai.providers.Cancel
import com.dpsmpx.dpai.providers.ChatMessage
import com.dpsmpx.dpai.providers.ChatRequest
import com.dpsmpx.dpai.providers.ChatRole
import com.dpsmpx.dpai.ui.CodeEditorBuffer
import com.dpsmpx.dpai.ui.CompletionSetting
import com.dpsmpx.dpai.ui.EditorCompletion
import com.dpsmpx.dpai.ui.EditorSelection
import com.dpsmpx.dpai.ui.ProjectEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Файл открыт в дереве проекта: ИИ-дополнение берёт настройку [project] (`DPmetaApp:D-133`; D-170). */
internal data class AiCompletionPlace(val model: DemoModel, val project: ProjectEntry)

/** Задаётся рабочим экраном проекта; вне проекта дополнения нет — источник и согласие у проекта. */
internal val LocalAiCompletion = staticCompositionLocalOf<AiCompletionPlace?> { null }

/**
 * Состояние ИИ-дополнения одного файла: запрос — только по кнопке, ответ — предложение
 * до «Вставить». Модель — источник проекта: локальная или облачная с согласием.
 */
internal class AiCompletionState(val place: AiCompletionPlace, private val path: String) {
    var busy by mutableStateOf(false)
        private set
    var note by mutableStateOf<String?>(null)
    var offer by mutableStateOf<Offer?>(null)
        private set
    var settings by mutableStateOf(false)
    private var cancel: Cancel? = null

    /** Предложение: вставить [text] в [cursor], если текст буфера всё ещё [before]. */
    data class Offer(val text: String, val before: String, val cursor: Int)

    suspend fun request(context: Context, buffer: CodeEditorBuffer) {
        // Занято с первого шага (T25): второе быстрое нажатие не отправит второй запрос и не сотрёт отмену первого.
        if (busy) return
        busy = true
        try {
            ask(context, buffer)
        } finally {
            busy = false
            cancel = null
        }
    }

    private suspend fun ask(context: Context, buffer: CodeEditorBuffer) {
        val text = buffer.text
        val selection = buffer.state.selection
        if (selection.start != selection.end) { note = "Снимите выделение: дополнение пишется в место курсора"; return }
        val setting = when (val s = withContext(Dispatchers.IO) { place.model.completion(place.project) }) {
            is Outcome.Failure -> { note = s.diagnostics.first().message; return }
            is Outcome.Success -> s.value
        }
        if (setting.source == CompletionSetting.Source.OFF) { settings = true; return }
        val app = context.applicationContext
        val profile = when (val p = withContext(Dispatchers.IO) {
            ModelsStore.init(app)
            EditorCompletion.profileFor(setting, ModelsStore.profiles.value) { LocalHints.candidates(listOf(it)).isNotEmpty() }
        }) {
            is Outcome.Failure -> { note = p.diagnostics.first().message; return }
            is Outcome.Success -> p.value
        }
        // Сервер телефона не запущен — на его порту может слушать кто угодно (T18): не спрашивать.
        if (LocalModel.isOwn(app, profile) && !LocalModel.usable(profile)) { note = "Сервер llama.cpp на телефоне не запущен: вкладка Linux → «Запустить»"; return }
        val req = EditorCompletion.request(path, LanguageServers.languageId(path), text, selection.end)
        req.problem?.let { note = it; return }
        val c = Cancel()
        cancel = c
        offer = null
        note = "Спрашиваю «${profile.name}»: уходит ${req.sentChars} знаков файла…"
        try {
            val reply = withContext(Dispatchers.IO) {
                ModelsStore.provider(profile).chat(
                    ChatRequest(profile.model, listOf(ChatMessage(ChatRole.USER, req.user)), req.system, minOf(profile.maxOutputTokens ?: EditorCompletion.MAX_OUTPUT_TOKENS, EditorCompletion.MAX_OUTPUT_TOKENS)),
                    c,
                ) {}
            }
            when (reply) {
                is Outcome.Failure -> note = reply.diagnostics.first().message
                is Outcome.Success -> {
                    val usage = "вход ${tokens(reply.value.usage.input)}, ответ ${tokens(reply.value.usage.output)} ток."
                    AppLogs.info(app, "ИИ-дополнение", "${place.project.project.value}: «${profile.name}» (${profile.host}), отправлено ${req.sentChars} знаков, $usage")
                    val insert = EditorCompletion.clean(reply.value.text, req.prefix, req.suffix)
                    if (insert == null) note = "Модель не предложила, что дописать ($usage)"
                    else { offer = Offer(insert, text, selection.end); note = "«${profile.name}»: $usage" }
                }
            }
        } finally {
            cancel = null
        }
    }

    fun stop() { cancel?.cancel() }

    /** Вставить предложение; текст ушёл вперёд — не вставлять. */
    fun accept(buffer: CodeEditorBuffer): Boolean {
        val o = offer ?: return false
        offer = null
        if (buffer.text != o.before) { note = "Текст изменился, пока модель отвечала — повторите"; return false }
        val (next, cursor) = EditorCompletion.apply(o.before, o.cursor, o.text)
        return if (buffer.edit(next, EditorSelection(cursor, cursor))) { note = null; true } else { note = "Текст превышает предел редактора; вставка не применена"; false }
    }

    fun reject() { offer = null; note = null }

    private fun tokens(m: Measure<Long>): String = when (m) {
        is Measure.Known -> m.value.toString()
        is Measure.Unknown -> "?"
    }
}

@Composable
internal fun rememberAiCompletion(path: String): AiCompletionState? {
    val place = LocalAiCompletion.current ?: return null
    val state = remember(place.project.project, path) { AiCompletionState(place, path) }
    // Вкладка закрыта или редактор ушёл — запрос отменяется, лимит модели не тратится зря.
    androidx.compose.runtime.DisposableEffect(state) { onDispose { state.stop() } }
    return state
}

/** Полоса ИИ-дополнения под панелью редактора: ход, предложение, «Вставить» и «Отклонить». */
@Composable
internal fun AiCompletionBar(state: AiCompletionState, buffer: CodeEditorBuffer, enabled: Boolean, onChange: () -> Unit) {
    state.note?.let { Text(it, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall) }
    if (state.busy) TextButton(onClick = state::stop) { Text("Отменить запрос") }
    state.offer?.let { o ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(o.text, Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Row {
                TextButton(enabled = enabled, onClick = { if (state.accept(buffer)) onChange() }) { Text("Вставить") }
                TextButton(onClick = state::reject) { Text("Отклонить") }
            }
        }
    }
    if (state.settings) AiCompletionSettings(state.place, onClose = { state.settings = false })
}

/**
 * Источник ИИ-дополнения проекта: выключено (по умолчанию), локальная модель (телефон
 * или частная сеть) или облачная — только с отметкой согласия на отправку кода этому
 * адресу. Настройка — документ проекта `dpai.editor-completion` v1.
 */
@Composable
internal fun AiCompletionSettings(place: AiCompletionPlace, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var setting by remember { mutableStateOf<CompletionSetting?>(null) }
    var profiles by remember { mutableStateOf<List<ApiProfile>>(emptyList()) }
    var local by remember { mutableStateOf<Set<String>>(emptySet()) }
    var source by remember { mutableStateOf(CompletionSetting.Source.OFF) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var consent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(place.project.project) {
        val (s, all) = withContext(Dispatchers.IO) {
            ModelsStore.init(app)
            place.model.completion(place.project) to ModelsStore.profiles.value
        }
        profiles = all
        local = LocalHints.candidates(all).map { it.id.value }.toSet()
        when (s) {
            is Outcome.Failure -> { error = s.diagnostics.first().message; setting = CompletionSetting.OFF }
            is Outcome.Success -> {
                setting = s.value
                source = s.value.source
                chosen = s.value.profile
                consent = s.value.cloudHost != null && all.firstOrNull { it.id.value == s.value.profile }?.host == s.value.cloudHost
            }
        }
    }
    val choices = when (source) {
        CompletionSetting.Source.OFF -> emptyList()
        CompletionSetting.Source.LOCAL -> profiles.filter { it.id.value in local }
        CompletionSetting.Source.CLOUD -> profiles.filter { it.id.value !in local }
    }
    val picked = choices.firstOrNull { it.id.value == chosen }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("ИИ-дополнение проекта") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Дописывает код в месте курсора по кнопке «ИИ: дописать». Каждый запрос — окно текущего файла вокруг курсора " +
                        "(до ${EditorCompletion.MAX_BEFORE + EditorCompletion.MAX_AFTER} знаков, секреты вырезаются) и расход лимита модели; " +
                        "ответ вставляется только по «Вставить».",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (setting == null) Text("Загрузка…")
                for (s in CompletionSetting.Source.entries) Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(source == s, onClick = { source = s; chosen = null; consent = false })
                    Text(s.title)
                }
                if (source != CompletionSetting.Source.OFF && choices.isEmpty()) {
                    Text(
                        if (source == CompletionSetting.Source.LOCAL) "Нет локальной модели: запустите llama.cpp во вкладке Linux или добавьте профиль своего сервера (Ollama, llama.cpp) во вкладке «Модели»."
                        else "Нет облачных профилей: добавьте API-профиль во вкладке «Модели».",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                for (p in choices) Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(chosen == p.id.value, onClick = { chosen = p.id.value; consent = false })
                    Text("${p.name} · ${p.model} · ${p.host}", style = MaterialTheme.typography.bodyMedium)
                }
                if (source == CompletionSetting.Source.CLOUD && picked != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(consent, onCheckedChange = { consent = it })
                    Text("Разрешаю отправлять код проекта «${place.project.project.value}» (окно открытого файла) на ${picked.host} — провайдеру профиля «${picked.name}»", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            val next = when (source) {
                CompletionSetting.Source.OFF -> CompletionSetting.OFF
                CompletionSetting.Source.LOCAL -> picked?.let { CompletionSetting(source, it.id.value) }
                CompletionSetting.Source.CLOUD -> picked?.takeIf { consent }?.let { CompletionSetting(source, it.id.value, it.host) }
            }
            TextButton(enabled = setting != null && next != null, onClick = {
                val value = next ?: return@TextButton
                scope.launch {
                    when (val r = withContext(Dispatchers.IO) { place.model.saveCompletion(place.project, value) }) {
                        is Outcome.Failure -> error = r.diagnostics.first().message
                        is Outcome.Success -> {
                            AppLogs.info(app, "ИИ-дополнение", "${place.project.project.value}: источник — ${value.source.title}" + (value.cloudHost?.let { ", согласие на отправку кода: $it" } ?: ""))
                            onClose()
                        }
                    }
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}
