package com.dpsmpx.dppass.core

/**
 * Строгий JSON для документов DPpass (D-05, «без молчаливых изменений»).
 *
 * Свой, а не kotlinx.serialization, потому что нужны три свойства,
 * которых нет у готового разбора деревом:
 * - **повторный ключ — ошибка**, а не тихая перезапись
 *   (`{"port":443,"port":80}`);
 * - **ограничение вложенности** до разбора вглубь — без переполнения
 *   стека на `[[[[…` (находка из fuzz-набора);
 * - **сообщение об ошибке без цитаты входа** — вход содержит секреты;
 *   ошибка несёт только позицию.
 * Числа хранятся текстом: преобразование — у читателя, с проверкой
 * диапазона.
 */
sealed interface JsonValue

data object JsonNull : JsonValue

data class JsonBool(val value: Boolean) : JsonValue

data class JsonNumber(val text: String) : JsonValue {
    fun toLongOrNull(): Long? = if ('.' in text || 'e' in text || 'E' in text) null else text.toLongOrNull()
    fun toIntOrNull(): Int? = toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}

data class JsonString(val value: String) : JsonValue

data class JsonArray(val items: List<JsonValue>) : JsonValue

/** Порядок ключей сохраняется: запись документа детерминирована. */
data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = fields[key]
}

class JsonParseException(
    val reason: String,
    val offset: Int,
    val line: Int,
    val column: Int,
    val tooDeep: Boolean = false,
    val duplicateKey: String? = null,
) : Exception("$reason (строка $line, столбец $column)")

object Json {
    const val DEFAULT_MAX_DEPTH: Int = 64

    fun parse(text: String, maxDepth: Int = DEFAULT_MAX_DEPTH): JsonValue = Parser(text, maxDepth).parseDocument()

    fun write(value: JsonValue, pretty: Boolean = true): String = buildString { Writer(this, pretty).write(value, 0) }

    fun obj(vararg pairs: Pair<String, JsonValue?>): JsonObject {
        val m = LinkedHashMap<String, JsonValue>()
        for ((k, v) in pairs) if (v != null) m[k] = v
        return JsonObject(m)
    }

    fun str(s: String?): JsonString? = s?.let(::JsonString)
    fun num(n: Long): JsonNumber = JsonNumber(n.toString())
    fun num(n: Int): JsonNumber = JsonNumber(n.toString())
    fun bool(b: Boolean): JsonBool = JsonBool(b)
    fun arr(items: List<JsonValue>): JsonArray = JsonArray(items)
    fun strings(items: List<String>): JsonArray = JsonArray(items.map(::JsonString))

    private class Parser(private val s: String, private val maxDepth: Int) {
        private var i = 0

        fun parseDocument(): JsonValue {
            skipWs()
            val v = value(0)
            skipWs()
            if (i < s.length) fail("лишние данные после документа")
            return v
        }

        private fun value(depth: Int): JsonValue {
            if (i >= s.length) fail("неожиданный конец документа")
            return when (val c = s[i]) {
                '{' -> obj(depth + 1)
                '[' -> arr(depth + 1)
                '"' -> JsonString(string())
                't' -> literal("true", JsonBool(true))
                'f' -> literal("false", JsonBool(false))
                'n' -> literal("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') number() else fail("неожиданный символ")
            }
        }

        private fun checkDepth(depth: Int) {
            if (depth > maxDepth) throw error("вложенность больше $maxDepth", tooDeep = true)
        }

        private fun obj(depth: Int): JsonObject {
            checkDepth(depth)
            i++ // {
            val m = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (peek() == '}') {
                i++
                return JsonObject(m)
            }
            while (true) {
                skipWs()
                if (peek() != '"') fail("ожидался ключ в кавычках")
                val keyOffset = i
                val key = string()
                skipWs()
                expect(':')
                skipWs()
                val v = value(depth)
                if (m.containsKey(key)) {
                    i = keyOffset
                    throw error("повторный ключ «$key»", duplicateKey = key)
                }
                m[key] = v
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return JsonObject(m)
                    }
                    else -> fail("ожидалась «,» или «}»")
                }
            }
        }

        private fun arr(depth: Int): JsonArray {
            checkDepth(depth)
            i++ // [
            val out = mutableListOf<JsonValue>()
            skipWs()
            if (peek() == ']') {
                i++
                return JsonArray(out)
            }
            while (true) {
                skipWs()
                out += value(depth)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return JsonArray(out)
                    }
                    else -> fail("ожидалась «,» или «]»")
                }
            }
        }

        private fun string(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("незакрытая строка")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("незакрытая строка")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("обрезанная \\u-последовательность")
                                val hex = s.substring(i, i + 4)
                                if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) fail("неверная \\u-последовательность")
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> {
                                i--
                                fail("неверная escape-последовательность «\\$e»")
                            }
                        }
                    }
                    c < ' ' -> {
                        i--
                        fail("управляющий символ внутри строки")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): JsonNumber {
            val start = i
            if (peek() == '-') i++
            if (peek() == '0') {
                i++
            } else if (peek() in '1'..'9') {
                while (peek() in '0'..'9') i++
            } else {
                fail("неверное число")
            }
            if (peek() == '.') {
                i++
                if (peek() !in '0'..'9') fail("неверное число")
                while (peek() in '0'..'9') i++
            }
            if (peek() == 'e' || peek() == 'E') {
                i++
                if (peek() == '+' || peek() == '-') i++
                if (peek() !in '0'..'9') fail("неверное число")
                while (peek() in '0'..'9') i++
            }
            if (i - start > 64) {
                i = start
                fail("слишком длинное число")
            }
            return JsonNumber(s.substring(start, i))
        }

        private fun literal(word: String, v: JsonValue): JsonValue {
            if (!s.startsWith(word, i)) fail("неизвестное слово")
            i += word.length
            return v
        }

        private fun expect(c: Char) {
            if (peek() != c) fail("ожидался «$c»")
            i++
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        private fun fail(reason: String): Nothing = throw error(reason)

        private fun error(reason: String, tooDeep: Boolean = false, duplicateKey: String? = null): JsonParseException {
            var line = 1
            var col = 1
            for (k in 0 until minOf(i, s.length)) {
                if (s[k] == '\n') {
                    line++
                    col = 1
                } else {
                    col++
                }
            }
            return JsonParseException(reason, i, line, col, tooDeep, duplicateKey)
        }
    }

    private class Writer(private val out: StringBuilder, private val pretty: Boolean) {
        fun write(v: JsonValue, indent: Int) {
            when (v) {
                JsonNull -> out.append("null")
                is JsonBool -> out.append(v.value)
                is JsonNumber -> out.append(v.text)
                is JsonString -> string(v.value)
                is JsonArray -> container('[', ']', v.items.size, indent) { k -> write(v.items[k], indent + 1) }
                is JsonObject -> {
                    val entries = v.fields.entries.toList()
                    container('{', '}', entries.size, indent) { k ->
                        string(entries[k].key)
                        out.append(if (pretty) ": " else ":")
                        write(entries[k].value, indent + 1)
                    }
                }
            }
        }

        private inline fun container(open: Char, close: Char, n: Int, indent: Int, item: (Int) -> Unit) {
            out.append(open)
            if (n == 0) {
                out.append(close)
                return
            }
            for (k in 0 until n) {
                if (k > 0) out.append(',')
                newline(indent + 1)
                item(k)
            }
            newline(indent)
            out.append(close)
        }

        private fun newline(indent: Int) {
            if (!pretty) return
            out.append('\n')
            repeat(indent) { out.append("  ") }
        }

        private fun string(s: String) {
            out.append('"')
            for (c in s) {
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> out.append("\\r")
                    c == '\t' -> out.append("\\t")
                    c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> out.append(c)
                }
            }
            out.append('"')
        }
    }
}
