package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.files.TextFile
import com.dpsmpx.dpai.lsp.DocumentUri
import com.dpsmpx.dpai.lsp.LanguageSession
import com.dpsmpx.dpai.lsp.Position

/** Семантика — только у настоящей службы и только для точного сохранённого текста. */
object DevelopmentSymbols {
    fun inspect(service: LanguageSession, root: String, path: String, bytes: ByteArray, expected: String,
        operation: String, line: Int, column: Int, cancelled: () -> Boolean): Outcome<JsonObject> {
        if (!DevelopmentTools.safePath(path)) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "путь символа недоступен")
        if (Hashing.sha256(bytes).hex != expected) return Outcome.failure(DiagCode.STATE_CONFLICT, "файл изменился; прочитайте его заново")
        val text = when (val r = TextFile.decode(bytes)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        val uri = DocumentUri.ofPath(root.trimEnd('/') + "/" + path)
        val doc = service.main.document(uri)
        if (!service.isRunning || doc?.second != text) return Outcome.failure(DiagCode.STATE_CONFLICT,
            "откройте сохранённый файл в редакторе с работающей языковой службой; черновик не подменяется")
        val row = text.lineSequence().drop(line - 1).firstOrNull()
        if (line < 1 || column < 1 || row == null || column - 1 > row.length ||
            column > 1 && column - 1 < row.length && row[column - 2].isHighSurrogate() && row[column - 1].isLowSurrogate())
            return Outcome.failure(DiagCode.VALUE_INVALID, "позиция вне текста; строка/столбец — с 1, UTF-16")
        if (cancelled()) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "запрос символа отменён")
        val info = service.main.info ?: return unsupported()
        val at = Position(line - 1, column - 1)
        val found: Outcome<List<JsonObject>> = when (operation) {
            "symbols" -> {
                if (!info.capabilities.documentSymbols) return unsupported()
                service.symbols(uri).map { symbols ->
                    val result = arrayListOf<JsonObject>()
                    val queue = ArrayDeque(symbols)
                    while (queue.isNotEmpty() && result.size < 60) {
                        val s = queue.removeFirst()
                        result += JsonObject.of("name" to json(Redactor.STANDARD.redact(s.name).take(200)), "kind" to json(s.kind),
                            "path" to json(path), "line" to json(s.range.start.line + 1), "column" to json(s.range.start.character + 1))
                        queue.addAll(s.children)
                    }
                    result
                }
            }
            "definition", "references", "impact", "incoming", "outgoing" -> {
                if (operation == "definition" && !info.capabilities.definition || operation in setOf("references", "impact") && !info.capabilities.references ||
                    operation in setOf("incoming", "outgoing") && !info.capabilities.callHierarchy) return unsupported()
                val locations = when (operation) {
                    "definition" -> service.definition(uri, at)
                    "references", "impact" -> service.references(uri, at)
                    else -> service.main.calls(uri, at, incoming = operation == "incoming", cancelled = cancelled)
                }
                locations.map { places -> places.mapNotNull { place ->
                    val target = DocumentUri.toPath(place.uri)?.let { BuildProblems.relative(it, root) }?.takeIf(DevelopmentTools::safePath)
                    target?.let { JsonObject.of("path" to json(it), "line" to json(place.range.start.line + 1), "column" to json(place.range.start.character + 1)) }
                }.distinct().take(60) }
            }
            else -> return unsupported()
        }
        if (cancelled()) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "запрос символа отменён")
        if (service.main.document(uri) != doc || !service.isRunning) return Outcome.failure(DiagCode.STATE_CONFLICT, "документ или служба изменились во время запроса")
        return found.map { items -> JsonObject.of("basis" to json("LSP"), "semantic" to json(true), "operation" to json(operation),
            "file" to json(path), "sha256" to json(expected), "documentVersion" to json(doc.first),
            "server" to (info.name?.let { json(Redactor.STANDARD.redact(it).take(100)) } ?: JsonNull),
            "serverVersion" to (info.version?.let { json(Redactor.STANDARD.redact(it).take(100)) } ?: JsonNull),
            "items" to JsonArray(items), "complete" to json(false), "indexCoverageKnown" to json(false),
            "note" to json("Ответ языковой службы для сохранённого текста; полнота индекса проекта и отсутствие внешних связей не доказаны.")) }
    }

    private fun <T> unsupported(): Outcome<T> = Outcome.failure(DiagCode.UNSUPPORTED, "языковая служба не предоставляет эту операцию; текстовый поиск не заменяет семантику")
}
