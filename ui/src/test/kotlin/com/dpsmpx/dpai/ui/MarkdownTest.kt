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
            "Важно: см. документацию", "", "def add(a, b):\n    return a + b", "────────", "Функция  │  Тест", "add  │  ok",
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
}
