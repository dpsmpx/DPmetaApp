package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Журнал Android (D-160): разбор threadtime, продолжения, фильтры, трассировки, предел буфера. */
class LogcatTest {
    @Test
    fun parsesThreadtimeAndContinuations() {
        val l = Logcat.parse("11-13 09:41:07.123  4242  4260 E AndroidRuntime: FATAL EXCEPTION: main")
        assertEquals(Logcat.Line("11-13 09:41:07.123", 4242, 4260, Logcat.Level.ERROR, "AndroidRuntime", "FATAL EXCEPTION: main"), l)
        val c = Logcat.parse("\tat com.example.app.MainActivity.onCreate(MainActivity.kt:42)", l)
        assertFalse(c.header)
        assertEquals(4242, c.pid)
        assertEquals(Logcat.Level.ERROR, c.level)
        val mark = Logcat.parse("--------- beginning of crash")
        assertFalse(mark.header)
        assertEquals("--------- beginning of crash", mark.text)
        assertEquals(Logcat.Level.WARN, Logcat.parse("11-13 09:41:07.123  1  2 W Tag with spaces : hello").level)
    }

    @Test
    fun filtersByLevelPidAndText() {
        val lines = listOf(
            "11-13 09:41:07.100  10  10 D A: debug",
            "11-13 09:41:07.200  10  10 W B: warn Network",
            "11-13 09:41:07.300  20  20 E C: error",
        ).let { Logcat.append(emptyList(), it) }
        assertEquals(listOf("warn Network", "error"), lines.filter(Logcat.Filter(Logcat.Level.WARN)::matches).map { it.message })
        assertEquals(listOf("debug", "warn Network"), lines.filter(Logcat.Filter(pid = 10)::matches).map { it.message })
        assertEquals(listOf("warn Network"), lines.filter(Logcat.Filter(query = "network")::matches).map { it.message })
        assertEquals(listOf("error"), lines.filter(Logcat.Filter(query = "c")::matches).filter { it.level == Logcat.Level.ERROR }.map { it.message })
    }

    @Test
    fun stackFramesPointToProjectFiles() {
        val f = Logcat.frame("\tat com.example.app.ui.MainActivity\$onCreate\$1.invoke(MainActivity.kt:42)")!!
        assertEquals(Logcat.Frame("com.example.app.ui.MainActivity", "MainActivity.kt", 42), f)
        assertEquals("com/example/app/ui/MainActivity.kt", f.pathSuffix)
        assertEquals(Logcat.Frame("Foo", "Foo.java", 7), Logcat.frame("at Foo.bar(Foo.java:7)"))
        assertNull(Logcat.frame("at android.os.Handler.dispatchMessage(Handler.java)"))
        assertNull(Logcat.frame("at java.lang.reflect.Method.invoke(Native Method)"))
    }

    @Test
    fun bufferKeepsTheTail() {
        val many = (1..Logcat.MAX_LINES + 10).map { "11-13 09:41:07.100  1  1 I T: $it" }
        val buffer = Logcat.append(emptyList(), many)
        assertEquals(Logcat.MAX_LINES, buffer.size)
        assertEquals("11", buffer.first().message)
        assertTrue(buffer.last().message == (Logcat.MAX_LINES + 10).toString())
    }
}
