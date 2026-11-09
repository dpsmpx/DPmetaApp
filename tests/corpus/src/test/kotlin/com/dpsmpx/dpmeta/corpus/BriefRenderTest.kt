package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.formats.Brief
import com.dpsmpx.dpmeta.formats.BriefFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Сборка поручения из шаблона (F-23): места — за один проход, пустое поле и длинный текст — отказ. */
class BriefRenderTest {
    private val brief: Brief = BriefFormat.parse(
        """
        +++
        schema = 1
        title = "Т"

        [[field]]
        name = "branch"
        label = "Ветка"

        [[field]]
        name = "answer"
        label = "Ответ"
        multiline = true
        +++
        {{repo}}: ветка {{branch}}.
        {{answer}}
        """.trimIndent(),
        "t",
    ).valueOrNull()!!

    private val builtIn = mapOf("project" to "P", "repo" to "o/r", "base" to "main")

    @Test
    fun placesFilledOnce() {
        val text = brief.render(builtIn, mapOf("branch" to " feat/x\n  y ", "answer" to "да\n{{repo}}\n")).valueOrNull()!!
        assertEquals("o/r: ветка feat/x y.\nда\n{{repo}}\n", text, "однострочное — в строку; значение с местом не раскрывается")
    }

    @Test
    fun missingAndTooLong() {
        val e = assertIs<Outcome.Failure>(brief.render(builtIn, mapOf("branch" to " ", "answer" to "a"))).errors.single()
        assertEquals(DiagCode.FIELD_MISSING, e.code)
        assertEquals(listOf("branch"), brief.missing(mapOf("answer" to "a")).map { it.name })
        assertEquals(DiagCode.FIELD_MISSING, assertIs<Outcome.Failure>(brief.render(mapOf("project" to "P"), mapOf("branch" to "b", "answer" to "a"))).errors.single().code)
        val long = "я".repeat(BriefFormat.MAX_TEXT)
        assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(brief.render(builtIn, mapOf("branch" to "b", "answer" to long))).errors.single().code)
    }

    @Test
    fun keyIsFileName() {
        assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(BriefFormat.parse("+++\nschema = 1\ntitle = \"x\"\n+++\nт", "../x")).errors.single().code)
    }
}
