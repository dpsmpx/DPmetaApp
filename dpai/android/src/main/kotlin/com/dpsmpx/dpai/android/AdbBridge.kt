package com.dpsmpx.dpai.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.StreamProcess
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.Adb
import com.dpsmpx.dpai.ui.Logcat
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.seconds

/**
 * adb к самому телефону (`DPmetaApp:D-125`; D-160): adb — гостя Linux приложения,
 * адрес — `127.0.0.1`, порты беспроводной отладки — из NSD своего телефона или от
 * человека. Сопряжение, подключение и «Забыть» — строкой в журнале, без кода.
 * Методы с процессами гостя — не из потока экрана: экран зовёт их через [work].
 */
object AdbBridge {
    data class State(
        val pairingPort: Int? = null,
        val connectPort: Int? = null,
        val paired: Boolean = false,
        val connected: Boolean? = null,
        val missing: Boolean = false,
        val busy: Boolean = false,
        val note: String? = null,
    )

    private val flow = MutableStateFlow(State())
    val state: StateFlow<State> = flow
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dpai-adb").apply { isDaemon = true } }
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()

    private fun androidDir(context: Context) = java.io.File(GuestPaths.home(context), ".android")

    /** Обновить состояние: есть ли ключ сопряжения и сохранённый порт. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val dir = androidDir(app)
        val port = runCatching { java.io.File(dir, "dpmetaapp-port").readText().trim().toIntOrNull() }.getOrNull()
        flow.update { it.copy(paired = java.io.File(dir, "adbkey").isFile, connectPort = it.connectPort ?: port) }
    }

    /**
     * Искать службы беспроводной отладки своего телефона: порт сопряжения (пока
     * открыто окно «Сопряжение по коду») и порт подключения. Чужие телефоны сети —
     * мимо: адрес службы должен быть адресом этого телефона.
     */
    fun discover(context: Context) {
        val nsd = context.applicationContext.getSystemService(NsdManager::class.java) ?: return
        stopDiscovery(context)
        for ((type, pairing) in listOf("_adb-tls-pairing._tcp" to true, "_adb-tls-connect._tcp" to false)) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) = resolve(nsd, info, pairing)
                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onDiscoveryStarted(type: String) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onStartDiscoveryFailed(type: String, code: Int) = Unit
                override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) }.onSuccess { synchronized(listeners) { listeners += l } }
        }
    }

    fun stopDiscovery(context: Context) {
        val nsd = context.applicationContext.getSystemService(NsdManager::class.java) ?: return
        synchronized(listeners) {
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            listeners.clear()
        }
    }

    private fun resolve(nsd: NsdManager, info: NsdServiceInfo, pairing: Boolean) {
        fun found(hosts: List<InetAddress>, port: Int) {
            if (port !in 1..65535 || hosts.none(::isLocal)) return
            flow.update { if (pairing) it.copy(pairingPort = port) else it.copy(connectPort = port) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(code: Int) = Unit
                override fun onServiceUpdated(s: NsdServiceInfo) {
                    found(s.hostAddresses, s.port)
                    runCatching { nsd.unregisterServiceInfoCallback(this) }
                }
                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            runCatching { nsd.registerServiceInfoCallback(info, worker, callback) }
        } else {
            resolveOld(nsd, info) { host, port -> found(listOfNotNull(host), port) }
        }
    }

    /** Android 11–13: `resolveService` — других средств там нет; с Android 14 — `registerServiceInfoCallback`. */
    @Suppress("DEPRECATION")
    private fun resolveOld(nsd: NsdManager, info: NsdServiceInfo, done: (InetAddress?, Int) -> Unit) {
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, code: Int) = Unit
                override fun onServiceResolved(s: NsdServiceInfo) = done(s.host, s.port)
            })
        }
    }

    private fun isLocal(a: InetAddress): Boolean = a.isLoopbackAddress || runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { n -> n.inetAddresses.toList().any { it == a } }
    }.getOrDefault(false)

    fun setPorts(pairing: Int?, connect: Int?) = flow.update { it.copy(pairingPort = pairing ?: it.pairingPort, connectPort = connect ?: it.connectPort) }

    /** Сопрячь: код — в stdin `adb pair`; ключ adb гостя создаётся сам. */
    fun pair(context: Context, port: Int, code: String) {
        val app = context.applicationContext
        work(app) { host ->
            val r = host.run(ProcessSpec(Adb.pair(port), stdin = Adb.pairInput(code)), 60.seconds, maxStdoutChars = 8_192)
            val result = when (r) {
                is Outcome.Failure -> r
                is Outcome.Success -> Adb.pairResult(r.value.stdout + r.value.stderr, r.value.exitCode)
            }
            when (result) {
                is Outcome.Failure -> "Не сопряжено: " + result.diagnostics.first().message
                is Outcome.Success -> {
                    AppLogs.info(app, "adb", "сопряжение с этим телефоном: порт $port")
                    refresh(app)
                    "Сопряжено. Теперь — «Подключить» с портом подключения."
                }
            }
        }
    }

    /** Подключиться и запомнить порт для терминала и ИИ (`~/.android/dpmetaapp-port`). */
    fun connect(context: Context, port: Int) {
        val app = context.applicationContext
        work(app) { host ->
            when (val r = host.run(ProcessSpec(Adb.state(port)), 60.seconds, maxStdoutChars = 4_096)) {
                is Outcome.Failure -> "Не подключено: " + r.diagnostics.first().message
                is Outcome.Success -> if (r.value.ok && r.value.stdout.any { it.trim() == "device" }) {
                    writePort(app, port)
                    AppLogs.info(app, "adb", "подключение к этому телефону: порт $port")
                    flow.update { it.copy(connected = true, connectPort = port) }
                    "Подключено: 127.0.0.1:$port. В терминале: adb connect 127.0.0.1:\$(cat ~/.android/dpmetaapp-port)"
                } else {
                    flow.update { it.copy(connected = false) }
                    "Не подключено: " + ((r.value.stdout + r.value.stderr).lastOrNull { it.isNotBlank() } ?: "код ${r.value.exitCode}") +
                        ". Беспроводная отладка включена? Порт подключения меняется при каждом включении."
                }
            }
        }
    }

    private fun writePort(app: Context, port: Int) {
        val dir = androidDir(app)
        runCatching {
            dir.mkdirs()
            NioVfs(dir.toPath()).write("dpmetaapp-port", "$port\n".toByteArray())
        }
    }

    /** «Забыть сопряжение»: ключ adb гостя и порт удаляются; телефон забывает ключ в «Беспроводной отладке». */
    fun forget(context: Context) {
        val app = context.applicationContext
        work(app, needsLinux = false) {
            val vfs = NioVfs(androidDir(app).toPath())
            listOf("adbkey", "adbkey.pub", "dpmetaapp-port").forEach { runCatching { vfs.delete(it) } }
            AppLogs.info(app, "adb", "сопряжение забыто: ключ adb удалён")
            flow.update { State() }
            "Сопряжение забыто. В «Беспроводной отладке» телефона уберите «DPmetaApp» из сопряжённых устройств."
        }
    }

    /** Поставить adb (пакет Alpine `android-tools`). */
    fun install(context: Context) {
        val app = context.applicationContext
        if (LongJobs.get(app).busy(*LongJobs.GUEST.toTypedArray())) { flow.update { it.copy(note = "Дождитесь завершения другой операции Linux") }; return }
        work(app) {
            when (val r = LinuxSetup.apk(app, LinuxEnvironment.Apk.ADD, listOf(Adb.PACKAGE))) {
                is Outcome.Failure -> "adb не поставлен: " + r.diagnostics.first().message
                is Outcome.Success -> if (r.value.ok) { flow.update { it.copy(missing = false) }; "adb поставлен." } else "adb не поставлен: код ${r.value.exitCode}"
            }
        }
    }

    /** Есть ли adb в госте; нет — [State.missing]. */
    fun check(context: Context) {
        val app = context.applicationContext
        work(app) { host ->
            val ok = (host.run(ProcessSpec(Adb.check), 30.seconds) as? Outcome.Success)?.value?.ok == true
            flow.update { it.copy(missing = !ok) }
            if (ok) null else "adb нет в Linux приложения — «Поставить adb»."
        }
    }

    /** pid пакета на этом телефоне; не запущен — `null`. */
    fun pid(context: Context, port: Int, pkg: String): Int? {
        val host = LinuxSetup.guestHost(context.applicationContext) ?: return null
        val r = host.run(ProcessSpec(Adb.pidof(port, pkg)), 30.seconds, maxStdoutChars = 1_024)
        return (r as? Outcome.Success)?.value?.stdout?.firstOrNull()?.trim()?.split(' ')?.firstOrNull()?.toIntOrNull()
    }

    /** Запустить приложение пакета; итог — словами. */
    fun launch(context: Context, port: Int, pkg: String, done: (String) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val host = LinuxSetup.guestHost(app) ?: return@execute done("Linux в приложении не установлен")
            val r = host.run(ProcessSpec(Adb.launch(port, pkg)), 60.seconds, maxStdoutChars = 8_192)
            done(
                when (r) {
                    is Outcome.Failure -> "Не запущено: " + r.diagnostics.first().message
                    is Outcome.Success -> if (r.value.ok && r.value.stdout.none { "No activities found" in it || "aborted" in it }) {
                        AppLogs.info(app, "adb", "запуск $pkg")
                        "Запущено: $pkg"
                    } else {
                        "Не запущено: " + ((r.value.stdout + r.value.stderr).lastOrNull { it.isNotBlank() } ?: "код ${r.value.exitCode}")
                    }
                },
            )
        }
    }

    private fun work(app: Context, needsLinux: Boolean = true, block: (com.dpsmpx.dpai.execution.process.ProcessHost) -> String?) {
        flow.update { it.copy(busy = true) }
        worker.execute {
            val note = runCatching {
                val host = LinuxSetup.guestHost(app)
                if (needsLinux && host == null) "Сначала установите Linux в приложении" else block(host ?: NO_HOST)
            }.getOrElse { "Сбой adb: ${it.javaClass.simpleName}" }
            flow.update { it.copy(busy = false, note = note ?: it.note) }
        }
    }

    private val NO_HOST = object : com.dpsmpx.dpai.execution.process.ProcessHost {
        override fun start(spec: ProcessSpec, onOutput: (com.dpsmpx.dpai.execution.process.OutputLine) -> Unit) =
            Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.OPERATION_NOT_ALLOWED, "Linux в приложении не установлен")
    }
}

/**
 * Поток logcat (D-160): процесс гостя `adb logcat`, строки — в буфер не больше
 * `Logcat.MAX_LINES`; один поток на приложение. Пауза — у экрана: поток идёт.
 */
object LogcatStream {
    data class View(val running: Boolean = false, val lines: List<Logcat.Line> = emptyList(), val pid: Int? = null, val pkg: String? = null, val note: String? = null)

    private val flow = MutableStateFlow(View())
    val view: StateFlow<View> = flow

    @Volatile
    private var process: StreamProcess? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dpai-logcat").apply { isDaemon = true } }

    /** Начать заново: [pkg] — только его процесс (pid сейчас; не запущен — весь журнал с пометкой). */
    fun start(context: Context, port: Int, level: Logcat.Level, pkg: String?) {
        val app = context.applicationContext
        stop()
        flow.value = View(running = true, pkg = pkg, note = "Подключаюсь…")
        worker.execute {
            val pid = pkg?.let { AdbBridge.pid(app, port, it) }
            val streams = LinuxSetup.guestStreams(app) ?: return@execute run { flow.update { it.copy(running = false, note = "Linux в приложении не установлен") } }
            val p = when (val r = streams.open(ProcessSpec(Adb.logcat(port, level, pid)))) {
                is Outcome.Failure -> return@execute run { flow.update { it.copy(running = false, note = "logcat не запустился: " + r.diagnostics.first().message) } }
                is Outcome.Success -> r.value
            }
            process = p
            flow.update { it.copy(pid = pid, note = if (pkg != null && pid == null) "$pkg не запущен — показан весь журнал" else null) }
            Thread({
                val pending = ArrayList<String>()
                var last = 0L
                p.stdout.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    for (line in seq) {
                        pending += line
                        val now = com.dpsmpx.dpai.core.clock.Clock.SYSTEM.now().epochMillis
                        if (pending.size >= BATCH || now - last > FLUSH_MS) {
                            val batch = pending.toList()
                            pending.clear()
                            last = now
                            flow.update { v -> v.copy(lines = Logcat.append(v.lines, batch)) }
                        }
                    }
                }
                if (pending.isNotEmpty()) flow.update { v -> v.copy(lines = Logcat.append(v.lines, pending)) }
                if (process === p) flow.update { it.copy(running = false, note = it.note ?: "logcat остановлен: связь с adb кончилась") }
            }, "dpai-logcat-read").apply { isDaemon = true }.start()
        }
    }

    fun stop() {
        process?.stop(2.seconds)
        process = null
        flow.update { it.copy(running = false) }
    }

    fun clear() = flow.update { it.copy(lines = emptyList()) }

    private const val BATCH = 200
    private const val FLUSH_MS = 250L
}
