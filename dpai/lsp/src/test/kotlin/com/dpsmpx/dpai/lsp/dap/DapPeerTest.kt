package com.dpsmpx.dpai.lsp.dap

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.lsp.LspReader
import com.dpsmpx.dpai.lsp.LspWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Клиент DAP на адаптере-двойнике в памяти (D-159): порядок запуска как у gdb
 * (`initialized` сразу после `initialize`, точки — до `launch`) и как у debugpy
 * (`initialized` после `launch`; `launch` отвечает после `configurationDone`), событие
 * остановки — стек и переменные, шаг, вывод, выход, отказ запроса, обратный запрос
 * адаптера, обрыв и мусор в потоке.
 */
class DapPeerTest {
    /** Адаптер-двойник: [reply] — ответ на запрос (`null` — не отвечать), всё полученное — в [received]. */
    private class FakeAdapter(private val reply: FakeAdapter.(command: String, args: JsonObject?, seq: Long) -> Unit) {
        private val toAdapter = PipedOutputStream()
        private val adapterIn = PipedInputStream(toAdapter, 1 shl 20)
        private val fromAdapter = PipedOutputStream()
        val clientIn = PipedInputStream(fromAdapter, 1 shl 20)
        val clientOut: java.io.OutputStream = toAdapter
        val received = CopyOnWriteArrayList<String>()
        val responses = CopyOnWriteArrayList<JsonObject>()
        private val writer = LspWriter(fromAdapter)
        private var seq = 1000L
        private val pendingLaunch = CopyOnWriteArrayList<Long>()

        init {
            Thread({
                val reader = LspReader(adapterIn)
                while (true) {
                    val m = (reader.read() as? Outcome.Success)?.value as? JsonObject ?: break
                    if ((m["type"] as? JsonString)?.value == "response") { responses += m; continue }
                    val command = (m["command"] as? JsonString)?.value ?: break
                    received += command
                    reply(command, m["arguments"] as? JsonObject, (m["seq"] as JsonNumber).toLongExact()!!)
                }
            }, "fake-dap").apply { isDaemon = true; start() }
        }

        fun respond(seqOf: Long, command: String, body: JsonValue? = null, success: Boolean = true, message: String? = null) {
            val m = linkedMapOf<String, JsonValue>("seq" to json(++seq), "type" to json("response"), "request_seq" to json(seqOf), "success" to JsonBool(success), "command" to json(command))
            if (body != null) m["body"] = body
            if (message != null) m["message"] = json(message)
            writer.write(JsonObject(m))
        }

        fun event(name: String, body: JsonObject? = null) {
            val m = linkedMapOf<String, JsonValue>("seq" to json(++seq), "type" to json("event"), "event" to json(name))
            if (body != null) m["body"] = body
            writer.write(JsonObject(m))
        }

        fun raw(message: JsonValue) = writer.write(message)

        fun holdLaunch(seqOf: Long) { pendingLaunch += seqOf }

        fun releaseLaunch() { pendingLaunch.forEach { respond(it, "launch") }; pendingLaunch.clear() }

        fun die() = fromAdapter.close()
    }

    /**
     * [gdb] — как gdb: `initialized` сразу после ответа `initialize`; иначе — как debugpy:
     * `initialized` после `launch`. Ответ `launch` — после `configurationDone`, затем остановка на точке.
     */
    private fun adapter(gdb: Boolean): FakeAdapter = FakeAdapter { command, args, seq ->
        when (command) {
            "initialize" -> { respond(seq, command, JsonObject.of("supportsConfigurationDoneRequest" to JsonBool(true))); if (gdb) event("initialized") }
            "launch" -> { holdLaunch(seq); if (!gdb) event("initialized") }
            "setBreakpoints" -> {
                // gdb до загрузки программы — точка отложена (verified=false), подтверждение — событием.
                val lines = (args!!["breakpoints"] as JsonArray).items.map { ((it as JsonObject)["line"] as JsonNumber).toLongExact()!! }
                respond(seq, command, JsonObject.of("breakpoints" to JsonArray(lines.mapIndexed { i, l -> JsonObject.of("id" to json(i + 1), "verified" to JsonBool(!gdb), "line" to json(l)) })))
            }
            "configurationDone" -> {
                respond(seq, command)
                if (gdb) event("breakpoint", JsonObject.of("reason" to json("changed"), "breakpoint" to JsonObject.of("id" to json(1), "verified" to JsonBool(true), "line" to json(2))))
                releaseLaunch()
                event("output", JsonObject.of("category" to json("stdout"), "output" to json("начало\nстрока 2\n")))
                event("output", JsonObject.of("category" to json("telemetry"), "output" to json("не показывать")))
                event("stopped", JsonObject.of("reason" to json("breakpoint"), "threadId" to json(1)))
            }
            "stackTrace" -> respond(seq, command, JsonObject.of("stackFrames" to JsonArray(listOf(
                JsonObject.of("id" to json(7), "name" to json("sq"), "line" to json(2), "source" to JsonObject.of("path" to json("/tmp/t.c"))),
                JsonObject.of("id" to json(8), "name" to json("main"), "line" to json(3), "source" to JsonObject.of("path" to json("/tmp/t.c"))),
            ))))
            "scopes" -> respond(seq, command, JsonObject.of("scopes" to JsonArray(listOf(
                JsonObject.of("name" to json("Locals"), "variablesReference" to json(if ((args!!["frameId"] as JsonNumber).toLongExact() == 8L) 21 else 20), "expensive" to JsonBool(false)),
            ))))
            "variables" -> respond(seq, command, JsonObject.of("variables" to JsonArray(listOf(
                if ((args!!["variablesReference"] as JsonNumber).toLongExact() == 20L) JsonObject.of("name" to json("x"), "value" to json("7"), "type" to json("int"), "variablesReference" to json(0))
                else JsonObject.of("name" to json("a"), "value" to json("7"), "variablesReference" to json(0)),
            ))))
            "evaluate" -> if ((args!!["expression"] as JsonString).value == "x*x") respond(seq, command, JsonObject.of("result" to json("49")))
                else respond(seq, command, success = false, body = JsonObject.of("error" to JsonObject.of("format" to json("No symbol \"y\" in current context."))))
            "next" -> { respond(seq, command); event("stopped", JsonObject.of("reason" to json("step"), "threadId" to json(1))) }
            "continue" -> { respond(seq, command); event("exited", JsonObject.of("exitCode" to json(3))); event("terminated") }
            "disconnect" -> respond(seq, command)
        }
    }

    private fun <T> ok(r: Outcome<T>): T = assertIs<Outcome.Success<T>>(r, r.toString()).value

    /** Ждать вида сеанса: до 10 с шагами по 10 мс — без системных часов (D-07). */
    private fun waitFor(session: DebugSession, what: String, test: (DebugView) -> Boolean): DebugView {
        val tick = java.util.concurrent.Semaphore(0)
        repeat(1000) {
            if (test(session.view)) return session.view
            tick.tryAcquire(10, TimeUnit.MILLISECONDS)
        }
        error("не дождались: $what; ${session.view}")
    }

    @Test
    fun debugpyOrderLaunchThenBreakpoints() {
        val adapter = adapter(gdb = false)
        val session = DebugSession(adapter.clientIn, adapter.clientOut, {})
        ok(session.start(DebugLaunches.debugpy("/usr/bin/python3", "/tmp/t.py", emptyList(), "/tmp"), mapOf("/tmp/t.py" to listOf(5))))
        assertEquals(listOf("initialize", "launch", "setBreakpoints", "configurationDone"), adapter.received.take(4))
        waitFor(session, "остановка") { it.status is DebugStatus.Stopped }
        session.stop()
    }

    @Test
    fun gdbOrderStopStackVariablesStepAndExit() {
        val adapter = adapter(gdb = true)
        val views = CopyOnWriteArrayList<DebugView>()
        val closed = CountDownLatch(1)
        val session = DebugSession(adapter.clientIn, adapter.clientOut, { views += it }, { closed.countDown() })
        ok(session.start(DebugLaunches.gdb("/tmp/t", listOf("--level", "1"), "/tmp"), mapOf("/tmp/t.c" to listOf(2, 2))))
        // gdb 15 запускает программу уже по launch: точки — до него (`LINUX_IN_APP.md`, §12).
        assertEquals(listOf("initialize", "setBreakpoints", "launch", "configurationDone"), adapter.received.take(4))

        val stopped = waitFor(session, "остановка со стеком") { it.status is DebugStatus.Stopped && it.variables.isNotEmpty() }
        assertEquals(DebugStatus.Stopped("breakpoint", 1, null), stopped.status)
        assertEquals(listOf(BreakpointState(2, true, null, 1)), session.view.breakpoints["/tmp/t.c"], "отложенная точка подтверждена событием")
        assertEquals(listOf("sq", "main"), stopped.frames.map { it.name })
        assertEquals(StackFrame(7, "sq", "/tmp/t.c", 2), stopped.location)
        assertEquals(listOf(DebugVariable("x", "7", "int", 0)), stopped.variables)
        assertEquals(listOf("начало", "строка 2"), stopped.output, "телеметрия не показывается")

        assertEquals("49", ok(session.evaluate("x*x")))
        val bad = assertIs<Outcome.Failure>(session.evaluate("y"))
        assertEquals(DiagCode.DAP_REQUEST_FAILED, bad.diagnostics.first().code)
        assertEquals("No symbol \"y\" in current context.", bad.diagnostics.first().message)
        assertEquals(listOf(DebugVariable("a", "7", null, 0)), ok(session.selectFrame(8)))
        assertEquals(8L, session.view.frame)

        ok(session.next())
        waitFor(session, "остановка после шага") { (it.status as? DebugStatus.Stopped)?.reason == "step" && it.frames.isNotEmpty() }
        ok(session.resume())
        val done = waitFor(session, "выход") { it.status is DebugStatus.Exited }
        assertEquals(DebugStatus.Exited(3), done.status)
        assertTrue(closed.await(5, TimeUnit.SECONDS), "после terminated связь закрыта")
        assertIs<Outcome.Failure>(session.next(), "шаг без остановки — отказ")
    }

    @Test
    fun launchRefusalIsTheReasonAndReverseRequestsAreRefused() {
        val adapter = FakeAdapter { command, _, seq ->
            when (command) {
                "initialize" -> {
                    // Обратный запрос адаптера: у редактора нет терминала — отказ, сеанс жив.
                    raw(JsonObject.of("seq" to json(1), "type" to json("request"), "command" to json("runInTerminal"), "arguments" to JsonObject.of()))
                    respond(seq, command, JsonObject.of())
                    event("initialized")
                }
                "launch" -> respond(seq, command, success = false, message = "Program \"/tmp/нет\" not found")
                "configurationDone" -> respond(seq, command, success = false, message = "not launched")
            }
        }
        val session = DebugSession(adapter.clientIn, adapter.clientOut, {})
        val r = assertIs<Outcome.Failure>(session.start(DebugLaunches.gdb("/tmp/нет", emptyList(), "/tmp"), emptyMap(), 5.seconds))
        assertEquals(DiagCode.DAP_REQUEST_FAILED, r.diagnostics.first().code)
        assertEquals("Program \"/tmp/нет\" not found", r.diagnostics.first().message)
        assertEquals(DebugStatus.Failed("Program \"/tmp/нет\" not found"), session.view.status)
        val refused = adapter.responses.single()
        assertEquals(JsonBool(false), refused["success"])
        assertEquals(json(1), refused["request_seq"])
        session.stop()
    }

    @Test
    fun brokenStreamOrGarbageStopTheSession() {
        // Адаптер умер посреди запроса — ждущий получает отказ, а не висит.
        val dying = FakeAdapter { command, _, _ -> if (command == "initialize") die() }
        val closed = CountDownLatch(1)
        val session = DebugSession(dying.clientIn, dying.clientOut, {}, { closed.countDown() })
        val r = assertIs<Outcome.Failure>(session.start(DebugLaunches.gdb("/tmp/t", emptyList(), "/tmp"), emptyMap(), 5.seconds))
        assertEquals(DiagCode.DAP_ADAPTER_STOPPED, r.diagnostics.first().code)
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertTrue(!session.isOpen)

        // Сообщение без type — протокол нарушен: сеанс остановлен с DAP_REPLY_INVALID.
        val garbage = FakeAdapter { command, _, _ -> if (command == "initialize") raw(JsonObject.of("seq" to json(1))) }
        val s2 = DebugSession(garbage.clientIn, garbage.clientOut, {})
        assertEquals(DiagCode.DAP_REPLY_INVALID, assertIs<Outcome.Failure>(s2.start(DebugLaunches.gdb("/tmp/t", emptyList(), "/tmp"), emptyMap(), 5.seconds)).diagnostics.first().code)

        // Адаптер молчит — запрос отказывает по сроку, сеанс жив.
        val silent = FakeAdapter { _, _, _ -> }
        val peer = DapPeer(silent.clientIn, silent.clientOut, object : DapPeer.Handler {
            override fun event(event: String, body: JsonObject?) = Unit
            override fun closed(reason: com.dpsmpx.dpai.core.diagnostics.Diagnostic) = Unit
        })
        peer.start()
        assertEquals(DiagCode.DAP_TIMEOUT, assertIs<Outcome.Failure>(peer.request("threads", null, 1.seconds)).diagnostics.first().code)
        assertTrue(peer.isOpen)
        peer.close()
        assertEquals(DiagCode.DAP_ADAPTER_STOPPED, assertIs<Outcome.Failure>(peer.request("threads", null, 1.seconds)).diagnostics.first().code)
    }

    @Test
    fun launchConfigurationsAreArgumentsNotShell() {
        val gdb = DebugLaunches.gdb("/root/p/build/game", listOf("--name", "a b; rm -rf /"), "/root/p")
        assertEquals(listOf("gdb", "-i=dap", "-q"), gdb.command)
        assertEquals(JsonArray(listOf(json("--name"), json("a b; rm -rf /"))), gdb.launch["args"])
        assertEquals(json("/root/p/build/game"), gdb.launch["program"])
        val py = DebugLaunches.debugpy("/root/.local/share/dpai/projects/k/python/bin/python", "/root/p/main.py", emptyList(), "/root/p")
        assertEquals(listOf("/root/.local/share/dpai/projects/k/python/bin/python", "-m", "debugpy.adapter"), py.command)
        assertEquals(json("internalConsole"), py.launch["console"])
        assertNull(py.launch["stopOnEntry"])
    }
}
