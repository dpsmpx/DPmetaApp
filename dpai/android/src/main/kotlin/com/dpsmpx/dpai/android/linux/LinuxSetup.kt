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
        return ProotLayout(
            proot = "$lib/libproot.so",
            loader = "$lib/libproot-loader.so",
            rootfs = File(context.filesDir, environment(context).rootfs).path,
            tmp = tmp.path,
        )
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
            val native = JniProcesses()
            val proot = ProotHost(NativeProcessHost(native, Clock.SYSTEM), layout(context).copy(binds = listOf(ProotLayout.Bind(home, home))))
            val app = context.applicationContext
            // DNS гостя — сети телефона на миг запуска: сеть могла смениться с прошлого хода (D-90).
            val guest = object : com.dpsmpx.dpai.execution.process.ProcessHost {
                override fun start(spec: ProcessSpec, onOutput: (com.dpsmpx.dpai.execution.process.OutputLine) -> Unit): Outcome<com.dpsmpx.dpai.execution.process.RunningProcess> {
                    refreshDns(app)
                    return proot.start(spec, onOutput)
                }
            }
            return com.dpsmpx.dpai.orchestrator.agent.InAppAgentHost.create(home, Clock.SYSTEM, version ?: "dev", native, guest, ProotHost.GUEST_ENVIRONMENT)
                .also { agent = it }
        }
    }

    /** Завершить оболочку: процесс-лидер сеанса получает HUP, proot с `--kill-on-exit` уносит гостя с собой. */
    fun hangUp(child: NativeChild) {
        JniProcesses().kill(child.pid, com.dpsmpx.dpai.execution.linux.Signals.HUP)
    }
}
