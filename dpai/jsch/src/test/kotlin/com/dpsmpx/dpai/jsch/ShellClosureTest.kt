package com.dpsmpx.dpai.jsch

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ShellClosureTest {
    @Test fun closingDoesNotWaitBehindBlockedWriteAndRejectsUnboundedQueuedInput() {
        val writeBegun = CountDownLatch(1); val release = CountDownLatch(1); val readEnd = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int { readEnd.await(); return -1 }
            override fun close() { readEnd.countDown() }
        }
        val output = object : OutputStream() {
            override fun write(b: Int) { writeBegun.countDown(); release.await() }
            override fun close() { release.countDown() }
        }
        val channel = ShellChannel(JSch().getSession("test", "127.0.0.1"), ChannelShell::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance(), { _, _ -> }, input, output, { _, _ -> }, {})
        try {
            channel.write(byteArrayOf(1))
            assertTrue(writeBegun.await(3, TimeUnit.SECONDS))
            assertFailsWith<java.io.IOException> { channel.write(ByteArray(1_048_577)) }
            assertFalse(channel.isOpen)
            assertTrue(channel.awaitClosed(3.seconds))
        } finally { channel.close(); release.countDown(); readEnd.countDown() }
    }
}
