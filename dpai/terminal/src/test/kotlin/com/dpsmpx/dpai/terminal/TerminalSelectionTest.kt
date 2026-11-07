package com.dpsmpx.dpai.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Выделение слова и ссылка в выделенном (терминал:A-2, A-19). */
class TerminalSelectionTest {
    private fun row(text: String): TerminalRow {
        val cps = text.codePoints().toArray()
        val r = TerminalRow(cps.size)
        cps.forEachIndexed { i, cp -> r.set(i, if (cp == '¤'.code) TerminalRow.WIDE_TAIL else cp, Style.DEFAULT) }
        return r
    }

    @Test
    fun wordIsPathOrUrlAsOne() {
        val r = row("cat ~/repos/dpai/README.md | less")
        assertEquals(4..25, TerminalSelection.word(r, 10), "путь — одно слово")
        assertEquals(0..2, TerminalSelection.word(r, 1))
        assertEquals(27..27, TerminalSelection.word(r, 27), "«|» — не слово: один столбец")
        val u = row("open https://claude.ai/oauth?code=x1 now")
        assertEquals(5..35, TerminalSelection.word(u, 20))
    }

    @Test
    fun wideCharsStayWhole() {
        // «界» занимает две ячейки: правая половина — часть знака.
        val r = row("a 界¤界¤ b")
        assertEquals(2..5, TerminalSelection.word(r, 3))
        assertEquals(2..5, TerminalSelection.word(r, 5))
    }

    @Test
    fun linkOnlyWhenSelectionIsOneUrl() {
        assertEquals("https://claude.ai/oauth?code=1", TerminalSelection.link("  https://claude.ai/oauth?code=1).\n"))
        assertEquals("https://a.b/c", TerminalSelection.link("https://a.b/\nc"), "перенос строки программы — не конец адреса")
        assertNull(TerminalSelection.link("см. https://a.b/c"))
        assertNull(TerminalSelection.link("intent://x"))
    }
}
