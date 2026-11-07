package com.dpsmpx.dpai.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Эмулятор xterm своего терминала (D-71): экран, режимы, ответы программе, смена размера. */
class TerminalEmulatorTest {
    private class Recorder : TerminalHost {
        val out = StringBuilder()
        var clip: String? = null
        var bells = 0
        override fun write(bytes: ByteArray) {
            out.append(String(bytes, Charsets.UTF_8))
        }
        override fun bell() {
            bells++
        }
        override fun clipboard(text: String) {
            clip = text
        }
    }

    private fun term(cols: Int = 20, rows: Int = 5, host: TerminalHost = TerminalHost.NONE) = TerminalEmulator(cols, rows, host = host)

    @Test
    fun textAndNewLines() {
        val t = term()
        t.feed("hello\r\nworld")
        assertEquals("hello\nworld", t.screenText())
        assertEquals(5, t.cursorX)
        assertEquals(1, t.cursorY)
    }

    @Test
    fun autoWrapWaitsForTheNextCharacter() {
        val t = term(cols = 5)
        t.feed("abcde")
        assertEquals(4, t.cursorX)
        assertEquals(0, t.cursorY)
        t.feed("f")
        assertEquals("abcde\nf", t.screenText())
        assertTrue(t.screenRow(0).wrapped)
        assertFalse(t.screenRow(1).wrapped)
        // Без автопереноса последний столбец перезаписывается.
        val n = term(cols = 5)
        n.feed("\u001b[?7labcdefg")
        assertEquals("abcdg", n.screenText())
    }

    @Test
    fun scrolledLinesGoToScrollbackButNotFromTheSecondScreen() {
        val t = term(rows = 3)
        t.feed("1\r\n2\r\n3\r\n4")
        assertEquals(1, t.scrollbackSize)
        assertEquals("1", t.scrollbackRow(0).text())
        assertEquals("2\n3\n4", t.screenText())
        t.feed("\u001b[?1049h\r\n\r\n\r\n\r\n")
        assertEquals(1, t.scrollbackSize)
        val limited = TerminalEmulator(10, 2, scrollbackMax = 3)
        repeat(10) { limited.feed("$it\r\n") }
        assertEquals(3, limited.scrollbackSize)
        assertEquals("8", limited.scrollbackRow(2).text())
    }

    @Test
    fun cursorPositioningAndErasing() {
        val t = term()
        t.feed("\u001b[2;3HX")
        assertEquals("  X", t.screenRow(1).text())
        t.feed("\u001b[1;1Habcdef\u001b[1;3H\u001b[K")
        assertEquals("ab", t.screenRow(0).text())
        t.feed("\u001b[1;2H\u001b[1K")
        assertEquals("", t.screenRow(0).text())
        t.feed("\u001b[2J")
        assertEquals("", t.screenText())
        // Курсор не уходит за край.
        t.feed("\u001b[99;99H")
        assertEquals(19, t.cursorX)
        assertEquals(4, t.cursorY)
        t.feed("\u001b[10D\u001b[2A")
        assertEquals(9, t.cursorX)
        assertEquals(2, t.cursorY)
    }

    @Test
    fun insertAndDeleteCharactersAndLines() {
        val t = term()
        t.feed("abcdef\u001b[1;3H\u001b[2P")
        assertEquals("abef", t.screenRow(0).text())
        t.feed("\u001b[2@")
        assertEquals("ab  ef", t.screenRow(0).text())
        t.feed("\u001b[4hXY\u001b[4l")
        assertEquals("abXY  ef", t.screenRow(0).text())
        t.feed("\u001b[2J\u001b[1;1H1\r\n2\r\n3\u001b[2;1H\u001b[L")
        assertEquals("1\n\n2\n3", t.screenText())
        t.feed("\u001b[2M")
        assertEquals("1\n3", t.screenText())
        t.feed("\u001b[1;1H\u001b[3X")
        assertEquals("", t.screenRow(0).text())
    }

    @Test
    fun scrollRegionKeepsLinesOutside() {
        val t = term(rows = 5)
        t.feed("a\r\nb\r\nc\r\nd\r\ne")
        t.feed("\u001b[2;4r")
        assertEquals(0, t.cursorY)
        t.feed("\u001b[4;1H\n")
        assertEquals("a\nc\nd\n\ne", t.screenText())
        assertEquals(0, t.scrollbackSize)
        t.feed("\u001b[2;1H\u001bM")
        assertEquals("a\n\nc\nd\ne", t.screenText())
        // DECOM: позиция — относительно области.
        t.feed("\u001b[?6h\u001b[1;1HZ")
        assertEquals("Z", t.screenRow(1).text())
    }

    @Test
    fun colorsAndAttributes() {
        val t = term()
        t.feed("\u001b[1;31mA\u001b[0mB\u001b[38;5;196mC\u001b[48;2;1;2;3mD\u001b[0;38:2::10:20:30mE\u001b[4:3;9;7mF\u001b[94;103mG")
        val r = t.screenRow(0)
        assertTrue(r.style(0).has(Attr.BOLD))
        assertEquals(TermColor.indexed(1), r.style(0).fg)
        assertEquals(Style.DEFAULT, r.style(1))
        assertEquals(TermColor.indexed(196), r.style(2).fg)
        assertEquals(TermColor.rgb(1, 2, 3), r.style(3).bg)
        assertEquals(TermColor.indexed(196), r.style(3).fg)
        assertEquals(TermColor.rgb(10, 20, 30), r.style(4).fg)
        assertEquals(TermColor.DEFAULT, r.style(4).bg)
        assertTrue(r.style(5).has(Attr.UNDERLINE) && r.style(5).has(Attr.STRIKE) && r.style(5).has(Attr.INVERSE))
        assertEquals(TermColor.indexed(12), r.style(6).fg)
        assertEquals(TermColor.indexed(11), r.style(6).bg)
        // Стирание — фоном текущего стиля, без признаков.
        t.feed("\u001b[0;44m\u001b[2;1H\u001b[K")
        assertEquals(TermColor.indexed(4), t.screenRow(1).style(10).bg)
        assertFalse(t.screenRow(1).style(10).has(Attr.UNDERLINE))
    }

    @Test
    fun secondScreenRestoresTheFirstAndTheCursor() {
        val t = term()
        t.feed("main")
        t.feed("\u001b[?1049h")
        assertTrue(t.altScreen)
        assertEquals("", t.screenText())
        t.feed("\u001b[3;3Halt")
        t.feed("\u001b[?1049l")
        assertFalse(t.altScreen)
        assertEquals("main", t.screenText())
        assertEquals(4, t.cursorX)
        assertEquals(0, t.cursorY)
    }

    /** Поиск по истории (терминал:A-4): к старым и к новым, без регистра, со столбцами совпадения. */
    @Test
    fun findGoesUpAndDownWithColumns() {
        val t = term(cols = 20, rows = 3)
        t.feed("alpha\r\nBuild ok\r\ngamma\r\nbuild fail\r\nend")
        val last = t.lineCount
        val m = t.find("build", last, up = true)!!
        assertEquals(3, m.line)
        assertEquals(0..4, m.cols)
        assertEquals(1, t.find("build", m.line, up = true)!!.line, "дальше вверх — старое совпадение")
        assertEquals(null, t.find("build", 1, up = true), "выше — нет")
        assertEquals(3, t.find("BUILD", 1, up = false)!!.line, "вниз — к новым")
        assertEquals(6..9, t.find("fail", last, up = true)!!.cols)
    }

    /** OSC 9 и 777;notify — сигнал программы, как звонок (терминал:A-8). */
    @Test
    fun programNotificationsRingTheBell() {
        val h = Recorder()
        val t = term(host = h)
        t.feed("\u001b]9;готово\u0007\u001b]777;notify;Claude;ждёт\u0007")
        assertEquals(2, h.bells)
    }

    /** OSC 8 (терминал:A-19): ссылка у ячеек текста, только http(s); пустой адрес закрывает. */
    @Test
    fun osc8LinksAreKeptOnCells() {
        val t = term(cols = 30)
        t.feed("\u001b]8;;https://example.org/doc\u001b\\docs\u001b]8;;\u001b\\ plain")
        assertEquals("https://example.org/doc", t.linkAt(t.scrollbackSize, 0))
        assertEquals("https://example.org/doc", t.linkAt(t.scrollbackSize, 3))
        assertEquals(null, t.linkAt(t.scrollbackSize, 6), "после закрытия — без ссылки")
        // Чужая схема не принимается: программа на target не подсунет intent:/file:.
        val u = term(cols = 30)
        u.feed("\u001b]8;;intent://x#Intent;end\u0007text\u001b]8;;\u0007")
        assertEquals(null, u.linkAt(u.scrollbackSize, 0))
        // Ссылка переживает вставку слева и смену ширины.
        t.feed("\r\u001b[2@")
        assertEquals("https://example.org/doc", t.linkAt(t.scrollbackSize, 2))
        t.resize(10, 5)
        assertEquals("https://example.org/doc", t.linkAt(t.scrollbackSize, 2))
    }

    /** Подчёркивание (терминал:A-19): ячейки найденного адреса и ссылки OSC 8 — да, прочие — нет. */
    @Test
    fun linkCellsMarkAddressesAndOsc8() {
        val t = term(cols = 40)
        t.feed("see https://example.org/a. and \u001b]8;;https://example.org/b\u0007b\u001b]8;;\u0007 x")
        val cells = t.linkCells(t.scrollbackSize)
        assertTrue(cells[4] && cells[24], "адрес — от «h» до последней буквы")
        assertTrue(!cells[25], "точка после адреса — не ссылка")
        assertTrue(cells[31], "знак со ссылкой OSC 8")
        assertTrue(!cells[0] && !cells[33])
    }

    /** Эмодзи-кластеры (терминал:A-17): как у программ — две ячейки, курсор за ними. */
    @Test
    fun emojiClustersTakeTwoCells() {
        // VS16: «❤» (ширина 1) с U+FE0F — эмодзи в две ячейки.
        val h = term(cols = 6)
        h.feed("\u2764\uFE0Fx")
        assertEquals(TerminalRow.WIDE_TAIL, h.screenRow(0).codePoint(1))
        assertEquals("\u2764\uFE0Fx", h.screenRow(0).text().trimEnd())
        assertEquals(3, h.cursorX)
        // ZWJ-последовательность «семья» — одна ячейка шириной 2.
        val z = term(cols = 8)
        z.feed("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67!")
        assertEquals(TerminalRow.WIDE_TAIL, z.screenRow(0).codePoint(1))
        assertEquals('!'.code, z.screenRow(0).codePoint(2))
        assertEquals(3, z.cursorX)
        // Флаг — пара региональных индикаторов: одна ячейка шириной 2.
        val f = term(cols = 8)
        f.feed("\uD83C\uDDF7\uD83C\uDDFA-")
        assertEquals(TerminalRow.WIDE_TAIL, f.screenRow(0).codePoint(1))
        assertEquals('-'.code, f.screenRow(0).codePoint(2))
        // Третья буква — новый флаг, а не продолжение первого.
        f.feed("\uD83C\uDDE9\uD83C\uDDEA")
        assertEquals(TerminalRow.WIDE_TAIL, f.screenRow(0).codePoint(4))
        assertEquals(5, f.cursorX)
        // Ширина кластера переживает смену ширины экрана.
        h.resize(4, 5)
        assertEquals(TerminalRow.WIDE_TAIL, h.screenRow(0).codePoint(1))
    }

    @Test
    fun wideAndCombiningCharacters() {
        val t = term(cols = 6)
        t.feed("中文é")
        val r = t.screenRow(0)
        assertEquals('中'.code, r.codePoint(0))
        assertEquals(TerminalRow.WIDE_TAIL, r.codePoint(1))
        assertEquals("中文é", r.text())
        assertEquals(5, t.cursorX)
        // Широкий символ не делится краем: переносится целиком.
        val n = term(cols = 3)
        n.feed("ab中")
        assertEquals("ab\n中", n.screenText())
        assertTrue(n.screenRow(0).wrapped)
        // Запись поверх половины широкого стирает и вторую.
        t.feed("\r\u001b[1CX")
        assertEquals(TerminalRow.EMPTY, t.screenRow(0).codePoint(0))
        assertEquals(" X文é", t.screenRow(0).text())
        // Эмодзи — две ячейки.
        assertEquals(2, CharWidth.of(0x1F600))
    }

    @Test
    fun utf8SplitAcrossChunks() {
        val t = term()
        val bytes = "жёлтый 中".toByteArray(Charsets.UTF_8)
        for (b in bytes) t.feed(byteArrayOf(b))
        assertEquals("жёлтый 中", t.screenText())
        t.feed(byteArrayOf(0xd0.toByte(), 0x41))
        assertTrue(t.screenRow(0).text().endsWith("�A"))
    }

    @Test
    fun repliesToTheProgram() {
        val h = Recorder()
        val t = term(host = h)
        t.feed("ab\u001b[6n")
        assertEquals("\u001b[1;3R", h.out.toString())
        h.out.setLength(0)
        t.feed("\u001b[c\u001b[>c")
        assertEquals("\u001b[?62;22c\u001b[>1;10;0c", h.out.toString())
        h.out.setLength(0)
        t.feed("\u001b[?2004\$p\u001b[?2004h\u001b[?2004\$p\u001b[?9999\$p")
        assertEquals("\u001b[?2004;2\$y\u001b[?2004;1\$y\u001b[?9999;0\$y", h.out.toString())
        assertTrue(t.bracketedPaste)
        h.out.setLength(0)
        t.feed("\u001b]11;?\u0007\u001b]10;?\u001b\\")
        assertEquals("\u001b]11;rgb:0000/0000/0000\u0007\u001b]10;rgb:e5e5/e5e5/e5e5\u001b\\", h.out.toString())
        h.out.setLength(0)
        t.feed("\u001b[18t")
        assertEquals("\u001b[8;5;20t", h.out.toString())
        t.feed("\u0007")
        assertEquals(1, h.bells)
    }

    @Test
    fun titleAndClipboardWriteButNoClipboardRead() {
        val h = Recorder()
        val t = term(host = h)
        t.feed("\u001b]0;мой заголовок\u001b\\x")
        assertEquals("мой заголовок", t.title)
        assertEquals("x", t.screenText())
        val b64 = java.util.Base64.getEncoder().encodeToString("скопировано".toByteArray())
        t.feed("\u001b]52;c;$b64\u0007")
        assertEquals("скопировано", h.clip)
        h.out.setLength(0)
        t.feed("\u001b]52;c;?\u0007")
        assertEquals("", h.out.toString())
    }

    @Test
    fun modesForInput() {
        val t = term()
        t.feed("\u001b[?1h\u001b[?1002h\u001b[?1006h\u001b[?1004h\u001b[?25l")
        assertTrue(t.cursorKeysApp)
        assertEquals(MouseMode.DRAG, t.mouseMode)
        assertTrue(t.mouseSgr && t.focusEvents)
        assertFalse(t.cursorVisible)
        t.feed("\u001b[5 q")
        assertEquals(5, t.cursorShape)
        t.feed("\u001bc")
        assertFalse(t.cursorKeysApp)
        assertEquals(MouseMode.NONE, t.mouseMode)
        assertTrue(t.cursorVisible)
    }

    @Test
    fun decLineDrawing() {
        val t = term()
        t.feed("\u001b(0lqk\u001b(Bq")
        assertEquals("┌─┐q", t.screenText())
    }

    @Test
    fun tabsAndRepeat() {
        val t = term()
        t.feed("a\tb\u001b[2Zc")
        assertEquals("c       b", t.screenRow(0).text())
        t.feed("\r\nx\u001b[3b")
        assertEquals("xxxx", t.screenRow(1).text())
    }

    @Test
    fun widerTerminalJoinsWrappedLinesAndNarrowerSplitsThem() {
        val t = TerminalEmulator(10, 3)
        t.feed("0123456789abcde")
        assertEquals("0123456789\nabcde", t.screenText())
        t.resize(20, 3)
        assertEquals("0123456789abcde", t.screenText())
        assertEquals(15, t.cursorX)
        assertEquals(0, t.cursorY)
        t.resize(10, 3)
        assertEquals("0123456789\nabcde", t.screenText())
        assertEquals(5, t.cursorX)
        assertEquals(1, t.cursorY)
        // Ввод после смены размера — у того же места.
        t.feed("f")
        assertEquals("abcdef", t.screenRow(1).text())
    }

    @Test
    fun fewerRowsMoveLinesToScrollbackAndMoreRowsBringThemBack() {
        val t = TerminalEmulator(10, 3)
        t.feed("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals(2, t.scrollbackSize)
        t.resize(10, 5)
        assertEquals(0, t.scrollbackSize)
        assertEquals("1\n2\n3\n4\n5", t.screenText())
        assertEquals(4, t.cursorY)
        t.resize(10, 2)
        assertEquals("4\n5", t.screenText())
        assertEquals(3, t.scrollbackSize)
        assertEquals(1, t.cursorY)
    }

    @Test
    fun resizeKeepsCursorOnEmptyLinesAndWideCharacters() {
        val t = TerminalEmulator(10, 5)
        t.feed("prompt$ \r\n\r\n")
        assertEquals(2, t.cursorY)
        t.resize(8, 5)
        assertEquals(2, t.cursorY)
        assertEquals(0, t.cursorX)
        val w = TerminalEmulator(5, 3)
        w.feed("ab中文")
        assertEquals("ab中\n文", w.screenText())
        w.resize(4, 3)
        assertEquals("ab中\n文", w.screenText())
        w.resize(3, 3)
        assertEquals("ab\n中\n文", w.screenText())
    }

    @Test
    fun secondScreenIsCutNotReflowed() {
        val t = TerminalEmulator(10, 3)
        t.feed("main line")
        t.feed("\u001b[?1049h\u001b[H0123456789")
        t.resize(5, 3)
        assertEquals("01234", t.screenText())
        t.feed("\u001b[?1049l")
        assertEquals("main \nline", t.screenText())
    }

    @Test
    fun selectionTextJoinsSoftWrappedLines() {
        val t = TerminalEmulator(5, 3)
        t.feed("abcdefg\r\nxyz")
        assertEquals("abcdefg\nxyz", t.text(0, 0, 2, 4))
        assertEquals("cdefg\nx", t.text(0, 2, 2, 0))
    }

    // ---- находки ревью перед вливанием (17 октября 2026) ----

    @Test
    fun combiningMarksPerCellAreCapped() {
        val t = term()
        t.feed("a" + "\u0301".repeat(100_000) + "b")
        assertTrue(t.screenRow(0).text(0).length <= 1 + TerminalRow.MAX_COMBINING)
        assertEquals("b", t.screenRow(0).text(1))
    }

    @Test
    fun resizeInSecondScreenKeepsTheSavedCursorOnItsLine() {
        val t = TerminalEmulator(20, 6)
        for (i in 1..10) t.feed("line$i\r\n")
        t.feed("\u001b[H\u001b[2J$ vim\r\n")
        t.feed("\u001b[?1049h")
        t.resize(20, 3)
        t.resize(20, 6)
        t.feed("\u001b[?1049l$ ")
        // Строки истории вернулись на экран при росте; приглашение — сразу под «$ vim», ничего не затёрто.
        assertTrue(t.screenText().endsWith("line5\n$ vim\n$ "), t.screenText())
        assertEquals("$ vim", t.screenRow(t.cursorY - 1).text())
        assertEquals(2, t.cursorX)
    }

    @Test
    fun erasingTheLineEndsItsSoftWrap() {
        val t = TerminalEmulator(10, 5)
        t.feed("0123456789AB\u001b[1;1H\u001b[2Kshort\r\n\u001b[2Knext\r\n")
        assertFalse(t.screenRow(0).wrapped)
        t.resize(10, 4)
        assertEquals("short", t.screenRow(0).text())
        assertEquals("next", t.screenRow(1).text())
        val j = TerminalEmulator(10, 5)
        j.feed("0123456789AB\u001b[1;1H\u001b[Jone\r\ntwo")
        j.resize(10, 4)
        assertEquals("one\ntwo", j.screenText())
    }

    @Test
    fun oneColumnResizeWithWideCharactersDoesNotThrow() {
        val t = TerminalEmulator(10, 3)
        t.feed("ab中")
        t.resize(1, 3)
        t.resize(10, 3)
        assertTrue("中" in t.screenText())
    }

    @Test
    fun deletingCharactersDropsTheirCombiningMarks() {
        val t = term()
        t.feed("ae\u0301x\u001b[1;2H\u001b[P")
        assertEquals("ax", t.screenRow(0).text())
    }

    @Test
    fun pendingWrapSurvivesResize() {
        val t = TerminalEmulator(10, 3)
        t.feed("0123456789")
        t.resize(10, 4)
        t.feed("X")
        assertEquals("0123456789\nX", t.screenText())
        val w = TerminalEmulator(10, 3)
        w.feed("0123456789")
        w.resize(20, 3)
        w.feed("X")
        assertEquals("0123456789X", w.screenText())
    }

    @Test
    fun shrinkingKeepsTheCursorLineOnScreen() {
        val t = TerminalEmulator(10, 4)
        t.feed("$ " + "x".repeat(35) + "\u001b[4A\r")
        t.resize(10, 2)
        assertEquals("$ xxxxxxxx", t.screenRow(t.cursorY).text())
        assertEquals(0, t.cursorX)
    }

    @Test
    fun insertAndDeleteLinesCancelPendingWrap() {
        val t = TerminalEmulator(5, 4)
        t.feed("\u001b[2;1Habcde\u001b[LX")
        assertEquals("X", t.screenRow(1).text())
        assertFalse(t.screenRow(1).wrapped)
        assertEquals("abcde", t.screenRow(2).text())
        val c = TerminalEmulator(5, 2)
        c.feed("abcde\u001b[@Y")
        assertEquals("abcdY", c.screenRow(0).text().replace(" ", "").let { it })
        assertEquals(0, c.cursorY)
    }

    @Test
    fun scrollRegionBottomBeyondTheScreenIsClamped() {
        val t = TerminalEmulator(10, 5)
        t.feed("top\u001b[2;9r\u001b[5;1HA\nB\nC\nD\nE")
        assertEquals("top", t.screenRow(0).text())
        assertEquals(0, t.scrollbackSize)
    }

    @Test
    fun linksAreFoundAcrossSoftWraps() {
        val t = TerminalEmulator(20, 4)
        t.feed("Open https://example.org/sign/in?code=abc123xyz (in browser).")
        // Адрес перенесён по ширине: касание на любой его части даёт его целиком.
        assertEquals("https://example.org/sign/in?code=abc123xyz", t.linkAt(0, 7))
        assertEquals("https://example.org/sign/in?code=abc123xyz", t.linkAt(2, 3))
        assertEquals(null, t.linkAt(0, 1))
        // Правая половина широкого знака прямо перед адресом — не ссылка.
        val w = TerminalEmulator(40, 2)
        w.feed("中https://example.org/x")
        assertEquals(null, w.linkAt(0, 1))
        assertEquals("https://example.org/x", w.linkAt(0, 2))
        val x = TerminalEmulator(40, 2)
        x.feed("intent://evil#Intent;end file:///etc/passwd")
        assertEquals(null, x.linkAt(0, 3))
        assertEquals(null, x.linkAt(0, 30))
    }

    @Test
    fun historyCountersFollowScrolling() {
        val t = TerminalEmulator(10, 2, scrollbackMax = 3)
        repeat(6) { t.feed("$it\r\n") }
        assertEquals(5, t.historyAdded)
        assertEquals(2, t.historyDropped)
        t.feed("\u001b[3J")
        assertEquals(5, t.historyDropped)
    }

    @Test
    fun supplementaryCharactersAreNotTruncatedToAscii() {
        val t = term()
        t.feed("hello\u001b" + String(Character.toChars(0x10063)))
        assertTrue(t.screenText().startsWith("hello"))
        val g = term()
        g.feed("\u001b(0" + String(Character.toChars(0x2006A)))
        assertEquals(0x2006A, g.screenRow(0).codePoint(0))
    }

    @Test
    fun garbageNeverBreaksTheEmulator() {
        val t = TerminalEmulator(30, 10, scrollbackMax = 50, host = Recorder())
        val rnd = java.util.Random(57)
        val alphabet = "\u001b[]();:?0123456789mHJKABCDhlrPLM@\r\n\t\u0007 abc中́".toCharArray()
        repeat(200) {
            val chunk = ByteArray(1_000)
            rnd.nextBytes(chunk)
            t.feed(chunk)
            val s = buildString { repeat(500) { append(alphabet[rnd.nextInt(alphabet.size)]) } }
            t.feed(s)
            if (it % 50 == 0) t.resize(5 + rnd.nextInt(60), 2 + rnd.nextInt(20))
            assertTrue(t.cursorX in 0 until t.cols && t.cursorY in 0 until t.rows)
        }
        // Бесконечные параметры не растят память и не роняют разбор.
        t.feed("\u001b[" + "1:".repeat(100_000) + "m\u001b[" + ";".repeat(100_000) + "H")
        t.feed("\u001bc")
        t.feed("ok")
        assertEquals("ok", t.screenRow(0).text())
    }
}
