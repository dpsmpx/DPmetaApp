package com.dpsmpx.dpai.core.serialization

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector

/**
 * Чтение полей объекта с путём в диагностике (D-09): отсутствующее
 * обязательное поле, поле не того типа, неизвестное поле — находка с местом
 * (`$.tasks[0].id`), а не исключение и не значение по умолчанию.
 *
 * Каждое прочитанное поле запоминается; [finish] сообщает о полях, которых
 * формат не знает (`FIELD_UNKNOWN`). Чтение продолжается после ошибки, чтобы
 * за один проход собрать все находки.
 */
class JsonFields(private val obj: JsonObject, val path: String, private val diags: DiagnosticCollector) {
    /** Сборщик находок — для расширений, читающих свои типы полей (кодеки модулей). */
    val diagnostics: DiagnosticCollector get() = diags

    private val known = mutableSetOf<String>()

    fun has(name: String): Boolean = obj.fields.containsKey(name)

    fun raw(name: String, required: Boolean = true): JsonValue? {
        known += name
        val v = obj.fields[name]
        if (v == null && required) diags.error(DiagCode.FIELD_MISSING, "нет обязательного поля", JsonPath.field(path, name))
        return v
    }

    fun string(name: String): String? = typed(name, true, "строка") { (it as? JsonString)?.value }

    fun optString(name: String): String? = typed(name, false, "строка") { (it as? JsonString)?.value }

    fun long(name: String): Long? = typed(name, true, "целое число") { (it as? JsonNumber)?.toLongExact() }

    fun optLong(name: String): Long? = typed(name, false, "целое число") { (it as? JsonNumber)?.toLongExact() }

    fun int(name: String): Int? = long(name)?.let { v ->
        if (v in Int.MIN_VALUE..Int.MAX_VALUE) {
            v.toInt()
        } else {
            diags.error(DiagCode.FIELD_TYPE, "число вне диапазона Int", JsonPath.field(path, name))
            null
        }
    }

    fun boolean(name: String): Boolean? = typed(name, true, "true или false") { (it as? JsonBool)?.value }

    fun optBoolean(name: String): Boolean? = typed(name, false, "true или false") { (it as? JsonBool)?.value }

    fun obj(name: String): JsonFields? = typed(name, true, "объект") { it as? JsonObject }?.let { child(name, it) }

    fun optObj(name: String): JsonFields? = typed(name, false, "объект") { it as? JsonObject }?.let { child(name, it) }

    fun array(name: String): JsonArray? = typed(name, true, "массив") { it as? JsonArray }

    /** Поля, которых формат не знает, — ошибка (D-09). */
    fun finish() {
        for (k in obj.fields.keys) {
            if (k !in known) diags.error(DiagCode.FIELD_UNKNOWN, "неизвестное поле", JsonPath.field(path, k))
        }
    }

    private fun child(name: String, o: JsonObject) = JsonFields(o, JsonPath.field(path, name), diags)

    private inline fun <T> typed(name: String, required: Boolean, expected: String, convert: (JsonValue) -> T?): T? {
        val v = raw(name, required) ?: return null
        if (!required && v == JsonNull) return null
        val r = convert(v)
        if (r == null) diags.error(DiagCode.FIELD_TYPE, "ожидалось: $expected", JsonPath.field(path, name))
        return r
    }

    companion object {
        /** Корень документа: объект или находка `FIELD_TYPE`. */
        fun root(value: JsonValue, diags: DiagnosticCollector): JsonFields? =
            (value as? JsonObject)?.let { JsonFields(it, "$", diags) }
                ?: run {
                    diags.error(DiagCode.FIELD_TYPE, "ожидался объект", "$")
                    null
                }
    }
}

/**
 * Заголовок сохраняемого документа `{"format": "dpai.<вид>", "version": N}` (D-09).
 *
 * Чужой формат — `FORMAT_MISMATCH`; версии нет — `FORMAT_VERSION_MISSING`;
 * версия новее поддерживаемой — `FORMAT_VERSION_NEWER` («создано более новой
 * версией»: такой документ не читается и не перезаписывается); старше —
 * `FORMAT_VERSION_UNSUPPORTED` (его дело — миграция, этап 2).
 */
object FormatHeader {
    const val FORMAT = "format"
    const val VERSION = "version"

    fun read(fields: JsonFields, format: String, supported: IntRange, diags: DiagnosticCollector): Int? {
        val actual = fields.string(FORMAT) ?: return null
        if (actual != format) {
            diags.error(DiagCode.FORMAT_MISMATCH, "документ другого формата, ожидался $format", "$.$FORMAT")
            return null
        }
        if (!fields.has(VERSION)) {
            fields.raw(VERSION, required = false)
            diags.error(DiagCode.FORMAT_VERSION_MISSING, "нет версии формата", "$.$VERSION")
            return null
        }
        val v = fields.int(VERSION) ?: return null
        return when {
            v > supported.last -> {
                diags.error(DiagCode.FORMAT_VERSION_NEWER, "создано более новой версией: $v > ${supported.last}", "$.$VERSION")
                null
            }
            v < supported.first -> {
                diags.error(DiagCode.FORMAT_VERSION_UNSUPPORTED, "версия $v не поддерживается: нужна ${supported.first}…${supported.last}", "$.$VERSION")
                null
            }
            else -> v
        }
    }

    fun write(format: String, version: Int): Array<Pair<String, JsonValue>> =
        arrayOf(FORMAT to JsonString(format), VERSION to JsonNumber.of(version))
}
