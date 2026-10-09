package com.dpsmpx.dpai.ui

/** Вид участка оформленного текста. */
enum class MarkStyle { BOLD, ITALIC, STRIKE, CODE, CODE_BLOCK, HEADING_1, HEADING_2, HEADING_3, QUOTE, LINK }

/** Участок [start, end) текста [Rendered.text] со стилем; у ссылки — [url]. */
data class Mark(val start: Int, val end: Int, val style: MarkStyle, val url: String? = null)

/**
 * Оформленный текст: [text] — без символов разметки (его и копировать),
 * [marks] — что как показать.
 */
data class Rendered(val text: String, val marks: List<Mark>)

/**
 * Markdown сообщений модели (D-42) — в текст без разметки и участки
 * стилей: заголовки, жирный, курсив, зачёркнутый, код в строке и блоком,
 * списки, цитаты, ссылки, таблицы, разделители. Только то, что пишет
 * Claude Code; неизвестное остаётся как есть (D-05: смысл не меняется).
 * Чистая функция без платформы — проверяется тестами (D-22).
 */
object Markdown {
    private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val ORDERED = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val QUOTE = Regex("""^\s*>\s?(.*)$""")
    private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val FENCE = Regex("""^\s*(```|~~~)""")
    private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
    private val TASK = Regex("""^\[( |x|X)]\s+(.*)$""")

    fun render(md: String): Rendered {
        val out = StringBuilder()
        val marks = mutableListOf<Mark>()
        val lines = md.replace("\r\n", "\n").split('\n')
        var i = 0
        fun newline() {
            if (out.isNotEmpty()) out.append('\n')
        }
        while (i < lines.size) {
            val line = lines[i]
            if (FENCE.containsMatchIn(line)) {
                val fence = FENCE.find(line)!!.groupValues[1]
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) body += lines[i++]
                i++ // закрывающая ограда (или конец текста)
                newline()
                val start = out.length
                out.append(body.joinToString("\n"))
                marks += Mark(start, out.length, MarkStyle.CODE_BLOCK)
                continue
            }
            newline()
            when {
                HEADING.matches(line) -> {
                    val m = HEADING.matchEntire(line)!!
                    val start = out.length
                    inline(m.groupValues[2], out, marks)
                    val style = when (m.groupValues[1].length) {
                        1 -> MarkStyle.HEADING_1
                        2 -> MarkStyle.HEADING_2
                        else -> MarkStyle.HEADING_3
                    }
                    marks += Mark(start, out.length, style)
                }
                RULE.matches(line) -> out.append("────────")
                QUOTE.matches(line) -> {
                    val start = out.length
                    inline(QUOTE.matchEntire(line)!!.groupValues[1], out, marks)
                    marks += Mark(start, out.length, MarkStyle.QUOTE)
                }
                BULLET.matches(line) -> {
                    val m = BULLET.matchEntire(line)!!
                    out.append(indent(m.groupValues[1]))
                    val task = TASK.matchEntire(m.groupValues[2])
                    if (task != null) {
                        out.append(if (task.groupValues[1].isBlank()) "☐ " else "☑ ")
                        inline(task.groupValues[2], out, marks)
                    } else {
                        out.append("• ")
                        inline(m.groupValues[2], out, marks)
                    }
                }
                ORDERED.matches(line) -> {
                    val m = ORDERED.matchEntire(line)!!
                    out.append(indent(m.groupValues[1])).append(m.groupValues[2]).append(". ")
                    inline(m.groupValues[3], out, marks)
                }
                line.trimStart().startsWith("|") -> {
                    if (!TABLE_SEPARATOR.matches(line)) {
                        val cells = line.trim().trim('|').split('|').map { it.trim() }
                        for ((k, c) in cells.withIndex()) {
                            if (k > 0) out.append("  │  ")
                            inline(c, out, marks)
                        }
                    } else if (out.endsWith('\n')) {
                        out.setLength(out.length - 1) // разделитель шапки таблицы — без пустой строки
                    }
                }
                else -> inline(line, out, marks)
            }
            i++
        }
        return Rendered(out.toString(), marks)
    }

    private fun indent(spaces: String) = "  ".repeat((spaces.replace("\t", "    ").length / 2).coerceAtMost(4))

    /** Разметка внутри строки: код, ссылка, жирный, зачёркнутый, курсив; экранирование `\*`. */
    private fun inline(s: String, out: StringBuilder, marks: MutableList<Mark>) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE -> {
                    out.append(s[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val ticks = s.drop(i).takeWhile { it == '`' }.length
                    val close = s.indexOf("`".repeat(ticks), i + ticks)
                    if (close < 0) {
                        out.append(s, i, i + ticks)
                        i += ticks
                    } else {
                        val start = out.length
                        out.append(s.substring(i + ticks, close).let { if (ticks > 1) it.trim() else it })
                        marks += Mark(start, out.length, MarkStyle.CODE)
                        i = close + ticks
                    }
                }
                c == '[' -> {
                    val endText = matching(s, i, '[', ']')
                    if (endText > 0 && endText + 1 < s.length && s[endText + 1] == '(') {
                        val endUrl = s.indexOf(')', endText + 2)
                        if (endUrl > 0) {
                            val start = out.length
                            inline(s.substring(i + 1, endText), out, marks)
                            marks += Mark(start, out.length, MarkStyle.LINK, s.substring(endText + 2, endUrl).trim())
                            i = endUrl + 1
                            continue
                        }
                    }
                    out.append(c)
                    i++
                }
                s.startsWith("**", i) || s.startsWith("__", i) -> i = span(s, i, s.substring(i, i + 2), MarkStyle.BOLD, out, marks)
                s.startsWith("~~", i) -> i = span(s, i, "~~", MarkStyle.STRIKE, out, marks)
                (c == '*' || c == '_') && opens(s, i) -> i = span(s, i, c.toString(), MarkStyle.ITALIC, out, marks)
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
    }

    /** Участок между парными маркерами; пары нет — маркер как текст. */
    private fun span(s: String, i: Int, marker: String, style: MarkStyle, out: StringBuilder, marks: MutableList<Mark>): Int {
        var close = s.indexOf(marker, i + marker.length)
        // `_` внутри слова (snake_case) — не курсив.
        while (close > 0 && marker == "_" && close + 1 < s.length && s[close + 1].isLetterOrDigit()) close = s.indexOf(marker, close + 1)
        if (close <= i + marker.length - 1 || close == i + marker.length) {
            out.append(marker)
            return i + marker.length
        }
        val start = out.length
        inline(s.substring(i + marker.length, close), out, marks)
        marks += Mark(start, out.length, style)
        return close + marker.length
    }

    /** Одиночный `*` или `_` открывает курсив: за ним не пробел, а `_` — не посреди слова. */
    private fun opens(s: String, i: Int): Boolean {
        if (i + 1 >= s.length || s[i + 1].isWhitespace()) return false
        if (s[i] == '_' && i > 0 && s[i - 1].isLetterOrDigit()) return false
        return true
    }

    private fun matching(s: String, from: Int, open: Char, close: Char): Int {
        var depth = 0
        for (k in from until s.length) {
            if (s[k] == open) depth++
            if (s[k] == close && --depth == 0) return k
        }
        return -1
    }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~>"
}
