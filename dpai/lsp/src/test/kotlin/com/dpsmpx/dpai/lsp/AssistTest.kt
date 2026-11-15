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

    private val root = "/root/repos/dpsmpx/Galdhold"
    private val here = DocumentUri.ofPath("$root/src/main.cpp")
    private fun at(path: String, line: Int, ch: Int = 0) = Location(DocumentUri.ofPath(path), Range(Position(line, ch), Position(line, ch + 1)))

    /** D-105: место службы — этот файл, вкладка того же корня или путь вне корня. */
    @Test
    fun targetIsHereTabOrOutside() {
        assertEquals(Assist.Target.Here(5, Position(4, 2)), Assist.target(at("$root/src/main.cpp", 4, 2), here, root))
        assertEquals(Assist.Target.Tab("src/world/Чанк.h", 10), Assist.target(at("$root/src/world/Чанк.h", 9), here, "$root/"))
        assertEquals(Assist.Target.Outside("/usr/include/c++/14/vector", 1), Assist.target(at("/usr/include/c++/14/vector", 0), here, root))
        assertEquals(Assist.Target.Outside("${root}x/a.h", 1), Assist.target(at("${root}x/a.h", 0), here, root), "соседний каталог с тем же началом — не корень")
        assertEquals(null, Assist.target(Location("http://x/a.h", Range(Position(0, 0), Position(0, 0))), here, root))
    }

    /** Правка только этого файла применяется; задевшая другие файлы — отказ целиком, без частичного переименования. */
    @Test
    fun editsForFileRefusesOtherFiles() {
        val e = edit(0, 0, 0, 1, "y")
        assertEquals(listOf(e), ok(Assist.editsForFile(WorkspaceEdit(mapOf(here to listOf(e))), here)))
        // Тот же файл другим кодированием адреса — тот же файл.
        val other = here.replace("main", "%6Dain")
        assertEquals(listOf(e), ok(Assist.editsForFile(WorkspaceEdit(mapOf(other to listOf(e))), here)))
        val wide = WorkspaceEdit(mapOf(here to listOf(e), DocumentUri.ofPath("$root/src/a.h") to listOf(e), DocumentUri.ofPath("$root/src/b.cpp") to emptyList()))
        val refused = Assist.editsForFile(wide, here)
        assertTrue(refused is Outcome.Failure && "другие файлы (1: a.h)" in refused.errors.single().message, refused.toString())
        assertEquals(emptyList(), ok(Assist.editsForFile(WorkspaceEdit(emptyMap()), here)))
    }

    @Test
    fun renameNameIsIdentifier() {
        for (good in listOf("x", "_tmp", "chunkSize2", "Мир".let { "a" })) assertTrue(Assist.validName(good), good)
        for (bad in listOf("", "2x", "a-b", "a b", "a::b", "мир", "x".repeat(256))) assertTrue(!Assist.validName(bad), bad)
    }

    @Test
    fun signatureLineMarksActiveParameter() {
        val sig = Signature("int add(int a, int b)", null, listOf(8..12, 15..19))
        val help = SignatureHelp(listOf(sig, Signature("double add(double a, double b)", null, emptyList())), 0, 1)
        assertEquals(Assist.SignatureLine("int add(int a, int b)", 15..19, 1), Assist.signature(help))
        assertEquals(Assist.SignatureLine("int add(int a, int b)", null, 0), Assist.signature(SignatureHelp(listOf(sig), 7, 5)), "номера за пределами — без отметки, перегрузка — последняя")
        assertEquals(null, Assist.signature(SignatureHelp(emptyList(), 0, null)))
        assertEquals(null, Assist.signature(null))
        // LSP 3.16: текущий параметр у самой подписи (kotlin-lsp) важнее общего.
        val own = Signature("twice(value: Int): Int", null, listOf(6..15), activeParameter = 0)
        assertEquals(Assist.SignatureLine("twice(value: Int): Int", 6..15, 0), Assist.signature(SignatureHelp(listOf(own), 0, null)))
    }

    @Test
    fun signatureTriggers() {
        assertTrue(Assist.wantsSignature("f(", 2))
        assertTrue(Assist.wantsSignature("f(a,  ", 6))
        assertTrue(!Assist.wantsSignature("f(a", 3))
        assertTrue(Assist.endsSignature("f(a)", 4))
        assertTrue(Assist.endsSignature("f(a);", 5))
        assertTrue(!Assist.endsSignature("f(a, b", 6))
    }

    @Test
    fun fixesWithEditFirstPreferred() {
        val fix = WorkspaceEdit(mapOf(here to listOf(edit(0, 0, 0, 0, ";"))))
        val actions = listOf(CodeAction("b: add include", "quickfix", fix, false), CodeAction("run tweak", "refactor", null, false), CodeAction("a: insert ;", "quickfix", fix, true))
        val f = Assist.fixes(actions)
        assertEquals(listOf("a: insert ;", "b: add include"), f.applicable.map { it.title })
        assertEquals(1, f.commands)
    }

    /** «Исправить всё без ИИ» (D-187): только ★ этого файла, без пересечений, одинаковая правка — раз. */
    @Test
    fun fixAllTakesOnlyPreferredNonOverlappingEditsOfThisFile() {
        fun fix(vararg e: TextEdit, uri: String = here) = WorkspaceEdit(mapOf(uri to e.toList()))
        val semicolon = edit(0, 5, 0, 5, ";")
        val include = edit(0, 0, 0, 0, "#include <vector>\n")
        val perDiagnostic = listOf(
            listOf(CodeAction("insert ;", "quickfix", fix(semicolon), true)),
            // Тот же ★ для второго замечания — одна правка, оба исправлены.
            listOf(CodeAction("insert ;", "quickfix", fix(semicolon), true)),
            // Без ★ — выбор человека, не трогаем.
            listOf(CodeAction("rename to foo", "quickfix", fix(edit(1, 0, 1, 3, "foo")), false)),
            // ★ с правкой другого файла — отказ.
            listOf(CodeAction("add include", "quickfix", fix(include, uri = DocumentUri.ofPath("$root/src/a.h")), true)),
            // Пересекается со взятой — остаётся.
            listOf(CodeAction("replace", "quickfix", fix(edit(0, 4, 0, 6, "x")), true)),
            // Только команда или не quickfix — остаётся.
            listOf(CodeAction("run", "quickfix", null, true), CodeAction("extract", "refactor.extract", fix(edit(2, 0, 2, 1, "y")), true)),
            listOf(CodeAction("add include", "quickfix", fix(include), true)),
        )
        val plan = Assist.fixAll(here, perDiagnostic)
        assertEquals(listOf(include, semicolon), plan.edits, "по порядку в тексте")
        assertEquals(3, plan.fixed)
        assertEquals(4, plan.skipped)
        assertEquals(Assist.FixAll(emptyList(), 0, 0), Assist.fixAll(here, emptyList()))
    }

    /** Без индекса проекта — только символы этого исходного файла. */
    @Test
    fun renameScopeOnlyForSymbolsOfThisSourceFile() {
        assertTrue(Assist.renameScope(here, listOf(at("$root/src/main.cpp", 3), at("$root/src/main.cpp", 9))) is Outcome.Success)
        val fromHeader = Assist.renameScope(here, listOf(at("$root/src/world/chunk.h", 3), at("$root/src/main.cpp", 9)))
        assertTrue(fromHeader is Outcome.Failure && "chunk.h" in fromHeader.errors.single().message, fromHeader.toString())
        val header = DocumentUri.ofPath("$root/src/world/chunk.hpp")
        assertTrue(Assist.renameScope(header, listOf(at("$root/src/world/chunk.hpp", 3))) is Outcome.Failure)
        assertTrue(Assist.renameScope(here, emptyList()) is Outcome.Failure)
    }
}
