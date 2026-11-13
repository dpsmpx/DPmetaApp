package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** adb к самому телефону (D-160): проверка ввода, аргументы — значениями, код — не в argv. */
class AdbTest {
    private fun <T> ok(r: Outcome<T>): T = assertIs<Outcome.Success<T>>(r, r.toString()).value

    @Test
    fun inputIsValidated() {
        assertEquals(37123, ok(Adb.port(" 37123 ")))
        for (bad in listOf("0", "70000", "x", "")) assertIs<Outcome.Failure>(Adb.port(bad), bad)
        assertEquals("123456", ok(Adb.code("123 456")))
        for (bad in listOf("12345", "1234567", "abcdef")) assertIs<Outcome.Failure>(Adb.code(bad), bad)
        assertEquals("com.example.app", ok(Adb.packageName("com.example.app")))
        for (bad in listOf("app", "com.example;rm", "1com.x", "com..x", "com.example.app \$(id)")) assertIs<Outcome.Failure>(Adb.packageName(bad), bad)
    }

    @Test
    fun commandsCarryValuesAsArgumentsAndCodeOnlyInStdin() {
        assertEquals(listOf("adb", "pair", "127.0.0.1:37123"), Adb.pair(37123))
        assertEquals("123456\n", Adb.pairInput("123456"))
        val log = Adb.logcat(40001, Logcat.Level.WARN, 4242, 300)
        assertEquals(listOf("40001", "4242", "300", "W"), log.takeLast(4))
        assertFalse(log[2].contains("40001") || log[2].contains("4242"), "значения — не в тексте сценария")
        assertEquals("", Adb.logcat(40001, Logcat.Level.VERBOSE, null).takeLast(4)[1], "без pid — весь журнал")
        assertEquals(listOf("40001", "com.example.app"), Adb.launch(40001, "com.example.app").takeLast(2))
        assertTrue("monkey -p" in Adb.launch(40001, "com.example.app")[2])
        assertEquals(listOf("40001", "com.example.app"), Adb.pidof(40001, "com.example.app").takeLast(2))
        assertIs<Outcome.Success<Unit>>(Adb.pairResult(listOf("Enter pairing code: Successfully paired to 127.0.0.1:37123 [guid=adb-x]"), 0))
        assertTrue((Adb.pairResult(listOf("Failed: Unable to start pairing client."), 1) as Outcome.Failure).diagnostics.first().message.contains("Unable to start"))
    }
}
