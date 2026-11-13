package com.dpsmpx.dpai.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeSyntaxTest {
    @Test
    fun stringsAndCommentsDoNotBecomeKeywords() {
        val root = System.getProperty("dpai.repoRoot") ?: error("нет dpai.repoRoot")
        val text = File(root, "tests/data/editor/sample.py.txt").readText()
        val h = CodeSyntax.highlight("sample.py", text)
        assertEquals("Python", h.language)
        assertEquals(listOf("def", "return"), h.spans.filter { it.kind == CodeToken.KEYWORD }.map { text.substring(it.start, it.end) })
        assertTrue(h.spans.any { it.kind == CodeToken.COMMENT })
        assertTrue(h.spans.any { it.kind == CodeToken.STRING && "#" in text.substring(it.start, it.end) })
        assertEquals(listOf("42"), h.spans.filter { it.kind == CodeToken.NUMBER }.map { text.substring(it.start, it.end) })
    }

    @Test
    fun supportedLanguagesAndUnclosedTokensStayBounded() {
        for ((path, keyword) in listOf("a.cpp" to "namespace", "a.c" to "struct", "a.kt" to "fun", "a.java" to "extends")) {
            val text = "$keyword value /* a comment */ \"escaped \\\" quote\""
            val h = CodeSyntax.highlight(path, text)
            assertTrue(h.spans.any { it.kind == CodeToken.KEYWORD && text.substring(it.start, it.end) == keyword })
            assertTrue(h.spans.all { it.start < it.end && it.start >= 0 && it.end <= text.length })
            assertTrue(h.spans.zipWithNext().all { (a, b) -> a.end <= b.start })
        }
        val big = "/*" + "x".repeat(CodeSyntax.MAX_CHARS * 2)
        val h = CodeSyntax.highlight("a.cpp", big)
        assertTrue(h.limited)
        assertTrue(h.spans.all { it.end <= CodeSyntax.MAX_CHARS })
        assertTrue(CodeSyntax.highlight("notes.md", "return 42").spans.isEmpty())
    }
    @Test fun pythonRecognizesSourceStubAndWindowedScriptExtensions() {
        for (path in listOf("main.py", "module.pyi", "UI.PYW")) assertEquals("Python", CodeSyntax.language(path))
    }
}
