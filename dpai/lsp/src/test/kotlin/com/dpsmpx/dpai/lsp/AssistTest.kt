package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssistTest {
    private fun edit(l1: Int, c1: Int, l2: Int, c2: Int, text: String) = TextEdit(Range(Position(l1, c1), Position(l2, c2)), text)

    private fun item(label: String, edit: TextEdit? = null, insert: String = label, additional: List<TextEdit> = emptyList(), sort: String? = null, filter: String? = null) =
        CompletionItem(label, null, null, null, sort, filter, edit?.newText ?: insert, edit, additional)

    private fun <T> ok(r: Outcome<T>): T = (r as Outcome.Success).value

    @Test
    fun cursorFollowsEdits() {
        val text = "ab\ncd\n"
        assertEquals(Assist.Applied("XXab\ncd\n", 6), ok(Assist.applyEdits(text, 4, listOf(edit(0, 0, 0, 0, "XX")))))
        assertEquals(Assist.Applied("ab\ncdYY\n", 4), ok(Assist.applyEdits(text, 4, listOf(edit(1, 2, 1, 2, "YY")))))
        assertEquals(Assist.Applied("ab\nZ\n", 4), ok(Assist.applyEdits(text, 4, listOf(edit(1, 0, 1, 2, "Z")))))
        assertEquals(Assist.Applied(text, 3), ok(Assist.applyEdits(text, 3, emptyList())))
    }

    @Test
    fun completionReplacesTypedWordAndPutsCursorAfter() {
        val text = "shape.wi;\n"
        val cursor = text.indexOf(';')
        assertEquals("wi", Assist.typedPrefix(text, cursor))
        // С правкой службы.
        assertEquals(Assist.Applied("shape.width;\n", 11), ok(Assist.complete(text, cursor, item("width", edit(0, 6, 0, 8, "width")))))
        // Без правки — заменяется набранное слово.
        assertEquals(Assist.Applied("shape.width;\n", 11), ok(Assist.complete(text, cursor, item("width"))))
        // Служба ответила на «shape.w», а человек уже набрал «wi»: лишняя буква не остаётся.
        assertEquals(Assist.Applied("shape.width;\n", 11), ok(Assist.complete(text, cursor, item("width", edit(0, 6, 0, 7, "width")))))
    }

    @Test
    fun completionWithIncludeEditKeepsCursorAtInsertedWord() {
        val text = "int main() { vec }\n"
        val cursor = text.indexOf(" }")
        val r = ok(Assist.complete(text, cursor, item("vector", edit(0, 13, 0, 16, "vector"), additional = listOf(edit(0, 0, 0, 0, "#include <vector>\n")))))
        assertEquals("#include <vector>\nint main() { vector }\n", r.text)
        assertEquals(r.text.indexOf(" }"), r.cursor)
    }

    @Test
    fun filterKeepsServerOrder() {
        val items = listOf(item("width", sort = "2"), item("what", sort = "1"), item("area", sort = "0"), item("operator==", filter = "wop"))
        assertEquals(listOf("what", "width", "operator=="), Assist.filter(items, "W").map { it.label })
        assertEquals(4, Assist.filter(items, "").size)
        assertEquals(2, Assist.filter(items, "", limit = 2).size)
    }

    @Test
    fun marksCoverRangesAndStayVisible() {
        val text = "int x\nreturn;\n"
        val d = { r: TextEdit, s: LspSeverity -> LspDiagnostic(r.range, s, null, null, "m") }
        val marks = Assist.marks(text, listOf(d(edit(0, 4, 0, 5, ""), LspSeverity.WARNING), d(edit(0, 5, 0, 5, ""), LspSeverity.ERROR), d(edit(9, 0, 9, 0, ""), LspSeverity.ERROR), d(edit(1, 7, 5, 0, ""), LspSeverity.HINT)))
        assertEquals(listOf(Assist.Mark(4, 5, LspSeverity.WARNING), Assist.Mark(5, 6, LspSeverity.ERROR), Assist.Mark(13, 14, LspSeverity.HINT)), marks)
        assertTrue(Assist.marks("", listOf(d(edit(0, 0, 0, 0, ""), LspSeverity.ERROR))).isEmpty())
        assertEquals(listOf(Assist.Mark(0, 1, LspSeverity.ERROR)), Assist.marks("x", listOf(d(edit(0, 1, 0, 1, ""), LspSeverity.ERROR))))
    }

    @Test
    fun summaryCountsBySeverity() {
        val d = { s: LspSeverity -> LspDiagnostic(Range(Position(0, 0), Position(0, 0)), s, null, null, "m") }
        assertEquals("замечаний нет", Assist.summary(emptyList()))
        assertEquals("ошибок: 2 · предупреждений: 1 · подсказок: 1", Assist.summary(listOf(d(LspSeverity.ERROR), d(LspSeverity.ERROR), d(LspSeverity.WARNING), d(LspSeverity.HINT))))
    }
}
