package com.dpsmpx.dpai.core.serialization

/**
 * Канонический вид JSON — основа хешей (D-09, D-10), по образцу RFC 8785:
 *
 * - ключи объекта — по порядку единиц UTF-16 (`String.compareTo`);
 * - без пробелов;
 * - строки: экранируются только `"`, `\` и управляющие символы
 *   (`\b \f \n \r \t`, остальные — `\u00xx`), всё прочее — как есть;
 * - числа — в нормальной записи [JsonNumber.canonical].
 *
 * Разбор канонического вида возвращает равное значение, а его повторная
 * запись — ту же строку (`CanonicalJsonTest`).
 */
object CanonicalJson {
    fun write(value: JsonValue): String = StringBuilder().also { append(it, value) }.toString()

    fun bytes(value: JsonValue): ByteArray = write(value).toByteArray(Charsets.UTF_8)

    private fun append(sb: StringBuilder, value: JsonValue) {
        when (value) {
            JsonNull -> sb.append("null")
            is JsonBool -> sb.append(if (value.value) "true" else "false")
            is JsonNumber -> sb.append(value.canonical)
            is JsonString -> appendString(sb, value.value)
            is JsonArray -> {
                sb.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    append(sb, item)
                }
                sb.append(']')
            }
            is JsonObject -> {
                sb.append('{')
                value.fields.keys.sorted().forEachIndexed { i, key ->
                    if (i > 0) sb.append(',')
                    appendString(sb, key)
                    sb.append(':')
                    append(sb, value.fields.getValue(key))
                }
                sb.append('}')
            }
        }
    }

    internal fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append("%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
