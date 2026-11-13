package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.StreamProcess
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.lsp.dap.DebugLaunch
import com.dpsmpx.dpai.lsp.dap.DebugLaunches
import com.dpsmpx.dpai.lsp.dap.DebugSession
import com.dpsmpx.dpai.lsp.dap.DebugStatus
import com.dpsmpx.dpai.lsp.dap.DebugView
import com.dpsmpx.dpai.lsp.dap.Debuggee
import com.dpsmpx.dpai.lsp.jdwp.JdwpSession
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.ui.Adb
import com.dpsmpx.dpai.ui.Breakpoints
import com.dpsmpx.dpai.ui.BuildProblems
import com.dpsmpx.dpai.ui.DebugTargets
import com.dpsmpx.dpai.ui.JvmSourceIndex
import com.dpsmpx.dpai.ui.PythonRuntime
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Отладчик IDE (`DPmetaApp:D-124`, D-126; D-159, D-161): один сеанс на приложение.
 * C/C++ и Python — в Linux приложения: проверка отладчика (gdb, debugpy) и установка
 * кнопкой, сборка файла C/C++ для отладки, адаптер DAP — процессом гостя. Kotlin/Java —
 * своё APK на телефоне: adb гостя перезапускает его с ожиданием отладчика и пересылает
 * JDWP на `127.0.0.1`, клиент — `JdwpSession`. Точки останова — общие для всех вкладок;
 * остановка — переход к строке во вкладке своего корня (`IdeState.jumpTo`). Методы,
 * ждущие отладчика, — не из потока экрана: экран зовёт их через [work].
 */
object Debugger {
    enum class Phase { IDLE, CHECKING, BUILDING, STARTING, ACTIVE, ENDED }

    /** Чего не хватает для отладки: ставится кнопкой «Поставить». */
    enum class Missing(val title: String) { GDB("gdb — пакет Alpine"), DEBUGPY("debugpy — в окружение Python проекта"), ADB("adb — пакет Alpine android-tools") }

    /**
     * [target] — что отлаживается; [rootKey] и [treeRoot] — корень редактора, куда
     * открывать строку остановки; [problems] — ошибки сборки для отладки (переход в код).
     */
    data class State(
        val phase: Phase = Phase.IDLE,
        val target: DebugTargets.Target? = null,
        val rootKey: String? = null,
        val treeRoot: String? = null,
        val view: DebugView = DebugView(),
        val note: String? = null,
        val missing: Missing? = null,
        val busy: Boolean = false,
        val build: List<String> = emptyList(),
        val problems: List<com.dpsmpx.dpai.ui.BuildProblem> = emptyList(),
    ) {
        val active: Boolean get() = phase in setOf(Phase.CHECKING, Phase.BUILDING, Phase.STARTING, Phase.ACTIVE)
    }

    private val flow = MutableStateFlow(State())
    val state: StateFlow<State> = flow

    private val marks = MutableStateFlow(Breakpoints())
    val breakpoints: StateFlow<Breakpoints> = marks

    @Volatile
    private var session: Debuggee? = null

    @Volatile
    private var process: StreamProcess? = null

    @Volatile
    private var socket: Socket? = null

    /** Последний отлаживаемый пакет по корню дерева: «Отладка» над файлом .kt/.java начинает его снова. */
    private val apks = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Пакет APK, который отлаживали из дерева [treeRoot] в этом запуске приложения. */
    fun lastApk(treeRoot: String): String? = apks[treeRoot.trimEnd('/')]

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "dpai-debugger").apply { isDaemon = true } }

    /** Поставить или снять точку; в идущем сеансе — сразу отладчику. */
    fun toggle(path: String, line: Int) {
        marks.update { it.toggle(path, line) }
        val s = session ?: return
        val lines = marks.value.lines(path).sorted()
        work { s.setBreakpoints(path, lines) }
    }

    fun clearBreakpoints() {
        val files = marks.value.byFile.keys
        marks.value = Breakpoints()
        val s = session ?: return
        work { files.forEach { s.setBreakpoints(it, emptyList()) } }
    }

    /** Строка остановки в файле [path] гостя (с единицы) или `null`. */
    fun stoppedLine(path: String): Int? {
        val s = flow.value
        if (s.view.status !is DebugStatus.Stopped) return null
        return s.view.location?.takeIf { it.path == path }?.line
    }

    /**
     * Начать отладку [target] (из редактора или со страницы «Запуск»): прежний сеанс
     * останавливается. [rootKey] и [treeRoot] — корень редактора для перехода к строке.
     */
    fun start(context: Context, target: DebugTargets.Target, rootKey: String?, treeRoot: String?) {
        val app = context.applicationContext
        stopNow()
        flow.value = State(Phase.CHECKING, target, rootKey, treeRoot, note = "Проверяю ${target.engine.title}…")
        work { run(app, target) }
    }

    private fun run(app: Context, target: DebugTargets.Target) {
        if (!LinuxSetup.available(app) || LinuxSetup.environment(app).installed() == null) return end("Сначала установите Linux в приложении")
        if (target.engine == DebugTargets.Engine.JDWP) return runApk(app, target)
        val host = LinuxSetup.guestHost(app) ?: return end("Linux в приложении недоступен")
        when (val c = host.run(ProcessSpec(check(target)), 60.seconds, maxStdoutChars = 8_192)) {
            is Outcome.Failure -> return end("Проверка отладчика не прошла: ${c.diagnostics.first().message}")
            is Outcome.Success -> if (!c.value.ok) {
                val missing = if (target.engine == DebugTargets.Engine.GDB) Missing.GDB else Missing.DEBUGPY
                flow.update { it.copy(phase = Phase.ENDED, missing = missing, note = "Нет отладчика: ${missing.title}. Поставьте его кнопкой и начните снова.") }
                return
            }
        }
        target.build?.let { build ->
            flow.update { it.copy(phase = Phase.BUILDING, note = "Собираю для отладки: -g -O0…") }
            val space = StorageSpace.check(app, com.dpsmpx.dpai.storage.space.SpaceGuard.Kind.BUILD, 0, "сборка для отладки")
            if (space is Outcome.Failure) return end(space.diagnostics.first().message)
            when (val b = host.run(ProcessSpec(build), 10.minutes, maxStdoutChars = 262_144)) {
                is Outcome.Failure -> return end("Сборка не запустилась: ${b.diagnostics.first().message}")
                is Outcome.Success -> if (!b.value.ok) {
                    val output = b.value.stdout + b.value.stderr
                    val problems = BuildProblems.parse(output.joinToString("\n")).items
                    flow.update { it.copy(phase = Phase.ENDED, note = "Сборка для отладки не прошла: код ${b.value.exitCode}.", build = output.takeLast(60), problems = problems) }
                    return
                }
            }
        }
        flow.update { it.copy(phase = Phase.STARTING, note = "Запускаю ${target.engine.title}…") }
        val streams = LinuxSetup.guestStreams(app) ?: return end("Linux в приложении недоступен")
        val p = when (val r = streams.open(ProcessSpec(target.adapter, workDir = target.cwd))) {
            is Outcome.Failure -> return end("Отладчик не запустился: ${r.diagnostics.first().message}")
            is Outcome.Success -> r.value
        }
        process = p
        val s = DebugSession(p.stdout, p.stdin, onView = { v -> onView(v) }, onClosed = { p.stop(2.seconds) })
        session = s
        val launch = launch(target)
        when (val r = s.start(launch, marks.value.forLaunch(), 2.minutes)) {
            is Outcome.Failure -> {
                stopNow()
                return end("Отладка не началась: ${r.diagnostics.first().message}")
            }
            is Outcome.Success -> flow.update { it.copy(phase = Phase.ACTIVE, note = null) }
        }
    }

    /**
     * Отладка своего APK (`DPmetaApp:D-126`; D-161): adb гостя (нет — «Поставить adb»),
     * подключение из «Logcat»; команда гостя перезапускает приложение с ожиданием
     * отладчика и пересылает JDWP на `127.0.0.1:<порт>` — к нему подключается
     * [JdwpSession]. Исходники — файлы .kt/.java дерева [DebugTargets.Target.cwd].
     */
    private fun runApk(app: Context, target: DebugTargets.Target) {
        val pkg = target.program
        if (pkg == app.packageName) return end("Сам DPmetaApp отлаживать нельзя: остановка его кода остановила бы и отладчик")
        val host = LinuxSetup.guestHost(app) ?: return end("Linux в приложении недоступен")
        if ((host.run(ProcessSpec(Adb.check), 30.seconds) as? Outcome.Success)?.value?.ok != true) {
            flow.update { it.copy(phase = Phase.ENDED, missing = Missing.ADB, note = "Нет adb в Linux приложения. Поставьте его кнопкой, подключите в «Logcat» и начните снова.") }
            return
        }
        AdbBridge.refresh(app)
        val port = AdbBridge.state.value.connectPort ?: return end("Сначала подключите adb к этому телефону: «Файлы» → «Logcat» → «Подключить»")
        val root = target.cwd.trimEnd('/')
        val hostRoot = hostOf(app, root) ?: return end("Исходники дерева $root не видны приложению")
        flow.update { it.copy(phase = Phase.STARTING, note = "Перезапускаю $pkg с ожиданием отладчика…") }
        val sources = JvmSourceIndex(sourceFiles(hostRoot, root)) { path -> hostOf(app, path)?.takeIf { it.isFile && it.length() <= MAX_SOURCE_BYTES }?.let { f -> runCatching { f.readText() }.getOrNull() } }
        val streams = LinuxSetup.guestStreams(app) ?: return end("Linux в приложении недоступен")
        val p = when (val r = streams.open(ProcessSpec(Adb.debugApk(port, pkg)))) {
            is Outcome.Failure -> return end("adb не запустился: ${r.diagnostics.first().message}")
            is Outcome.Success -> r.value
        }
        process = p
        val reader = p.stdout.bufferedReader(Charsets.UTF_8)
        val ready = when (val line = generateSequence { runCatching { reader.readLine() }.getOrNull() }.firstNotNullOfOrNull(Adb::debugLine)) {
            null -> { stopNow(); return end("Отладка не началась: adb кончился раньше пересылки JDWP") }
            is Adb.DebugLine.Error -> { stopNow(); return end("Отладка не началась: ${line.message}") }
            is Adb.DebugLine.Ready -> line
        }
        val sock = try {
            Socket().apply { connect(InetSocketAddress(Adb.HOST, ready.port), CONNECT_TIMEOUT_MS) }
        } catch (e: java.io.IOException) {
            stopNow()
            return end("Пересылка JDWP не открылась: ${e.javaClass.simpleName}")
        }
        socket = sock
        // Связь кончилась — закрыть свои сокет и команду adb (поля к этому времени могут быть сброшены onView).
        val s = JdwpSession(sock.getInputStream(), sock.getOutputStream(), sources, onView = { v -> onView(v) }, onClosed = { runCatching { sock.close() }; p.stop(2.seconds) })
        session = s
        apks[root] = pkg
        AppLogs.info(app, "отладка", "отладка $pkg по JDWP: pid ${ready.pid}")
        val points = marks.value.forLaunch().filterKeys { it.startsWith("$root/") && DebugTargets.engineOf(it) == DebugTargets.Engine.JDWP }
        when (val r = s.attach(points)) {
            is Outcome.Failure -> {
                stopNow()
                return end("Отладка не началась: ${r.diagnostics.first().message}")
            }
            is Outcome.Success -> flow.update { it.copy(phase = Phase.ACTIVE, note = "Отладка $pkg (pid ${ready.pid}): точки — касанием номера строки в файлах .kt и .java") }
        }
    }

    /** Файлы .kt/.java дерева [hostRoot] путями гостя под [guestRoot]; папки сборки и служебные — мимо. */
    private fun sourceFiles(hostRoot: File, guestRoot: String): List<String> =
        hostRoot.walkTopDown().onEnter { it == hostRoot || it.name !in SKIPPED_DIRS }
            .filter { it.isFile && it.extension in JvmSourceIndex.EXTENSIONS }
            .take(MAX_SOURCES)
            .map { guestRoot + "/" + it.relativeTo(hostRoot).invariantSeparatorsPath }
            .toList()

    /** Файл телефона для пути гостя: домашняя папка Linux и общая память (D-99). */
    private fun hostOf(app: Context, guest: String): File? {
        val path = guest.trimEnd('/')
        if (path.split('/').any { it == ".." }) return null
        val shared = LinuxSetup.GUEST_SHARED
        return when {
            path == shared || path.startsWith("$shared/") -> File(android.os.Environment.getExternalStorageDirectory(), path.removePrefix(shared).trimStart('/'))
            path == "/root" -> GuestPaths.home(app)
            path.startsWith("/root/") -> File(GuestPaths.home(app), path.removePrefix("/root/"))
            else -> null
        }
    }

    private fun onView(v: DebugView) {
        val before = flow.value
        val ended = v.status is DebugStatus.Exited || v.status is DebugStatus.Failed
        flow.update { it.copy(view = v, phase = if (ended) Phase.ENDED else if (it.phase == Phase.STARTING || it.phase == Phase.ACTIVE) Phase.ACTIVE else it.phase) }
        if (ended) { session = null; process = null }
        // Новая остановка — открыть её строку во вкладке своего корня.
        val at = v.location
        val path = at?.path
        if (v.status is DebugStatus.Stopped && path != null && (before.view.status !is DebugStatus.Stopped || at != before.view.location)) {
            val root = before.treeRoot?.trimEnd('/')
            val key = before.rootKey
            if (root != null && key != null && path.startsWith("$root/")) IdeState.jumpTo(key, path.removePrefix("$root/"), at.line)
        }
    }

    fun resume() = act { it.resume() }

    fun next() = act { it.next() }

    fun stepIn() = act { it.stepIn() }

    fun stepOut() = act { it.stepOut() }

    fun pause() = act { it.pause() }

    fun selectFrame(id: Long) = act { it.selectFrame(id).map { } }

    /** Вычислить выражение в выбранном кадре; итог — в [done] (из потока отладчика). */
    fun evaluate(expression: String, done: (Outcome<String>) -> Unit) {
        val s = session ?: return done(Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.STATE_CONFLICT, "отладка не идёт"))
        work { done(s.evaluate(expression)) }
    }

    /** Остановить отладку: программа завершается, адаптер — тоже. */
    fun stop() = work { stopNow(); flow.update { if (it.phase == Phase.IDLE) it else it.copy(phase = Phase.ENDED, note = "Отладка остановлена") } }

    /** Закрыть панель: сеанс остановлен, состояние — пустое; точки остаются. */
    fun close() = work { stopNow(); flow.value = State() }

    /** Поставить недостающий отладчик ([State.missing]) — в Linux приложения, итогом — строка панели. */
    fun install(context: Context) {
        val app = context.applicationContext
        val s = flow.value
        val missing = s.missing ?: return
        val target = s.target ?: return
        if (LongJobs.get(app).busy(*LongJobs.GUEST.toTypedArray())) { flow.update { it.copy(note = "Дождитесь завершения другой операции Linux") }; return }
        flow.update { it.copy(busy = true, note = "Ставлю ${missing.title}…") }
        work {
            val result = when (missing) {
                Missing.GDB -> LinuxSetup.apk(app, LinuxEnvironment.Apk.ADD, listOf(DebugTargets.GDB_PACKAGE))
                Missing.ADB -> LinuxSetup.apk(app, LinuxEnvironment.Apk.ADD, listOf(Adb.PACKAGE))
                Missing.DEBUGPY -> {
                    val root = s.treeRoot ?: target.cwd
                    val steps = listOf(LinuxEnvironment.apk(LinuxEnvironment.Apk.ADD, PythonRuntime.packages), PythonRuntime.install(root, target.program, listOf(DebugTargets.DEBUGPY_MODULE)).map { ProcessSpec(it) })
                    steps.firstOrNull { it is Outcome.Failure }?.let { it as Outcome.Failure } ?: LinuxSetup.runSteps(app, steps.map { (it as Outcome.Success).value })
                }
            }
            val note = when (result) {
                is Outcome.Failure -> "Не поставлено: ${result.diagnostics.first().message}"
                is Outcome.Success -> if (result.value.ok) "Поставлено: ${missing.title}. Нажмите «Отладка» снова." else "Не поставлено: код ${result.value.exitCode}. ${(result.value.stdout + result.value.stderr).takeLast(3).joinToString(" ")}"
            }
            val ok = (result as? Outcome.Success)?.value?.ok == true
            flow.update { it.copy(busy = false, missing = if (ok) null else it.missing, note = note) }
        }
    }

    private fun act(block: (Debuggee) -> Outcome<Unit>) {
        val s = session ?: return
        work {
            val r = block(s)
            if (r is Outcome.Failure) flow.update { it.copy(note = r.diagnostics.first().message) }
        }
    }

    private fun end(note: String) {
        flow.update { it.copy(phase = Phase.ENDED, note = note) }
    }

    private fun stopNow() {
        session?.stop()
        session = null
        stopTransport()
    }

    /** Процесс отладчика или пересылки adb и связь JDWP. */
    private fun stopTransport() {
        socket?.let { runCatching { it.close() } }
        socket = null
        process?.stop(2.seconds)
        process = null
    }

    private fun work(block: () -> Unit) {
        worker.execute { runCatching(block).onFailure { e -> flow.update { it.copy(note = "Сбой отладчика: ${e.javaClass.simpleName}") } } }
    }

    /** Проверка отладчика: gdb — в `PATH`, debugpy — импортируется интерпретатором проекта. */
    private fun check(target: DebugTargets.Target): List<String> = when (target.engine) {
        DebugTargets.Engine.GDB -> listOf("/bin/sh", "-c", "command -v gdb >/dev/null 2>&1")
        DebugTargets.Engine.DEBUGPY -> target.adapter.toMutableList().also { it[2] = it[2].replace("-m debugpy.adapter", "-c 'import debugpy'") }
        DebugTargets.Engine.JDWP -> Adb.check
    }

    private fun launch(target: DebugTargets.Target): DebugLaunch {
        val base = when (target.engine) {
            DebugTargets.Engine.GDB -> DebugLaunches.gdb(target.program, target.args, target.cwd)
            DebugTargets.Engine.DEBUGPY -> DebugLaunches.debugpy("python3", target.program, target.args, target.cwd)
            DebugTargets.Engine.JDWP -> error("APK отлаживается по JDWP, не адаптером DAP")
        }
        // Адаптер — команда цели (интерпретатор проекта для debugpy); программа debugpy — тем же интерпретатором.
        val launch: JsonObject = if (target.engine == DebugTargets.Engine.DEBUGPY) JsonObject(LinkedHashMap<String, JsonValue>(base.launch.fields).apply { remove("python") }) else base.launch
        return base.copy(command = target.adapter, launch = launch, workDir = target.cwd)
    }

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val MAX_SOURCES = 20_000
    private const val MAX_SOURCE_BYTES = 2L shl 20
    private val SKIPPED_DIRS = setOf(".git", ".gradle", ".idea", "build", ".cxx", "node_modules", ".kotlin", "intermediates")
}
