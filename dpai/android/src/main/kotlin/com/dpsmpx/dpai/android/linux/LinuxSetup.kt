package com.dpsmpx.dpai.android.linux

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.linux.NativeChild
import com.dpsmpx.dpai.execution.linux.NativeProcessHost
import com.dpsmpx.dpai.execution.linux.ProotHost
import com.dpsmpx.dpai.execution.linux.ProotLayout
import com.dpsmpx.dpai.execution.linux.WindowSize
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.orchestrator.linux.RootfsInstaller
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * Linux-среда в приложении на Android (D-75, шаг 5): где лежат proot и
 * корневая система, установка из APK, DNS сети телефона, пакеты и оболочка
 * для терминала. Смысл — в `LinuxEnvironment` (ядро, тесты на JVM); здесь —
 * только пути и вызовы Android.
 */
object LinuxSetup {
    /** Версия minirootfs в APK — задача `linuxFiles` сборки, `docs/PROVENANCE.md`, §4. */
    const val VERSION = "alpine-minirootfs-3.24.3"

    private const val ASSET = "linux/rootfs.tgz"

    /** proot Alpine — только aarch64 (`docs/EXECUTION_MODEL.md`, §8). */
    fun available(context: Context): Boolean =
        "arm64-v8a" in Build.SUPPORTED_ABIS && File(layout(context).proot).isFile

    fun environment(context: Context): LinuxEnvironment = LinuxEnvironment(NioVfs(context.filesDir.toPath()))

    fun layout(context: Context): ProotLayout {
        val lib = context.applicationInfo.nativeLibraryDir
        val tmp = File(context.cacheDir, "linux-tmp").apply { mkdirs() }
        val rootfs = File(context.filesDir, environment(context).rootfs)
        return ProotLayout(
            proot = "$lib/libproot.so",
            loader = "$lib/libproot-loader.so",
            rootfs = rootfs.path,
            tmp = tmp.path,
            binds = storageBinds(context, rootfs) + sharedBinds(rootfs),
        )
    }

    /**
     * Каталоги приложения, которые оболочка показывает гостю (`DPmetaApp:D-70`:
     * клоны репозиториев пульта — `/root/repos`, «AI» работает в них).
     * Регистрирует приложение при запуске; подсистема о самих каталогах не
     * знает. Гостевой путь — только внутри `/root`, без `..`.
     */
    private val shared = java.util.concurrent.CopyOnWriteArrayList<ProotLayout.Bind>()

    fun share(host: File, guest: String) {
        require(guest.startsWith("/root/") && guest.split('/').none { it == ".." || it == "." } && !guest.endsWith("/")) { "каталог гостя — внутри /root, без «..»: $guest" }
        if (shared.none { it.guest == guest }) shared += ProotLayout.Bind(host.path, guest)
    }

    /** Привязки [share]: каталог приложения создаётся, точка в госте — тоже, если Linux уже установлен. */
    private fun sharedBinds(rootfs: File): List<ProotLayout.Bind> = shared.mapNotNull { b ->
        val host = File(b.host)
        if (!host.isDirectory && !host.mkdirs()) return@mapNotNull null
        if (!rootfs.isDirectory) return@mapNotNull null
        File(rootfs, b.guest.removePrefix("/")).mkdirs()
        b
    }

    /** Каталог гостя для общего хранилища в режиме всех файлов (D-99), как у `termux-setup-storage`. */
    const val GUEST_SHARED = "/root/storage/shared"

    /**
     * Общее хранилище телефона — гостю, если «Доступ ко всем файлам»
     * включён и разрешение дано (D-99, T17). Решается на каждый запуск
     * процесса гостя: выключенный переключатель снимает привязку со
     * следующего запуска.
     */
    private fun storageBinds(context: Context, rootfs: File): List<ProotLayout.Bind> {
        if (!com.dpsmpx.dpai.android.AllFiles.active(context)) return emptyList()
        val shared = android.os.Environment.getExternalStorageDirectory()
        if (!shared.isDirectory || !rootfs.isDirectory) return emptyList()
        File(rootfs, GUEST_SHARED.removePrefix("/")).mkdirs()
        return listOf(ProotLayout.Bind(shared.path, GUEST_SHARED))
    }

    /** Распаковать Alpine из APK, если ещё не; `null` — уже установлена. */
    fun install(context: Context): Outcome<RootfsInstaller.Summary?> {
        if (!available(context)) return Outcome.failure(DiagCode.UNSUPPORTED, "Linux в приложении — только на телефоне с arm64")
        return environment(context).install(VERSION) { context.assets.open(ASSET) }
    }

    /** DNS гостя — серверы сети телефона: перед каждым запуском, сеть могла смениться. */
    fun refreshDns(context: Context): Outcome<Int> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val servers = cm?.activeNetwork?.let { cm.getLinkProperties(it) }?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
        return environment(context).writeDns(servers)
    }

    private fun host(context: Context): ProotHost = ProotHost(NativeProcessHost(JniProcesses(), Clock.SYSTEM), layout(context))

    /** Пакеты Alpine (`apk` без `/proc`, D-82): итог и вывод — для экрана. */
    fun apk(context: Context, action: LinuxEnvironment.Apk, packages: List<String>): Outcome<Completed> {
        if (environment(context).installed() == null) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "сначала установите Linux")
        val spec = when (val s = LinuxEnvironment.apk(action, packages)) {
            is Outcome.Failure -> return s
            is Outcome.Success -> s.value
        }
        refreshDns(context)
        return host(context).run(spec, 15.minutes, maxStdoutChars = 200_000)
    }

    /** Команда в госте с выводом для экрана (установка Codex и Gemini, D-100): DNS — сети телефона. */
    fun run(context: Context, spec: ProcessSpec): Outcome<Completed> {
        if (environment(context).installed() == null) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "сначала установите Linux")
        refreshDns(context)
        return host(context).run(spec, 20.minutes, maxStdoutChars = 200_000)
    }

    /**
     * Оболочка гостя для терминала: `sh -l` под proot с псевдотерминалом
     * (JNI, шаг 3). Окружение — гостя, не Android.
     */
    fun shell(context: Context, size: WindowSize, command: List<String> = emptyList()): Outcome<NativeChild> {
        if (environment(context).installed() == null) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "сначала установите Linux")
        refreshDns(context)
        // Команда вкладки (например, `claude` слота «в приложении») — через оболочку гостя по полному пути.
        val argv = if (command.isEmpty()) listOf("/bin/sh", "-l") else command.mapIndexed { i, a -> if (i == 0 && a == "sh") "/bin/sh" else a }
        val spec = ProcessSpec(argv, environment = ProotHost.GUEST_ENVIRONMENT + ("TERM" to "xterm-256color"))
        val wrapped = host(context).wrap(spec)
        return JniProcesses().spawn(wrapped.command, wrapped.environment.orEmpty(), null, size)
    }

    /** Каталог агента target «в приложении»: в данных приложения, гостю виден по тому же пути. */
    private const val AGENT_HOME = "agent-in-app"

    @Volatile
    private var agent: com.dpsmpx.dpai.orchestrator.agent.InAppAgentHost? = null

    /**
     * Агент target «в приложении» (D-75, шаг 5) — один на процесс: его ходы
     * держит поток ([com.dpsmpx.dpai.orchestrator.agent.ThreadSupervision]),
     * и второй экземпляр их бы не видел. Процессы хода — в Alpine под proot;
     * каталог агента привязан к гостю по тому же пути, поэтому пути ходов
     * одни для агента и для гостя; окружение — гостя; DNS сети телефона —
     * перед каждым запуском (D-90). Linux не установлен или не arm64 —
     * `null`: target «в приложении» не регистрируется, причина — на экране.
     */
    fun agentHost(context: Context, version: String?): com.dpsmpx.dpai.orchestrator.agent.InAppAgentHost? {
        if (!available(context) || environment(context).installed() == null) return null
        agent?.let { return it }
        synchronized(this) {
            agent?.let { return it }
            val home = File(context.filesDir, AGENT_HOME).apply { mkdirs() }.path
            return com.dpsmpx.dpai.orchestrator.agent.InAppAgentHost.create(home, Clock.SYSTEM, version ?: "dev", JniProcesses(), guestHost(context)!!, ProotHost.GUEST_ENVIRONMENT)
                .also { agent = it }
        }
    }

    /**
     * Процессы гостя для контура (D-75, шаг 5): ходы агента «в приложении»
     * и Git проекта на таком target. Каталог агента привязан к гостю по тому
     * же пути — пути одни для агента и гостя; DNS сети телефона — на миг
     * запуска (сеть могла смениться, D-90). Linux не установлен — `null`.
     */
    fun guestHost(context: Context): com.dpsmpx.dpai.execution.process.ProcessHost? {
        if (!available(context) || environment(context).installed() == null) return null
        val home = File(context.filesDir, AGENT_HOME).apply { mkdirs() }.path
        val app = context.applicationContext
        return object : com.dpsmpx.dpai.execution.process.ProcessHost {
            override fun start(spec: ProcessSpec, onOutput: (com.dpsmpx.dpai.execution.process.OutputLine) -> Unit): Outcome<com.dpsmpx.dpai.execution.process.RunningProcess> {
                refreshDns(app)
                // Раскладка — на каждый запуск: привязка хранилища следует переключателю (D-99).
                val layout = layout(app)
                return ProotHost(NativeProcessHost(JniProcesses(), Clock.SYSTEM), layout.copy(binds = layout.binds + ProotLayout.Bind(home, home))).start(spec, onOutput)
            }
        }
    }

    /** Завершить оболочку: процесс-лидер сеанса получает HUP, proot с `--kill-on-exit` уносит гостя с собой. */
    fun hangUp(child: NativeChild) {
        JniProcesses().kill(child.pid, com.dpsmpx.dpai.execution.linux.Signals.HUP)
    }
}
