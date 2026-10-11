package com.dpsmpx.dpmeta.formats

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/**
 * Подмножество TOML 1.0, которого хватает реестру DPmeta (ТЗ, §6.1, §10):
 * комментарии, ключи (простые и в кавычках), строки обычные и буквальные,
 * целые, `true`/`false`, массивы (в несколько строк, с запятой в конце),
 * встроенные таблицы, `[таблица]` и `[[массив таблиц]]`. Неподдержанное —
 * многострочные строки, дробные числа, даты, ключи через точку —
 * ошибка `FORMAT_UNRECOGNIZED` с местом «строка:столбец», а не догадка.
 *
 * Итог — дерево JSON ядра (`dpai/core`): разбор не хранит своих типов.
 */
object Toml {
    fun parse(text: String, source: String = "toml"): Outcome<JsonObject> = try {
        Outcome.success(Parser(text).document())
    } catch (e: TomlError) {
        Outcome.failure(DiagCode.FORMAT_UNRECOGNIZED, e.message ?: "TOML не разобран", "$source:${e.line}:${e.col}")
    }

    private class TomlError(message: String, val line: Int, val col: Int) : Exception(message)

    private class Parser(private val text: String) {
        private var pos = 0
        private var line = 1
        private var col = 1

        private val root = LinkedHashMap<String, Node>()

        /** Таблицы, открытые заголовком: повторное `[x]` — ошибка. */
        private val opened = HashSet<String>()

        private fun fail(message: String): Nothing = throw TomlError(message, line, col)

        private fun peek(offset: Int = 0): Char? = text.getOrNull(pos + offset)

        private fun next(): Char {
            val c = text[pos++]
            if (c == '\n') {
                line++
                col = 1
            } else {
                col++
            }
            return c
        }

        private fun spaces() {
            while (peek() == ' ' || peek() == '\t') next()
        }

        private fun comment() {
            if (peek() == '#') {
                while (peek() != null && peek() != '\n') {
                    val c = next()
                    if (c.isISOControl() && c != '\t' && c != '\r') fail("управляющий знак в комментарии")
                }
            }
        }

        /** Конец строки: пробелы, комментарий, перевод строки или конец файла. */
        private fun endOfLine() {
            spaces()
            comment()
            when (peek()) {
                null -> Unit
                '\n' -> next()
                '\r' -> {
                    next()
                    if (peek() != '\n') fail("одиночный \\r")
                    next()
                }
                else -> fail("лишнее в конце строки")
            }
        }

        fun document(): JsonObject {
            var table: MutableMap<String, Node> = root
            while (peek() != null) {
                spaces()
                when (peek()) {
                    null -> break
                    '#', '\n', '\r' -> endOfLine()
                    '[' -> {
                        table = header()
                        endOfLine()
                    }
                    else -> {
                        keyValue(table)
                        endOfLine()
                    }
                }
            }
            return freeze(root)
        }

        private fun header(): MutableMap<String, Node> {
            next()
            val array = peek() == '['
            if (array) next()
            spaces()
            val name = key()
            spaces()
            if (peek() == '.') fail("таблица через точку не поддержана")
            if (peek() != ']') fail("ожидалась «]»")
            next()
            if (array) {
                if (peek() != ']') fail("ожидалась «]]»")
                next()
                val list = when (val existing = root[name]) {
                    null -> Node.TableArray().also { root[name] = it }
                    is Node.TableArray -> existing
                    else -> fail("«$name» уже определено не как массив таблиц")
                }
                return LinkedHashMap<String, Node>().also { list.items += Node.Table(it) }
            }
            if (!opened.add(name) || root.containsKey(name)) fail("таблица «$name» определена второй раз")
            return LinkedHashMap<String, Node>().also { root[name] = Node.Table(it) }
        }

        private fun key(): String = when (val c = peek()) {
            '"' -> basicString()
            '\'' -> literalString()
            null -> fail("ожидался ключ")
            else -> {
                if (!bare(c)) fail("недопустимый знак ключа «$c»")
                val sb = StringBuilder()
                while (peek()?.let(::bare) == true) sb.append(next())
                sb.toString()
            }
        }

        private fun bare(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-'

        private fun keyValue(table: MutableMap<String, Node>) {
            val (name, v) = pair(table.keys)
            table[name] = Node.Value(v)
        }

        /** `ключ = значение`; ключ из [taken] — повтор, ошибка. */
        private fun pair(taken: Set<String>): Pair<String, JsonValue> {
            val name = key()
            spaces()
            if (peek() == '.') fail("ключ через точку не поддержан")
            if (peek() != '=') fail("ожидался «=» после ключа «$name»")
            next()
            spaces()
            if (name in taken) fail("ключ «$name» задан второй раз")
            return name to value()
        }

        private fun value(): JsonValue = when (val c = peek()) {
            '"' -> JsonString(basicString())
            '\'' -> JsonString(literalString())
            '[' -> array()
            '{' -> inlineTable()
            't', 'f' -> bool()
            null, '\n', '\r', '#' -> fail("ожидалось значение")
            else -> if (c == '+' || c == '-' || c in '0'..'9') number() else fail("значение не поддержано: «$c»")
        }

        private fun basicString(): String {
            if (text.startsWith("\"\"\"", pos)) fail("многострочная строка не поддержана")
            next()
            val sb = StringBuilder()
            while (true) {
                val c = peek() ?: fail("строка не закрыта")
                when {
                    c == '"' -> {
                        next()
                        return sb.toString()
                    }
                    c == '\n' || c == '\r' -> fail("строка не закрыта до конца строки")
                    c == '\\' -> {
                        next()
                        sb.append(
                            when (val e = peek() ?: fail("строка не закрыта")) {
                                'b' -> '\b'.also { next() }
                                't' -> '\t'.also { next() }
                                'n' -> '\n'.also { next() }
                                'f' -> '\u000C'.also { next() }
                                'r' -> '\r'.also { next() }
                                '"' -> '"'.also { next() }
                                '\\' -> '\\'.also { next() }
                                'u', 'U' -> {
                                    next()
                                    val digits = if (e == 'u') 4 else 8
                                    val hex = (0 until digits).map { next() }.joinToString("")
                                    val code = hex.toIntOrNull(16) ?: fail("неверная escape-последовательность \\$e$hex")
                                    if (code in 0xD800..0xDFFF || code > 0x10FFFF) fail("недопустимый код \\$e$hex")
                                    String(Character.toChars(code))
                                }
                                else -> fail("неизвестная escape-последовательность \\$e")
                            },
                        )
                    }
                    c.isISOControl() && c != '\t' -> fail("управляющий знак в строке")
                    else -> sb.append(next())
                }
            }
        }

        private fun literalString(): String {
            if (text.startsWith("'''", pos)) fail("многострочная строка не поддержана")
            next()
            val sb = StringBuilder()
            while (true) {
                val c = peek() ?: fail("строка не закрыта")
                when {
                    c == '\'' -> {
                        next()
                        return sb.toString()
                    }
                    c == '\n' || c == '\r' -> fail("строка не закрыта до конца строки")
                    c.isISOControl() && c != '\t' -> fail("управляющий знак в строке")
                    else -> sb.append(next())
                }
            }
        }

        private fun bool(): JsonValue = when {
            text.startsWith("true", pos) -> JsonBool(true).also { repeat(4) { next() } }
            text.startsWith("false", pos) -> JsonBool(false).also { repeat(5) { next() } }
            else -> fail("значение не поддержано")
        }.also { if (peek()?.let(::bare) == true) fail("значение не поддержано") }

        private fun number(): JsonValue {
            val startLine = line
            val startCol = col
            val sb = StringBuilder()
            while (peek()?.let { it in '0'..'9' || it in "+-_.eExXoObB:TZ" || it in 'a'..'f' || it in 'A'..'F' } == true) sb.append(next())
            val raw = sb.toString()
            if (raw.any { it in ".eE:TZ" } || raw.drop(1).contains('-') || raw.drop(1).contains('+')) {
                throw TomlError("дробные числа и даты не поддержаны: «$raw»", startLine, startCol)
            }
            val body = raw.removePrefix("+").removePrefix("-")
            if (body.startsWith("0x") || body.startsWith("0o") || body.startsWith("0b")) throw TomlError("целые не в десятичной записи не поддержаны: «$raw»", startLine, startCol)
            val ok = Regex("[+-]?(0|[1-9](_?[0-9])*)").matches(raw)
            if (!ok) throw TomlError("неверное целое «$raw»", startLine, startCol)
            val v = raw.replace("_", "").toLongOrNull() ?: throw TomlError("целое вне диапазона: «$raw»", startLine, startCol)
            return JsonNumber.of(v)
        }

        /** Пробелы, переводы строк и комментарии внутри массива. */
        private fun gap() {
            while (true) {
                spaces()
                when (peek()) {
                    '#' -> comment()
                    '\n' -> next()
                    '\r' -> {
                        next()
                        if (peek() != '\n') fail("одиночный \\r")
                        next()
                    }
                    else -> return
                }
            }
        }

        private fun array(): JsonValue {
            next()
            val items = mutableListOf<JsonValue>()
            while (true) {
                gap()
                if (peek() == ']') {
                    next()
                    return JsonArray(items)
                }
                items += value()
                gap()
                when (peek()) {
                    ',' -> next()
                    ']' -> {
                        next()
                        return JsonArray(items)
                    }
                    null -> fail("массив не закрыт")
                    else -> fail("ожидалась «,» или «]» в массиве")
                }
            }
        }

        private fun inlineTable(): JsonValue {
            next()
            val fields = LinkedHashMap<String, JsonValue>()
            spaces()
            if (peek() == '}') {
                next()
                return JsonObject(fields)
            }
            while (true) {
                spaces()
                pair(fields.keys).let { (k, v) -> fields[k] = v }
                spaces()
                when (peek()) {
                    ',' -> {
                        next()
                        spaces()
                        if (peek() == '}') fail("запятая в конце встроенной таблицы")
                    }
                    '}' -> {
                        next()
                        return JsonObject(fields)
                    }
                    '\n', '\r' -> fail("встроенная таблица — в одну строку")
                    else -> fail("ожидалась «,» или «}» во встроенной таблице")
                }
            }
        }

        /** Таблица и массив таблиц до конца разбора — изменяемые; итог — неизменяемые объекты JSON. */
        private sealed interface Node {
            class Value(val value: JsonValue) : Node

            class Table(val fields: MutableMap<String, Node>) : Node

            class TableArray(val items: MutableList<Table> = mutableListOf()) : Node
        }

        private fun freeze(map: Map<String, Node>): JsonObject = JsonObject(LinkedHashMap(map.mapValues { (_, v) -> freezeNode(v) }))

        private fun freezeNode(v: Node): JsonValue = when (v) {
            is Node.Value -> v.value
            is Node.Table -> freeze(v.fields)
            is Node.TableArray -> JsonArray(v.items.map { freeze(it.fields) })
        }
    }
}
