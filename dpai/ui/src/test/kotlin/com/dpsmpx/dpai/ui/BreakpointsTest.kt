package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Точки останова (D-159): переключение, предел на файл, строки не сдвигаются, порядок для запуска. */
class BreakpointsTest {
    @Test
    fun breakpointsToggleLimitAndStayOnTheirLines() {
        var b = Breakpoints()
        b = b.toggle("/r/a.c", 3).toggle("/r/a.c", 10).toggle("/r/b.py", 5)
        assertEquals(mapOf("/r/a.c" to listOf(3, 10), "/r/b.py" to listOf(5)), b.forLaunch())
        b = b.toggle("/r/a.c", 3)
        assertEquals(setOf(10), b.lines("/r/a.c"))
        assertEquals(b, b.toggle("/r/a.c", 0), "строки — с единицы")
        b = b.toggle("/r/b.py", 5)
        assertEquals(setOf("/r/a.c"), b.byFile.keys, "пустой файл убран")
        var full = Breakpoints()
        for (l in 1..Breakpoints.MAX_PER_FILE) full = full.toggle("/r/x.c", l)
        assertEquals(full, full.toggle("/r/x.c", 999), "сверх предела — без изменений")
        assertEquals(Breakpoints.MAX_PER_FILE, full.count)
        assertEquals(0, full.clear().count)
    }
}
