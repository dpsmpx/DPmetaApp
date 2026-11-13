package com.dpsmpx.dpai.lsp.jdwp

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Связь JDWP на ВМ-двойнике в памяти (D-161): рукопожатие, ответ и отказ с кодом
 * ошибки словами, событие ВМ слушателю, обрыв и пакет не по протоколу закрывают
 * связь; разбор сигнатур для экрана.
 */
class JdwpPeerTest {
    /** ВМ-двойник: [answer] — данные ответа на команду (`null` — ошибка [ERROR]). */
    private class FakeVm(handshake: String = "JDWP-Handshake", private val answer: (set: Int, cmd: Int, data: ByteArray) -> ByteArray?) {
        private val toVm = PipedOutputStream()
        private val vmIn = DataInputStream(PipedInputStream(toVm, 1 shl 16))
        private val fromVm = PipedOutputStream()
        val clientIn = PipedInputStream(fromVm, 1 shl 16)
        val clientOut: java.io.OutputStream = toVm
        val out = DataOutputStream(fromVm)
        val commands = CopyOnWriteArrayList<Pair<Int, Int>>()

        init {
            Thread({
                runCatching {
                    val hello = ByteArray(14)
                    vmIn.readFully(hello)
                    synchronized(out) { out.write(handshake.toByteArray()); out.flush() }
                    while (true) {
                        val length = vmIn.readInt()
                        val id = vmIn.readInt()
                        vmIn.readUnsignedByte()
                        val set = vmIn.readUnsignedByte()
                        val cmd = vmIn.readUnsignedByte()
                        val data = ByteArray(length - 11)
                        vmIn.readFully(data)
                        commands += set to cmd
                        val body = answer(set, cmd, data)
                        synchronized(out) {
                            out.writeInt(11 + (body?.size ?: 0)); out.writeInt(id); out.writeByte(0x80); out.writeShort(if (body == null) ERROR else 0)
                            body?.let { out.write(it) }
                            out.flush()
                        }
                    }
                }
            }, "fake-jdwp").apply { isDaemon = true; start() }
        }

        fun event(data: ByteArray) = synchronized(out) {
            out.writeInt(11 + data.size); out.writeInt(7); out.writeByte(0); out.writeByte(64); out.writeByte(100); out.write(data); out.flush()
        }
    }

    private class Listener : JdwpPeer.Handler {
        val events = CopyOnWriteArrayList<Triple<Int, Int, Int>>()
        val closed = CountDownLatch(1)

        @Volatile
        var reason: Diagnostic? = null
        val got = CountDownLatch(1)

        override fun command(set: Int, cmd: Int, data: ByteArray) {
            events += Triple(set, cmd, data.size)
            got.countDown()
        }

        override fun closed(reason: Diagnostic) {
            this.reason = reason
            closed.countDown()
        }
    }

    @Test
    fun handshakeReplyRefusalAndEvent() {
        val vm = FakeVm { set, cmd, _ -> if (set == 1 && cmd == 1) byteArrayOf(0, 0, 0, 42) else null }
        val l = Listener()
        val peer = JdwpPeer(vm.clientIn, vm.clientOut, l)
        assertIs<Outcome.Success<Unit>>(peer.start())
        val ok = assertIs<Outcome.Success<ByteArray>>(peer.request(1, 1, ByteArray(0), 5.seconds))
        assertEquals(42, JdwpIn(ok.value, JdwpSizes()).int())
        val refused = assertIs<Outcome.Failure>(peer.request(2, 7, ByteArray(8), 5.seconds))
        assertEquals(DiagCode.JDWP_REQUEST_FAILED, refused.diagnostics.first().code)
        assertTrue("нет сведений" in refused.diagnostics.first().message, refused.toString())
        vm.event(byteArrayOf(2, 0, 0, 0, 0))
        assertTrue(l.got.await(5, TimeUnit.SECONDS))
        assertEquals(Triple(64, 100, 5), l.events.single())
        assertEquals(listOf(1 to 1, 2 to 7), vm.commands.toList())
        peer.close()
        assertTrue(l.closed.await(5, TimeUnit.SECONDS))
        assertFalse(peer.isOpen)
        assertEquals(DiagCode.JDWP_CONNECTION_LOST, assertIs<Outcome.Failure>(peer.request(1, 1, ByteArray(0), 1.seconds)).diagnostics.first().code)
    }

    @Test
    fun wrongHandshakeGarbageAndEofCloseTheConnection() {
        val wrong = Listener()
        val w = FakeVm(handshake = "NOT-A-JDWP-VM!") { _, _, _ -> ByteArray(0) }
        assertIs<Outcome.Failure>(JdwpPeer(w.clientIn, w.clientOut, wrong).start())
        assertTrue(wrong.closed.await(5, TimeUnit.SECONDS))

        val garbage = Listener()
        val g = FakeVm { _, _, _ -> ByteArray(0) }
        val peer = JdwpPeer(g.clientIn, g.clientOut, garbage)
        assertIs<Outcome.Success<Unit>>(peer.start())
        synchronized(g.out) { g.out.writeInt(3); g.out.flush() }
        assertTrue(garbage.closed.await(5, TimeUnit.SECONDS))
        assertEquals(DiagCode.JDWP_REPLY_INVALID, garbage.reason?.code)

        val eof = Listener()
        val e = FakeVm { _, _, _ -> ByteArray(0) }
        val p = JdwpPeer(e.clientIn, e.clientOut, eof)
        assertIs<Outcome.Success<Unit>>(p.start())
        e.out.close()
        assertTrue(eof.closed.await(5, TimeUnit.SECONDS))
        assertEquals(DiagCode.JDWP_CONNECTION_LOST, eof.reason?.code)
    }

    @Test
    fun signaturesForTheScreen() {
        assertTrue(JdwpSession.inPackage("Lcom/a/B\$C;", "com.a"))
        assertFalse(JdwpSession.inPackage("Lcom/a/b/C;", "com.a"))
        assertTrue(JdwpSession.inPackage("LMain;", ""))
        assertEquals("B.C", JdwpSession.className("Lcom/a/B\$C;"))
        assertEquals("String[]", JdwpSession.typeName("[Ljava/lang/String;"))
        assertEquals("int", JdwpSession.typeName("I"))
        assertTrue(JdwpSession.system("Lkotlin/jvm/internal/Intrinsics;"))
        assertTrue(JdwpSession.system("Landroid/os/Looper;"))
        assertFalse(JdwpSession.system("Lcom/dpsmpx/app/MainActivity;"))
        // Пакет, лишь начинающийся с системного имени, — не системный.
        assertFalse(JdwpSession.system("Ljavafx2/Thing;"))
    }

    private companion object {
        const val ERROR = 101
    }
}
