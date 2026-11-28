package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.OutputLines
import com.dpsmpx.dpai.execution.process.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputLinesTest {
    @Test fun wrapperPercentArrivesWithoutDelimiterAndJsonStaysOneLine() {
        val input = PipedInputStream()
        val output = PipedOutputStream(input)
        val seen = Collections.synchronizedList(mutableListOf<OutputLine>())
        val arrived = CountDownLatch(1)
        val reader = OutputLines.reader(input, OutputStream.STDOUT) { seen += it; arrived.countDown() }
        val json = "{\"text\":\"....20%\"}"
        try {
            output.write(".........10%".toByteArray())
            output.flush()
            assertTrue(arrived.await(3, TimeUnit.SECONDS), "Gradle Wrapper не завершил строку")
            assertTrue(reader.isAlive)
            output.write(("\n" + json + "\n").toByteArray())
        } finally {
            output.close()
            reader.join(3000)
        }
        assertEquals(listOf(".........10%", json), seen.map { it.text })
    }

    @Test fun carriageReturnProgressArrivesBeforeEofAndCrlfIsNotDuplicated() {
        val input = PipedInputStream()
        val output = PipedOutputStream(input)
        val seen = Collections.synchronizedList(mutableListOf<OutputLine>())
        val arrived = CountDownLatch(1)
        val reader = OutputLines.reader(input, OutputStream.STDERR) { seen += it; arrived.countDown() }
        try {
            output.write("### 25%\r".toByteArray())
            output.flush()
            assertTrue(arrived.await(3, TimeUnit.SECONDS), "CR-прогресс не должен ждать конца процесса")
            assertTrue(reader.isAlive, "поток ещё открыт")
            output.write("\nготово\n\n".toByteArray())
        } finally {
            output.close()
            reader.join(3000)
        }
        assertFalse(reader.isAlive)
        assertEquals(listOf("### 25%", "готово", ""), seen.map { it.text })
    }

    @Test fun limitAndTruncationResetAtEveryProgressUpdate() {
        val seen = mutableListOf<OutputLine>()
        val input = ("x".repeat(LocalProcessHost.MAX_LINE + 100) + "\r20%\r\n100%").byteInputStream()
        OutputLines.read(input, OutputStream.STDOUT, seen::add)
        assertEquals(3, seen.size)
        assertEquals(LocalProcessHost.MAX_LINE, seen[0].text.length)
        assertTrue(seen[0].truncated)
        assertEquals(listOf("20%", "100%"), seen.drop(1).map { it.text })
        assertTrue(seen.drop(1).none { it.truncated })
    }
}
