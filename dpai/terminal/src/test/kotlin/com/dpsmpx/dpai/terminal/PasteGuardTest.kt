package com.dpsmpx.dpai.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Вопрос перед вставкой (терминал:A-7): многострочная без скобок 2004 и большая. */
class PasteGuardTest {
    @Test
    fun multilineWithoutBracketsAsks() {
        assertTrue(PasteGuard.needsConfirm("ls\nrm -rf build", bracketed = false))
        assertTrue(PasteGuard.needsConfirm("echo 1\r\n", bracketed = false), "Enter в конце — уже выполнение")
        assertFalse(PasteGuard.needsConfirm("ls\nrm -rf build", bracketed = true), "в скобках программа сама решает")
        assertFalse(PasteGuard.needsConfirm("одна строка", bracketed = false))
    }

    @Test
    fun bigPasteAsksEvenInBrackets() {
        val big = "x".repeat(PasteGuard.ASK_CHARS + 1)
        assertTrue(PasteGuard.needsConfirm(big, bracketed = true))
        assertFalse(PasteGuard.needsConfirm("x".repeat(PasteGuard.ASK_CHARS), bracketed = true))
    }

    @Test
    fun linesCountCrLfOnce() {
        assertEquals(3, PasteGuard.lines("a\r\nb\nc"))
        assertEquals(2, PasteGuard.lines("a\n"))
        assertEquals(1, PasteGuard.lines("a"))
    }
    @Test fun clipboardPreviewExposesInvisibleControlsWithoutChangingInput() {
        val source = "safe\u202e\u200b\u0000\nnext"
        assertEquals("safe\\u202e\\u200b\\u0000\nnext", PasteGuard.visible(source, 100))
        assertEquals("safe", PasteGuard.visible(source, 4))
    }

}
