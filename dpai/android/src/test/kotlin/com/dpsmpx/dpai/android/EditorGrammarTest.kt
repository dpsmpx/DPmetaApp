package com.dpsmpx.dpai.android

import java.nio.charset.StandardCharsets.UTF_8
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.Registry

/** Реальный TextMate/Joni, те же локальные грамматики, которые загружает поле. */
class EditorGrammarTest {
    private fun tokens(language: String, lines: List<String>): List<List<Pair<IntRange, List<String>>>> {
        val stream = java.io.File(System.getProperty("dpai.editor.assets"), "editor/$language.json").inputStream()
        val grammar = stream.use { Registry().addGrammar(IGrammarSource.fromInputStream(it, "$language.json", UTF_8)) }
        var state: IStateStack? = null
        return lines.map { line ->
            val result = grammar.tokenizeLine(line, state, null)
            assertFalse(result.isStoppedEarly)
            state = result.ruleStack
            result.tokens.map { (it.startIndex until it.endIndex) to it.scopes }
        }
    }
    private fun List<Pair<IntRange, List<String>>>.has(at: Int, scope: String) = any { at in it.first && it.second.any { s -> s.startsWith(scope) } }

    @Test fun cppBlockCommentsAndRawStringsContinueAcrossLines() {
        val t = tokens("cpp", listOf("/* int", "still comment */ int x;", "auto s = R\"tag(// raw", "still raw )tag\"; return 1'000;"))
        assertTrue(t[0].has(3, "comment.block")); assertTrue(t[1].has(2, "comment.block"))
        assertTrue(t[1].has(17, "storage.type")); assertTrue(t[2].has(18, "string.quoted.raw"))
        assertTrue(t[3].has(2, "string.quoted.raw")); assertTrue(t[3].has(17, "keyword.control"))
        assertTrue(t[3].has(24, "constant.numeric"))
    }
    @Test fun kotlinNestedCommentsCloseOnlyAtMatchingDepth() {
        val t = tokens("kotlin", listOf("/* outer /* nested", "*/ still comment", "*/ val x = 1"))
        assertTrue(t[1].has(10, "comment.block")); assertTrue(t[2].has(4, "keyword.control"))
    }
    @Test fun kotlinStringsInterpolateAndEscapedNamesAreNotKeywords() {
        val t = tokens("kotlin", listOf("val s = \"\"\"hello", "// raw \${if (true) 1 else 0}\"\"\"", "val `class` = \"// no comment\""))
        assertTrue(t[1].has(2, "string.quoted.triple")); assertTrue(t[1].has(9, "keyword.control"))
        assertTrue(t[2].has(6, "variable.other.escaped")); assertFalse(t[2].has(6, "keyword"))
        assertTrue(t[2].has(18, "string")); assertFalse(t[2].has(18, "comment"))
    }
    @Test fun allOtherBundledGrammarsTokenizeUnicodeAndLongLines() {
        for (language in listOf("java", "python", "json")) {
            val t = tokens(language, listOf("\"🙂 Русский\"", " ".repeat(20_000) + "42"))
            assertTrue(t[0].has(3, "string")); assertTrue(t[1].has(20_000, "constant.numeric"))
        }
    }
}
