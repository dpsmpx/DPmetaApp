package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.linux.NativeChild
import com.dpsmpx.dpai.execution.linux.NativeProcesses
import com.dpsmpx.dpai.execution.linux.WindowSize
import com.dpsmpx.dpai.execution.process.LocalStreamHost
import com.dpsmpx.dpai.execution.process.NativeStreamHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StreamProcessTest {
    @Test
    fun localProcessTalksBothWaysWhileErrorsAreDiscarded() {
        val p = (LocalStreamHost().open(ProcessSpec(TestChild.command("echo"))) as Outcome.Success).value
        try {
            // Два обмена подряд: вход не закрыт после первой записи, мегабайт в stderr процесс не остановил.
            for (round in listOf("первый\n", "второй ☃\n")) {
                val bytes = round.toByteArray()
                p.stdin.write(bytes)
                p.stdin.flush()
                assertContentEquals(bytes, p.stdout.readNBytes(bytes.size))
            }
            assertTrue(p.isAlive)
        } finally {
            val report = p.stop(2.seconds)
            assertTrue(report.clean)
        }
        assertFalse(p.isAlive)
    }

    @Test
    fun missingProgramIsStartFailure() {
        val r = LocalStreamHost().open(ProcessSpec(listOf("/нет/такой/программы")))
        assertEquals(DiagCode.PROCESS_START_FAILED, (r as Outcome.Failure).diagnostics.first().code)
    }

    @Test
    fun nativeHostWrapsCommandAndExposesChildStreams() {
        val spawned = mutableListOf<List<String>>()
        val exit = CountDownLatch(1)
        val input = ByteArrayOutputStream()
        val native = object : NativeProcesses {
            override fun spawn(argv: List<String>, environment: Map<String, String>, workDir: String?, pty: WindowSize?): Outcome<NativeChild> {
                spawned += argv
                assertEquals(mapOf("HOME" to "/root"), environment)
                return Outcome.success(
                    object : NativeChild {
                        override val pid = 4242
                        override val stdin: OutputStream = input
                        override val stdout: InputStream = ByteArrayInputStream("ответ".toByteArray())
                        override val stderr: InputStream = ByteArrayInputStream(ByteArray(100_000))

                        override fun waitFor(): Int {
                            exit.await()
                            return 0
                        }

                        override fun resize(size: WindowSize) = Unit
                    },
                )
            }

            override fun kill(pid: Int, signal: Int): Boolean = false
        }
        val host = NativeStreamHost(native, wrap = { it.copy(command = listOf("proot", "-r", "/rootfs") + it.command) })
        val p = (host.open(ProcessSpec(listOf("clangd"), environment = mapOf("HOME" to "/root"))) as Outcome.Success).value
        assertEquals(listOf(listOf("proot", "-r", "/rootfs", "clangd")), spawned)
        p.stdin.write("запрос".toByteArray())
        assertEquals("запрос", input.toString(Charsets.UTF_8))
        assertEquals("ответ", String(p.stdout.readAllBytes(), Charsets.UTF_8))
        assertTrue(p.isAlive)
        exit.countDown()
    }
}
