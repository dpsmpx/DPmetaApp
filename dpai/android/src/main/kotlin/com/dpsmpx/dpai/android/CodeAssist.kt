package com.dpsmpx.dpai.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.lsp.Assist
import com.dpsmpx.dpai.lsp.CodeAction
import com.dpsmpx.dpai.lsp.CompletionItem
import com.dpsmpx.dpai.lsp.LineIndex
import com.dpsmpx.dpai.lsp.Location
import com.dpsmpx.dpai.lsp.LanguageServers
import com.dpsmpx.dpai.lsp.LanguageSession
import com.dpsmpx.dpai.lsp.LspDiagnostic
import com.dpsmpx.dpai.lsp.LspSeverity
import com.dpsmpx.dpai.lsp.PythonServers
import com.dpsmpx.dpai.ui.CodeServerSetup
import com.dpsmpx.dpai.lsp.WorkspaceEdit
import com.dpsmpx.dpai.ui.CodeEditorBuffer
import com.dpsmpx.dpai.ui.EditorSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Семантика C и C++ над полем редактора (DPmetaApp:D-105, D-137): строка
 * службы, полоска вариантов дополнения, подпись параметров, замечания с
 * исправлениями, сведения, переход (в другой файл корня — вкладкой через
 * [onOpen]), использования, переименование, форматирование. Служба
 * включается здесь же кнопкой. Все правки идут в буфер редактора одним
 * шагом отмены; файл меняет только «Сохранить». Правка службы, задевающая
 * другие файлы, не применяется целиком (`Assist.editsForFile`), а
 * переименование — только символа этого исходного файла
 * (`Assist.renameScope`): без фонового индекса служба не видит проекта.
 *
 * [onMarks] получает отрезки для подчёркивания. Они посчитаны для текста,
 * который видела служба: после нового ввода обновятся со следующей
 * диагностикой (доли секунды).
 */
@Composable
internal fun CodeAssist(buffer: CodeEditorBuffer, guestPath: String, guestRoot: String, revision: Int, enabled: Boolean, tabSize: Int, tabs: Boolean, onChange: () -> Unit, onMarks: (List<Assist.Mark>) -> Unit, compact: Boolean = false,
    onOpen: ((String, Int) -> Unit)? = null, editorRootKey: String? = null, relativePath: String? = null) {
    val language = LanguageServers.languageId(guestPath) ?: return
    val title = LanguageServers.language(guestPath)?.title ?: return
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val session = remember(guestPath, editorRootKey, guestRoot) { CodeService.session(guestPath, editorRootKey, guestRoot, relativePath) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<CompletionItem>>(emptyList()) }
    var details by remember { mutableStateOf<String?>(null) }
    var problems by remember { mutableStateOf(false) }
    var signature by remember { mutableStateOf<Assist.SignatureLine?>(null) }
    var references by remember { mutableStateOf<List<Location>?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var fixes by remember { mutableStateOf<Pair<Assist.Fixes, String>?>(null) }
    var confirm by remember { mutableStateOf<CodeService.Install?>(null) }
    var indexDialog by remember { mutableStateOf(false) }
    var indexEnabled by remember { mutableStateOf(false) }
    var license by remember { mutableStateOf<CodeServerSetup.AndroidPlatform?>(null) }
    val state = session.state
    val diagnostics = session.diagnostics[session.uri].orEmpty()

    DisposableEffect(session) {
        onDispose {
            onMarks(emptyList())
            if (editorRootKey == null) {
                CodeService.forget(session)
                kotlin.concurrent.thread(name = "dpai-code-service-release") { CodeService.release(session) }
            }
        }
    }
    LaunchedEffect(session) { withContext(Dispatchers.IO) { CodeService.resume(context, session, guestRoot) } }
    LaunchedEffect(diagnostics) { onMarks(withContext(Dispatchers.Default) { Assist.marks(buffer.text, diagnostics) }) }

    /** Текст буфера — службе; затем [ask] с позицией курсора. Вне потока интерфейса. */
    suspend fun <T> withClient(ask: (LanguageSession, String, Int) -> Outcome<T>): Outcome<T>? {
        val client = session.ready() ?: return null
        val text = buffer.text
        val cursor = buffer.state.selection.end.coerceIn(0, text.length)
        return withContext(Dispatchers.IO) {
            when (val o = client.open(session.uri, language, text)) {
                is Outcome.Failure -> o
                is Outcome.Success -> ask(client, text, cursor)
            }
        }
    }

    // Синхронизация текста и дополнение по мере ввода: после паузы, чтобы не спрашивать службу на каждый знак.
    LaunchedEffect(revision, state) {
        if (state !is CodeService.State.Ready) { suggestions = emptyList(); return@LaunchedEffect }
        val text = buffer.text
        val selection = buffer.state.selection
        val cursor = selection.end.coerceIn(0, text.length)
        val prefix = Assist.typedPrefix(text, cursor)
        val before = text.substring(0, cursor - prefix.length)
        val trigger = when {
            prefix.isNotEmpty() -> null
            before.endsWith(".") -> '.'
            before.endsWith("->") -> '>'
            before.endsWith("::") -> ':'
            else -> null
        }
        val wanted = enabled && selection.start == selection.end && (prefix.length >= 2 || trigger != null || (prefix.isNotEmpty() && (before.endsWith(".") || before.endsWith("->") || before.endsWith("::"))))
        // Подпись параметров — после `(` и `,`; снимается закрытием вызова или концом строки.
        val wantsSignature = enabled && selection.start == selection.end && Assist.wantsSignature(text, cursor)
        if (Assist.endsSignature(text, cursor)) signature = null
        delay(if (wanted || wantsSignature) 250 else 400)
        val result = withClient { client, _, _ -> if (wanted) client.completion(session.uri, LineIndex(text).position(cursor), trigger) else Outcome.success(null) }
        suggestions = ((result as? Outcome.Success)?.value)?.let { Assist.filter(it.items, prefix, 24) }.orEmpty()
        if (wantsSignature) {
            val help = withClient { client, _, _ -> client.signatureHelp(session.uri, LineIndex(text).position(cursor)) }
            signature = Assist.signature((help as? Outcome.Success)?.value)
        }
        // Службы без присылаемой диагностики (kotlin-lsp) спрашиваются после паузы в наборе.
        if (session.ready()?.pulls == true) withContext(Dispatchers.IO) { CodeService.pull(session, text) }
    }

    fun apply(applied: Outcome<Assist.Applied>, done: String?) {
        when (applied) {
            is Outcome.Failure -> note = applied.diagnostics.first().message
            is Outcome.Success -> if (buffer.edit(applied.value.text, EditorSelection(applied.value.cursor, applied.value.cursor))) {
                note = done
                onChange()
            } else {
                note = "Текст превышает предел редактора; правка не применена"
            }
        }
    }

    /** Правка службы для этого файла: другие файлы — отказ целиком; текст ушёл вперёд — повторить. */
    fun applyWorkspace(edit: WorkspaceEdit, before: String, cursor: Int, done: String) {
        when (val mine = Assist.editsForFile(edit, session.uri)) {
            is Outcome.Failure -> note = mine.diagnostics.first().message
            is Outcome.Success -> when {
                buffer.text != before -> note = "Текст изменился, пока служба отвечала; повторите"
                mine.value.isEmpty() -> note = "Служба не предложила правок"
                else -> apply(Assist.applyEdits(before, cursor, mine.value), done)
            }
        }
    }

    /** Переход к месту службы: этот файл — курсор, файл корня — вкладка на строке, вне корня — путь и строка. */
    fun go(location: Location) {
        note = when (val target = Assist.target(location, session.uri, guestRoot)) {
            null -> "Место вне файлов службы: ${location.uri}"
            is Assist.Target.Here -> { LineIndex(buffer.text).offset(target.position)?.let { buffer.select(it); onChange() }; null }
            is Assist.Target.Tab -> onOpen?.let { it(target.path, target.line); null } ?: "${target.path}, строка ${target.line}"
            is Assist.Target.Outside -> "${target.path}, строка ${target.line} — вне открытой папки"
        }
    }

    fun launch(step: String, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            note = step
            try { block() } finally { busy = false }
        }
    }

    /** Другая служба Python: остановить эту и запустить выбранную (выбор помнится). */
    fun switchPython(kind: PythonServers.Kind) {
        launch("…") {
            withContext(Dispatchers.IO) {
                CodeService.disable(session)
                CodeService.setPythonKind(context, kind)
                CodeService.start(context, session, guestRoot)
            }
            note = null
        }
    }

    /** Поставить службу после подтверждения и сразу запустить её; платформа Android — через лицензию. */
    fun install(what: CodeService.Install) {
        if (what == CodeService.Install.ANDROID_SDK) {
            launch("Читаю репозиторий Android SDK…") {
                when (val r = withContext(Dispatchers.IO) { CodeService.androidPlatform(context, session.compileSdk ?: 36) }) {
                    is Outcome.Failure -> note = "Платформа не найдена: ${r.diagnostics.first().message}"
                    is Outcome.Success -> { note = null; license = r.value }
                }
            }
            return
        }
        launch("${what.title}: нужна сеть, это несколько минут…") {
            when (val r = withContext(Dispatchers.IO) { CodeService.install(context, what) }) {
                is Outcome.Failure -> note = "Не установлено: ${r.diagnostics.first().message}"
                is Outcome.Success -> {
                    note = null
                    withContext(Dispatchers.IO) {
                        CodeService.disable(session)
                        CodeService.start(context, session, guestRoot)
                    }
                }
            }
        }
    }

    if (!compact) Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            if (session.cppProject != null) TextButton(enabled = !busy && state !is CodeService.State.Starting, onClick = {
                indexEnabled = CodeService.cppIndex(context, session.cppProject!!.root)
                indexDialog = true
            }) { Text("Индекс C++…") }
            when (state) {
                CodeService.State.Off -> {
                    Text("$title: семантика выключена", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { launch("…") { withContext(Dispatchers.IO) { CodeService.start(context, session, guestRoot) }; note = null } }) { Text("Включить") }
                }
                is CodeService.State.Starting -> Text("$title: ${state.step}…", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                is CodeService.State.Missing -> {
                    Text(state.message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    for (action in state.actions) TextButton(enabled = !busy, onClick = { confirm = action }) { Text(action.title) }
                    state.switchTo?.let { other -> TextButton(enabled = !busy, onClick = { switchPython(other) }) { Text("Взять: ${other.title}") } }
                }
                is CodeService.State.Failed -> {
                    Text("$title: ${state.message}", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    for (action in state.actions) TextButton(enabled = !busy, onClick = { confirm = action }) { Text(action.title) }
                    TextButton(enabled = !busy, onClick = { launch("…") { withContext(Dispatchers.IO) { CodeService.start(context, session, guestRoot) }; note = null } }) { Text("Повторить") }
                }
                is CodeService.State.Ready -> {
                    TextButton(onClick = { problems = true }) { Text(Assist.summary(diagnostics)) }
                    TextButton(enabled = !busy, onClick = {
                        launch("…") {
                            val r = withClient { c, t, cursor -> c.hover(session.uri, LineIndex(t).position(cursor)) }
                            note = null
                            when (r) {
                                is Outcome.Success -> details = r.value?.text ?: "Об этом месте службе нечего сказать"
                                is Outcome.Failure -> note = r.diagnostics.first().message
                                null -> Unit
                            }
                        }
                    }) { Text("Сведения") }
                    TextButton(enabled = !busy, onClick = {
                        launch("…") {
                            val r = withClient { c, t, cursor -> c.definition(session.uri, LineIndex(t).position(cursor)) }
                            note = null
                            when (r) {
                                is Outcome.Failure -> note = r.diagnostics.first().message
                                is Outcome.Success -> r.value.firstOrNull()?.let(::go) ?: run { note = "Определение не найдено" }
                                null -> Unit
                            }
                        }
                    }) { Text("Определение") }
                    TextButton(enabled = !busy, onClick = {
                        launch("…") {
                            val r = withClient { c, t, cursor -> c.references(session.uri, LineIndex(t).position(cursor)) }
                            note = null
                            when (r) {
                                is Outcome.Success -> if (r.value.isEmpty()) note = "Использований не найдено" else references = r.value
                                is Outcome.Failure -> note = r.diagnostics.first().message
                                null -> Unit
                            }
                        }
                    }) { Text("Использования") }
                    TextButton(enabled = enabled && !busy, onClick = { renaming = identifierAt(buffer.text, buffer.state.selection.end) }) { Text("Переименовать") }
                    TextButton(enabled = enabled && !busy, onClick = {
                        launch("…") {
                            val cursor = buffer.state.selection.end
                            val before = buffer.text
                            when (val r = withClient { c, _, _ -> c.format(session.uri, tabSize, spaces = !tabs) }) {
                                is Outcome.Failure -> note = r.diagnostics.first().message
                                is Outcome.Success -> if (buffer.text != before) note = "Текст изменился, пока служба форматировала; повторите" else if (r.value.isEmpty()) note = "Уже отформатировано" else apply(Assist.applyEdits(before, cursor, r.value), "Отформатировано; отмена — ↶")
                                null -> note = null
                            }
                        }
                    }) { Text("Формат") }
                    TextButton(onClick = { kotlin.concurrent.thread(name = "dpai-code-service-release") { CodeService.disable(session) } }) { Text("Выкл") }
                    for (action in state.actions) TextButton(enabled = !busy, onClick = { confirm = action }) { Text(action.title) }
                    state.switchTo?.let { other -> TextButton(enabled = !busy, onClick = { switchPython(other) }) { Text("Взять: ${other.title}") } }
                }
            }
        }
        (state as? CodeService.State.Ready)?.note?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        note?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        signature?.let { s ->
            Text(buildAnnotatedString {
                val p = s.parameter
                if (p == null) append(s.label) else {
                    append(s.label.substring(0, p.first))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.label.substring(p.first, p.last + 1)) }
                    append(s.label.substring(p.last + 1))
                }
                if (s.more > 0) append("  (+${s.more})")
            }, Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        if (suggestions.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            for (item in suggestions) TextButton(enabled = enabled, onClick = {
                val cursor = buffer.state.selection.end
                suggestions = emptyList()
                apply(Assist.complete(buffer.text, cursor, item), null)
            }) { Text(item.label.trim().take(40), fontFamily = FontFamily.Monospace) }
        }
    }
    details?.let { text ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text("Сведения") },
            text = { Clipboard.Sensitive { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } } },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Закрыть") } })
    }
    if (problems) ProblemsDialog(diagnostics, canFix = enabled && !busy, onGo = { d -> problems = false; if (buffer.goToLine(d.range.start.line + 1)) onChange() },
        onFix = { d ->
            problems = false
            launch("…") {
                val before = buffer.text
                val r = withClient { c, _, _ -> c.codeActions(session.uri, d.range, listOf(d)) }
                note = null
                when (r) {
                    is Outcome.Success -> {
                        val f = Assist.fixes(r.value)
                        if (f.applicable.isEmpty()) note = if (f.commands > 0) "Служба предлагает только команды, без правки текста — пока не поддержано" else "Исправлений нет" else fixes = f to before
                    }
                    is Outcome.Failure -> note = r.diagnostics.first().message
                    null -> Unit
                }
            }
        }) { problems = false }
    fixes?.let { (f, before) ->
        AlertDialog(onDismissRequest = { fixes = null }, title = { Text("Исправления службы") },
            text = { LazyColumn {
                items(f.applicable) { a: CodeAction ->
                    TextButton(enabled = enabled, onClick = { fixes = null; a.edit?.let { applyWorkspace(it, before, buffer.state.selection.end, "Исправлено; отмена — ↶") } }) {
                        Text((if (a.preferred) "★ " else "") + a.title, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (f.commands > 0) item { Text("Ещё ${f.commands} — командой службы, без правки текста: пока не поддержано", style = MaterialTheme.typography.bodySmall) }
            } },
            confirmButton = { TextButton(onClick = { fixes = null }) { Text("Закрыть") } })
    }
    references?.let { found ->
        AlertDialog(onDismissRequest = { references = null }, title = { Text("Использования: ${found.size}") },
            text = { Column {
                Text("Без индекса проекта — места в этом файле и разобранных заголовках, не все файлы проекта.", style = MaterialTheme.typography.bodySmall)
                LazyColumn {
                    items(found) { l ->
                        val target = Assist.target(l, session.uri, guestRoot)
                        val label = when (target) {
                            is Assist.Target.Here -> "этот файл"
                            is Assist.Target.Tab -> target.path
                            is Assist.Target.Outside -> target.path
                            null -> l.uri
                        }
                        TextButton(onClick = { references = null; go(l) }) {
                            Text("$label:${l.range.start.line + 1}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { references = null }) { Text("Закрыть") } })
    }
    if (indexDialog) AlertDialog(
        onDismissRequest = { indexDialog = false },
        title = { Text("Индекс проекта C++") },
        text = { Text(if (indexEnabled) {
            "Индекс включён для ${session.cppProject?.root}. Отключение остановит службу всех открытых файлов этого проекта. Кэш на диске сохранится."
        } else {
            "Включить для ${session.cppProject?.root}? Нужен compile_commands.json. Разбор идёт в двух потоках с низким приоритетом; расход памяти, батареи и размер кэша ещё не измерены на телефоне. Кэш .cache/clangd исключается из Git локально. Проверка свободного места использует предварительную оценку 1 ГБ, это не предел размера индекса. Служба проекта будет перезапущена."
        }) },
        confirmButton = { TextButton(onClick = {
            indexDialog = false
            val restart = state is CodeService.State.Ready || !indexEnabled
            launch("Меняю режим индекса…") {
                when (val result = withContext(Dispatchers.IO) { CodeService.setCppIndex(context, session, !indexEnabled) }) {
                    is Outcome.Failure -> note = result.diagnostics.first().message
                    is Outcome.Success -> {
                        note = null
                        if (restart) withContext(Dispatchers.IO) { CodeService.start(context, session, guestRoot) }
                    }
                }
            }
        }) { Text(if (indexEnabled) "Отключить" else "Включить") } },
        dismissButton = { TextButton(onClick = { indexDialog = false }) { Text("Отмена") } },
    )

    confirm?.let { what ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(what.title) },
            text = { Text(what.note, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { confirm = null; install(what) }) { Text("Поставить") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } })
    }
    license?.let { platform ->
        AlertDialog(onDismissRequest = { license = null }, title = { Text("Лицензия ${platform.path}") },
            text = { Column {
                Text("Платформа ставится, только если вы принимаете лицензию Google (${platform.licenseId}); архив ${platform.size / 1_000_000} МБ сверяется по SHA-1 манифеста.", style = MaterialTheme.typography.bodySmall)
                Text(platform.license, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = {
                license = null
                launch("Ставлю ${platform.path}…") {
                    when (val r = withContext(Dispatchers.IO) { CodeService.installAndroid(context, platform) }) {
                        is Outcome.Failure -> note = "Не установлено: ${r.diagnostics.first().message}"
                        is Outcome.Success -> {
                            note = null
                            withContext(Dispatchers.IO) {
                                CodeService.disable(session)
                                CodeService.start(context, session, guestRoot)
                            }
                        }
                    }
                }
            }) { Text("Принимаю, поставить") } },
            dismissButton = { TextButton(onClick = { license = null }) { Text("Не принимаю") } })
    }
    renaming?.let { initial ->
        var name by remember(initial) { mutableStateOf(initial) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Переименовать") },
            text = { Column {
                Text("Только символ этого файла: другие файлы проекта служба без индекса не видит.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { if (it.length <= 255) name = it.trim() }, label = { Text("Новое имя") }, singleLine = true,
                    isError = name.isNotEmpty() && !Assist.validName(name))
            } },
            confirmButton = { TextButton(enabled = enabled && Assist.validName(name) && name != initial, onClick = {
                renaming = null
                launch("…") {
                    val before = buffer.text
                    val cursor = buffer.state.selection.end.coerceIn(0, before.length)
                    val r = withClient { c, t, at ->
                        val place = LineIndex(t).position(at)
                        val places = when (val d = c.declaration(session.uri, place)) {
                            is Outcome.Failure -> return@withClient d
                            is Outcome.Success -> d.value + ((c.definition(session.uri, place) as? Outcome.Success)?.value.orEmpty())
                        }
                        when (val scope = Assist.renameScope(session.uri, places)) {
                            is Outcome.Failure -> scope
                            is Outcome.Success -> c.rename(session.uri, place, name)
                        }
                    }
                    note = null
                    when (r) {
                        is Outcome.Success -> applyWorkspace(r.value, before, cursor, "Переименовано; отмена — ↶")
                        is Outcome.Failure -> note = r.diagnostics.first().message
                        null -> Unit
                    }
                }
            }) { Text("Переименовать") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена") } })
    }
}

/** Идентификатор под курсором (обе стороны) — начальное имя для переименования. */
private fun identifierAt(text: String, cursor: Int): String {
    fun part(c: Char) = c.isLetterOrDigit() || c == '_'
    var from = cursor.coerceIn(0, text.length)
    var to = from
    while (from > 0 && part(text[from - 1])) from--
    while (to < text.length && part(text[to])) to++
    return text.substring(from, to)
}

@Composable
private fun ProblemsDialog(items: List<LspDiagnostic>, canFix: Boolean, onGo: (LspDiagnostic) -> Unit, onFix: (LspDiagnostic) -> Unit, onClose: () -> Unit) {
    val sorted = remember(items) { items.sortedWith(compareBy({ it.severity.ordinal }, { it.range.start })) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Замечания службы") },
        text = {
            if (sorted.isEmpty()) Text("Замечаний нет") else LazyColumn {
                items(sorted) { d ->
                    Row {
                        TextButton(onClick = { onGo(d) }, modifier = Modifier.weight(1f)) {
                            Text("${d.range.start.line + 1}:${d.range.start.character + 1} · ${d.message}",
                                color = if (d.severity == LspSeverity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(enabled = canFix, onClick = { onFix(d) }) { Text("Исправить") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } })
}
