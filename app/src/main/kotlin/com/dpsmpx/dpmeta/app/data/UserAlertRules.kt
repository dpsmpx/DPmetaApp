package com.dpsmpx.dpmeta.app.data

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.core.result.then
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpmeta.formats.AlertFormat
import com.dpsmpx.dpmeta.model.EventCatalog

/** Локальное наложение правил D-140: проверка до записи, атомарность и last-good — DocumentStore. */
class UserAlertRules(private val documents: DocumentStore) {
    fun read(): Outcome<String?> = documents.read(KEY).then { document ->
        if (document == null) return@then Outcome.success(null)
        val diagnostics = DiagnosticCollector()
        val fields = JsonFields.root(document.content, diagnostics) ?: return@then Outcome.Failure(diagnostics.all)
        FormatHeader.read(fields, FORMAT, VERSION..VERSION, diagnostics) ?: return@then Outcome.Failure(diagnostics.all)
        val text = fields.string("text")
        if (text != null && text.length > MAX_TEXT) return@then Outcome.failure(DiagCode.VALUE_INVALID, "файл правил превышает допустимый размер")
        fields.finish()
        if (diagnostics.hasErrors) Outcome.Failure(diagnostics.all) else Outcome.success(text)
    }

    fun save(text: String, catalog: EventCatalog): Outcome<Unit> {
        if (text.length > MAX_TEXT) return Outcome.failure(DiagCode.VALUE_INVALID, "правила — не больше $MAX_TEXT знаков")
        return AlertFormat.parse(text, catalog, "локальные правила").then {
            documents.write(KEY, JsonObject.of(*FormatHeader.write(FORMAT, VERSION), "text" to JsonString(text))).map { }
        }
    }

    fun reset(): Outcome<Unit> = documents.delete(KEY)

    companion object {
        const val FORMAT = "dpmeta.alert-rules"
        const val VERSION = 1
        const val MAX_TEXT = 65536
        private const val KEY = "alert-rules"

        /** Импорт ограничен до разбора, размер файла не определяет расход памяти. */
        fun importText(reader: java.io.Reader): Outcome<String> {
            val text = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_TEXT + 1 - text.length))
                if (count < 0) return Outcome.success(text.toString())
                text.append(buffer, 0, count)
                if (text.length > MAX_TEXT) return Outcome.failure(DiagCode.VALUE_INVALID, "правила — не больше $MAX_TEXT знаков")
            }
        }
    }
}
