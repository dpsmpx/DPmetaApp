package com.dpsmpx.dpai.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ввод человека → байты программе (D-71). */
class KeyEncoderTest {
    private fun s(b: ByteArray?) = b?.let { String(it, Charsets.UTF_8) }

    @Test
    fun cursorKeysFollowApplicationMode() {
        val t = TerminalEmulator(10, 3)
        assertEquals("\u001b[A", s(KeyEncoder.key(Key.UP, 0, t)))
        assertEquals("\u001b[H", s(KeyEncoder.key(Key.HOME, 0, t)))
        t.feed("\u001b[?1h")
        assertEquals("\u001bOA", s(KeyEncoder.key(Key.UP, 0, t)))
        assertEquals("\u001bOF", s(KeyEncoder.key(Key.END, 0, t)))
        // С модификатором — всегда CSI 1;m.
        assertEquals("\u001b[1;5D", s(KeyEncoder.key(Key.LEFT, Mod.CTRL, t)))
        assertEquals("\u001b[1;4C", s(KeyEncoder.key(Key.RIGHT, Mod.SHIFT or Mod.ALT, t)))
    }

    @Test
    fun editingAndFunctionKeys() {
        val t = TerminalEmulator(10, 3)
        assertEquals("\u001b[3~", s(KeyEncoder.key(Key.DELETE, 0, t)))
        assertEquals("\u001b[5;5~", s(KeyEncoder.key(Key.PAGE_UP, Mod.CTRL, t)))
        assertEquals("\u001bOP", s(KeyEncoder.key(Key.F1, 0, t)))
        assertEquals("\u001b[1;2S", s(KeyEncoder.key(Key.F4, Mod.SHIFT, t)))
        assertEquals("\u001b[15~", s(KeyEncoder.key(Key.F5, 0, t)))
        assertEquals("\u001b[24;3~", s(KeyEncoder.key(Key.F12, Mod.ALT, t)))
        assertEquals("\u001b[Z", s(KeyEncoder.key(Key.TAB, Mod.SHIFT, t)))
        assertEquals("\u007f", s(KeyEncoder.key(Key.BACKSPACE, 0, t)))
        assertEquals("\b", s(KeyEncoder.key(Key.BACKSPACE, Mod.CTRL, t)))
        assertEquals("\u001b\u007f", s(KeyEncoder.key(Key.BACKSPACE, Mod.ALT, t)))
        assertEquals("\r", s(KeyEncoder.key(Key.ENTER, 0, t)))
        t.feed("\u001b[20h")
        assertEquals("\r\n", s(KeyEncoder.key(Key.ENTER, 0, t)))
    }

    @Test
    fun controlAndAltCharacters() {
        assertContentEquals(byteArrayOf(3), KeyEncoder.char('c'.code, Mod.CTRL))
        assertContentEquals(byteArrayOf(3), KeyEncoder.char('C'.code, Mod.CTRL))
        assertContentEquals(byteArrayOf(0), KeyEncoder.char(' '.code, Mod.CTRL))
        assertContentEquals(byteArrayOf(0x1b), KeyEncoder.char('['.code, Mod.CTRL))
        assertContentEquals(byteArrayOf(0x7f), KeyEncoder.char('?'.code, Mod.CTRL))
        assertEquals("\u001bx", s(KeyEncoder.char('x'.code, Mod.ALT)))
        assertEquals("\u001b\u0018", s(KeyEncoder.char('x'.code, Mod.ALT or Mod.CTRL)))
        assertEquals("ж", s(KeyEncoder.char('ж'.code, 0)))
        // Русская раскладка: Ctrl — по месту клавиши.
        assertContentEquals(byteArrayOf(3), KeyEncoder.char('с'.code, Mod.CTRL))
        assertContentEquals(byteArrayOf(3), KeyEncoder.char('С'.code, Mod.CTRL))
        assertContentEquals(byteArrayOf(4), KeyEncoder.char('в'.code, Mod.CTRL))
        assertContentEquals(byteArrayOf(0x1b), KeyEncoder.char('х'.code, Mod.CTRL))
        assertEquals("\u001bж", s(KeyEncoder.char('ж'.code, Mod.ALT)))
    }

    @Test
    fun pasteCannotEscapeTheBrackets() {
        val t = TerminalEmulator(10, 3)
        assertEquals("a\rb\tc", s(KeyEncoder.paste("a\r\nb\tc", t)))
        t.feed("\u001b[?2004h")
        val evil = "ls\u001b[201~rm -rf ~\n"
        assertEquals("\u001b[200~ls[201~rm -rf ~\r\u001b[201~", s(KeyEncoder.paste(evil, t)))
        assertEquals("\u001b[200~ab\u001b[201~", s(KeyEncoder.paste("a\u0003\u009bb", t)))
    }

    @Test
    fun mouseIsReportedOnlyWhenAsked() {
        val t = TerminalEmulator(300, 3)
        assertNull(KeyEncoder.mouse(MouseButton.LEFT, 0, 0, pressed = true, motion = false, mods = 0, term = t))
        t.feed("\u001b[?1000h")
        assertEquals("\u001b[M !!", s(KeyEncoder.mouse(MouseButton.LEFT, 0, 0, pressed = true, motion = false, mods = 0, term = t)))
        assertEquals("\u001b[M#!!", s(KeyEncoder.mouse(MouseButton.LEFT, 0, 0, pressed = false, motion = false, mods = 0, term = t)))
        assertNull(KeyEncoder.mouse(MouseButton.LEFT, 1, 1, pressed = true, motion = true, mods = 0, term = t))
        // Старая запись не выражает дальние столбцы.
        assertNull(KeyEncoder.mouse(MouseButton.LEFT, 250, 0, pressed = true, motion = false, mods = 0, term = t))
        t.feed("\u001b[?1006h")
        assertEquals("\u001b[<0;251;1M", s(KeyEncoder.mouse(MouseButton.LEFT, 250, 0, pressed = true, motion = false, mods = 0, term = t)))
        assertEquals("\u001b[<16;3;2m", s(KeyEncoder.mouse(MouseButton.LEFT, 2, 1, pressed = false, motion = false, mods = Mod.CTRL, term = t)))
        assertEquals("\u001b[<64;1;1M", s(KeyEncoder.mouse(MouseButton.WHEEL_UP, 0, 0, pressed = true, motion = false, mods = 0, term = t)))
        t.feed("\u001b[?1002h")
        assertEquals("\u001b[<32;2;2M", s(KeyEncoder.mouse(MouseButton.LEFT, 1, 1, pressed = true, motion = true, mods = 0, term = t)))
        assertNull(KeyEncoder.mouse(MouseButton.NONE, 1, 1, pressed = false, motion = true, mods = 0, term = t))
        t.feed("\u001b[?1003h")
        assertEquals("\u001b[<35;2;2M", s(KeyEncoder.mouse(MouseButton.NONE, 1, 1, pressed = false, motion = true, mods = 0, term = t)))
    }

    @Test
    fun focusOnlyWhenAsked() {
        val t = TerminalEmulator(10, 3)
        assertNull(KeyEncoder.focus(true, t))
        t.feed("\u001b[?1004h")
        assertEquals("\u001b[I", s(KeyEncoder.focus(true, t)))
        assertEquals("\u001b[O", s(KeyEncoder.focus(false, t)))
    }
}
