package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.json
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Клиент языковой службы (LSP 3.17) для редактора (D-137): рукопожатие,
 * открытые документы с версиями, диагностика, дополнение, подсказки,
 * переходы, переименование, форматирование, исправления.
 *
 * Потоки [input] и [output] — уже запущенной службы: сам клиент процессов
 * не запускает (D-03). Позиции — UTF-16, как в поле редактора. Текст
 * открытого документа клиент помнит сам: изменение уходит службе одной
 * правкой ([TextEdits.delta]), если она это умеет, иначе — текстом целиком.
 *
 * [Listener] вызывается из потока чтения и не должен обращаться к клиенту
 * с ожиданием ответа. Запросы блокируют вызывающий поток до ответа или
 * тайм-аута — их место вне потока интерфейса.
 *
 * [settings] — постоянные настройки службы (D-142): ими клиент отвечает на
 * `workspace/configuration` (по разделу через точку) и шлёт их
 * `workspace/didChangeConfiguration` после рукопожатия. Пустые — на каждый
 * вопрос `null`, как раньше.
 */
class LspClient(
    input: InputStream,
    output: OutputStream,
    private val rootUri: String,
    private val listener: Listener,
    maxBody: Int = LspReader.DEFAULT_MAX_BODY,
    private val settings: JsonObject = JsonObject(emptyMap()),
    private val initializationOptions: JsonObject = JsonObject(emptyMap()),
) {
    interface Listener {
        /** Диагностика файла целиком: новый список заменяет прежний. [version] — версия текста, к которой он относится, если служба её назвала. */
        fun diagnostics(uri: String, version: Int?, items: List<LspDiagnostic>)

        /** Служба остановилась или нарушила протокол; клиент больше не работает. */
        fun stopped(reason: Diagnostic)
    }

    private class Document(val languageId: String, var version: Int, var text: String, var retained: Boolean, var borrowers: Int = 0)

    /** Временный документ запроса: не закрывает файл, который за это время открыл редактор. */
    class DocumentBorrow internal constructor(private val release: () -> Unit) : AutoCloseable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }

    private val documents = HashMap<String, Document>()
    private val lock = Any()

    private val projectIndex = ProjectIndex()
    fun projectIndexLease(): Outcome<ProjectIndex.Lease> = if (isRunning) projectIndex.lease()
        else Outcome.failure(DiagCode.LSP_SERVER_STOPPED, "служба индекса остановлена")

    private val completionSerial = Any()
    private val completionGeneration = java.util.concurrent.atomic.AtomicLong()
    private class CompletionCapture(val item: CompletionItem) {
        var edit: WorkspaceEdit? = null
        var caret: Position? = null
        var failure: String? = null
    }
    @Volatile private var completionCapture: CompletionCapture? = null

    @Volatile
    private var server: ServerInfo? = null

    private val peer = JsonRpcPeer(
        input, output,
        object : JsonRpcPeer.Handler {
            override fun notification(method: String, params: JsonValue?) {
                if (method == "$/progress") {
                    val o = params as? JsonObject
                    val value = o?.get("value") as? JsonObject
                    projectIndex.progress(LspParse.str(o?.get("token")), LspParse.str(value?.get("kind")))
                    return
                }
                if (method != "textDocument/publishDiagnostics") return
                val o = params as? JsonObject ?: return
                val uri = LspParse.str(o["uri"]) ?: return
                val items = ((o["diagnostics"] as? JsonArray)?.items ?: return).take(LspParse.MAX_ITEMS).mapNotNull { LspParse.diagnostic(it) }
                listener.diagnostics(uri, LspParse.int(o["version"]), items)
            }

            override fun request(method: String, params: JsonValue?): Outcome<JsonValue> = when (method) {
                // Каждый вопрос — раздел настроек [settings] через точку; нет раздела — null.
                "workspace/configuration" -> Outcome.success(
                    JsonArray((((params as? JsonObject)?.get("items") as? JsonArray)?.items ?: emptyList()).map { section(LspParse.str((it as? JsonObject)?.get("section"))) }),
                )
                "client/registerCapability", "client/unregisterCapability" -> Outcome.success(JsonNull)
                "window/workDoneProgress/create" -> {
                    projectIndex.requested(LspParse.str((params as? JsonObject)?.get("token")))
                    Outcome.success(JsonNull)
                }
                // Правки по команде службы не применяются молча: только те, что редактор запросил сам.
                "workspace/applyEdit" -> captureCompletionEdit(params)
                "window/showDocument" -> captureCompletionCaret(params)
                else -> Outcome.failure(DiagCode.UNSUPPORTED, "редактор не выполняет $method")
            }

            override fun closed(reason: Diagnostic) { projectIndex.stopped(); listener.stopped(reason) }
        },
        maxBody,
    )

    val info: ServerInfo? get() = server

    val isRunning: Boolean get() = peer.isOpen

    /** Рукопожатие: `initialize` и `initialized`. До него остальные вызовы — отказ. */
    fun start(timeout: Duration = START_TIMEOUT): Outcome<ServerInfo> {
        peer.start()
        val reply = when (val r = peer.request("initialize", initializeParams(), timeout)) {
            is Outcome.Failure -> {
                peer.close()
                return r
            }
            is Outcome.Success -> r.value as? JsonObject ?: run {
                peer.close()
                return Outcome.failure(DiagCode.LSP_REPLY_INVALID, "ответ на initialize — не объект")
            }
        }
        val about = reply["serverInfo"] as? JsonObject
        val caps = reply["capabilities"] as? JsonObject
        val result = ServerInfo(LspParse.str(about?.get("name"))?.take(100), LspParse.str(about?.get("version"))?.take(200), LspParse.capabilities(caps),
            LspParse.str((caps?.get("experimental") as? JsonObject)?.get("indexDir")))
        peer.notify("initialized", JsonObject(emptyMap())).let { if (it is Outcome.Failure) return it }
        if (settings.fields.isNotEmpty()) peer.notify("workspace/didChangeConfiguration", JsonObject.of("settings" to settings)).let { if (it is Outcome.Failure) return it }
        server = result
        return Outcome.success(result)
    }

    /** Снимок текста и версии для сверки проектной правки. */
    fun document(uri: String): Pair<Int, String>? = synchronized(lock) { documents[uri]?.let { it.version to it.text } }

    /** Открыть документ или заменить его текст, если он уже открыт. */
    fun open(uri: String, languageId: String, text: String): Outcome<Unit> = synchronized(lock) {
        ready()?.let { return it }
        documents[uri]?.let { it.retained = true; return update(uri, text) }
        openDocument(uri, languageId, text, retained = true)
    }

    private fun openDocument(uri: String, languageId: String, text: String, retained: Boolean): Outcome<Unit> {
        val result = peer.notify(
            "textDocument/didOpen",
            JsonObject.of("textDocument" to JsonObject.of("uri" to json(uri), "languageId" to json(languageId), "version" to json(1), "text" to json(text))),
        )
        if (result is Outcome.Success) documents[uri] = Document(languageId, 1, text, retained)
        return result
    }

    /** Зафиксировать текст для повторного rename; чужой несохранённый текст не заменяется. */
    fun borrowDocument(uri: String, languageId: String, text: String): Outcome<DocumentBorrow> = synchronized(lock) {
        ready()?.let { return it }
        val known = documents[uri]
        if (known != null && known.text != text) return Outcome.failure(DiagCode.STATE_CONFLICT, "текст службы отличается от файла; сначала сохраните правки", uri)
        if (known == null) {
            val result = openDocument(uri, languageId, text, retained = false)
            if (result is Outcome.Failure) return result
        }
        val document = documents.getValue(uri)
        document.borrowers++
        Outcome.success(DocumentBorrow {
            synchronized(lock) {
                if (documents[uri] === document) {
                    document.borrowers--
                    if (!document.retained && document.borrowers == 0) {
                        documents.remove(uri)
                        peer.notify("textDocument/didClose", JsonObject.of("textDocument" to JsonObject.of("uri" to json(uri))))
                    }
                }
            }
        })
    }

    /** Новый текст открытого документа; тот же текст — ничего не уходит. */
    fun update(uri: String, text: String): Outcome<Unit> = synchronized(lock) {
        val caps = ready()?.let { return it } ?: checkNotNull(server).capabilities
        val doc = documents[uri] ?: return Outcome.failure(DiagCode.STATE_CONFLICT, "документ не открыт у службы", uri)
        val delta = TextEdits.delta(doc.text, text) ?: return Outcome.success(Unit)
        doc.version++
        doc.text = text
        val change = if (caps.incrementalSync) {
            JsonObject.of("range" to LspParse.toJson(delta.range), "text" to json(delta.newText))
        } else {
            JsonObject.of("text" to json(text))
        }
        peer.notify(
            "textDocument/didChange",
            JsonObject.of("textDocument" to JsonObject.of("uri" to json(uri), "version" to json(doc.version)), "contentChanges" to JsonArray(listOf(change))),
        )
    }

    /** Файл записан на диск: служба перечитывает зависящее от него (заголовки). */
    fun saved(uri: String): Outcome<Unit> = synchronized(lock) {
        ready()?.let { return it }
        if (!documents.containsKey(uri)) return Outcome.success(Unit)
        peer.notify("textDocument/didSave", JsonObject.of("textDocument" to JsonObject.of("uri" to json(uri))))
    }

    fun close(uri: String): Outcome<Unit> = synchronized(lock) {
        ready()?.let { return it }
        val document = documents[uri] ?: return Outcome.success(Unit)
        document.retained = false
        if (document.borrowers > 0) return Outcome.success(Unit)
        documents.remove(uri)
        peer.notify("textDocument/didClose", JsonObject.of("textDocument" to JsonObject.of("uri" to json(uri))))
    }

    /** Записанные файлы, включая закрытые документы. */
    fun filesChanged(uris: Set<String>): Outcome<Unit> = synchronized(lock) {
        ready()?.let { return it }
        peer.notify("workspace/didChangeWatchedFiles", JsonObject.of("changes" to JsonArray(uris.map { JsonObject.of("uri" to json(it), "type" to json(2)) })))
    }

    /** Версия открытого документа у службы; `null` — не открыт. */
    fun version(uri: String): Int? = synchronized(lock) { documents[uri]?.version }

    fun completion(uri: String, at: Position, trigger: Char? = null, timeout: Duration = REQUEST_TIMEOUT): Outcome<CompletionList> = synchronized(completionSerial) {
        val context = if (trigger != null) {
            JsonObject.of("triggerKind" to json(2), "triggerCharacter" to json(trigger.toString()))
        } else {
            JsonObject.of("triggerKind" to json(1))
        }
        val snapshot = document(uri)
        val generation = completionGeneration.incrementAndGet()
        return when (val result = ask("textDocument/completion", JsonObject(place(uri, at).fields + ("context" to context)), timeout, LspParse::completion)) {
            is Outcome.Failure -> result
            is Outcome.Success -> Outcome.success(result.value.copy(items = result.value.items.map { it.copy(owner = this, uri = uri, snapshot = snapshot, generation = generation, position = at,
                resolvable = info?.capabilities?.completionResolve == true && it.payload?.get("data") != null && it.payload["data"] != JsonNull) }))
        }
    }

    /** Отложенный импорт/описание — только для того же снимка и клиента. */
    fun resolveCompletion(item: CompletionItem, timeout: Duration = SLOW_TIMEOUT): Outcome<CompletionItem> = synchronized(completionSerial) {
        if (item.owner !== this || !isRunning || item.uri == null || item.snapshot == null || document(item.uri) != item.snapshot || item.generation != completionGeneration.get()) {
            return Outcome.failure(DiagCode.STATE_CONFLICT, "дополнение устарело; запросите варианты заново")
        }
        val command = item.payload?.get("command") as? JsonObject
        if (LspParse.str(command?.get("command")) == KOTLIN_COMPLETION) return prepareKotlinCompletion(item, checkNotNull(command), timeout)
        if (!item.resolvable) return Outcome.success(item)
        return when (val result = ask("completionItem/resolve", checkNotNull(item.payload), timeout, LspParse::completionItem)) {
            is Outcome.Failure -> result
            is Outcome.Success -> if (document(item.uri) != item.snapshot) Outcome.failure(DiagCode.STATE_CONFLICT, "текст изменился во время дополнения; повторите")
                else Outcome.success(result.value.copy(owner = this, uri = item.uri, snapshot = item.snapshot, generation = item.generation, position = item.position))
        }
    }

    /**
     * Единственная известная команда: pinned kotlin-lsp вычисляет вставку на копии
     * и посылает один applyEdit (D-211). Ответ всегда applied:false: буфер ещё не
     * изменён. После завершения запроса клиент проверяет и применяет его сам.
     */
    private fun prepareKotlinCompletion(item: CompletionItem, command: JsonObject, timeout: Duration): Outcome<CompletionItem> {
        val args = command["arguments"] as? JsonArray
            ?: return Outcome.failure(DiagCode.LSP_REPLY_INVALID, "нет аргумента команды дополнения")
        val key = (args.items.singleOrNull() as? JsonNumber)?.toLongExact()
        val data = item.payload?.get("data") as? JsonObject
        val at = item.position
        if (info?.capabilities?.executeCommands?.contains(KOTLIN_COMPLETION) != true || key == null || key < 0 || at == null ||
            item.edit != TextEdit(Range(at, at), "") || item.additional.isNotEmpty() ||
            data?.get("KotlinCompletionItemKey") != args.items.single() ||
            LspParse.str(data["configurationEntryId"]) != "KotlinCompletionProvider") {
            return Outcome.failure(DiagCode.UNSUPPORTED, "неподдерживаемый контракт команды дополнения Kotlin; текст не изменён")
        }
        val capture = CompletionCapture(item)
        completionCapture = capture
        try {
            val reply = peer.request("workspace/executeCommand", JsonObject.of("command" to json(KOTLIN_COMPLETION), "arguments" to args), timeout)
            // Таймаут/обрыв не превращаются в успешное вычисление даже после applyEdit.
            if (reply is Outcome.Failure && reply.diagnostics.any { it.code != DiagCode.LSP_REQUEST_FAILED }) return reply
            if (document(checkNotNull(item.uri)) != item.snapshot) return Outcome.failure(DiagCode.STATE_CONFLICT, "текст изменился во время дополнения; повторите")
            return synchronized(capture) {
                capture.failure?.let { return Outcome.failure(DiagCode.LSP_REPLY_INVALID, it) }
                val edit = capture.edit ?: return Outcome.failure(DiagCode.STATE_CONFLICT, "вариант дополнения устарел; запросите варианты заново")
                Outcome.success(item.copy(unsupported = false, resolvable = false, prepared = edit, preparedCaret = capture.caret))
            }
        } finally { completionCapture = null }
    }

    private fun captureCompletionEdit(params: JsonValue?): Outcome<JsonValue> {
        val active = completionCapture
        if (active != null) synchronized(active) {
            val item = active.item
            val uri = checkNotNull(item.uri)
            val edit = LspParse.workspaceEdit((params as? JsonObject)?.get("edit"))
            val expected = edit?.versions?.get(uri)
            when {
                active.edit != null || active.failure != null -> active.failure = "команда прислала несколько наборов; текст не изменён"
                edit == null || edit.changes.keys != setOf(uri) -> active.failure = "команда дополнения меняет неподдерживаемые документы; текст не изменён"
                document(checkNotNull(item.uri)) != item.snapshot || expected != null && expected != item.snapshot?.first -> active.failure = "снимок дополнения изменился; текст не изменён"
                else -> active.edit = edit
            }
        }
        return Outcome.success(JsonObject.of("applied" to json(false), "failureReason" to json("DP_IDE prepares the selected completion; the editor has not applied it yet")))
    }

    private fun captureCompletionCaret(params: JsonValue?): Outcome<JsonValue> {
        val active = completionCapture
        val o = params as? JsonObject
        if (active != null && LspParse.str(o?.get("uri")) == active.item.uri && o?.get("external") != json(true)) {
            val selection = LspParse.range(o?.get("selection"))
            if (selection != null && selection.start == selection.end) synchronized(active) { active.caret = selection.start }
        }
        // Навигация будет выполнена только вместе с проверенной вставкой.
        return Outcome.success(JsonObject.of("success" to json(false)))
    }

    /** Подсказка под курсором; `null` — службе нечего сказать об этом месте. */
    fun hover(uri: String, at: Position, timeout: Duration = REQUEST_TIMEOUT): Outcome<Hover?> = askOptional("textDocument/hover", place(uri, at), timeout, LspParse::hover)

    fun signatureHelp(uri: String, at: Position, timeout: Duration = REQUEST_TIMEOUT): Outcome<SignatureHelp?> =
        askOptional("textDocument/signatureHelp", place(uri, at), timeout, LspParse::signatureHelp)

    fun definition(uri: String, at: Position, timeout: Duration = REQUEST_TIMEOUT): Outcome<List<Location>> = ask("textDocument/definition", place(uri, at), timeout, LspParse::locations)

    /** Символы документа (функции, классы…) — для символа вокруг выделения в контексте ИИ (D-165). */
    fun symbols(uri: String, timeout: Duration = REQUEST_TIMEOUT): Outcome<List<CodeSymbol>> =
        ask("textDocument/documentSymbol", JsonObject.of("textDocument" to JsonObject.of("uri" to json(uri))), timeout) { LspParse.symbols(it) }

    /** Объявление символа под курсором — служба находит его и без индекса проекта, по разобранным заголовкам. */
    fun declaration(uri: String, at: Position, timeout: Duration = REQUEST_TIMEOUT): Outcome<List<Location>> = ask("textDocument/declaration", place(uri, at), timeout, LspParse::locations)

    fun references(uri: String, at: Position, declaration: Boolean = true, timeout: Duration = SLOW_TIMEOUT): Outcome<List<Location>> = ask(
        "textDocument/references",
        JsonObject(place(uri, at).fields + ("context" to JsonObject.of("includeDeclaration" to json(declaration)))),
        timeout, LspParse::locations,
    )

    /** Правки переименования символа под курсором по всем файлам; сами файлы клиент не трогает. */
    fun rename(uri: String, at: Position, newName: String, timeout: Duration = SLOW_TIMEOUT): Outcome<WorkspaceEdit> =
        ask("textDocument/rename", JsonObject(place(uri, at).fields + ("newName" to json(newName))), timeout, LspParse::workspaceEdit)

    /** Правки форматирования всего файла ([range] — только его части). */
    fun format(uri: String, tabSize: Int, spaces: Boolean, range: Range? = null, timeout: Duration = REQUEST_TIMEOUT): Outcome<List<TextEdit>> {
        val options = JsonObject.of("tabSize" to json(tabSize), "insertSpaces" to json(spaces))
        val params = linkedMapOf<String, JsonValue>("textDocument" to JsonObject.of("uri" to json(uri)), "options" to options)
        if (range != null) params["range"] = LspParse.toJson(range)
        return ask(if (range == null) "textDocument/formatting" else "textDocument/rangeFormatting", JsonObject(params), timeout, LspParse::textEdits)
    }

    /** Исправления и преобразования для [range]; [diagnostics] — диагностика этого места, к которой нужны исправления. */
    fun codeActions(uri: String, range: Range, diagnostics: List<LspDiagnostic>, timeout: Duration = REQUEST_TIMEOUT): Outcome<List<CodeAction>> = when (val result = ask(
        "textDocument/codeAction",
        JsonObject.of(
            "textDocument" to JsonObject.of("uri" to json(uri)),
            "range" to LspParse.toJson(range),
            "context" to JsonObject.of("diagnostics" to JsonArray(diagnostics.map { LspParse.toJson(it) })),
        ), timeout, LspParse::codeActions,
    )) {
        is Outcome.Failure -> result
        is Outcome.Success -> Outcome.success(result.value.map { action -> action.copy(owner = this,
            resolvable = info?.capabilities?.codeActionResolve == true && action.edit == null && action.payload?.get("data") != null && action.payload["data"] != JsonNull) })
    }

    /** Чужая/прежняя служба не получает opaque data другой сессии. Команды не исполняются. */
    fun resolveCodeAction(action: CodeAction, timeout: Duration = SLOW_TIMEOUT): Outcome<CodeAction> {
        if (action.owner !== this || !isRunning) return Outcome.failure(DiagCode.STATE_CONFLICT, "служба исправления изменилась; запросите исправления заново")
        if (action.disabledReason != null) return Outcome.failure(DiagCode.STATE_CONFLICT, action.disabledReason)
        if (!action.resolvable) return Outcome.success(action)
        return when (val result = ask("codeAction/resolve", checkNotNull(action.payload), timeout, LspParse::codeAction)) {
            is Outcome.Failure -> result
            is Outcome.Success -> Outcome.success(result.value.copy(owner = this))
        }
    }

    /**
     * Диагностика файла по запросу (`textDocument/diagnostic`, LSP 3.17) — для
     * служб, которые её не присылают сами ([ServerCapabilities.pullDiagnostics]).
     * `null` — с прошлого раза не изменилась.
     */
    fun pullDiagnostics(uri: String, timeout: Duration = SLOW_TIMEOUT): Outcome<List<LspDiagnostic>?> =
        when (val r = askOptional("textDocument/diagnostic", JsonObject.of("textDocument" to JsonObject.of("uri" to json(uri))), timeout, LspParse::documentDiagnostics)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(r.value?.items)
        }

    /** Вежливая остановка: `shutdown`, `exit`, закрытие потоков. Процесс службы завершает тот, кто его запустил. */
    fun stop(timeout: Duration = STOP_TIMEOUT) {
        if (server != null && peer.isOpen) {
            peer.request("shutdown", null, timeout)
            peer.notify("exit", null)
        }
        peer.close()
    }

    private fun ready(): Outcome.Failure? = when {
        server == null -> Outcome.failure(DiagCode.STATE_CONFLICT, "служба ещё не запущена")
        else -> null
    }

    /** Раздел настроек: `null` — все; `a.b` — вложенный объект; нет такого — `null`. */
    private fun section(name: String?): JsonValue {
        var at: JsonValue = settings
        for (part in name?.split('.').orEmpty().filter { it.isNotEmpty() }) at = (at as? JsonObject)?.get(part) ?: return JsonNull
        return at
    }

    private fun place(uri: String, at: Position): JsonObject = JsonObject.of("textDocument" to JsonObject.of("uri" to json(uri)), "position" to LspParse.toJson(at))

    private fun <T : Any> ask(method: String, params: JsonValue, timeout: Duration, parse: (JsonValue?) -> T?): Outcome<T> {
        ready()?.let { return it }
        return when (val r = peer.request(method, params, timeout)) {
            is Outcome.Failure -> r
            is Outcome.Success -> parse(r.value)?.let { Outcome.success(it) } ?: Outcome.failure(DiagCode.LSP_REPLY_INVALID, "ответ службы не разобран", method)
        }
    }

    private fun <T : Any> askOptional(method: String, params: JsonValue, timeout: Duration, parse: (JsonValue?) -> T?): Outcome<T?> {
        ready()?.let { return it }
        return when (val r = peer.request(method, params, timeout)) {
            is Outcome.Failure -> r
            is Outcome.Success -> if (r.value == JsonNull) {
                Outcome.success(null)
            } else {
                parse(r.value)?.let { Outcome.success(it) } ?: Outcome.failure(DiagCode.LSP_REPLY_INVALID, "ответ службы не разобран", method)
            }
        }
    }

    private fun initializeParams(): JsonObject {
        val plain = JsonArray(listOf(json("plaintext")))
        val text = JsonObject.of(
            "synchronization" to JsonObject.of("didSave" to json(true)),
            "publishDiagnostics" to JsonObject.of("versionSupport" to json(true)),
            // Сниппеты не запрашиваются: вставляется готовый текст, без меток ${1:…}.
            "completion" to JsonObject.of(
                "contextSupport" to json(true),
                "completionItem" to JsonObject.of("snippetSupport" to json(false), "documentationFormat" to plain,
                    "resolveSupport" to JsonObject.of("properties" to JsonArray(listOf(json("additionalTextEdits"), json("detail"), json("documentation"))))),
            ),
            "hover" to JsonObject.of("contentFormat" to plain),
            "signatureHelp" to JsonObject.of(
                "signatureInformation" to JsonObject.of("documentationFormat" to plain, "parameterInformation" to JsonObject.of("labelOffsetSupport" to json(true))),
            ),
            "definition" to JsonObject(emptyMap()),
            "references" to JsonObject(emptyMap()),
            "documentSymbol" to JsonObject.of("hierarchicalDocumentSymbolSupport" to json(true)),
            "rename" to JsonObject(emptyMap()),
            "formatting" to JsonObject(emptyMap()),
            "rangeFormatting" to JsonObject(emptyMap()),
            // С литералами служба отдаёт исправления готовыми правками, а не командой.
            "codeAction" to JsonObject.of(
                "dataSupport" to json(true), "disabledSupport" to json(true),
                "resolveSupport" to JsonObject.of("properties" to JsonArray(listOf(json("edit")))),
                "codeActionLiteralSupport" to JsonObject.of("codeActionKind" to JsonObject.of("valueSet" to JsonArray(listOf(json("quickfix"), json("refactor"))))),
            ),
        )
        return JsonObject.of(
            "processId" to JsonNull,
            "clientInfo" to JsonObject.of("name" to json("dpai")),
            "rootUri" to json(rootUri),
            "capabilities" to JsonObject.of(
                "window" to JsonObject.of("workDoneProgress" to json(true), "showDocument" to JsonObject.of("support" to json(true))),
                "general" to JsonObject.of("positionEncodings" to JsonArray(listOf(json("utf-16")))),
                "textDocument" to text,
                "workspace" to JsonObject.of(
                    "workspaceEdit" to JsonObject.of("documentChanges" to json(true)),
                    "applyEdit" to json(false),
                    "configuration" to json(settings.fields.isNotEmpty()),
                    "didChangeConfiguration" to JsonObject(emptyMap()),
                ),
            ),
        ).let { params -> if (initializationOptions.fields.isEmpty()) params else JsonObject(params.fields + ("initializationOptions" to initializationOptions)) }
    }

    companion object {
        private const val KOTLIN_COMPLETION = "jetbrains.kotlin.completion.apply"
        val START_TIMEOUT: Duration = 30.seconds
        val REQUEST_TIMEOUT: Duration = 5.seconds

        /** Ссылки и переименование по проекту — дольше: служба читает другие файлы. */
        val SLOW_TIMEOUT: Duration = 30.seconds
        val STOP_TIMEOUT: Duration = 3.seconds
    }
}
