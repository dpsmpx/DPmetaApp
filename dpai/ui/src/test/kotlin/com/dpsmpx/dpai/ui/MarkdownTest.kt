package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Markdown сообщений модели (D-42): текст без разметки — копируется чисто; стили — участками. */
class MarkdownTest {
    private fun marked(r: Rendered, style: MarkStyle) = r.marks.filter { it.style == style }.map { r.text.substring(it.start, it.end) }

    @Test
    fun claudeStyleMessage() {
        val md = """
            ## Итог

            Создал `calc.py` и **тесты**, всё *проходит*.

            - **`add`** — сложение
            - [x] деление на ноль
            1. Первый шаг
            > Важно: см. [документацию](https://example.org/doc)

            ```python
            def add(a, b):
                return a + b
            ```
            ---
            | Функция | Тест |
            |---|---|
            | add | ok |
        """.trimIndent()
        val r = Markdown.render(md)
        val expected = listOf(
            "Итог", "", "Создал calc.py и тесты, всё проходит.", "", "• add — сложение", "☑ деление на ноль", "1. Первый шаг",
            "Важно: см. документацию", "", "def add(a, b):\n    return a + b", "────────", "Функция │ Тест", "───────" + "─┼─" + "────", "add     │ ok",
        ).joinToString("\n")
        assertEquals(expected, r.text)
        assertEquals(listOf("Итог"), marked(r, MarkStyle.HEADING_2))
        assertEquals(listOf("calc.py", "add"), marked(r, MarkStyle.CODE))
        assertEquals(listOf("тесты", "add"), marked(r, MarkStyle.BOLD))
        assertEquals(listOf("проходит"), marked(r, MarkStyle.ITALIC))
        assertEquals(listOf("документацию"), marked(r, MarkStyle.LINK))
        assertEquals("https://example.org/doc", r.marks.single { it.style == MarkStyle.LINK }.url)
        assertEquals(listOf("def add(a, b):\n    return a + b"), marked(r, MarkStyle.CODE_BLOCK))
    }

    @Test
    fun plainTextIsNotMangled() {
        val t = "file_name_v2, 2 * 3 = 6, звёздочка * отдельно, путь my_dir/my_file.py, \\*не курсив\\*"
        val r = Markdown.render(t)
        assertEquals("file_name_v2, 2 * 3 = 6, звёздочка * отдельно, путь my_dir/my_file.py, *не курсив*", r.text)
        assertEquals(emptyList(), r.marks, "ни курсива в snake_case, ни звёздочки с пробелом")
        // Незакрытые маркеры — как текст.
        assertEquals("**без пары и `без пары", Markdown.render("**без пары и `без пары").text)
    }

    /**
     * Настоящее сообщение Claude (Redmi Note 13, 9 октября 2026): текст без
     * разметки — эталон `calc-explain.txt`; таблица выровнена.
     */
    @Test
    fun realClaudeMessageMatchesGolden() {
        val md = javaClass.getResource("/markdown/calc-explain.md")!!.readText()
        val expected = javaClass.getResource("/markdown/calc-explain.txt")!!.readText()
        val r = Markdown.render(md)
        assertEquals(expected.trimEnd(), r.text.trimEnd())
        assertTrue(r.marks.any { it.style == MarkStyle.TABLE } && r.marks.count { it.style == MarkStyle.CODE_BLOCK } == 4, r.marks.toString())
    }

    /** Заголовок и разделитель — проходом по строке (аудит N-14): смысл прежнего шаблона сохранён. */
    @Test
    fun headingAndRuleKeepMeaning() {
        fun heading(md: String) = Markdown.render(md).let { r -> r.text to r.marks.map { it.style } }
        assertEquals("Заголовок" to listOf(MarkStyle.HEADING_2), heading("## Заголовок ##"))
        assertEquals("Заголовок" to listOf(MarkStyle.HEADING_1), heading("#\t Заголовок  \t"))
        assertEquals("a#b" to listOf(MarkStyle.HEADING_3), heading("### a#b"))
        assertEquals("" to listOf(MarkStyle.HEADING_1), heading("# #"))
        assertEquals("####### семь", Markdown.render("####### семь").text, "семь # — не заголовок")
        assertEquals("#тег", Markdown.render("#тег").text, "без пробела — не заголовок")
        for (r in listOf("---", "- - -", " *  *  * ", "___")) assertEquals("────────", Markdown.render(r).text, r)
        for (r in listOf("--", "- - x", "-*-", "* * *x")) assertTrue(Markdown.render(r).text != "────────", r)
    }

    /** Враждебная строка (вывод модели, документ репозитория) не подвешивает разбор (аудит N-14). */
    @Test
    fun hostileLinesAreFast() {
        val n = 20_000
        val lines = listOf(
            "# x" + " \t".repeat(n / 2) + "\u00A0",
            "-" + " -".repeat(n / 2) + " x",
            "[".repeat(n),
            "x" + " `".repeat(n / 2),
            "| --" + " ".repeat(n) + "x",
        )
        for (line in lines) {
            val started = System.nanoTime()
            val r = Markdown.render(line + "\n" + line)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(ms < 2_000, "разбор строки ${line.take(8)}… — $ms мс")
            assertTrue(r.text.isNotEmpty())
        }
    }
}
