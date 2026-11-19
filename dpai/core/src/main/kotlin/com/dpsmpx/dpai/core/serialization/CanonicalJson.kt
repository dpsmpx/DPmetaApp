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
        var from = 0
        for (i in s.indices) {
            val c = s[i]
            val escaped = when (c) {
                '"' -> "\\\""
                '\\' -> "\\\\"
                '\b' -> "\\b"
                '\u000C' -> "\\f"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> null
            }
            if (escaped != null || c < ' ') {
                // Неэкранируемый участок копируется целиком, без append на каждый символ.
                sb.append(s, from, i)
                if (escaped != null) sb.append(escaped)
                else sb.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 15])
                from = i + 1
            }
        }
        sb.append(s, from, s.length)
        sb.append('"')
    }

    private const val HEX = "0123456789abcdef"
}
