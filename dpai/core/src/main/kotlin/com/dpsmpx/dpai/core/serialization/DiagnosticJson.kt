package com.dpsmpx.dpai.core.serialization

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.diagnostics.Severity

/**
 * Диагностика в JSON — для ответов между процессами (агент target ↔
 * контроллер): код, серьёзность, сообщение, предмет, причины. Сводка
 * исключения не передаётся: тип и текст уже в сообщении (D-05).
 * Неизвестный код — ошибка чтения, а не «похожий» код (D-05).
 */
object DiagnosticJson {
    private const val MAX_DEPTH = 8

    fun write(d: Diagnostic): JsonObject {
        val fields = linkedMapOf<String, JsonValue>(
            "code" to JsonString(d.code.name),
            "severity" to JsonString(d.severity.name),
            "message" to JsonString(d.message),
        )
        d.subject?.let { fields["subject"] = JsonString(it) }
        if (d.causes.isNotEmpty()) fields["causes"] = JsonArray(d.causes.map(::write))
        return JsonObject(fields)
    }

    fun writeAll(ds: List<Diagnostic>): JsonArray = JsonArray(ds.map(::write))

    /** Чтение массива; находки чтения — в [diags], путь — [path]. */
    fun readAll(value: JsonArray, path: String, diags: DiagnosticCollector): List<Diagnostic> =
        value.items.mapIndexedNotNull { i, item -> read(item, JsonPath.index(path, i), diags, 0) }

    private fun read(value: JsonValue, path: String, diags: DiagnosticCollector, depth: Int): Diagnostic? {
        val obj = value as? JsonObject ?: return null.also { diags.error(DiagCode.FIELD_TYPE, "ожидалось: объект", path) }
        val f = JsonFields(obj, path, diags)
        val code = f.string("code")?.let { name ->
            DiagCode.entries.firstOrNull { it.name == name }
                ?: null.also { diags.error(DiagCode.VALUE_INVALID, "неизвестный код диагностики", JsonPath.field(path, "code")) }
        }
        val severity = f.string("severity")?.let { name ->
            Severity.entries.firstOrNull { it.name == name }
                ?: null.also { diags.error(DiagCode.VALUE_INVALID, "неизвестная серьёзность", JsonPath.field(path, "severity")) }
        }
        val message = f.string("message")
        val subject = f.optString("subject")
        val causes = (f.raw("causes", required = false) as? JsonArray)?.let { arr ->
            if (depth >= MAX_DEPTH) {
                diags.error(DiagCode.JSON_TOO_DEEP, "цепочка причин глубже $MAX_DEPTH", JsonPath.field(path, "causes"))
                emptyList()
            } else {
                arr.items.mapIndexedNotNull { i, c -> read(c, JsonPath.index(JsonPath.field(path, "causes"), i), diags, depth + 1) }
            }
        }.orEmpty()
        f.finish()
        if (code == null || severity == null || message == null) return null
        return Diagnostic.of(code, message, subject, severity, causes)
    }
}
