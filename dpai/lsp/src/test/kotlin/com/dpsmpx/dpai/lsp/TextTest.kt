package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextTest {
    private fun edit(l1: Int, c1: Int, l2: Int, c2: Int, text: String) = TextEdit(Range(Position(l1, c1), Position(l2, c2)), text)

    @Test
    fun lineIndexConvertsBothWays() {
        val text = "ab\r\nжз😀\n\nконец"
        val index = LineIndex(text)
        assertEquals(4, index.lines)
        for (offset in 0..text.length) assertEquals(offset, index.offset(index.position(offset)))
        assertEquals(Position(1, 0), index.position(4))
        assertEquals(3, index.lineLength(0)) // «ab\r»
        assertEquals(4, index.lineLength(1)) // эмодзи — две единицы UTF-16
        assertEquals(text.length, index.offset(Position(3, 99)))
        assertNull(index.offset(Position(4, 0)))
        assertEquals(1, LineIndex("").lines)
    }

    @Test
    fun editsApplyInSourceCoordinates() {
        val text = "int a;\nint b;\n"
        val r = TextEdits.apply(text, listOf(edit(1, 4, 1, 5, "second"), edit(0, 4, 0, 5, "first"), edit(0, 0, 0, 0, "// h\n")))
        assertEquals("// h\nint first;\nint second;\n", (r as Outcome.Success).value)
        assertEquals(text, (TextEdits.apply(text, emptyList()) as Outcome.Success).value)
    }

    @Test
    fun insertsAtOnePlaceKeepTheirOrder() {
        val r = TextEdits.apply("x", listOf(edit(0, 0, 0, 0, "a"), edit(0, 0, 0, 0, "b")))
        assertEquals("abx", (r as Outcome.Success).value)
    }

    @Test
    fun overlappingOrOutsideEditsAreRefusedWhole() {
        val text = "abcdef\n"
        val overlap = TextEdits.apply(text, listOf(edit(0, 0, 0, 3, "x"), edit(0, 2, 0, 4, "y")))
        assertEquals(DiagCode.LSP_REPLY_INVALID, (overlap as Outcome.Failure).diagnostics.first().code)
        val outside = TextEdits.apply(text, listOf(edit(5, 0, 5, 0, "x")))
        assertEquals(DiagCode.LSP_REPLY_INVALID, (outside as Outcome.Failure).diagnostics.first().code)
    }

    @Test
    fun deltaIsTheSmallestSingleEdit() {
        assertNull(TextEdits.delta("same", "same"))
        assertEquals(edit(0, 4, 0, 4, "x"), TextEdits.delta("int ;", "int x;"))
        assertEquals(edit(1, 0, 1, 2, ""), TextEdits.delta("a\nbbc", "a\nc"))
        assertEquals(edit(0, 0, 0, 0, "new"), TextEdits.delta("", "new"))
        assertEquals(edit(0, 2, 0, 2, "a"), TextEdits.delta("aa", "aaa"))
    }

    @Test
    fun deltaAppliedToOldGivesNewOnAnyTexts() {
        val samples = listOf("", "a", "ab\ncd", "😀x😀", "😀", "🙂", "a\r\nb\r\n", "жжж", "aaa", "a😀b", "a🙂b", "\n\n\n", "x😀", "x🙂y", "\uD83E\uDE00", "a\uD83E\uDE00")
        for (old in samples) for (new in samples) {
            val d = TextEdits.delta(old, new)
            val got = if (d == null) old else (TextEdits.apply(old, listOf(d)) as Outcome.Success).value
            assertEquals(new, got, "«$old» → «$new»")
            // Пара суррогатов не разрезана: правка — корректный текст.
            if (d != null) assertEquals(d.newText, String(d.newText.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
    }

    @Test
    fun uriEncodesLikeTheServer() {
        assertEquals("file:///root/repos/Galdhold/src/main.cpp", DocumentUri.ofPath("/root/repos/Galdhold/src/main.cpp"))
        assertEquals("file:///a%20b/%D0%B6%2B%2B.cpp", DocumentUri.ofPath("/a b/ж++.cpp"))
        for (p in listOf("/a b/ж++.cpp", "/x/%41/#?.h", "/😀")) assertEquals(p, DocumentUri.toPath(DocumentUri.ofPath(p)))
        assertNull(DocumentUri.toPath("https://example.com/a"))
        assertNull(DocumentUri.toPath("file://host/a"))
        assertNull(DocumentUri.toPath("file:///a%2"))
        assertNull(DocumentUri.toPath("file:///a%zz"))
        assertNull(DocumentUri.toPath("file:///%FF"))
    }

    @Test
    fun clangdKnowsItsFilesAndCommand() {
        assertEquals("cpp", Clangd.languageId("src/a.cpp"))
        assertEquals("cpp", Clangd.languageId("a.h"))
        assertEquals("cpp", Clangd.languageId("a.HPP"))
        assertEquals("c", Clangd.languageId("a.c"))
        assertNull(Clangd.languageId("a.kt"))
        assertNull(Clangd.languageId("Makefile"))
        assertNull(Clangd.languageId("dir.cpp/readme"))
        assertEquals("build", Clangd.compileCommandsDir(listOf("src/a.cpp", "build/compile_commands.json")))
        assertEquals("", Clangd.compileCommandsDir(listOf("compile_commands.json", "build/compile_commands.json")))
        assertNull(Clangd.compileCommandsDir(listOf("deep/dir/compile_commands.json")))
        val argv = Clangd.command("/root/repos/g/build", jobs = 2)
        assertEquals("clangd", argv.first())
        assertEquals(true, "--compile-commands-dir=/root/repos/g/build" in argv)
        assertEquals(true, "--background-index=false" in argv)
        assertEquals(false, Clangd.command(null).any { it.startsWith("--compile-commands-dir") })
    }
}
