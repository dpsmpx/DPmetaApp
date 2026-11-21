package com.dpsmpx.dpai.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
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
import com.dpsmpx.dpai.lsp.EditorSessions
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
            "Проверенная сборка ${KotlinLsp.PINNED_BUILD} официальной службы JetBrains: SHA-256 закреплена в приложении (около 370 МБ загрузки, около 1 ГБ на диске), RocksDB для musl с Maven Central (84 МБ) и JDK 25/21 Alpine. " +
                "Сборки EAP перестают запускаться примерно через 30 дней — новая проверенная версия приходит с обновлением DP_IDE. Статистика и отчёты JetBrains выключены (--data-sharing=none).",
            1536L shl 20,
        ),
        KOTLIN_UPDATE("Поставить проверенную версию", KOTLIN.note, 1536L shl 20),
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

    class Session internal constructor(val file: String, internal val ownerKey: String? = null) {
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

        @Volatile
        internal var closed: Boolean = false

        /** Найденный проект — также для ручного включения индекса после отказа службы. */
        var cppProject by mutableStateOf<Clangd.Project?>(null)
            internal set

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

    private class Service(val session: LanguageSession, val processes: List<StreamProcess>, val ready: State.Ready, val files: MutableSet<Session>, val board: DiagnosticsBoard, val generation: Any, val language: CodeLanguage, val owners: MutableSet<String>)

    /** Работающие службы по ключу «язык|корень». */
    private val running = HashMap<String, Service>()
    // Резервируется под тем же monitor, что start(); длительная загрузка monitor не держит.
    private var kotlinInstalling = false

    private val editors = EditorSessions<Session>()

    /** Поток UI; регистрация не ждёт монитор start(), который выполняет медленный ввод-вывод. */
    fun session(guestPath: String, ownerKey: String? = null, root: String? = null, path: String? = null): Session {
        val session = if (ownerKey != null && root != null && path != null) {
            editors.acquire(ownerKey, root, path) { Session(guestPath, ownerKey) }.let {
                if (it.retired.isNotEmpty()) retire(it.retired)
                it.session
            }
        } else Session(guestPath)
        if (!session.closed) shownSessions[guestPath] = session
        return session
    }

    fun enterEditor(key: String, owner: Any) = editors.enter(key, owner)

    fun retainEditor(key: String, owner: Any, paths: Set<String>) {
        retire(editors.retain(key, owner, paths))
    }

    fun leaveEditor(key: String, owner: Any) {
        // Новый экземпляр Activity успевает перенять вкладки; старый токен его не закроет.
        main.postDelayed({ retire(editors.close(key, owner)) }, 5_000)
    }

    private fun retire(sessions: List<Session>) {
        sessions.forEach(::forget)
        kotlin.concurrent.thread(name = "dpai-code-tabs-release") {
            sessions.forEach(::release)
            prune()
        }
    }

    @Synchronized
    private fun prune() {
        val unused = synchronized(lock) {
            running.filter { (key, service) -> service.files.isEmpty() && !held(key, service) }
                .map { (key, service) -> key to service.generation }
        }
        unused.forEach { (key, generation) -> stop(key, "Проект закрыт", generation) }
    }

    private fun held(key: String, service: Service): Boolean =
        service.owners.any { editors.keeps(it, key.substringAfter('|'), service.language) }

    private fun attach(key: String, service: Service, session: Session) {
        service.files += session
        session.ownerKey?.let { service.owners += it }
        session.service = service.session
        session.key = key
        main.post {
            if (!session.closed && session.service === service.session) {
                session.state = service.ready
                session.diagnostics[session.uri] = service.board.merged(session.uri)
            }
        }
    }

    /** Подключение к работающей службе после определения корня; без нового LSP-сервера и импорта. */
    @Synchronized
    fun resume(context: Context, session: Session, stopDir: String) {
        if (session.closed || session.ready() != null || synchronized(lock) { running.isEmpty() }) return
        val language = session.language ?: return
        val host = LinuxSetup.guestHost(context) ?: return
        val key = if (language == CodeLanguage.CPP) {
            val dir = session.file.substringBeforeLast('/').ifEmpty { "/" }
            val result = host.run(ProcessSpec(Clangd.locateCommand(dir, stopDir)), 20.seconds) as? Outcome.Success ?: return
            val project = Clangd.parseProject(result.value.stdout) ?: Clangd.Project(dir, null)
            main.post { if (!session.closed) session.cppProject = project }
            "cpp|${project.root}"
        } else {
            val found = (locate(host, session, stopDir) as? Outcome.Success)?.value ?: return
            session.compileSdk = found.compileSdk
            if (language == CodeLanguage.PYTHON) "python-${pythonKind(context).name}|${found.root}" else "kotlin|${found.root}"
        }
        if (session.closed) return
        synchronized(lock) {
            running[key]?.takeIf { it.session.isRunning }?.let { attach(key, it, session) }
        }
    }

    /** Сеанс службы, показанный сейчас для файла [guestPath] (поток интерфейса): «В ИИ» берёт у него замечания и символы (D-165). */
    fun sessionOf(guestPath: String): Session? = shownSessions[guestPath]

    /** Сеанс больше не показан (поток интерфейса). */
    fun forget(session: Session) {
        session.closed = true
        if (shownSessions[session.file] === session) shownSessions.remove(session.file)
    }

    private val shownSessions = HashMap<String, Session>()

    private const val PREFS = "dpai-code-service"
    private const val PYTHON_KIND = "python.kind"
    private const val CPP_INDEX = "cpp.index.roots"

    fun cppIndex(context: Context, root: String): Boolean = root in
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(CPP_INDEX, emptySet()).orEmpty()

    /** Меняет только явно выбранный проект; перезапуск нужен всем открытым файлам этого проекта. */
    @SuppressLint("UseKtx") // Нужен Boolean commit(): KTX его отбрасывает, а ошибка записи должна отменить переключение.
    @Synchronized
    fun setCppIndex(context: Context, session: Session, enabled: Boolean): Outcome<Unit> {
        val project = session.cppProject ?: return Outcome.failure(DiagCode.VALUE_INVALID, "сначала запустите службу для определения проекта")
        if (enabled) {
            val space = StorageSpace.check(context, com.dpsmpx.dpai.storage.space.SpaceGuard.Kind.BUILD, null, "индекс C++")
            if (space is Outcome.Failure) return space
            val command = when (val r = Clangd.prepareIndex(project)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            val host = LinuxSetup.guestHost(context) ?: return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "сначала установите Linux")
            val result = done(host.run(ProcessSpec(command), 20.seconds), "DPAI_CPP_INDEX_READY")
            if (result is Outcome.Failure) return result
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(CPP_INDEX, emptySet()).orEmpty().toSet()
        val roots = previous.toMutableSet()
        if (enabled) roots += project.root else roots -= project.root
        if (!prefs.edit().putStringSet(CPP_INDEX, roots).commit()) {
            // SharedPreferences меняет RAM даже при отказе диска; восстановить и её прежнее значение.
            prefs.edit { putStringSet(CPP_INDEX, previous) }
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "не удалось сохранить настройку индекса")
        }
        stop("cpp|${project.root}", "Режим индекса изменён; запустите службу снова")
        return Outcome.success(Unit)
    }

    fun pythonKind(context: Context): PythonServers.Kind =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PYTHON_KIND, null) == PythonServers.Kind.HEAVY.name) PythonServers.Kind.HEAVY else PythonServers.Kind.LIGHT

    fun setPythonKind(context: Context, kind: PythonServers.Kind) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(PYTHON_KIND, kind.name) }
    }

    private fun other(kind: PythonServers.Kind) = if (kind == PythonServers.Kind.LIGHT) PythonServers.Kind.HEAVY else PythonServers.Kind.LIGHT

    /**
     * Запустить службу для файла [session] (вне потока интерфейса): найти
     * проект до [stopDir], проверить, что служба поставлена, запустить и
     * поздороваться. Проект уже обслуживается — файл подключается к ней.
     */
    @Synchronized
    fun start(context: Context, session: Session, stopDir: String) {
        if (session.closed) return
        fun state(s: State) {
            main.post { if (!session.closed) session.state = s }
        }
        val language = session.language ?: return state(State.Failed("для этого файла семантической службы нет"))
        if (language == CodeLanguage.KOTLIN && kotlinInstalling) return state(State.Failed(context.getString(R.string.kotlin_install_busy)))
        state(State.Starting("ищу проект"))
        val host = LinuxSetup.guestHost(context) ?: return state(State.Failed("Linux в приложении не установлен: «AI» → «Настройки AI» → «Linux в приложении»"))
        val plan = when (language) {
            CodeLanguage.CPP -> cpp(context, host, session, stopDir)
            CodeLanguage.PYTHON -> python(context, host, session, stopDir)
            CodeLanguage.KOTLIN -> kotlin(host, session, stopDir, ::state)
        }
        val (key, launches, ready) = when (plan) {
            is Plan.Stop -> return state(plan.state)
            is Plan.Go -> Triple(plan.key, plan.launches, plan.ready)
        }
        if (session.closed && session.ownerKey?.let { editors.keeps(it, key.substringAfter('|'), language) } != true) return
        if (session.service != null && session.key != key) release(session)
        val previous = synchronized(lock) { running[key] }
        if (previous != null && !previous.session.isRunning) stop(key, "Служба остановилась", previous.generation)
        synchronized(lock) {
            running[key]?.let { service ->
                if (service.session.isRunning) {
                    if (!session.closed) attach(key, service, session)
                    else session.ownerKey?.let { service.owners += it }
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
        val generation = Any()
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
                        main.post {
                            if (synchronized(lock) { running[key]?.generation === generation }) {
                                val files = synchronized(lock) { running[key]?.files?.toList().orEmpty() }
                                files.filter { !it.closed && it.service != null }.forEach { it.diagnostics[uri] = merged }
                            }
                        }
                    }

                    override fun stopped(reason: Diagnostic) = stop(key, "${launch.name} остановилась: ${reason.message}", generation)
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
        val keep = session.ownerKey?.let { editors.keeps(it, root, language) } == true
        if ((session.closed && !keep) || !languageSession.isRunning) return abandon("Служба остановилась при запуске")
        synchronized(lock) {
            val service = Service(languageSession, processes, shown, mutableSetOf(), board, generation, language, mutableSetOf())
            session.ownerKey?.let { service.owners += it }
            running[key] = service
            if (!session.closed) attach(key, service, session)
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

    private fun cpp(context: Context, host: ProcessHost, session: Session, stopDir: String): Plan {
        val dir = session.file.substringBeforeLast('/').ifEmpty { "/" }
        val project = when (val r = host.run(ProcessSpec(Clangd.locateCommand(dir, stopDir)), 20.seconds)) {
            is Outcome.Failure -> return Plan.Stop(State.Failed(r.diagnostics.first().message))
            is Outcome.Success -> Clangd.parseProject(r.value.stdout) ?: Clangd.Project(dir, null)
        }
        main.post { if (!session.closed) session.cppProject = project }
        when (val r = installed(host, "command -v ${Clangd.PROGRAM}")) {
            is Outcome.Failure -> return Plan.Stop(State.Failed(r.diagnostics.first().message))
            is Outcome.Success -> if (!r.value) return Plan.Stop(State.Missing("clangd не установлен в Linux приложения", listOf(Install.CLANGD)))
        }
        val indexed = cppIndex(context, project.root) && project.compileCommandsDir != null
        val note = if (project.compileCommandsDir == null) {
            "Нет compile_commands.json: служба не знает флагов сборки и заголовков проекта — подсказки неполны (CMake: -DCMAKE_EXPORT_COMPILE_COMMANDS=ON)"
        } else {
            if (indexed) "Индекс проекта включён: первые ответы могут быть неполными, пока идёт разбор; растут расход памяти и кэш на диске" else null
        }
        return Plan.Go("cpp|${project.root}", listOf(ServerLaunch(Clangd.PROGRAM, Clangd.command(project.compileCommandsDir, backgroundIndex = indexed))), State.Ready(Clangd.PROGRAM, note))
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
        val command = when (val r = KotlinLsp.command(files.first, files.second, CodeServerSetup.KOTLIN_HOME, KotlinLsp.JAVA, CodeServerSetup.kotlinSystem(found.root), arch)) {
            is Outcome.Failure -> return Plan.Stop(State.Failed(r.diagnostics.first().message, listOf(Install.KOTLIN_UPDATE)))
            is Outcome.Success -> r.value
        }
        if (KotlinLsp.productBuild(files.first) != KotlinLsp.PINNED_BUILD) {
            return Plan.Stop(State.Failed("Установленная сборка kotlin-lsp не закреплена в этой версии DP_IDE", listOf(Install.KOTLIN_UPDATE)))
        }
        state(State.Starting("проверяю срок сборки kotlin-lsp"))
        val validity = when (val r = host.run(ProcessSpec(KotlinLsp.statusCommand(command)), 2.minutes)) {
            is Outcome.Failure -> return Plan.Stop(State.Failed(r.diagnostics.first().message))
            is Outcome.Success -> KotlinLsp.validity(r.value.exitCode, (r.value.stdout + r.value.stderr).joinToString("\n"))
        }
        if (validity.expired) return Plan.Stop(State.Failed("Срок проверенной сборки kotlin-lsp истёк — обновите DP_IDE для получения новой версии службы"))
        val notes = mutableListOf("Импорт Gradle идёт в фоне: первые минуты замечаний может не быть; синхронизация качает зависимости проекта из его репозиториев")
        val actions = mutableListOf<Install>()
        validity.daysLeft?.takeIf { it <= 3 }?.let {
            notes += "проверенная сборка kotlin-lsp истекает через $it дн.; новая версия службы приходит с обновлением DP_IDE"
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
        val owner = synchronized(lock) { running[key]?.takeIf { it.session === service } } ?: return
        for ((index, result) in service.pullDiagnostics(session.uri)) {
            val items = (result as? Outcome.Success)?.value ?: continue
            val merged = owner.board.put(index, session.uri, items)
            main.post {
                val files = synchronized(lock) { running[key]?.takeIf { it === owner }?.files?.toList().orEmpty() }
                files.filter { !it.closed && it.service === service }.forEach { it.diagnostics[session.uri] = merged }
            }
        }
    }

    private fun stop(key: String, message: String, expected: Any? = null, off: Boolean = false) {
        val service = synchronized(lock) {
            val found = running[key] ?: return
            if (expected != null && found.generation !== expected) return
            running.remove(key)
            found.files.forEach { if (it.service === found.session) { it.service = null; it.key = null; it.pulledText = null } }
            found
        }
        service.processes.forEach { it.stop(2.seconds) }
        service.session.stop()
        main.post {
            service.files.filter { !it.closed && it.service == null }.forEach { s ->
                if (off) s.state = State.Off else if (s.state !is State.Off) s.state = State.Failed(message)
                s.diagnostics.clear()
            }
        }
    }

    /** Явное выключение/перенастройка относится к общей службе проекта, включая фоновые вкладки. */
    @Synchronized
    fun disable(session: Session) {
        val key = session.key
        if (key != null) stop(key, "Служба выключена", off = true) else release(session)
    }

    /** Вкладка закрыта (вне потока интерфейса); последняя вкладка проекта останавливает службы. */
    @Synchronized
    fun release(session: Session) {
        val key = session.key
        val service = session.service
        session.service = null
        session.key = null
        session.pulledText = null
        main.post {
            if (session.service == null) {
                session.state = State.Off
                session.diagnostics.clear()
            }
        }
        if (key == null || service == null) return
        val (closeDocument, last) = synchronized(lock) {
            val svc = running[key]?.takeIf { it.session === service } ?: return
            svc.files -= session
            val closeDocument = svc.files.none { it.uri == session.uri }
            val last = if (svc.files.isEmpty() && !held(key, svc)) running.remove(key) else null
            closeDocument to last
        }
        // Два вида того же файла могут кратко сосуществовать при смене корня UI.
        if (closeDocument) service.close(session.uri)
        last?.session?.stop()
        last?.processes?.forEach { it.stop(2.seconds) }
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
        val kotlin = what == Install.KOTLIN || what == Install.KOTLIN_UPDATE
        if (kotlin) synchronized(this) {
            if (kotlinInstalling) return Outcome.failure(DiagCode.STATE_CONFLICT, context.getString(R.string.kotlin_install_busy))
            kotlinInstalling = true
        }
        return try {
            if (kotlin) synchronized(this) {
                val keys = synchronized(lock) { running.filterValues { it.language == CodeLanguage.KOTLIN }.keys.toList() }
                keys.forEach { stop(it, context.getString(R.string.kotlin_install_stopped), off = true) }
            }
            installChecked(context, what).also { if (it is Outcome.Success) StorageMap.refresh(context) }
        } finally {
            if (kotlin) synchronized(this) { kotlinInstalling = false }
        }
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
        val build = KotlinLsp.PINNED_BUILD
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
