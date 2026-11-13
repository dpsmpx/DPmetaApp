package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
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
) {
    interface Listener {
        /** Диагностика файла целиком: новый список заменяет прежний. [version] — версия текста, к которой он относится, если служба её назвала. */
        fun diagnostics(uri: String, version: Int?, items: List<LspDiagnostic>)

        /** Служба остановилась или нарушила протокол; клиент больше не работает. */
        fun stopped(reason: Diagnostic)
    }

    private class Document(val languageId: String, var version: Int, var text: String)

    private val documents = HashMap<String, Document>()
    private val lock = Any()

    @Volatile
    private var server: ServerInfo? = null

    private val peer = JsonRpcPeer(
        input, output,
        object : JsonRpcPeer.Handler {
            override fun notification(method: String, params: JsonValue?) {
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
                "client/registerCapability", "client/unregisterCapability", "window/workDoneProgress/create" -> Outcome.success(JsonNull)
                // Правки по команде службы не применяются молча: только те, что редактор запросил сам.
                "workspace/applyEdit" -> Outcome.success(JsonObject.of("applied" to json(false)))
                else -> Outcome.failure(DiagCode.UNSUPPORTED, "редактор не выполняет $method")
            }

            override fun closed(reason: Diagnostic) = listener.stopped(reason)
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
        val result = ServerInfo(LspParse.str(about?.get("name"))?.take(100), LspParse.str(about?.get("version"))?.take(200), LspParse.capabilities(reply["capabilities"]))
        peer.notify("initialized", JsonObject(emptyMap())).let { if (it is Outcome.Failure) return it }
        if (settings.fields.isNotEmpty()) peer.notify("workspace/didChangeConfiguration", JsonObject.of("settings" to settings)).let { if (it is Outcome.Failure) return it }
        server = result
        return Outcome.success(result)
    }

    /** Открыть документ или заменить его текст, если он уже открыт. */
    fun open(uri: String, languageId: String, text: String): Outcome<Unit> = synchronized(lock) {
        ready()?.let { return it }
        if (documents.containsKey(uri)) return update(uri, text)
        documents[uri] = Document(languageId, 1, text)
        peer.notify(
            "textDocument/didOpen",
            JsonObject.of("textDocument" to JsonObject.of("uri" to json(uri), "languageId" to json(languageId), "version" to json(1), "text" to json(text))),
        )
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
        documents.remove(uri) ?: return Outcome.success(Unit)
        peer.notify("textDocument/didClose", JsonObject.of("textDocument" to JsonObject.of("uri" to json(uri))))
    }

    /** Версия открытого документа у службы; `null` — не открыт. */
    fun version(uri: String): Int? = synchronized(lock) { documents[uri]?.version }

    fun completion(uri: String, at: Position, trigger: Char? = null, timeout: Duration = REQUEST_TIMEOUT): Outcome<CompletionList> {
        val context = if (trigger != null) {
            JsonObject.of("triggerKind" to json(2), "triggerCharacter" to json(trigger.toString()))
        } else {
            JsonObject.of("triggerKind" to json(1))
        }
        return ask("textDocument/completion", JsonObject(place(uri, at).fields + ("context" to context)), timeout, LspParse::completion)
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
    fun codeActions(uri: String, range: Range, diagnostics: List<LspDiagnostic>, timeout: Duration = REQUEST_TIMEOUT): Outcome<List<CodeAction>> = ask(
        "textDocument/codeAction",
        JsonObject.of(
            "textDocument" to JsonObject.of("uri" to json(uri)),
            "range" to LspParse.toJson(range),
            "context" to JsonObject.of("diagnostics" to JsonArray(diagnostics.map { LspParse.toJson(it) })),
        ),
        timeout, LspParse::codeActions,
    )

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
                "completionItem" to JsonObject.of("snippetSupport" to json(false), "documentationFormat" to plain),
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
                "codeActionLiteralSupport" to JsonObject.of("codeActionKind" to JsonObject.of("valueSet" to JsonArray(listOf(json("quickfix"), json("refactor"))))),
            ),
        )
        return JsonObject.of(
            "processId" to JsonNull,
            "clientInfo" to JsonObject.of("name" to json("dpai")),
            "rootUri" to json(rootUri),
            "capabilities" to JsonObject.of(
                "general" to JsonObject.of("positionEncodings" to JsonArray(listOf(json("utf-16")))),
                "textDocument" to text,
                "workspace" to JsonObject.of(
                    "workspaceEdit" to JsonObject.of("documentChanges" to json(false)),
                    "applyEdit" to json(false),
                    "configuration" to json(settings.fields.isNotEmpty()),
                    "didChangeConfiguration" to JsonObject(emptyMap()),
                ),
            ),
        )
    }

    companion object {
        val START_TIMEOUT: Duration = 30.seconds
        val REQUEST_TIMEOUT: Duration = 5.seconds

        /** Ссылки и переименование по проекту — дольше: служба читает другие файлы. */
        val SLOW_TIMEOUT: Duration = 30.seconds
        val STOP_TIMEOUT: Duration = 3.seconds
    }
}
