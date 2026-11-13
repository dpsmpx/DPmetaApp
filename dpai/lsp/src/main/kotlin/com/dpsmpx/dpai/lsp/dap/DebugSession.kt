package com.dpsmpx.dpai.lsp.dap

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.json
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Что запускать под отладчиком (D-159): [command] — процесс адаптера, [adapterId] —
 * для `initialize`, [launch] — аргументы `launch`, [workDir] — каталог адаптера.
 */
data class DebugLaunch(val name: String, val adapterId: String, val command: List<String>, val launch: JsonObject, val workDir: String)

/** Адаптеры отладки в Linux приложения (D-159): путь и аргументы — значениями, без оболочки. */
object DebugLaunches {
    /** gdb 14+ в режиме DAP: C и C++. */
    fun gdb(program: String, args: List<String>, cwd: String): DebugLaunch = DebugLaunch(
        "gdb", "gdb", listOf("gdb", "-i=dap", "-q"),
        JsonObject.of(
            "program" to json(program), "args" to JsonArray(args.map(::json)), "cwd" to json(cwd),
            "stopAtBeginningOfMainSubprogram" to JsonBool(false),
        ),
        cwd,
    )

    /** debugpy из окружения проекта: [python] — его интерпретатор, адаптер и программа идут им. */
    fun debugpy(python: String, program: String, args: List<String>, cwd: String): DebugLaunch = DebugLaunch(
        "debugpy", "debugpy", listOf(python, "-m", "debugpy.adapter"),
        JsonObject.of(
            "program" to json(program), "args" to JsonArray(args.map(::json)), "cwd" to json(cwd),
            "console" to json("internalConsole"), "justMyCode" to JsonBool(true), "python" to JsonArray(listOf(json(python))),
        ),
        cwd,
    )
}

/** Кадр стека: [path] — файл гостя или `null` (код без исходника), [line] — с единицы. */
data class StackFrame(val id: Long, val name: String, val path: String?, val line: Int)

/** Переменная: [ref] больше нуля — у неё есть поля ([DebugSession.children]). */
data class DebugVariable(val name: String, val value: String, val type: String?, val ref: Long)

/** Точка останова, как её принял отладчик: [line] — где встала (может сдвинуться к ближайшей строке кода). */
data class BreakpointState(val line: Int, val verified: Boolean, val message: String? = null)

/** Состояние сеанса для экрана. */
sealed interface DebugStatus {
    data object Starting : DebugStatus

    data object Running : DebugStatus

    /** Остановлен: [reason] — `breakpoint`, `step`, `pause`, `exception`…; [description] — слова отладчика. */
    data class Stopped(val reason: String, val threadId: Long, val description: String?) : DebugStatus

    /** Программа кончилась; [code] — код выхода, неизвестен — `null`. */
    data class Exited(val code: Int?) : DebugStatus

    data class Failed(val message: String) : DebugStatus
}

/** Снимок сеанса: состояние, стек и переменные выбранного кадра, вывод, точки по файлам. */
data class DebugView(
    val status: DebugStatus = DebugStatus.Starting,
    val frames: List<StackFrame> = emptyList(),
    val frame: Long? = null,
    val variables: List<DebugVariable> = emptyList(),
    val output: List<String> = emptyList(),
    val breakpoints: Map<String, List<BreakpointState>> = emptyMap(),
) {
    /** Где стоит программа: файл и строка верхнего кадра с исходником. */
    val location: StackFrame? get() = (status as? DebugStatus.Stopped)?.let { frames.firstOrNull { f -> f.id == frame } ?: frames.firstOrNull { it.path != null } }
}

/**
 * Сеанс отладки по DAP (D-159): запуск в порядке спецификации, события остановки
 * со стеком и переменными, шаги, выражения, остановка. Запросы после события
 * идут своим потоком: поток чтения [DapPeer] ответа ждать не может.
 *
 * Методы блокируют до ответа адаптера — звать не из потока экрана. [onView] —
 * из потоков сеанса; [onClosed] — один раз, когда связь с адаптером кончилась.
 */
class DebugSession(
    input: InputStream,
    output: OutputStream,
    private val onView: (DebugView) -> Unit,
    private val onClosed: (Diagnostic) -> Unit = {},
) {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "dpai-dap-session").apply { isDaemon = true } }
    private val initialized = CompletableFuture<Unit>()

    @Volatile
    var view: DebugView = DebugView()
        private set

    @Volatile
    private var exitCode: Int? = null

    private val peer = DapPeer(
        input, output,
        object : DapPeer.Handler {
            override fun event(event: String, body: JsonObject?) = onEvent(event, body)

            override fun closed(reason: Diagnostic) {
                initialized.complete(Unit)
                val s = view.status
                if (s !is DebugStatus.Exited && s !is DebugStatus.Failed) publish { it.copy(status = if (s is DebugStatus.Starting) DebugStatus.Failed(reason.message) else DebugStatus.Exited(exitCode)) }
                worker.shutdown()
                onClosed(reason)
            }
        },
    )

    val isOpen: Boolean get() = peer.isOpen

    /**
     * Запустить [launch] с точками [breakpoints] (путь гостя → строки с единицы):
     * `initialize`, `launch` без ожидания, после `initialized` — точки и
     * `configurationDone`, затем ответ `launch` (gdb и debugpy дают его только после
     * `configurationDone`, `docs/research/LINUX_IN_APP.md`, §12).
     */
    fun start(launch: DebugLaunch, breakpoints: Map<String, List<Int>>, timeout: Duration = START_TIMEOUT): Outcome<Unit> {
        peer.start()
        val init = JsonObject.of(
            "clientID" to json("dpmetaapp"), "clientName" to json("DPmetaApp"), "adapterID" to json(launch.adapterId),
            "linesStartAt1" to JsonBool(true), "columnsStartAt1" to JsonBool(true), "pathFormat" to json("path"),
            "supportsRunInTerminalRequest" to JsonBool(false), "locale" to json("ru"),
        )
        peer.request("initialize", init, REQUEST_TIMEOUT).let { if (it is Outcome.Failure) return fail(it) }
        val launched = peer.call("launch", launch.launch)
        try {
            initialized.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            return fail(Outcome.failure(DiagCode.DAP_TIMEOUT, "отладчик не сообщил о готовности за ${timeout.inWholeSeconds} с", launch.name))
        }
        // Отказ launch до готовности (нет программы) — его причина, а не «не готов».
        if (launched.isDone) (launched.await(REQUEST_TIMEOUT) as? Outcome.Failure)?.let { return fail(it) }
        if (!peer.isOpen) return fail(Outcome.failure(DiagCode.DAP_ADAPTER_STOPPED, "отладчик вышел при запуске", launch.name))
        for ((path, lines) in breakpoints) setBreakpoints(path, lines)
        peer.request("configurationDone", null, REQUEST_TIMEOUT).let { if (it is Outcome.Failure) return fail(it) }
        when (val r = launched.await(timeout)) {
            is Outcome.Failure -> return fail(r)
            is Outcome.Success -> Unit
        }
        publish { if (it.status is DebugStatus.Starting) it.copy(status = DebugStatus.Running) else it }
        return Outcome.success(Unit)
    }

    /** Точки файла [path] целиком (DAP заменяет набор файла): как их принял отладчик. */
    fun setBreakpoints(path: String, lines: List<Int>): Outcome<List<BreakpointState>> {
        val args = JsonObject.of(
            "source" to JsonObject.of("path" to json(path)),
            "breakpoints" to JsonArray(lines.distinct().sorted().map { JsonObject.of("line" to json(it)) }),
        )
        return when (val r = peer.request("setBreakpoints", args, REQUEST_TIMEOUT)) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                val got = ((r.value as? JsonObject)?.get("breakpoints") as? JsonArray)?.items.orEmpty().mapIndexedNotNull { i, b ->
                    val o = b as? JsonObject ?: return@mapIndexedNotNull null
                    BreakpointState(o.int("line") ?: lines.getOrNull(i) ?: 0, (o["verified"] as? JsonBool)?.value == true, o.text("message"))
                }
                publish { it.copy(breakpoints = it.breakpoints + (path to got)) }
                Outcome.success(got)
            }
        }
    }

    fun resume(): Outcome<Unit> = step("continue")

    fun next(): Outcome<Unit> = step("next")

    fun stepIn(): Outcome<Unit> = step("stepIn")

    fun stepOut(): Outcome<Unit> = step("stepOut")

    /** Остановить идущую программу; поток — первый из `threads`. */
    fun pause(): Outcome<Unit> {
        val thread = when (val t = peer.request("threads", null, REQUEST_TIMEOUT)) {
            is Outcome.Failure -> return t
            is Outcome.Success -> (((t.value as? JsonObject)?.get("threads") as? JsonArray)?.items?.firstOrNull() as? JsonObject)?.long("id") ?: 1L
        }
        return peer.request("pause", JsonObject.of("threadId" to json(thread)), REQUEST_TIMEOUT).map { }
    }

    /** Выбрать кадр стека [frameId]: его переменные верхнего уровня. */
    fun selectFrame(frameId: Long): Outcome<List<DebugVariable>> = when (val v = locals(frameId)) {
        is Outcome.Failure -> v
        is Outcome.Success -> {
            publish { it.copy(frame = frameId, variables = v.value) }
            v
        }
    }

    /** Поля переменной [ref]. */
    fun children(ref: Long): Outcome<List<DebugVariable>> = variables(ref)

    /** Значение выражения [expression] в выбранном кадре. */
    fun evaluate(expression: String): Outcome<String> {
        if (expression.isBlank() || expression.length > MAX_EXPRESSION) return Outcome.failure(DiagCode.VALUE_INVALID, "выражение — от 1 до $MAX_EXPRESSION знаков")
        val args = linkedMapOf<String, JsonValue>("expression" to json(expression), "context" to json("repl"))
        view.frame?.let { args["frameId"] = json(it) }
        return when (val r = peer.request("evaluate", JsonObject(args), REQUEST_TIMEOUT)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(((r.value as? JsonObject)?.text("result") ?: "").take(MAX_VALUE))
        }
    }

    /** Остановить отладку: программа завершается (`terminateDebuggee`), связь закрывается. */
    fun stop() {
        if (peer.isOpen) peer.request("disconnect", JsonObject.of("terminateDebuggee" to JsonBool(true)), STOP_TIMEOUT)
        peer.close()
        worker.shutdownNow()
    }

    private fun step(command: String): Outcome<Unit> {
        val before = view
        val thread = (before.status as? DebugStatus.Stopped)?.threadId ?: return Outcome.failure(DiagCode.STATE_CONFLICT, "программа не остановлена", command)
        // «Идёт» — до запроса: следующее `stopped` может прийти сразу за ответом и не должно быть затёрто.
        publish { it.copy(status = DebugStatus.Running, frames = emptyList(), frame = null, variables = emptyList()) }
        val r = peer.request(command, JsonObject.of("threadId" to json(thread)), REQUEST_TIMEOUT)
        if (r is Outcome.Failure) publish { if (it.status is DebugStatus.Running) before else it }
        return r.map { }
    }

    private fun onEvent(event: String, body: JsonObject?) {
        when (event) {
            "initialized" -> initialized.complete(Unit)
            "stopped" -> {
                val thread = body?.long("threadId") ?: 1L
                publish { it.copy(status = DebugStatus.Stopped(body?.text("reason") ?: "pause", thread, body?.text("description") ?: body?.text("text"))) }
                // Стек и переменные — своим потоком: поток чтения ответа не дождётся.
                submit { refresh(thread) }
            }
            "continued" -> publish { it.copy(status = DebugStatus.Running, frames = emptyList(), frame = null, variables = emptyList()) }
            "output" -> {
                val category = body?.text("category")
                if (category == "telemetry") return
                val text = body?.text("output") ?: return
                publish { v -> v.copy(output = (v.output + lines(text)).takeLast(MAX_OUTPUT_LINES)) }
            }
            "exited" -> exitCode = body?.int("exitCode")
            "terminated" -> {
                publish { it.copy(status = DebugStatus.Exited(exitCode), frames = emptyList(), frame = null, variables = emptyList()) }
                submit { peer.close() }
            }
        }
    }

    private fun refresh(thread: Long) {
        val frames = when (val r = peer.request("stackTrace", JsonObject.of("threadId" to json(thread), "levels" to json(MAX_FRAMES)), REQUEST_TIMEOUT)) {
            is Outcome.Failure -> return
            is Outcome.Success -> ((r.value as? JsonObject)?.get("stackFrames") as? JsonArray)?.items.orEmpty().mapNotNull { f ->
                val o = f as? JsonObject ?: return@mapNotNull null
                StackFrame(o.long("id") ?: return@mapNotNull null, o.text("name").orEmpty().take(MAX_VALUE), (o["source"] as? JsonObject)?.text("path"), o.int("line") ?: 0)
            }.take(MAX_FRAMES)
        }
        val top = frames.firstOrNull { it.path != null } ?: frames.firstOrNull()
        val vars = top?.let { locals(it.id).valueOrNull() }.orEmpty()
        publish { if (it.status is DebugStatus.Stopped) it.copy(frames = frames, frame = top?.id, variables = vars) else it }
    }

    /** Переменные первой недорогой области кадра (локальные). */
    private fun locals(frameId: Long): Outcome<List<DebugVariable>> {
        val scopes = when (val r = peer.request("scopes", JsonObject.of("frameId" to json(frameId)), REQUEST_TIMEOUT)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> ((r.value as? JsonObject)?.get("scopes") as? JsonArray)?.items.orEmpty().mapNotNull { it as? JsonObject }
        }
        val scope = scopes.firstOrNull { (it["expensive"] as? JsonBool)?.value != true } ?: scopes.firstOrNull() ?: return Outcome.success(emptyList())
        val ref = scope.long("variablesReference") ?: return Outcome.success(emptyList())
        return variables(ref)
    }

    private fun variables(ref: Long): Outcome<List<DebugVariable>> =
        when (val r = peer.request("variables", JsonObject.of("variablesReference" to json(ref)), REQUEST_TIMEOUT)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(
                ((r.value as? JsonObject)?.get("variables") as? JsonArray)?.items.orEmpty().mapNotNull { v ->
                    val o = v as? JsonObject ?: return@mapNotNull null
                    DebugVariable(o.text("name") ?: return@mapNotNull null, o.text("value").orEmpty().take(MAX_VALUE), o.text("type"), o.long("variablesReference") ?: 0L)
                }.take(MAX_VARIABLES),
            )
        }

    private fun submit(work: () -> Unit) {
        try {
            worker.execute(work)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Сеанс уже закрыт — обновлять нечего.
        }
    }

    @Synchronized
    private fun publish(change: (DebugView) -> DebugView) {
        view = change(view)
        onView(view)
    }

    private fun fail(r: Outcome.Failure): Outcome.Failure {
        publish { it.copy(status = DebugStatus.Failed(r.diagnostics.first().message)) }
        return r
    }

    private fun lines(text: String): List<String> = text.removeSuffix("\n").split('\n').map { it.trimEnd('\r').take(MAX_LINE) }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonString)?.value

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonNumber)?.toLongExact()

    private fun JsonObject.int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    companion object {
        val START_TIMEOUT: Duration = 60.seconds
        val REQUEST_TIMEOUT: Duration = 20.seconds
        val STOP_TIMEOUT: Duration = 5.seconds
        const val MAX_OUTPUT_LINES: Int = 2000
        const val MAX_LINE: Int = 2000
        const val MAX_FRAMES: Int = 50
        const val MAX_VARIABLES: Int = 200
        const val MAX_VALUE: Int = 1000
        const val MAX_EXPRESSION: Int = 500
    }
}
