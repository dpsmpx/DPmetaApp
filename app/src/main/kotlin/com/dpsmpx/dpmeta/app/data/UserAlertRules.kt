package com.dpsmpx.dpmeta.app.data

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.core.result.then
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpmeta.formats.AlertFormat
import com.dpsmpx.dpmeta.formats.Toml
import com.dpsmpx.dpmeta.model.EventCatalog
import com.dpsmpx.dpmeta.model.AlertRules
import com.dpsmpx.dpmeta.model.AlertAction

/** Локальное наложение правил D-140: проверка до записи, атомарность и last-good — DocumentStore. */
class UserAlertRules(private val documents: DocumentStore) {
    private data class Stored(val text: String, val version: Int)

    private fun configured(): Outcome<Stored?> = documents.read(KEY).then { document ->
        if (document == null) return@then Outcome.success(null)
        val diagnostics = DiagnosticCollector()
        val fields = JsonFields.root(document.content, diagnostics) ?: return@then Outcome.Failure(diagnostics.all)
        val version = FormatHeader.read(fields, FORMAT, 1..VERSION, diagnostics) ?: return@then Outcome.Failure(diagnostics.all)
        val text = fields.string("text")
        if (text != null && text.length > MAX_TEXT) return@then Outcome.failure(DiagCode.VALUE_INVALID, "файл правил превышает допустимый размер")
        fields.finish()
        if (diagnostics.hasErrors || text == null) Outcome.Failure(diagnostics.all) else Outcome.success(Stored(text, version))
    }

    fun read(): Outcome<String?> = configured().map { it?.text }

    /** v1 не знала обычных событий ИИ: новые правила дополняют её без фоновой перезаписи. */
    fun effective(defaults: AlertRules, catalog: EventCatalog): Outcome<AlertRules> = configured().then { saved ->
        if (saved == null) Outcome.success(defaults) else validate(saved.text, catalog).map { rules ->
            if (saved.version == 1) rules.copy(rules = rules.rules + additions(rules.rules.map { it.id }.toSet(), defaults)) else rules
        }
    }

    fun editorText(defaults: AlertRules): Outcome<String> = configured().then { saved ->
        if (saved == null) AlertFormat.write(defaults) else if (saved.version == 1) {
            val parsed = Toml.parse(saved.text).valueOrNull()
            val ids = (parsed?.get("rule") as? JsonArray)?.items.orEmpty().mapNotNull { ((it as? JsonObject)?.get("id") as? JsonString)?.value }.toSet()
            AlertFormat.write(defaults.copy(rules = additions(ids, defaults))).map { extra ->
                saved.text + "\n\n# Стандартные правила ИИ v2; после сохранения они станут явной частью настройки.\n" + extra.substringAfter('\n')
            }
        } else Outcome.success(saved.text)
    }

    fun save(text: String, catalog: EventCatalog): Outcome<Unit> {
        return validate(text, catalog).then {
            documents.write(KEY, JsonObject.of(*FormatHeader.write(FORMAT, VERSION), "text" to JsonString(text))).map { }
        }
    }

    fun reset(): Outcome<Unit> = documents.delete(KEY)

    private fun additions(ids: Set<String>, defaults: AlertRules): List<com.dpsmpx.dpmeta.model.AlertRule> {
        val used = ids.toMutableSet()
        return defaults.rules.filter { it.on.startsWith("ai.") }.map { rule ->
            var id = rule.id
            var suffix = 1
            while (id in used) { id = "${rule.id}.compat.$suffix"; suffix++ }
            used += id
            rule.copy(id = id)
        }
    }

    companion object {
        const val FORMAT = "dpmeta.alert-rules"
        const val VERSION = 2
        const val MAX_TEXT = 65536
        private const val KEY = "alert-rules"

        fun validate(text: String, catalog: EventCatalog): Outcome<AlertRules> {
            if (text.length > MAX_TEXT) return Outcome.failure(DiagCode.VALUE_INVALID, "правила — не больше $MAX_TEXT знаков")
            return AlertFormat.parse(text, catalog, "локальные правила").then { rules ->
                if (rules.rules.any { it.enabled && it.action == AlertAction.DIGEST }) {
                    Outcome.failure(DiagCode.UNSUPPORTED, "дайджест по расписанию ещё не поддерживается; выберите уведомление, ленту или тишину")
                } else Outcome.success(rules)
            }
        }

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
