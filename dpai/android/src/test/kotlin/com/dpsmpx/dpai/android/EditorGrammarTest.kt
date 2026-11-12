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
    @Test fun pythonPrefixedStringsAndDocstringsKeepTheirOwnEscapes() {
        val lines = listOf("path = r\"C:\\new\\name\"", "data = b\"\\x41\"", "doc = r'''# not comment", "still raw''' # comment")
        val t = tokens("python", lines)
        assertTrue(t[0].has(lines[0].indexOf("\\new"), "string"))
        assertFalse(t[0].has(lines[0].indexOf("\\new"), "constant.character.escape"))
        assertTrue(t[1].has(lines[1].indexOf("\\x41"), "constant.character.escape"))
        assertTrue(t[2].has(10, "string.quoted.triple")); assertFalse(t[2].has(10, "comment"))
        assertTrue(t[3].has(2, "string.quoted.triple")); assertTrue(t[3].has(lines[3].indexOf('#'), "comment"))
    }
    @Test fun pythonFStringsDistinguishEscapedBracesAndNestedExpressions() {
        val line = "value = f\"{{literal}} {items['key'] if ready else {'nested': 2}}\" # end"
        val t = tokens("python", listOf(line))[0]
        assertFalse(t.has(line.indexOf("literal"), "meta.interpolation"))
        assertTrue(t.has(line.indexOf("items"), "meta.interpolation"))
        assertTrue(t.has(line.indexOf("if ready"), "keyword.control.python"))
        assertTrue(t.has(line.indexOf("'nested'"), "string.quoted.single.python"))
        assertTrue(t.has(line.indexOf("# end"), "comment"))
    }
    @Test fun pythonRawFStringsSupportMultilineExpressionsAndQuoteReuse() {
        val lines = listOf("value = RF\"\"\"\\n {", "    data[\"key\"] if ready else 0", "}\"\"\" # end")
        val t = tokens("python", lines)
        assertFalse(t[0].has(lines[0].indexOf("\\n"), "constant.character.escape"))
        assertTrue(t[1].has(4, "meta.interpolation")); assertTrue(t[1].has(15, "keyword.control.python"))
        assertTrue(t[2].has(lines[2].indexOf('#'), "comment"))
        val single = "f\"{data[\"key\"]}\" # done"
        assertTrue(tokens("python", listOf(single))[0].has(single.indexOf('#'), "comment"))
    }
    @Test fun pythonDecoratorsDefinitionsSoftKeywordsAndNumericForms() {
        val lines = listOf("@cache.memoize", "async def привет(x):", "class Thing:", "match value:", "    case [first, *rest]:", "values = [0x_FF, 0b_1010, 0o755, 1_000j, .5e-2, 1.]")
        val t = tokens("python", lines)
        assertTrue(t[0].has(2, "entity.name.function.decorator"))
        assertTrue(t[1].has(lines[1].indexOf("привет"), "entity.name.function"))
        assertTrue(t[2].has(6, "entity.name.type")); assertTrue(t[3].has(0, "keyword.control")); assertTrue(t[4].has(4, "keyword.control"))
        for (number in listOf("0x_FF", "0b_1010", "0o755", "1_000j", ".5e-2", "1.")) {
            val at = lines[5].indexOf(number)
            assertTrue(t[5].has(at, "constant.numeric"), number)
            assertTrue(t[5].has(at + number.length - 1, "constant.numeric"), number)
        }
    }
    private fun pythonConfig() = java.io.File(System.getProperty("dpai.editor.assets"), "editor/python.config.json").reader().use {
        checkNotNull(org.eclipse.tm4e.languageconfiguration.internal.model.LanguageConfiguration.load(it))
    }
    @Test fun pythonEnterIndentsBlockHeadersAndOpeningBrackets() {
        val config = pythonConfig()
        val enter = org.eclipse.tm4e.languageconfiguration.internal.supports.OnEnterSupport(config.brackets, config.onEnterRules)
        val indent = org.eclipse.tm4e.languageconfiguration.internal.supports.IndentRulesSupport(checkNotNull(config.indentationRules))
        for (line in listOf("def f(x):", "async def f(): # note", "class Thing:", "    if x == ':': # note", "elif ready:", "else:", "for x in xs:", "async with lock:", "try:", "except* ValueError:", "finally:", "match value:", "    case {'key': x}:", ") -> list[int]:")) {
            kotlin.test.assertEquals(org.eclipse.tm4e.languageconfiguration.internal.model.EnterAction.IndentAction.Indent, enter.onEnter("", line, "")?.indentAction, line)
            assertTrue(indent.shouldIncrease(line), line)
        }
        assertTrue(indent.shouldIncrease("items = [ # values"))
        kotlin.test.assertEquals(org.eclipse.tm4e.languageconfiguration.internal.model.EnterAction.IndentAction.IndentOutdent, enter.onEnter("", "call(", ")")?.indentAction)
    }
    @Test fun pythonEnterDoesNotIndentCommentOrStringColons() {
        val config = pythonConfig()
        val enter = org.eclipse.tm4e.languageconfiguration.internal.supports.OnEnterSupport(config.brackets, config.onEnterRules)
        val indent = org.eclipse.tm4e.languageconfiguration.internal.supports.IndentRulesSupport(checkNotNull(config.indentationRules))
        for (line in listOf("# if ready:", "text = 'if ready:'", "mapping = {'key': 1}", "if ready: run()", "@decorate", "value = '[('", "if ready # :")) {
            kotlin.test.assertNull(enter.onEnter("", line, ""), line)
            assertFalse(indent.shouldIncrease(line), line)
        }
        assertTrue(indent.shouldDecrease("    ] # end"))
    }
}
