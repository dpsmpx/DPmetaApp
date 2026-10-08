package com.dpsmpx.dpai.verification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Выжимка вывода инструмента (D-40, п. 3; `docs/EFFICIENCY.md`, §5): из
 * тысяч строк — то, что нужно для решения, и ничего выдуманного.
 */
class OutputDigestTest {
    private fun gradleLog(okLines: Int): List<String> = buildList {
        add("> Configure project :")
        repeat(okLines) { add("> Task :m$it:compileKotlin UP-TO-DATE") }
        add("ParserTest > parsesExponent() FAILED")
        add("    org.opentest4j.AssertionFailedError: expected: <1.0E3> but was: <1.0>")
        repeat(30) { add("        at app//org.junit.Frame$it.call(Frame.java:$it)") }
        add("        ... 12 more")
        add("50 tests completed, 1 failed")
        add("")
        add("FAILURE: Build failed with an exception.")
        add("BUILD FAILED in 12s")
    }

    @Test
    fun noisyBuildLogShrinksToTheFailure() {
        val raw = gradleLog(5_000)
        val d = OutputDigest.digest(raw)
        assertTrue(d.lines.size < 60, "выжимка — десятки строк, а не ${d.lines.size}")
        assertEquals(raw.size, d.totalLines)
        for (needle in listOf("parsesExponent() FAILED", "expected: <1.0E3> but was: <1.0>", "50 tests completed, 1 failed", "BUILD FAILED in 12s")) {
            assertTrue(d.lines.any { needle in it }, "в выжимке нет «$needle»: ${d.render()}")
        }
        // Стек — первые кадры после ошибки (и край вывода), середина пропущена; пропуски названы.
        assertTrue(d.lines.any { "Frame0." in it } && d.lines.none { "Frame15." in it }, d.render())
        assertTrue(d.lines.any { it.startsWith("… пропущено строк:") })
        // Каждая строка выжимки — строка вывода или пометка пропуска.
        assertTrue(d.lines.all { it in raw || it.startsWith("…") })
    }

    @Test
    fun ansiAndHugeLinesAreCleaned() {
        val d = OutputDigest.digest(listOf("\u001B[31merror:\u001B[0m bad", "x".repeat(10_000) + " error"), OutputDigest.Policy(maxLineChars = 100))
        assertEquals("error: bad", d.lines[0])
        assertTrue(d.lines[1].length < 130 && d.lines[1].endsWith("знаков]"), d.lines[1])
    }

    @Test
    fun repeatedErrorsAppearOnceAndManyAreCapped() {
        val same = OutputDigest.digest(List(500) { "error: same thing" })
        assertEquals(1, same.lines.count { it == "error: same thing" })
        assertEquals(500, same.errors)
        val many = OutputDigest.digest(List(1_000) { "error: e$it" }, OutputDigest.Policy(maxErrors = 10))
        assertEquals(10, many.lines.count { it.startsWith("error:") })
        assertTrue("error: e0" in many.lines && "error: e999" in many.lines, "первые и последние ошибки")
        assertTrue(many.lines.last().startsWith("… ошибок всего: 1000"))
    }

    @Test
    fun deterministicAndSafeOnOddInput() {
        val raw = gradleLog(100) + listOf("\u0000\u0001 бинарное �", "")
        assertEquals(OutputDigest.digest(raw), OutputDigest.digest(raw))
        assertEquals(emptyList(), OutputDigest.digest(emptyList()).lines)
        assertFalse(OutputDigest.digest(listOf("all good")).lines.isEmpty(), "край вывода остаётся")
    }
}
