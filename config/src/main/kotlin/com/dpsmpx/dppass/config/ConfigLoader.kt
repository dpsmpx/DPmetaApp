package com.dpsmpx.dppass.config

import com.dpsmpx.dppass.core.Checksums
import com.dpsmpx.dppass.core.Json
import com.dpsmpx.dppass.core.JsonObject
import com.dpsmpx.dppass.core.JsonParseException
import com.dpsmpx.dppass.core.JsonString
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.DiagnosticCollector
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.diagnostics.then

/** Загруженная конфигурация: IR и откуда он взялся. */
data class LoadedConfig(
    val ir: ConfigIr,
    /** SHA-256 исходного текста — как он пришёл, до миграций. */
    val sourceChecksum: String,
    /** Версия формата исходного документа. */
    val sourceVersion: Int,
)

/**
 * Конвейер «документ → IR» (D-05):
 * размер → строгий JSON → маркер формата → миграции → IR →
 * нормализация → проверка смысла.
 *
 * Не бросает исключений на любом входе (fuzz-набор `tests/fuzz`);
 * исходный текст не изменяется — при отказе вызывающий сохраняет его
 * как есть (D-06).
 */
class ConfigLoader(
    private val limits: ConfigLimits = ConfigLimits(),
    private val migrator: Migrator = Migrator(),
) {
    fun load(text: String): Outcome<LoadedConfig> {
        if (text.isBlank()) return Outcome.failure(DiagCode.DOCUMENT_EMPTY, "документ пуст")
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > limits.maxBytes) {
            return Outcome.failure(DiagCode.DOCUMENT_TOO_LARGE, "документ больше ${limits.maxBytes} байт")
        }
        val checksum = Checksums.sha256(bytes)
        return parse(text)
            .then(::checkFormat)
            .then { migrator.toCurrent(it) }
            .then { m -> toIr(m.document).then { ir -> Outcome.Success(LoadedConfig(ir, checksum, m.fromVersion)) } }
    }

    private fun parse(text: String): Outcome<JsonObject> {
        val root = try {
            Json.parse(text, limits.maxDepth)
        } catch (e: JsonParseException) {
            val code = when {
                e.tooDeep -> DiagCode.DOCUMENT_TOO_DEEP
                e.duplicateKey != null -> DiagCode.JSON_DUPLICATE_KEY
                else -> DiagCode.JSON_SYNTAX
            }
            // В сообщении — причина и позиция, без цитаты входа: там секреты.
            return Outcome.failure(code, "${e.reason}: строка ${e.line}, столбец ${e.column}")
        }
        return (root as? JsonObject)?.let { Outcome.Success(it) }
            ?: Outcome.failure(DiagCode.FIELD_TYPE, "документ должен быть объектом JSON", "$")
    }

    private fun checkFormat(root: JsonObject): Outcome<JsonObject> {
        val marker = (root["format"] as? JsonString)?.value
        return if (marker == ConfigFormat.MARKER) {
            Outcome.Success(root)
        } else {
            Outcome.failure(DiagCode.FORMAT_NOT_DPPASS, "это не документ конфигурации DPpass (ожидалось format: \"${ConfigFormat.MARKER}\")", "$.format")
        }
    }

    private fun toIr(document: JsonObject): Outcome<ConfigIr> {
        val diags = DiagnosticCollector()
        val tooLong = longestString(document)
        if (tooLong > limits.maxStringLength) {
            return Outcome.failure(DiagCode.LIMIT_EXCEEDED, "строка длиннее ${limits.maxStringLength} символов")
        }
        val ir = DocumentMapper(diags, limits).map(document)
        if (ir == null || diags.hasErrors) {
            return if (diags.hasErrors) Outcome.Failure(diags.all) else Outcome.failure(DiagCode.INTERNAL_ERROR, "документ не разобран")
        }
        val normalized = Normalizer.normalize(ir)
        diags.addAll(normalized.notes)
        diags.addAll(ConfigValidator.validate(normalized.ir, limits))
        return diags.result { normalized.ir }
    }

    private fun longestString(v: com.dpsmpx.dppass.core.JsonValue): Int = when (v) {
        is JsonString -> v.value.length
        is JsonObject -> maxOf(v.fields.keys.maxOfOrNull { it.length } ?: 0, v.fields.values.maxOfOrNull(::longestString) ?: 0)
        is com.dpsmpx.dppass.core.JsonArray -> v.items.maxOfOrNull(::longestString) ?: 0
        else -> 0
    }

    companion object {
        /** Для ошибок, которые надо показать вместе с исходным текстом: только код и место. */
        fun summary(diagnostics: List<Diagnostic>): String = diagnostics.joinToString("\n") { it.render() }
    }
}
