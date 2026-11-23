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
import kotlin.time.Duration.Companion.seconds

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
        // Разделы хранилища и перенос из прежних мест — до первого запуска гостя (D-143).
        com.dpsmpx.dpai.android.StorageMap.prepare(context)
        val lib = context.applicationInfo.nativeLibraryDir
        val tmp = File(context.cacheDir, "linux-tmp").apply { mkdirs() }
        val rootfs = File(context.filesDir, environment(context).rootfs)
        return ProotLayout(
            proot = "$lib/libproot.so",
            loader = "$lib/libproot-loader.so",
            rootfs = rootfs.path,
            tmp = tmp.path,
            // Bionic tools (the same native ABI used by Termux) need the Android
            // dynamic linker and APEX libraries visible *inside* PRoot.
            binds = nativeSystemBinds(rootfs) + storageBinds(context, rootfs) + sharedBinds(rootfs),
        )
    }

    private fun nativeSystemBinds(rootfs: File): List<ProotLayout.Bind> {
        // layout() is consulted before installation too: it MUST NOT create a
        // partial rootfs, otherwise RootfsInstaller can mistake it for Linux.
        if (!rootfs.isDirectory) return emptyList()
        // PRoot maps just this one socket; its parent must exist in the guest
        // without exposing the host's entire /dev/socket directory.
        File(rootfs, "dev/socket").mkdirs()
        val dns = ProotLayout.Bind("/dev/socket/dnsproxyd", "/dev/socket/dnsproxyd")
        // Termux's own termux-chroot binds /vendor as well: certain Android
        // devices resolve vendor runtime libraries from there.
        return listOf("/system", "/apex", "/vendor", "/linkerconfig").mapNotNull { path ->
            if (!File(path).isDirectory) return@mapNotNull null
            val mountPoint = File(rootfs, path.removePrefix("/"))
            if (!mountPoint.isDirectory && !mountPoint.mkdirs()) return@mapNotNull null
            ProotLayout.Bind(path, path)
        } + dns
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
        return environment(context).install(VERSION) { context.assets.open(ASSET) }.also {
            // `~/tools/bin` в PATH оболочек входа и карта — сразу после установки (D-143).
            // Сброс Alpine оборвался после распаковки (D-196): вернуть сохранённый дом.
            if (it is Outcome.Success) {
                val reset = com.dpsmpx.dpai.orchestrator.linux.RootfsReset(NioVfs(context.filesDir.toPath()), context.filesDir.toPath())
                if (reset.pending()) reset.restore()
            }
            if (it is Outcome.Success && it.value != null) {
                environment(context).writeProfile()
                // Помощник входа Git из GH_TOKEN (D-158): без токена молчит.
                environment(context).writeGitConfig()
                com.dpsmpx.dpai.android.StorageMap.refresh(context)
            }
        }
    }

    /** DNS гостя — серверы сети телефона: перед каждым запуском, сеть могла смениться. */
    fun refreshDns(context: Context): Outcome<Int> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val servers = cm?.activeNetwork?.let { cm.getLinkProperties(it) }?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
        return environment(context).writeDns(servers)
    }

    /**
     * DNS гостя — и при смене сети (D-158): гость, запущенный до смены Wi-Fi на мобильную
     * сеть, иначе спрашивал бы прежние серверы («DNS: transient error»). Слушатель — один
     * на процесс; Linux не установлен — файл не пишется.
     */
    fun watchDns(context: Context) {
        if (!dnsWatched.compareAndSet(false, true)) return
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: android.net.Network, lp: android.net.LinkProperties) {
                    val env = environment(app)
                    if (env.installed() == null) return
                    val servers = lp.dnsServers.mapNotNull { it.hostAddress }
                    if (servers.isEmpty() || servers == lastDns) return
                    if (env.writeDns(servers) is Outcome.Success) lastDns = servers
                }
            })
        }
    }

    private val dnsWatched = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var lastDns: List<String>? = null

    /**
     * Автор коммитов Git гостя (D-158): `git config --global` с полями, которые вписал
     * человек; приложение значений не подставляет. Имя и почта — отдельными аргументами,
     * без оболочки.
     */
    fun setGitIdentity(context: Context, name: String, email: String): Outcome<Unit> {
        com.dpsmpx.dpai.ui.Health.identityNameProblem(name)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it) }
        com.dpsmpx.dpai.ui.Health.identityEmailProblem(email)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it) }
        if (environment(context).installed() == null) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "сначала установите Linux")
        for ((key, value) in listOf("user.name" to name.trim(), "user.email" to email.trim())) {
            val r = host(context).run(ProcessSpec(listOf("git", "config", "--global", key, value)), 30.seconds)
            when {
                r is Outcome.Failure -> return r
                r is Outcome.Success && !r.value.ok -> return Outcome.failure(
                    DiagCode.PROCESS_EXIT_NONZERO, "git config: код ${r.value.exitCode} — ${(r.value.stderr + r.value.stdout).lastOrNull().orEmpty()}",
                )
            }
        }
        com.dpsmpx.dpai.android.AppLogs.info(context, "проверка", "задан автор коммитов Git в Linux (имя и почта — по вводу автора)")
        return Outcome.success(Unit)
    }

    private fun host(context: Context): ProotHost = ProotHost(NativeProcessHost(JniProcesses(), Clock.SYSTEM), layout(context))

    /** Последняя починка без нажатия (D-158): набор пакетов и время — не чаще [REPAIR_EVERY_MS]. */
    @Volatile
    private var lastRepair: Pair<Set<String>, Long>? = null

    /** Пауза между починками без нажатия одного и того же набора. */
    private const val REPAIR_EVERY_MS = 10 * 60_000L

    /**
     * Пакеты Alpine, которые база `apk` отметила повреждёнными (D-158), — `apk fix` тех же
     * пакетов: иначе каждая следующая команда пакетов падает их ошибками («3 errors; …»).
     * Итог — имена починенных; чинить нечего или [force] нет и тот же набор чинили меньше
     * [REPAIR_EVERY_MS] назад — пусто. Не вышло — отказ с именами и причиной.
     */
    fun repairBroken(context: Context, force: Boolean): Outcome<List<String>> {
        val env = environment(context)
        if (env.installed() == null) return Outcome.success(emptyList())
        // База не читается (ссылка гостя на её месте, A-3) — чинить по ней нечего; команда пакетов идёт как прежде.
        val broken = env.brokenPackages().valueOrNull().orEmpty()
        if (broken.isEmpty()) return Outcome.success(emptyList())
        val names = broken.map { it.name }
        val now = Clock.SYSTEM.monotonicNanos() / 1_000_000
        lastRepair?.let { (set, at) -> if (!force && set == names.toSet() && now - at < REPAIR_EVERY_MS) return Outcome.success(emptyList()) }
        lastRepair = names.toSet() to now
        val spec = when (val s = LinuxEnvironment.apk(LinuxEnvironment.Apk.REPAIR, names)) {
            is Outcome.Failure -> return s
            is Outcome.Success -> s.value
        }
        refreshDns(context)
        val list = names.joinToString(", ")
        val r = when (val run = host(context).run(spec, 15.minutes, maxStdoutChars = 200_000)) {
            is Outcome.Failure -> return Outcome.failure(DiagCode.PROCESS_START_FAILED, "пакеты Alpine $list повреждены прежней установкой; починка не запустилась: ${run.errors.first().message}")
            is Outcome.Success -> run.value
        }
        val left = env.brokenPackages().valueOrNull().orEmpty().map { it.name }
        if (r.ok && left.isEmpty()) {
            com.dpsmpx.dpai.android.HealthAuto.fixed(context, "восстановлены пакеты Alpine, повреждённые прежней установкой: $list")
            com.dpsmpx.dpai.android.StorageMap.refresh(context)
            return Outcome.success(names)
        }
        val why = com.dpsmpx.dpai.ui.Health.apkReason((r.stderr + r.stdout).joinToString("\n"))
        return Outcome.failure(DiagCode.PROCESS_EXIT_NONZERO, "пакеты Alpine ${left.ifEmpty { names }.joinToString(", ")} повреждены прежней установкой, и починить их не вышло (apk: код ${r.exitCode}): $why")
    }

    /** Пакеты Alpine (`apk` без `/proc`, D-82): итог и вывод — для экрана. */
    fun apk(context: Context, action: LinuxEnvironment.Apk, packages: List<String>, snapshot: Boolean = true): Outcome<Completed> {
        if (environment(context).installed() == null) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "сначала установите Linux")
        val spec = when (val s = LinuxEnvironment.apk(action, packages)) {
            is Outcome.Failure -> return s
            is Outcome.Success -> s.value
        }
        // Снимок окружения перед правкой (D-194): откат вернёт состояние до неё.
        if (snapshot) com.dpsmpx.dpai.android.EnvSnapshotStore.take(context, com.dpsmpx.dpai.ui.EnvSnapshots.label(action, packages))
        if (action == LinuxEnvironment.Apk.ADD) {
            (com.dpsmpx.dpai.android.StorageSpace.check(context, com.dpsmpx.dpai.storage.space.SpaceGuard.Kind.PACKAGES, null, packages.joinToString(" ")) as? Outcome.Failure)?.let { return it }
        }
        refreshDns(context)
        // Повреждённые прежде пакеты валят итог любой команды (D-158): сначала — их починка.
        if (action != LinuxEnvironment.Apk.REPAIR) (repairBroken(context, force = true) as? Outcome.Failure)?.let { return it }
        return host(context).run(spec, 15.minutes, maxStdoutChars = 200_000).also { com.dpsmpx.dpai.android.StorageMap.refresh(context) }
    }

    /**
     * Только чтение пакетов (D-197, проверка обновлений): `apk update` и `apk version` — без
     * починки повреждённых, без снимка и без вопроса о месте: в фоне ничего не меняется,
     * кроме индексов репозиториев в кеше.
     */
    fun apkReadOnly(context: Context, action: LinuxEnvironment.Apk): Outcome<Completed> {
        if (action != LinuxEnvironment.Apk.UPDATE && action != LinuxEnvironment.Apk.LIST_UPGRADES) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "в фоне — только чтение пакетов")
        if (environment(context).installed() == null) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "Linux не установлен")
        val spec = (LinuxEnvironment.apk(action, emptyList()) as? Outcome.Success)?.value ?: return Outcome.failure(DiagCode.VALUE_INVALID, "команда apk не собрана")
        refreshDns(context)
        return host(context).run(spec, 10.minutes, maxStdoutChars = 200_000)
    }

    /**
     * Шаги установки в госте по очереди (Claude Code, Codex и Gemini, llama.cpp): `apk` — без
     * `/proc` (D-82), остальное — с ним; первый неудачный шаг — итог. Вывод — всех шагов, для
     * экрана; DNS — сети телефона.
     */
    fun runSteps(context: Context, steps: List<ProcessSpec>, snapshot: Boolean = true): Outcome<Completed> {
        if (environment(context).installed() == null) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "сначала установите Linux")
        // Снимок окружения (D-194), если шаги трогают пакеты.
        if (snapshot && steps.any { it.command.firstOrNull() == "apk" }) {
            com.dpsmpx.dpai.android.EnvSnapshotStore.take(context, "Установка: " + steps.last().command.joinToString(" ").take(80))
        }
        refreshDns(context)
        if (steps.any { ProotHost.needsNoProc(it.command) }) (repairBroken(context, force = true) as? Outcome.Failure)?.let { return it }
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        var cut = false
        for (spec in steps) {
            val r = when (val one = host(context).run(spec, 20.minutes, maxStdoutChars = 200_000)) {
                is Outcome.Failure -> return one
                is Outcome.Success -> one.value
            }
            out += r.stdout
            err += r.stderr
            cut = cut || r.truncated
            if (!r.ok) return Outcome.success(Completed(r.exitCode, out, err, cut))
        }
        return Outcome.success(Completed(0, out, err, cut))
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
        // 24-битный цвет (терминал:A-23): COLORTERM — как у kitty и Windows Terminal.
        // Секреты, выданные ИИ автором (DPmetaApp:D-114), — и терминалу; слот во вкладке убирает перекрывающие вход сам (`env -u`).
        // Свои переменные автора (D-193) — под окружением гостя: оно сильнее.
        val env = guestEnvironment(context, com.dpsmpx.dpai.android.OwnVariablesStore.under(
            context,
            ProotHost.GUEST_ENVIRONMENT + com.dpsmpx.dpai.android.SecretGrants.environment(context) +
                ("TERM" to "xterm-256color") + ("COLORTERM" to com.dpsmpx.dpai.ui.TerminalLaunch.COLORTERM),
        ))
        val spec = ProcessSpec(argv, environment = env)
        val wrapped = host(context).wrap(spec)
        return JniProcesses().spawn(wrapped.command, wrapped.environment.orEmpty(), null, size)
    }

    /** Каталог агента target «в приложении»: в данных приложения, гостю виден по тому же пути. */
    private const val AGENT_HOME = "agent-in-app"

    /** Та же настройка, которую агент перечитывает перед каждым новым ИИ-ходом (D-125). */
    fun sharedToolsStore(context: Context): com.dpsmpx.dpai.orchestrator.agent.SharedToolsStore =
        com.dpsmpx.dpai.orchestrator.agent.SharedToolsStore(NioVfs(File(context.filesDir, AGENT_HOME).toPath()))

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
            val app = context.applicationContext
            return com.dpsmpx.dpai.orchestrator.agent.InAppAgentHost.create(
                home, Clock.SYSTEM, version ?: "dev", JniProcesses(), guestHost(context)!!, ProotHost.GUEST_ENVIRONMENT,
                // Секреты, выданные ИИ автором (DPmetaApp:D-114; D-144): на каждый запуск, не в документ хода.
                // Свои переменные (D-193) — на каждый запуск тем же путём; секрет с тем же именем сильнее (имена не пересекаются по проверке).
                secrets = { com.dpsmpx.dpai.android.OwnVariablesStore.map(app) + com.dpsmpx.dpai.android.SecretGrants.environment(app) },
            )
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
                // Свои переменные автора (D-193) — шагам профилей и Git проекта, под окружением процесса: оно сильнее.
                val withOwn = spec.copy(environment = guestEnvironment(app, com.dpsmpx.dpai.android.OwnVariablesStore.under(app, spec.environment ?: ProotHost.GUEST_ENVIRONMENT)))
                return ProotHost(NativeProcessHost(JniProcesses(), Clock.SYSTEM), layout.copy(binds = layout.binds + ProotLayout.Bind(home, home))).start(withOwn, onOutput)
            }
        }
    }

    /**
     * Процессы-собеседники гостя (D-137): языковая служба рядом с деревом,
     * с той же раскладкой и привязками, что у [guestHost]. Сети службе не
     * нужно — DNS не трогается. Linux не установлен — `null`.
     */
    fun guestStreams(context: Context): com.dpsmpx.dpai.execution.process.StreamHost? {
        if (!available(context) || environment(context).installed() == null) return null
        val home = File(context.filesDir, AGENT_HOME).apply { mkdirs() }.path
        val app = context.applicationContext
        val native = JniProcesses()
        return com.dpsmpx.dpai.execution.process.NativeStreamHost(native, wrap = { spec ->
            val layout = layout(app)
            val withOwn = spec.copy(environment = guestEnvironment(app, com.dpsmpx.dpai.android.OwnVariablesStore.under(app, spec.environment ?: ProotHost.GUEST_ENVIRONMENT)))
            ProotHost(NativeProcessHost(native, Clock.SYSTEM), layout.copy(binds = layout.binds + ProotLayout.Bind(home, home))).wrap(withOwn)
        })
    }

    private fun guestEnvironment(context: Context, base: Map<String, String>): Map<String, String> {
        val rootfs = File(context.filesDir, environment(context).rootfs)
        return com.dpsmpx.dpai.ui.AndroidSdk.discoveredEnv(base) { File(rootfs, it.removePrefix("/")).isFile }
    }

    /** Завершить оболочку: процесс-лидер сеанса получает HUP, proot с `--kill-on-exit` уносит гостя с собой. */
    fun hangUp(child: NativeChild) {
        JniProcesses().kill(child.pid, com.dpsmpx.dpai.execution.linux.Signals.HUP)
    }
}
