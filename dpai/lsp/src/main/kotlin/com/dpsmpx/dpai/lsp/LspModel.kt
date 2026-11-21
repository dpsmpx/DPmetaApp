package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.json

/** Важность диагностики службы; неизвестное число — [ERROR]: ошибку нельзя показать мягче, чем она есть. */
enum class LspSeverity {
    ERROR, WARNING, INFORMATION, HINT;

    companion object {
        fun of(n: Long?): LspSeverity = when (n) {
            2L -> WARNING
            3L -> INFORMATION
            4L -> HINT
            else -> ERROR
        }
    }
}

data class LspDiagnostic(val range: Range, val severity: LspSeverity, val code: String?, val source: String?, val message: String)

/** Файл и место в нём. */
data class Location(val uri: String, val range: Range)

/**
 * Вариант дополнения. [edit] — что и где заменить; его нет — вставить
 * [insertText] на место слова под курсором. [additional] — правки в других
 * местах файла (например, `#include`).
 */
data class CompletionItem(
    val label: String,
    val kind: Int?,
    val detail: String?,
    val documentation: String?,
    val sortText: String?,
    val filterText: String?,
    val insertText: String,
    val edit: TextEdit?,
    val additional: List<TextEdit>,
    val unsupported: Boolean = false,
    val resolvable: Boolean = false,
    internal val payload: JsonObject? = null,
    internal val owner: LspClient? = null,
    internal val uri: String? = null,
    internal val snapshot: Pair<Int, String>? = null,
    internal val generation: Long = 0,
    internal val position: Position? = null,
    internal val prepared: WorkspaceEdit? = null,
    internal val preparedCaret: Position? = null,
)

/** [incomplete] — список неполон: при следующем знаке спрашивать службу заново, а не фильтровать этот. */
data class CompletionList(val items: List<CompletionItem>, val incomplete: Boolean)

data class Hover(val text: String, val range: Range?)

/**
 * Подпись функции: [parameters] — границы параметров в [label] (UTF-16),
 * `null` — службе неизвестны. [activeParameter] — текущий параметр этой
 * подписи (LSP 3.16, так отвечает kotlin-lsp); его нет — общий из [SignatureHelp].
 */
data class Signature(val label: String, val documentation: String?, val parameters: List<IntRange?>, val activeParameter: Int? = null)

data class SignatureHelp(val signatures: List<Signature>, val active: Int, val activeParameter: Int?)

/** Правки по файлам: адрес → правки в координатах текущего текста файла. */
data class WorkspaceEdit(val changes: Map<String, List<TextEdit>>, val versions: Map<String, Int?> = emptyMap()) {
    companion object { const val MAX_EDITS = 10_000 }
}

/**
 * Символ документа (`textDocument/documentSymbol`): функция, класс, поле…
 * [kind] — число `SymbolKind` LSP; [range] — весь символ; [children] —
 * вложенные (у плоского ответа `SymbolInformation` — пусто).
 */
data class CodeSymbol(val name: String, val kind: Int, val range: Range, val children: List<CodeSymbol>)

/** Исправление; отложенная правка разрешается только породившей её службой. */
data class CodeAction(
    val title: String, val kind: String?, val edit: WorkspaceEdit?, val preferred: Boolean,
    val disabledReason: String? = null,
    val requiresCommand: Boolean = false,
    val resolvable: Boolean = false,
    internal val payload: JsonObject? = null,
    internal val owner: LspClient? = null,
)

/** Что служба умеет — по её ответу на `initialize`. */
data class ServerCapabilities(
    val incrementalSync: Boolean,
    val completion: Boolean,
    val completionTriggers: Set<String>,
    val hover: Boolean,
    val signatureHelp: Boolean,
    val signatureTriggers: Set<String>,
    val definition: Boolean,
    val references: Boolean,
    val rename: Boolean,
    val formatting: Boolean,
    val codeAction: Boolean,
    /** Служба отдаёт диагностику по запросу `textDocument/diagnostic`; присылает ли сама — не обещано. */
    val pullDiagnostics: Boolean = false,
    val declaration: Boolean = false,
    val documentSymbols: Boolean = false,
    val codeActionResolve: Boolean = false,
    val completionResolve: Boolean = false,
    val executeCommands: Set<String> = emptySet(),
)

/** Имя и версия службы, если она их назвала. */
data class ServerInfo(val name: String?, val version: String?, val capabilities: ServerCapabilities)

/**
 * Разбор ответов службы. Служба — чужая программа: поле не того типа —
 * `null` (вызывающий превращает его в `LSP_REPLY_INVALID`), неизвестные
 * поля пропускаются, списки и тексты обрезаются по пределам.
 */
internal object LspParse {
    const val MAX_ITEMS = 2000
    const val MAX_TEXT = 8000

    fun str(v: JsonValue?): String? = (v as? JsonString)?.value

    fun int(v: JsonValue?): Int? = (v as? JsonNumber)?.toLongExact()?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()

    fun position(v: JsonValue?): Position? {
        val o = v as? JsonObject ?: return null
        return Position(int(o["line"]) ?: return null, int(o["character"]) ?: return null)
    }

    fun range(v: JsonValue?): Range? {
        val o = v as? JsonObject ?: return null
        val start = position(o["start"]) ?: return null
        val end = position(o["end"]) ?: return null
        return if (start <= end) Range(start, end) else null
    }

    fun textEdit(v: JsonValue?): TextEdit? {
        val o = v as? JsonObject ?: return null
        // InsertReplaceEdit не запрашивается, но служба может прислать: берём замену.
        val range = range(o["range"]) ?: range(o["replace"]) ?: return null
        return TextEdit(range, str(o["newText"]) ?: return null)
    }

    fun textEdits(v: JsonValue?): List<TextEdit>? {
        if (v == null || v == JsonNull) return emptyList()
        val a = v as? JsonArray ?: return null
        if (a.items.size > MAX_ITEMS) return null
        return a.items.map { textEdit(it) ?: return null }
    }

    /** Текст документации: строка, `MarkupContent` или `MarkedString` со списком. */
    fun markup(v: JsonValue?): String? = when (v) {
        is JsonString -> v.value
        is JsonObject -> str(v["value"])
        is JsonArray -> v.items.mapNotNull { markup(it) }.joinToString("\n\n")
        else -> null
    }?.take(MAX_TEXT)?.ifBlank { null }

    fun diagnostic(v: JsonValue?): LspDiagnostic? {
        val o = v as? JsonObject ?: return null
        val code = when (val c = o["code"]) {
            is JsonString -> c.value
            is JsonNumber -> c.literal
            else -> null
        }
        return LspDiagnostic(
            range(o["range"]) ?: return null,
            LspSeverity.of((o["severity"] as? JsonNumber)?.toLongExact()),
            code?.take(200),
            str(o["source"])?.take(200),
            (str(o["message"]) ?: return null).take(MAX_TEXT),
        )
    }

    fun location(v: JsonValue?): Location? {
        val o = v as? JsonObject ?: return null
        // LocationLink: targetUri и targetSelectionRange.
        val uri = str(o["uri"]) ?: str(o["targetUri"]) ?: return null
        val range = range(o["range"]) ?: range(o["targetSelectionRange"]) ?: return null
        return Location(uri, range)
    }

    fun locations(v: JsonValue?): List<Location>? = when (v) {
        null, JsonNull -> emptyList()
        is JsonObject -> location(v)?.let(::listOf)
        is JsonArray -> v.items.take(MAX_ITEMS).map { location(it) ?: return null }
        else -> null
    }

    fun completion(v: JsonValue?): CompletionList? {
        val (items, incomplete) = when (v) {
            null, JsonNull -> return CompletionList(emptyList(), false)
            is JsonArray -> v.items to false
            is JsonObject -> ((v["items"] as? JsonArray)?.items ?: return null) to ((v["isIncomplete"] as? JsonBool)?.value ?: false)
            else -> return null
        }
        val parsed = items.take(MAX_ITEMS).map { completionItem(it) ?: return null }
        return CompletionList(parsed, incomplete || items.size > MAX_ITEMS)
    }

    fun completionItem(value: JsonValue?): CompletionItem? {
        val o = value as? JsonObject ?: return null
        val label = str(o["label"]) ?: return null
        val edit = if (o["textEdit"] == null || o["textEdit"] == JsonNull) null else textEdit(o["textEdit"]) ?: return null
        return CompletionItem(
            label = label, kind = int(o["kind"]), detail = str(o["detail"])?.take(MAX_TEXT),
            documentation = markup(o["documentation"]), sortText = str(o["sortText"]), filterText = str(o["filterText"]),
            insertText = edit?.newText ?: str(o["insertText"]) ?: label, edit = edit,
            additional = textEdits(o["additionalTextEdits"]) ?: return null,
            unsupported = o["command"] != null && o["command"] != JsonNull || o["insertTextFormat"] != null && int(o["insertTextFormat"]) != 1,
            payload = o,
        )
    }

    /** Ответ `textDocument/diagnostic`: полный список; «не изменилось» — список `null`. */
    data class DocumentDiagnostics(val items: List<LspDiagnostic>?)

    fun documentDiagnostics(v: JsonValue?): DocumentDiagnostics? {
        val o = v as? JsonObject ?: return null
        return when (str(o["kind"])) {
            "unchanged" -> DocumentDiagnostics(null)
            "full" -> DocumentDiagnostics(((o["items"] as? JsonArray)?.items ?: return null).take(MAX_ITEMS).mapNotNull { diagnostic(it) })
            else -> null
        }
    }

    /** Иерархия `DocumentSymbol` или плоский `SymbolInformation` (без детей); глубже [depth] — обрезается. */
    fun symbols(v: JsonValue?, depth: Int = 0): List<CodeSymbol>? {
        if (v == null || v == JsonNull) return emptyList()
        val a = v as? JsonArray ?: return null
        return a.items.take(MAX_ITEMS).map { item ->
            val o = item as? JsonObject ?: return null
            val name = str(o["name"])?.take(200) ?: return null
            val kind = int(o["kind"]) ?: 0
            val range = range(o["range"]) ?: range((o["location"] as? JsonObject)?.get("range")) ?: return null
            val children = if (depth >= 16) emptyList() else symbols(o["children"], depth + 1) ?: return null
            CodeSymbol(name, kind, range, children)
        }
    }

    fun hover(v: JsonValue?): Hover? {
        val o = v as? JsonObject ?: return null
        return Hover(markup(o["contents"]) ?: return null, range(o["range"]))
    }

    fun signatureHelp(v: JsonValue?): SignatureHelp? {
        val o = v as? JsonObject ?: return null
        val list = (o["signatures"] as? JsonArray)?.items ?: return null
        val signatures = list.take(MAX_ITEMS).map { s ->
            val so = s as? JsonObject ?: return null
            val label = str(so["label"]) ?: return null
            val params = ((so["parameters"] as? JsonArray)?.items ?: emptyList()).map { p ->
                when (val l = (p as? JsonObject)?.get("label")) {
                    is JsonArray -> {
                        val from = int(l.items.getOrNull(0))
                        val to = int(l.items.getOrNull(1))
                        if (from != null && to != null && from <= to && to <= label.length) from until to else null
                    }
                    is JsonString -> label.indexOf(l.value).takeIf { it >= 0 && l.value.isNotEmpty() }?.let { it until it + l.value.length }
                    else -> null
                }
            }
            Signature(label.take(MAX_TEXT), markup(so["documentation"]), params, int(so["activeParameter"]))
        }
        return SignatureHelp(signatures, int(o["activeSignature"]) ?: 0, int(o["activeParameter"]))
    }

    /** `changes` и `documentChanges` с правками текста; создание, переименование и удаление файлов — `null`: не поддержано. */
    fun workspaceEdit(v: JsonValue?): WorkspaceEdit? {
        if (v == null || v == JsonNull) return WorkspaceEdit(emptyMap())
        val o = v as? JsonObject ?: return null
        val out = linkedMapOf<String, MutableList<TextEdit>>()
        if (o["changes"] != null && o["changes"] != JsonNull && o["changes"] !is JsonObject) return null
        if (o["documentChanges"] != null && o["documentChanges"] != JsonNull && o["documentChanges"] !is JsonArray) return null
        val legacy = (o["changes"] as? JsonObject)?.fields.orEmpty()
        val documents = (o["documentChanges"] as? JsonArray)?.items.orEmpty()
        if (legacy.size > MAX_ITEMS || documents.size > MAX_ITEMS) return null
        var count = 0
        for ((uri, value) in legacy) {
            val edits = textEdits(value) ?: return null
            count += edits.size
            if (count > WorkspaceEdit.MAX_EDITS) return null
            out[uri] = edits.toMutableList()
        }
        val versions = linkedMapOf<String, Int?>()
        documents.forEach { change ->
            val co = change as? JsonObject ?: return null
            if (co["kind"] != null) return null
            val document = co["textDocument"] as? JsonObject ?: return null
            val uri = str(document["uri"]) ?: return null
            val version = when (val number = document["version"]) {
                null, JsonNull -> null
                is JsonNumber -> number.toLongExact()?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt() ?: return null
                else -> return null
            }
            if (uri in versions && versions[uri] != version) return null
            // Версионированные правки этого адреса главнее changes; два представления
            // одной замены не применяются дважды. Разные файлы прежних ответов сохранены.
            if (uri !in versions) count -= out.remove(uri)?.size ?: 0
            versions[uri] = version
            val edits = textEdits(co["edits"]) ?: return null
            count += edits.size
            if (count > WorkspaceEdit.MAX_EDITS || uri !in out && out.size >= MAX_ITEMS) return null
            // Повторный URI накапливается линейно, без копирования всего списка на каждом шаге.
            out.getOrPut(uri) { arrayListOf() }.addAll(edits)
        }
        return WorkspaceEdit(out.mapValues { it.value.toList() }, versions)
    }

    fun codeActions(v: JsonValue?): List<CodeAction>? {
        if (v == null || v == JsonNull) return emptyList()
        val a = v as? JsonArray ?: return null
        return a.items.take(MAX_ITEMS).map { codeAction(it) ?: return null }
    }

    fun codeAction(value: JsonValue?): CodeAction? {
        val o = value as? JsonObject ?: return null
        val title = str(o["title"]) ?: return null
        val disabled = when (val d = o["disabled"]) {
            null, JsonNull -> null
            is JsonObject -> str(d["reason"])?.take(500) ?: return null
            else -> return null
        }
        val rawEdit = o["edit"]
        var edit = if (rawEdit == null || rawEdit == JsonNull) null else workspaceEdit(rawEdit) ?: return null
        val command = when (val c = o["command"]) {
            null, JsonNull -> null
            is JsonString -> o // прежняя голая Command
            is JsonObject -> c
            else -> return null
        }
        var requiresCommand = command != null
        if (command != null && str(command["command"]) == null) return null
        // clangd.applyFix — документированный контейнер WorkspaceEdit, не выполнение кода.
        if (command != null && str(command["command"]) == "clangd.applyFix" && edit == null) {
            val args = (command["arguments"] as? JsonArray)?.items ?: return null
            if (args.size != 1) return null
            edit = workspaceEdit(args.single()) ?: return null
            requiresCommand = false
        }
        return CodeAction(title.take(500), str(o["kind"]), edit, (o["isPreferred"] as? JsonBool)?.value ?: false,
            disabledReason = disabled, requiresCommand = requiresCommand, payload = o)
    }

    fun capabilities(v: JsonValue?): ServerCapabilities {
        val o = v as? JsonObject ?: JsonObject(emptyMap())
        fun has(name: String): Boolean = when (val p = o[name]) {
            null, JsonNull -> false
            is JsonBool -> p.value
            else -> true
        }
        fun triggers(name: String): Set<String> =
            ((o[name] as? JsonObject)?.get("triggerCharacters") as? JsonArray)?.items?.mapNotNull { str(it) }?.take(64)?.toSet().orEmpty()
        val sync = when (val s = o["textDocumentSync"]) {
            is JsonNumber -> s.toLongExact()
            is JsonObject -> (s["change"] as? JsonNumber)?.toLongExact()
            else -> null
        }
        return ServerCapabilities(
            incrementalSync = sync == 2L,
            completion = has("completionProvider"),
            completionTriggers = triggers("completionProvider"),
            hover = has("hoverProvider"),
            signatureHelp = has("signatureHelpProvider"),
            signatureTriggers = triggers("signatureHelpProvider"),
            definition = has("definitionProvider"),
            references = has("referencesProvider"),
            rename = has("renameProvider"),
            formatting = has("documentFormattingProvider"),
            codeAction = has("codeActionProvider"),
            pullDiagnostics = has("diagnosticProvider"),
            declaration = has("declarationProvider"),
            documentSymbols = has("documentSymbolProvider"),
            codeActionResolve = ((o["codeActionProvider"] as? JsonObject)?.get("resolveProvider") as? JsonBool)?.value == true,
            completionResolve = ((o["completionProvider"] as? JsonObject)?.get("resolveProvider") as? JsonBool)?.value == true,
            executeCommands = (((o["executeCommandProvider"] as? JsonObject)?.get("commands") as? JsonArray)?.items.orEmpty()).take(128).mapNotNull { str(it) }.toSet(),
        )
    }

    fun toJson(p: Position): JsonObject = JsonObject.of("line" to json(p.line), "character" to json(p.character))

    fun toJson(r: Range): JsonObject = JsonObject.of("start" to toJson(r.start), "end" to toJson(r.end))

    fun toJson(d: LspDiagnostic): JsonObject {
        val m = linkedMapOf<String, JsonValue>("range" to toJson(d.range), "message" to json(d.message), "severity" to json(d.severity.ordinal + 1))
        d.code?.let { m["code"] = json(it) }
        d.source?.let { m["source"] = json(it) }
        return JsonObject(m)
    }
}
