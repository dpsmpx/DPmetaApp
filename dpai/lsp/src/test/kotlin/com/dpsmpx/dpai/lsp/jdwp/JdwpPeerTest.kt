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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    private class FakeVm(handshake: String = "JDWP-Handshake", private val error: Int = ERROR, private val answer: (set: Int, cmd: Int, data: ByteArray) -> ByteArray?) {
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
                            out.writeInt(11 + (body?.size ?: 0)); out.writeInt(id); out.writeByte(0x80); out.writeShort(if (body == null) error else 0)
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

    /** Временный CLASS_NOT_PREPARED не должен навсегда скрыть точку после CLASS_PREPARE. */
    @ParameterizedTest
    @ValueSource(strings = ["source", "methods", "lines"])
    fun metadataRefusalIsRetriedWhenClassIsPrepared(part: String) {
        val ready = AtomicBoolean(false)
        val ids = AtomicInteger()
        val sizes = JdwpSizes()
        fun out() = JdwpOut(sizes)
        val vm = FakeVm(error = 22) { set, cmd, _ ->
            when (set to cmd) {
                1 to 7 -> out().int(8).int(8).int(8).int(8).int(8).bytes()
                1 to 1 -> out().string("двойник").int(1).int(8).string("21").string("тестовая ВМ").bytes()
                // Класс уже известен, но до события CLASS_PREPARE ещё не подготовлен.
                1 to 3 -> out().int(1).byte(1).typeId(11).string("Lapp/Main;").int(1).bytes()
                2 to 7 -> if (part == "source" && !ready.get()) null else out().string("Main.java").bytes()
                2 to 5 -> if (part == "methods" && !ready.get()) null else
                    out().int(1).methodId(21).string("main").string("()V").int(8).bytes()
                6 to 1 -> if (part == "lines" && !ready.get()) null else out().long(0).long(5).int(1).long(0).int(7).bytes()
                15 to 1 -> out().int(ids.incrementAndGet()).bytes()
                else -> ByteArray(0)
            }
        }
        val sources = object : JvmSources {
            override fun packageOf(path: String) = "app"
            override fun find(pkg: String, file: String) = "app/$file"
        }
        val verified = CountDownLatch(1)
        val session = JdwpSession(vm.clientIn, vm.clientOut, sources, { view ->
            if (view.breakpoints["app/Main.java"].orEmpty().any { it.verified }) verified.countDown()
        })
        try {
            assertIs<Outcome.Success<Unit>>(session.attach(mapOf("app/Main.java" to listOf(7))))
            assertFalse(session.view.breakpoints.getValue("app/Main.java").single().verified)
            ready.set(true)
            vm.event(out().byte(1).int(1).byte(8).int(1).objectId(7).byte(1).typeId(11).string("Lapp/Main;").int(3).bytes())
            assertTrue(verified.await(5, TimeUnit.SECONDS), "$part: ${session.view}")
            assertEquals(listOf(7), session.view.breakpoints.getValue("app/Main.java").filter { it.verified }.map { it.line })
        } finally {
            session.stop()
        }
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
