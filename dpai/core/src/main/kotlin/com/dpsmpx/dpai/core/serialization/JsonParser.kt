package com.dpsmpx.dpai.core.serialization

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Строгий разбор JSON (D-09, RFC 8259 без послаблений):
 *
 * - размер и глубина ограничены до и во время разбора ([Limits],
 *   `docs/PERFORMANCE.md`, §2);
 * - повтор ключа в объекте — `JSON_DUPLICATE_KEY`, а не «последний побеждает»;
 * - управляющие символы в строках, одиночные суррогаты, ведущие нули,
 *   `NaN`, комментарии, лишние запятые — `JSON_SYNTAX`;
 * - сообщение об ошибке называет место, но не цитирует вход: во входе
 *   может быть секрет (D-14).
 */
class JsonParser(private val limits: Limits = Limits()) {
    data class Limits(val maxChars: Int = 16 * 1024 * 1024, val maxDepth: Int = 64) {
        init {
            require(maxChars > 0 && maxDepth > 0) { "пределы положительны" }
        }
    }

    fun parse(text: String): Outcome<JsonValue> {
        if (text.isBlank()) return Outcome.failure(DiagCode.JSON_EMPTY, "пустой документ")
        if (text.length > limits.maxChars) {
            return Outcome.failure(DiagCode.JSON_TOO_LARGE, "документ больше ${limits.maxChars} знаков")
        }
        val reader = Reader(text, limits.maxDepth)
        return try {
            reader.skipWs()
            val value = reader.value(0)
            reader.skipWs()
            if (!reader.atEnd()) reader.fail(DiagCode.JSON_SYNTAX, "лишнее после значения")
            Outcome.success(value)
        } catch (e: ParseError) {
            Outcome.failure(e.code, e.message ?: "ошибка разбора", e.where)
        }
    }

    private class ParseError(val code: DiagCode, message: String, val where: String) : RuntimeException(message)

    private class Reader(val s: String, val maxDepth: Int) {
        var i = 0
        // Не копируем весь путь для каждого значения (D-129): имя может занимать мегабайты.
        private val path = ArrayList<Any>()

        private fun pathAt(key: String): String = buildString {
            append('$')
            for (step in path) {
                if (step is String) JsonPath.appendField(this, step) else append('[').append(step).append(']')
            }
            JsonPath.appendField(this, key)
        }

        private fun child(step: Any, depth: Int): JsonValue {
            path.add(step)
            return try {
                value(depth)
            } finally {
                path.removeAt(path.lastIndex)
            }
        }

        fun atEnd() = i >= s.length

        fun fail(code: DiagCode, message: String, at: Int = i): Nothing {
            var line = 1
            var col = 1
            for (k in 0 until minOf(at, s.length)) {
                if (s[k] == '\n') {
                    line++
                    col = 1
                } else {
                    col++
                }
            }
            throw ParseError(code, message, "строка $line, столбец $col")
        }

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(depth: Int): JsonValue {
            if (atEnd()) fail(DiagCode.JSON_SYNTAX, "неожиданный конец документа")
            return when (val c = s[i]) {
                '{' -> obj(depth + 1)
                '[' -> array(depth + 1)
                '"' -> JsonString(string())
                't' -> literal("true", JsonBool(true))
                'f' -> literal("false", JsonBool(false))
                'n' -> literal("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') number() else fail(DiagCode.JSON_SYNTAX, "ожидалось значение")
            }
        }

        fun literal(word: String, v: JsonValue): JsonValue {
            if (!s.startsWith(word, i)) fail(DiagCode.JSON_SYNTAX, "ожидалось значение")
            i += word.length
            return v
        }

        fun obj(depth: Int): JsonObject {
            if (depth > maxDepth) fail(DiagCode.JSON_TOO_DEEP, "вложенность больше $maxDepth")
            i++ // {
            val fields = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return JsonObject(fields)
            }
            while (true) {
                skipWs()
                if (atEnd() || s[i] != '"') fail(DiagCode.JSON_SYNTAX, "ожидалось имя поля в кавычках")
                val key = string()
                if (key in fields) throw ParseError(DiagCode.JSON_DUPLICATE_KEY, "повтор ключа", pathAt(key))
                skipWs()
                if (atEnd() || s[i] != ':') fail(DiagCode.JSON_SYNTAX, "ожидалось «:»")
                i++
                skipWs()
                fields[key] = child(key, depth)
                skipWs()
                if (atEnd()) fail(DiagCode.JSON_SYNTAX, "незакрытый объект")
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return JsonObject(fields)
                    }
                    else -> fail(DiagCode.JSON_SYNTAX, "ожидалось «,» или «}»")
                }
            }
        }

        fun array(depth: Int): JsonArray {
            if (depth > maxDepth) fail(DiagCode.JSON_TOO_DEEP, "вложенность больше $maxDepth")
            i++ // [
            val items = ArrayList<JsonValue>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return JsonArray(items)
            }
            while (true) {
                skipWs()
                items += child(items.size, depth)
                skipWs()
                if (atEnd()) fail(DiagCode.JSON_SYNTAX, "незакрытый массив")
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return JsonArray(items)
                    }
                    else -> fail(DiagCode.JSON_SYNTAX, "ожидалось «,» или «]»")
                }
            }
        }

        fun string(): String {
            i++ // "
            val start = i
            var from = i
            var sb: StringBuilder? = null
            while (true) {
                if (atEnd()) fail(DiagCode.JSON_SYNTAX, "незакрытая строка")
                val c = s[i]
                when {
                    c == '"' -> {
                        val end = i
                        i++
                        val builder = sb ?: return s.substring(start, end)
                        return builder.append(s, from, end).toString()
                    }
                    c == '\\' -> {
                        val out = sb ?: StringBuilder().also { sb = it }
                        out.append(s, from, i)
                        i++
                        if (atEnd()) fail(DiagCode.JSON_SYNTAX, "незакрытая строка")
                        when (s[i]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                val cp = hex4(i + 1)
                                i += 4
                                if (Character.isHighSurrogate(cp)) {
                                    if (!(s.startsWith("\\u", i + 1))) fail(DiagCode.JSON_SYNTAX, "одиночный суррогат")
                                    val low = hex4(i + 3)
                                    if (!Character.isLowSurrogate(low)) fail(DiagCode.JSON_SYNTAX, "одиночный суррогат")
                                    out.append(cp).append(low)
                                    i += 6
                                } else if (Character.isLowSurrogate(cp)) {
                                    fail(DiagCode.JSON_SYNTAX, "одиночный суррогат")
                                } else {
                                    out.append(cp)
                                }
                            }
                            else -> fail(DiagCode.JSON_SYNTAX, "неизвестное экранирование")
                        }
                        i++
                        from = i
                    }
                    c < ' ' -> fail(DiagCode.JSON_SYNTAX, "управляющий символ в строке")
                    Character.isHighSurrogate(c) -> {
                        if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) {
                            fail(DiagCode.JSON_SYNTAX, "одиночный суррогат")
                        }
                        i += 2
                    }
                    Character.isLowSurrogate(c) -> fail(DiagCode.JSON_SYNTAX, "одиночный суррогат")
                    else -> {
                        i++
                    }
                }
            }
        }

        fun hex4(at: Int): Char {
            if (at + 4 > s.length) fail(DiagCode.JSON_SYNTAX, "неполное \\u-экранирование")
            var v = 0
            for (k in at until at + 4) {
                val d = Character.digit(s[k], 16)
                if (d < 0) fail(DiagCode.JSON_SYNTAX, "неверное \\u-экранирование", k)
                v = v * 16 + d
            }
            return v.toChar()
        }

        fun number(): JsonNumber {
            val start = i
            if (s[i] == '-') i++
            if (atEnd()) fail(DiagCode.JSON_SYNTAX, "неполное число")
            if (s[i] == '0') {
                i++
                if (i < s.length && s[i] in '0'..'9') fail(DiagCode.JSON_SYNTAX, "ведущий ноль в числе")
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else {
                fail(DiagCode.JSON_SYNTAX, "неполное число")
            }
            if (i < s.length && s[i] == '.') {
                i++
                if (atEnd() || s[i] !in '0'..'9') fail(DiagCode.JSON_SYNTAX, "нет цифр после точки")
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val expStart = i
                if (atEnd() || s[i] !in '0'..'9') fail(DiagCode.JSON_SYNTAX, "нет цифр в порядке числа")
                while (i < s.length && s[i] in '0'..'9') i++
                val exp = s.substring(expStart, i).trimStart('0')
                if (exp.length > 4) fail(DiagCode.JSON_NUMBER_OUT_OF_RANGE, "порядок числа больше 9999", start)
            }
            if (i - start > MAX_NUMBER_CHARS) {
                fail(DiagCode.JSON_NUMBER_OUT_OF_RANGE, "число длиннее $MAX_NUMBER_CHARS знаков", start)
            }
            return JsonNumber(s.substring(start, i))
        }

        companion object {
            const val MAX_NUMBER_CHARS = 1000
        }
    }
}

/** Путь в документе в нотации `$.a.b[0]`; имя не из букв и цифр — `$["имя"]`. */
object JsonPath {
    private val SIMPLE = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun field(parent: String, name: String): String = buildString {
        append(parent)
        appendField(this, name)
    }

    internal fun appendField(out: StringBuilder, name: String) {
        if (SIMPLE.matches(name)) {
            out.append('.').append(name)
        } else {
            out.append('[')
            CanonicalJson.appendString(out, name)
            out.append(']')
        }
    }

    fun index(parent: String, index: Int): String = "$parent[$index]"
}
