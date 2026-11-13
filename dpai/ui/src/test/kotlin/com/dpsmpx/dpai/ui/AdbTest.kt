package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

/**
 * adb к самому телефону (D-160, D-161): проверка ввода, аргументы — значениями, код —
 * не в argv; сценарий отладки APK — настоящим `sh` с adb-двойником.
 */
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

    /** adb-двойник: отвечает на команды сценария и пишет их в журнал [log]. */
    private fun fakeAdb(dir: File, debuggable: Boolean): File {
        val log = File(dir, "adb.log")
        File(dir, "adb").apply {
            writeText(
                """
                #!/bin/sh
                echo "${'$'}*" >> "${log.path}"
                case "${'$'}*" in
                  "connect 127.0.0.1:40001") echo "connected to 127.0.0.1:40001" ;;
                  *"dumpsys package com.example.app") ${if (debuggable) "echo '    flags=[ DEBUGGABLE HAS_CODE ALLOW_BACKUP ]'" else "echo '    flags=[ HAS_CODE ALLOW_BACKUP ]'"} ;;
                  *"monkey -p com.example.app"*) echo "Events injected: 1" ;;
                  *"pidof com.example.app") printf '4242\r\n' ;;
                  *" jdwp") echo 1000; echo 4242 ;;
                  *"forward tcp:0 jdwp:4242") printf '45678\r\n' ;;
                esac
                exit 0
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        return log
    }

    @Test
    fun debugApkScriptForwardsJdwpAndCleansUp(@TempDir dir: File) {
        val log = fakeAdb(dir, debuggable = true)
        val env = mapOf("PATH" to dir.path + File.pathSeparator + System.getenv("PATH").orEmpty())
        val argv = Adb.debugApk(40001, "com.example.app")
        assertEquals(listOf("40001", "com.example.app"), argv.takeLast(2))
        assertFalse("com.example.app" in argv[2], "пакет — аргументом, не текстом сценария")
        val r = ok(LocalProcessHost().run(ProcessSpec(argv, environment = env, stdin = ""), 60.seconds))
        assertTrue(r.ok, r.toString())
        assertEquals(Adb.DebugLine.Ready(45678, 4242), r.stdout.firstNotNullOfOrNull(Adb::debugLine))
        val calls = log.readLines()
        val order = listOf("shell dumpsys package", "shell am force-stop", "shell am set-debug-app -w com.example.app", "shell monkey", "shell pidof", "jdwp", "forward tcp:0 jdwp:4242", "forward --remove tcp:45678", "shell am clear-debug-app")
        val at = order.map { want -> calls.indexOfFirst { want in it }.also { assertTrue(it >= 0, "нет вызова «$want»: $calls") } }
        assertEquals(at.sorted(), at, "порядок вызовов: $calls")
    }

    @Test
    fun debugApkRefusesNonDebuggableAppAndParsesLines(@TempDir dir: File) {
        fakeAdb(dir, debuggable = false)
        val env = mapOf("PATH" to dir.path + File.pathSeparator + System.getenv("PATH").orEmpty())
        val r = ok(LocalProcessHost().run(ProcessSpec(Adb.debugApk(40001, "com.example.app"), environment = env, stdin = ""), 60.seconds))
        assertFalse(r.ok)
        val line = r.stdout.firstNotNullOfOrNull(Adb::debugLine)
        assertTrue(line is Adb.DebugLine.Error && "без отладки" in line.message, r.toString())
        assertNull(Adb.debugLine("connected to 127.0.0.1:40001"))
        assertNull(Adb.debugLine("DPAI-JDWP 0 12"))
        assertNull(Adb.debugLine("DPAI-JDWP 45678 x"))
    }
}
