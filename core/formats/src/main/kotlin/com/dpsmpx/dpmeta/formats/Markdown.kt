package com.dpsmpx.dpmeta.formats

/**
 * Разбор Markdown, которого хватает документам процесса (ТЗ, §10, I-06):
 * строки, заголовки ATX вне блоков кода, пункты списков. Не рендер: только
 * структура, по которой разборщики находят записи.
 */
object Markdown {
    /** Заголовок `#…`: уровень 1–6, текст без решёток, номер строки с 1. */
    data class Heading(val level: Int, val text: String, val line: Int)

    /** Строка документа вне блока кода: номер с 1 и текст без `\r`. */
    data class Line(val number: Int, val text: String)

    private val HEADING = Regex("^(#{1,6})[ \\t]+(.*?)(?:[ \\t]+#+)?[ \\t]*$")
    private val FENCE = Regex("^[ ]{0,3}(```+|~~~+)")

    fun lines(text: String): List<String> = text.replace("\r\n", "\n").split('\n')

    /** Строки вне блоков кода (``` и ~~~): разметку внутри кода не читаем. */
    fun prose(text: String): List<Line> {
        val out = mutableListOf<Line>()
        var fence: String? = null
        for ((i, raw) in lines(text).withIndex()) {
            val m = FENCE.find(raw)
            if (fence == null && m != null) {
                fence = m.groupValues[1].take(3)
                continue
            }
            if (fence != null) {
                if (m != null && m.groupValues[1].startsWith(fence)) fence = null
                continue
            }
            out += Line(i + 1, raw)
        }
        return out
    }

    fun headings(text: String): List<Heading> = prose(text).mapNotNull { l ->
        HEADING.find(l.text)?.let { Heading(it.groupValues[1].length, it.groupValues[2].trim(), l.number) }
    }

    /**
     * Разделы уровня [level]: заголовок и строки до следующего заголовка того
     * же или более высокого уровня.
     */
    fun sections(text: String, level: Int): List<Pair<Heading, List<Line>>> {
        val out = mutableListOf<Pair<Heading, MutableList<Line>>>()
        for (l in prose(text)) {
            val h = HEADING.find(l.text)
            val hl = h?.groupValues?.get(1)?.length
            when {
                hl == level -> out += Heading(level, h.groupValues[2].trim(), l.number) to mutableListOf()
                hl != null && hl < level -> out += Heading(hl, "", l.number) to mutableListOf()
                else -> out.lastOrNull()?.second?.add(l)
            }
        }
        return out.filter { it.first.level == level }
    }

    /** Убрать разметку выделения и ссылок для показа: `**x**`, `` `x` ``, `[x](y)`. */
    fun plain(text: String): String = text
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("<a [^>]*></a>"), "")
        .replace("**", "")
        .replace("`", "")
        .trim()
}
