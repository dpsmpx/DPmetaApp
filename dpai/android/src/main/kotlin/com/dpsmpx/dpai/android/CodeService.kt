package com.dpsmpx.dpai.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.StreamProcess
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.lsp.Clangd
import com.dpsmpx.dpai.lsp.CodeLanguage
import com.dpsmpx.dpai.lsp.DiagnosticsBoard
import com.dpsmpx.dpai.lsp.DocumentUri
import com.dpsmpx.dpai.lsp.KotlinLsp
import com.dpsmpx.dpai.lsp.LanguageServers
import com.dpsmpx.dpai.lsp.LanguageSession
import com.dpsmpx.dpai.lsp.LspClient
import com.dpsmpx.dpai.lsp.LspDiagnostic
import com.dpsmpx.dpai.lsp.PythonServers
import com.dpsmpx.dpai.lsp.ServerLaunch
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.ui.CodeServerSetup
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Языковые службы редактора в Linux приложения (D-137, D-142;
 * DPmetaApp:D-105, D-110): C и C++ — clangd; Python — лёгкая служба pylsp
 * или pyright по выбору, обе вместе с ruff; Kotlin — kotlin-lsp JetBrains.
 * Одна служба на проект и язык, пока открыт хотя бы один его файл.
 * Включается человеком, ставится отдельной кнопкой; всё — вне потока
 * интерфейса, состояние и диагностика возвращаются в него.
 */
internal object CodeService {
    /** Что можно поставить кнопкой; [note] — что и откуда придёт, показывается до установки. */
    /** Службы кнопкой; [need] — сколько займёт на диске (для защиты места DPmetaApp:D-113), по описанию службы. */
    enum class Install(val title: String, val note: String, val need: Long = 0) {
        CLANGD("Поставить clangd", "Пакет Alpine clang-extra-tools: нужна сеть и около 400 МБ на диске, это несколько минут.", 400L shl 20),
        PYTHON_LIGHT("Поставить лёгкую службу", "Пакеты Alpine py3-lsp-server и ruff: около 50 МБ. Дополнение, переходы, переименование — Jedi; замечания, исправления и формат — ruff. Без проверки типов.", 50L shl 20),
        PYTHON_HEAVY("Поставить pyright", "Node.js из Alpine и pyright ${PythonServers.PYRIGHT_VERSION} из реестра npm со сверкой SHA-256, плюс ruff: около 130 МБ. Проверка типов; памяти нужно больше, чем лёгкой службе.", 130L shl 20),
        KOTLIN(
            "Поставить kotlin-lsp",
            "Последняя сборка официальной службы JetBrains с download.jetbrains.com со сверкой SHA-256 (около 370 МБ загрузки, около 1 ГБ на диске), RocksDB для musl с Maven Central (84 МБ) и JDK 25/21 Alpine. " +
                "Сборки EAP перестают запускаться примерно через 30 дней — тогда «Обновить». Статистика и отчёты JetBrains выключены (--data-sharing=none).",
            1536L shl 20,
        ),
        KOTLIN_UPDATE("Обновить kotlin-lsp", KOTLIN.note, 1536L shl 20),
        ANDROID_SDK("Поставить платформу Android", "Платформа Android SDK уровня compileSdk проекта из репозитория Google (около 65 МБ) — после того, как вы примете её лицензию.", 65L shl 20),
    }

    sealed interface State {
        data object Off : State
        data class Starting(val step: String) : State

        /** Службы нет в Linux приложения: что поставить; [switchTo] — другая служба Python, если эта не поставлена. */
        data class Missing(val message: String, val actions: List<Install>, val switchTo: PythonServers.Kind? = null) : State

        /** [note] — чего службе не хватает или что сейчас идёт; [actions] — что можно поставить; [switchTo] — другая служба Python. */
        data class Ready(val server: String, val note: String?, val actions: List<Install> = emptyList(), val switchTo: PythonServers.Kind? = null) : State
        data class Failed(val message: String, val actions: List<Install> = emptyList()) : State
    }

    class Session internal constructor(val file: String) {
        var state by mutableStateOf<State>(State.Off)
            internal set

        /** Диагностика по адресу файла — объединение служб. */
        val diagnostics = mutableStateMapOf<String, List<LspDiagnostic>>()
        val uri: String = DocumentUri.ofPath(file)
        val language: CodeLanguage? = LanguageServers.language(file)

        @Volatile
        internal var service: LanguageSession? = null

        @Volatile
        internal var key: String? = null

        /** compileSdk Android-проекта файла — для установки платформы. */
        @Volatile
        var compileSdk: Int? = null
            internal set

        @Volatile
        internal var pulledText: String? = null

        /** Службы, если работают; `null` — не запущены. */
        fun ready(): LanguageSession? = service?.takeIf { it.isRunning && state is State.Ready }
    }

    /** Пакет Alpine с clangd. */
    const val PACKAGE = "clang-extra-tools"

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private class Service(val session: LanguageSession, val processes: List<StreamProcess>, val ready: State.Ready, var files: Int, val board: DiagnosticsBoard)

    /** Работающие службы по ключу «язык|корень». */
    private val running = HashMap<String, Service>()

    /** Сеансы по ключу — только в потоке интерфейса: им приходят диагностика и остановка. */
    private val owners = HashMap<String, MutableList<Session>>()

    fun session(guestPath: String): Session = Session(guestPath)

    private const val PREFS = "dpai-code-service"
    private const val PYTHON_KIND = "python.kind"

    fun pythonKind(context: Context): PythonServers.Kind =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PYTHON_KIND, null) == PythonServers.Kind.HEAVY.name) PythonServers.Kind.HEAVY else PythonServers.Kind.LIGHT

    fun setPythonKind(context: Context, kind: PythonServers.Kind) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PYTHON_KIND, kind.name).apply()
    }

    private fun other(kind: PythonServers.Kind) = if (kind == PythonServers.Kind.LIGHT) PythonServers.Kind.HEAVY else PythonServers.Kind.LIGHT

    /**
     * Запустить службу для файла [session] (вне потока интерфейса): найти
     * проект до [stopDir], проверить, что служба поставлена, запустить и
     * поздороваться. Проект уже обслуживается — файл подключается к ней.
     */
    fun start(context: Context, session: Session, stopDir: String) {
        fun state(s: State) {
            main.post { session.state = s }
        }
        val language = session.language ?: return state(State.Failed("для этого файла семантической службы нет"))
        state(State.Starting("ищу проект"))
        val host = LinuxSetup.guestHost(context) ?: return state(State.Failed("Linux в приложении не установлен: «AI» → «Настройки AI» → «Linux в приложении»"))
        val plan = when (language) {
            CodeLanguage.CPP -> cpp(host, session, stopDir)
            CodeLanguage.PYTHON -> python(context, host, session, stopDir)
            CodeLanguage.KOTLIN -> kotlin(host, session, stopDir, ::state)
        }
        val (key, launches, ready) = when (plan) {
            is Plan.Stop -> return state(plan.state)
            is Plan.Go -> Triple(plan.key, plan.launches, plan.ready)
        }
        synchronized(lock) {
            running[key]?.let { service ->
                if (service.session.isRunning) {
                    service.files++
                    session.service = service.session
                    session.key = key
                    main.post {
                        owners.getOrPut(key) { mutableListOf() } += session
                        session.state = service.ready
                    }
                    return
                }
                running.remove(key)
            }
        }
        state(State.Starting("запускаю ${launches.joinToString(" и ") { it.name }}"))
        if (language == CodeLanguage.KOTLIN) LinuxSetup.refreshDns(context)
        val streams = LinuxSetup.guestStreams(context) ?: return state(State.Failed("Linux в приложении не установлен"))
        val root = key.substringAfter('|')
        val board = DiagnosticsBoard()
        val processes = mutableListOf<StreamProcess>()
        val clients = mutableListOf<LspClient>()
        fun abandon(message: String) {
            clients.forEach { it.stop() }
            processes.forEach { it.stop(2.seconds) }
            state(State.Failed(message))
        }
        for ((index, launch) in launches.withIndex()) {
            // Переменные — приставкой env: свои переменные гостя (PATH и др.) остаются.
            val argv = if (launch.environment.isEmpty()) launch.command else listOf("env") + launch.environment.map { (k, v) -> "$k=$v" } + launch.command
            val process = when (val r = streams.open(ProcessSpec(argv, workDir = root))) {
                is Outcome.Failure -> return abandon(r.diagnostics.first().message)
                is Outcome.Success -> r.value
            }
            processes += process
            val client = LspClient(
                process.stdout, process.stdin, DocumentUri.ofPath(root),
                object : LspClient.Listener {
                    override fun diagnostics(uri: String, version: Int?, items: List<LspDiagnostic>) {
                        val merged = board.put(index, uri, items)
                        main.post { owners[key].orEmpty().forEach { it.diagnostics[uri] = merged } }
                    }

                    override fun stopped(reason: Diagnostic) = stop(key, "${launch.name} остановилась: ${reason.message}")
                },
                settings = launch.settings,
            )
            clients += client
            when (val r = client.start(if (language == CodeLanguage.KOTLIN) 3.minutes else LspClient.START_TIMEOUT)) {
                is Outcome.Failure -> return abandon("${launch.name} не ответила: ${r.diagnostics.first().message}")
                is Outcome.Success -> Unit
            }
        }
        val pulls = clients.filterIndexed { i, _ -> launches[i].pull }.toSet()
        val languageSession = LanguageSession(clients.first(), clients.getOrNull(1), pulls)
        val info = clients.first().info
        val shown = ready.copy(server = listOfNotNull(info?.name, info?.version).joinToString(" ").ifEmpty { ready.server }.take(60))
        session.service = languageSession
        session.key = key
        synchronized(lock) { running[key] = Service(languageSession, processes, shown, 1, board) }
        main.post {
            owners.getOrPut(key) { mutableListOf() } += session
            session.state = shown
        }
    }

    private sealed interface Plan {
        data class Stop(val state: State) : Plan
        data class Go(val key: String, val launches: List<ServerLaunch>, val ready: State.Ready) : Plan
    }

    private fun installed(host: ProcessHost, check: String): Outcome<Boolean> =
        when (val r = host.run(ProcessSpec(listOf("sh", "-c", check)), 20.seconds)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(r.value.ok)
        }

    private fun cpp(host: ProcessHost, session: Session, stopDir: String): Plan {
        val dir = session.file.substringBeforeLast('/').ifEmpty { "/" }
        val project = when (val r = host.run(ProcessSpec(Clangd.locateCommand(dir, stopDir)), 20.seconds)) {
            is Outcome.Failure -> return Plan.Stop(State.Failed(r.diagnostics.first().message))
            is Outcome.Success -> Clangd.parseProject(r.value.stdout) ?: Clangd.Project(dir, null)
        }
        when (val r = installed(host, "command -v ${Clangd.PROGRAM}")) {
            is Outcome.Failure -> return Plan.Stop(State.Failed(r.diagnostics.first().message))
            is Outcome.Success -> if (!r.value) return Plan.Stop(State.Missing("clangd не установлен в Linux приложения", listOf(Install.CLANGD)))
        }
        val note = if (project.compileCommandsDir == null) {
            "Нет compile_commands.json: служба не знает флагов сборки и заголовков проекта — подсказки неполны (CMake: -DCMAKE_EXPORT_COMPILE_COMMANDS=ON)"
        } else {
            null
        }
        return Plan.Go("cpp|${project.root}", listOf(ServerLaunch(Clangd.PROGRAM, Clangd.command(project.compileCommandsDir))), State.Ready(Clangd.PROGRAM, note))
    }

    private fun locate(host: ProcessHost, session: Session, stopDir: String): Outcome<CodeServerSetup.Located> {
        if (!stopDir.startsWith('/') || !session.file.startsWith('/')) return Outcome.failure(DiagCode.VALUE_INVALID, "пути службы — абсолютные")
        return when (val r = host.run(ProcessSpec(CodeServerSetup.locate(stopDir, session.file)), 30.seconds)) {
            is Outcome.Failure -> r
            is Outcome.Success -> CodeServerSetup.parseLocated(r.value.stdout)?.let { Outcome.success(it) }
                ?: Outcome.failure(DiagCode.VALUE_INVALID, "проект файла не найден: ${(r.value.stderr + r.value.stdout).lastOrNull().orEmpty()}")
        }
    }

    private fun python(context: Context, host: ProcessHost, session: Session, stopDir: String): Plan {
        val found = when (val r = locate(host, session, stopDir)) {
            is Outcome.Failure -> return Plan.Stop(State.Failed(r.diagnostics.first().message))
            is Outcome.Success -> r.value
        }
        val kind = pythonKind(context)
        val check = when (kind) {
            PythonServers.Kind.LIGHT -> "command -v ${PythonServers.PYLSP} >/dev/null && command -v ${PythonServers.RUFF} >/dev/null"
            PythonServers.Kind.HEAVY -> "command -v ${PythonServers.NODE} >/dev/null && command -v ${PythonServers.RUFF} >/dev/null && test -f '${PythonServers.pyrightServer(CodeServerSetup.PYRIGHT_HOME)}'"
        }
        when (val r = installed(host, check)) {
            is Outcome.Failure -> return Plan.Stop(State.Failed(r.diagnostics.first().message))
            is Outcome.Success -> if (!r.value) {
                val action = if (kind == PythonServers.Kind.LIGHT) Install.PYTHON_LIGHT else Install.PYTHON_HEAVY
                return Plan.Stop(State.Missing("Python: служба «${kind.title}» не установлена", listOf(action), switchTo = other(kind)))
            }
        }
        val primary = when (kind) {
            PythonServers.Kind.LIGHT -> PythonServers.pylsp(found.python)
            PythonServers.Kind.HEAVY -> PythonServers.pyright(CodeServerSetup.PYRIGHT_HOME, found.python)
        }
        val note = "Python: ${kind.title}; интерпретатор — ${found.python ?: "не найден: модули проекта служба не увидит"}"
        return Plan.Go("python-${kind.name}|${found.root}", listOf(primary, PythonServers.ruff()), State.Ready(primary.name, note, switchTo = other(kind)))
    }

    private fun kotlin(host: ProcessHost, session: Session, stopDir: String, state: (State) -> Unit): Plan {
        val found = when (val r = locate(host, session, stopDir)) {
            is Outcome.Failure -> return Plan.Stop(State.Failed(r.diagnostics.first().message))
            is Outcome.Success -> r.value
        }
        session.compileSdk = found.compileSdk
        val arch = found.arch ?: return Plan.Stop(State.Failed("архитектура Linux приложения неизвестна"))
        val check = "test -x ${KotlinLsp.JAVA} && test -x ${KotlinLsp.GRADLE_JAVA_HOME}/bin/javac && test -f ${CodeServerSetup.KOTLIN_HOME}/product-info.json"
        when (val r = installed(host, check)) {
            is Outcome.Failure -> return Plan.Stop(State.Failed(r.diagnostics.first().message))
            is Outcome.Success -> if (!r.value) return Plan.Stop(State.Missing("kotlin-lsp не установлен в Linux приложения", listOf(Install.KOTLIN)))
        }
        val files = when (val r = host.run(ProcessSpec(CodeServerSetup.kotlinFilesCommand()), 20.seconds, maxStdoutChars = 262_144)) {
            is Outcome.Failure -> return Plan.Stop(State.Failed(r.diagnostics.first().message))
            is Outcome.Success -> CodeServerSetup.kotlinFiles(r.value.stdout) ?: return Plan.Stop(State.Failed("сборка kotlin-lsp повреждена — обновите её", listOf(Install.KOTLIN_UPDATE)))
        }
        val command = when (val r = KotlinLsp.command(files.first, files.second, CodeServerSetup.KOTLIN_HOME, KotlinLsp.JAVA, CodeServerSetup.KOTLIN_SYSTEM, arch)) {
            is Outcome.Failure -> return Plan.Stop(State.Failed(r.diagnostics.first().message, listOf(Install.KOTLIN_UPDATE)))
            is Outcome.Success -> r.value
        }
        state(State.Starting("проверяю срок сборки kotlin-lsp"))
        val validity = when (val r = host.run(ProcessSpec(KotlinLsp.statusCommand(command)), 2.minutes)) {
            is Outcome.Failure -> return Plan.Stop(State.Failed(r.diagnostics.first().message))
            is Outcome.Success -> KotlinLsp.validity(r.value.exitCode, (r.value.stdout + r.value.stderr).joinToString("\n"))
        }
        if (validity.expired) return Plan.Stop(State.Failed("Сборка kotlin-lsp истекла (EAP работает около 30 дней) — поставьте последнюю", listOf(Install.KOTLIN_UPDATE)))
        val notes = mutableListOf("Импорт Gradle идёт в фоне: первые минуты замечаний может не быть; синхронизация качает зависимости проекта из его репозиториев")
        val actions = mutableListOf<Install>()
        validity.daysLeft?.takeIf { it <= 3 }?.let {
            notes += "сборка kotlin-lsp истекает через $it дн."
            actions += Install.KOTLIN_UPDATE
        }
        if (found.androidProject && found.androidSdk == null) {
            notes += "Android-проект: нет платформы Android SDK${found.compileSdk?.let { " $it" }.orEmpty()} — Android API служба не увидит"
            actions += Install.ANDROID_SDK
        }
        return Plan.Go("kotlin|${found.root}", listOf(KotlinLsp.launch(command, found.androidSdk)), State.Ready("kotlin-lsp", notes.joinToString(". "), actions))
    }

    /** Диагностика по запросу у служб, которые её не шлют (kotlin-lsp), если текст файла изменился с прошлого раза. Вне потока интерфейса. */
    fun pull(session: Session, text: String) {
        val service = session.ready()?.takeIf { it.pulls } ?: return
        val key = session.key ?: return
        if (session.pulledText == text) return
        session.pulledText = text
        val board = synchronized(lock) { running[key]?.board } ?: return
        for ((index, result) in service.pullDiagnostics(session.uri)) {
            val items = (result as? Outcome.Success)?.value ?: continue
            val merged = board.put(index, session.uri, items)
            main.post { owners[key].orEmpty().forEach { it.diagnostics[session.uri] = merged } }
        }
    }

    private fun stop(key: String, message: String) {
        val service = synchronized(lock) { running.remove(key) }
        service?.processes?.forEach { it.stop(2.seconds) }
        service?.session?.stop()
        main.post {
            owners.remove(key).orEmpty().forEach { s ->
                if (s.state !is State.Off) s.state = State.Failed(message)
                s.service = null
            }
        }
    }

    /** Файл закрыт или служба выключена человеком (вне потока интерфейса); последний файл проекта останавливает службы. */
    fun release(session: Session) {
        val key = session.key
        val service = session.service
        session.service = null
        session.pulledText = null
        main.post {
            session.state = State.Off
            session.diagnostics.clear()
            if (key != null) owners[key]?.remove(session)
        }
        if (key == null || service == null) return
        service.close(session.uri)
        val last = synchronized(lock) {
            val svc = running[key]?.takeIf { it.session === service } ?: return
            if (--svc.files > 0) return
            running.remove(key)
            svc
        }
        last.session.stop()
        last.processes.forEach { it.stop(2.seconds) }
    }

    private fun done(result: Outcome<Completed>, marker: String?): Outcome<Unit> = when (result) {
        is Outcome.Failure -> result
        is Outcome.Success -> if (result.value.ok && (marker == null || marker in result.value.stdout)) {
            Outcome.success(Unit)
        } else {
            val reason = (result.value.stderr + result.value.stdout).lastOrNull { it.isNotBlank() } ?: "код ${result.value.exitCode}"
            Outcome.failure(DiagCode.PROCESS_EXIT_NONZERO, reason.take(300))
        }
    }

    private fun apkAdd(packages: List<String>): Outcome<ProcessSpec> = LinuxEnvironment.apk(LinuxEnvironment.Apk.ADD, packages)

    /**
     * Поставить службу кнопкой человека (D-142) — вне потока интерфейса.
     * [Install.ANDROID_SDK] ставится через [androidPlatform] и
     * [installAndroid]: сначала человек принимает лицензию.
     */
    fun install(context: Context, what: Install): Outcome<Unit> {
        // Предел хранилища и жёсткий запас 1 ГБ (DPmetaApp:D-113) — до загрузки.
        (StorageSpace.check(context, com.dpsmpx.dpai.storage.space.SpaceGuard.Kind.SERVICE, what.need.takeIf { it > 0 }, what.title) as? Outcome.Failure)?.let { return it }
        return installChecked(context, what).also { if (it is Outcome.Success) StorageMap.refresh(context) }
    }

    private fun installChecked(context: Context, what: Install): Outcome<Unit> = when (what) {
        Install.CLANGD -> done(LinuxSetup.apk(context, LinuxEnvironment.Apk.ADD, listOf(PACKAGE)), null)
        Install.PYTHON_LIGHT -> done(LinuxSetup.apk(context, LinuxEnvironment.Apk.ADD, PythonServers.LIGHT_PACKAGES), null).also {
            if (it is Outcome.Success) setPythonKind(context, PythonServers.Kind.LIGHT)
        }
        Install.PYTHON_HEAVY -> when (val apk = apkAdd(PythonServers.HEAVY_PACKAGES)) {
            is Outcome.Failure -> apk
            is Outcome.Success -> done(LinuxSetup.runSteps(context, listOf(apk.value, ProcessSpec(CodeServerSetup.pyrightInstall()))), "DPAI_PYRIGHT_OK").also {
                if (it is Outcome.Success) setPythonKind(context, PythonServers.Kind.HEAVY)
            }
        }
        Install.KOTLIN, Install.KOTLIN_UPDATE -> installKotlin(context)
        Install.ANDROID_SDK -> Outcome.failure(DiagCode.UNSUPPORTED, "платформа Android ставится после принятия лицензии")
    }

    private fun installKotlin(context: Context): Outcome<Unit> {
        val apk = when (val r = apkAdd(KotlinLsp.PACKAGES)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        done(LinuxSetup.runSteps(context, listOf(apk)), null).let { if (it is Outcome.Failure) return it }
        val host = LinuxSetup.guestHost(context) ?: return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "сначала установите Linux")
        val build = when (val r = host.run(ProcessSpec(CodeServerSetup.kotlinLatest()), 90.seconds)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> CodeServerSetup.latestBuild(r.value.stdout)
                ?: return Outcome.failure(DiagCode.VALUE_INVALID, "страница выпусков kotlin-lsp не назвала сборку")
        }
        val arch = when (val r = host.run(ProcessSpec(listOf("uname", "-m")), 20.seconds)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value.stdout.firstOrNull()?.trim().orEmpty()
        }
        val command = when (val r = CodeServerSetup.kotlinInstall(build, arch)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return done(LinuxSetup.runSteps(context, listOf(ProcessSpec(command))), "DPAI_KOTLIN_LSP_OK")
    }

    /** Платформа Android для compileSdk [api] из манифеста Google — с текстом её лицензии для показа человеку. */
    fun androidPlatform(context: Context, api: Int): Outcome<CodeServerSetup.AndroidPlatform> {
        val host = LinuxSetup.guestHost(context) ?: return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "сначала установите Linux")
        val tools = when (val r = apkAdd(listOf("curl", "unzip", "ca-certificates"))) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        done(LinuxSetup.runSteps(context, listOf(tools)), null).let { if (it is Outcome.Failure) return it }
        val xml = when (val r = host.run(ProcessSpec(CodeServerSetup.androidManifest()), 3.minutes, maxStdoutChars = 4_000_000)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> if (r.value.ok && !r.value.truncated) r.value.stdout.joinToString("\n") else return Outcome.failure(DiagCode.PROCESS_EXIT_NONZERO, "манифест Android SDK не получен")
        }
        return CodeServerSetup.androidPlatform(xml, api)?.let { Outcome.success(it) }
            ?: Outcome.failure(DiagCode.VALUE_INVALID, "в репозитории Google нет стабильной платформы android-$api")
    }

    /** Поставить платформу после того, как человек принял её лицензию. */
    fun installAndroid(context: Context, platform: CodeServerSetup.AndroidPlatform): Outcome<Unit> =
        done(LinuxSetup.runSteps(context, listOf(ProcessSpec(CodeServerSetup.androidInstall(platform)))), "DPAI_ANDROID_SDK_OK")
}
