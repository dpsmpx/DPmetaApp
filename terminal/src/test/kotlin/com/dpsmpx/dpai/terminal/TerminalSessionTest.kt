package com.dpsmpx.dpai.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Сессия терминала (D-57): ответы программе, события экрана, размер окна, ввод. */
class TerminalSessionTest {
    private class FakeTransport : TerminalTransport {
        val sent = StringBuilder()
        val sizes = ArrayList<Pair<Int, Int>>()
        var closed = false
        override fun write(bytes: ByteArray) {
            sent.append(String(bytes, Charsets.UTF_8))
        }
        override fun resize(cols: Int, rows: Int) {
            sizes += cols to rows
        }
        override fun close() {
            closed = true
        }
    }

    private class Events : TerminalListener {
        var changes = 0
        val titles = ArrayList<String>()
        var exit: Int? = -1
        override fun changed() {
            changes++
        }
        override fun title(title: String) {
            titles += title
        }
        override fun closed(exitCode: Int?) {
            exit = exitCode
        }
    }

    @Test
    fun outputRepliesAndEvents() {
        val ev = Events()
        val s = TerminalSession(20, 5, listener = ev)
        val t = FakeTransport()
        s.attach(t)
        assertEquals(listOf(20 to 5), t.sizes)
        s.output("hi\u001b[6n\u001b]2;заголовок\u0007".toByteArray())
        assertEquals("\u001b[1;3R", t.sent.toString())
        assertEquals(listOf("заголовок"), ev.titles)
        assertEquals(1, ev.changes)
        assertEquals("hi", s.read { it.screenText() })
    }

    @Test
    fun inputFollowsModes() {
        val s = TerminalSession(20, 5)
        val t = FakeTransport()
        s.attach(t)
        s.key(Key.UP)
        s.output("\u001b[?1h\u001b[?2004h".toByteArray())
        s.key(Key.UP)
        s.paste("a\nb")
        s.type("c", Mod.CTRL)
        s.type("ж")
        assertEquals("\u001b[A\u001bOA\u001b[200~a\rb\u001b[201~\u0003ж", t.sent.toString())
    }

    @Test
    fun resizeReachesTheProgramAndCloseStopsInput() {
        val ev = Events()
        val s = TerminalSession(20, 5, listener = ev)
        val t = FakeTransport()
        s.attach(t)
        s.resize(40, 10)
        assertEquals(40 to 10, t.sizes.last())
        assertEquals(40, s.read { it.cols })
        s.closed(0)
        assertEquals(0, ev.exit)
        assertFalse(s.isConnected)
        s.type("lost")
        assertFalse("lost" in t.sent)
        val again = FakeTransport()
        s.attach(again)
        assertTrue(s.isConnected)
        s.close()
        assertTrue(again.closed)
    }
}
