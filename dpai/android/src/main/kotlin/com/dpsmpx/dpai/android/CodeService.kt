package com.dpsmpx.dpai.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.StreamProcess
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.lsp.Clangd
import com.dpsmpx.dpai.lsp.DocumentUri
import com.dpsmpx.dpai.lsp.LspClient
import com.dpsmpx.dpai.lsp.LspDiagnostic
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import kotlin.time.Duration.Companion.seconds

/**
 * Языковая служба C и C++ в Linux приложения (D-137, DPmetaApp:D-105):
 * один clangd на проект, пока открыт хотя бы один его файл. Включается
 * человеком, а не открытием файла; clangd ставится отдельной кнопкой.
 * Все обращения к службе — вне потока интерфейса; состояние и диагностика
 * возвращаются в него.
 */
internal object CodeService {
    sealed interface State {
        data object Off : State
        data class Starting(val step: String) : State

        /** clangd в Linux приложения не установлен. */
        data object Missing : State
        data class Ready(val server: String, val compileCommands: String?) : State
        data class Failed(val message: String) : State
    }

    class Session internal constructor(val file: String) {
        var state by mutableStateOf<State>(State.Off)
            internal set

        /** Диагностика по адресу файла. */
        val diagnostics = mutableStateMapOf<String, List<LspDiagnostic>>()
        val uri: String = DocumentUri.ofPath(file)

        @Volatile
        internal var client: LspClient? = null

        @Volatile
        internal var root: String? = null

        /** Клиент работающей службы; `null` — служба не запущена. */
        fun ready(): LspClient? = client?.takeIf { it.isRunning && state is State.Ready }
    }

    /** Пакет Alpine с clangd. */
    const val PACKAGE = "clang-extra-tools"

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** Работающая служба проекта и число открытых в ней файлов. */
    private class Service(val client: LspClient, val process: StreamProcess, val ready: State.Ready, var files: Int)

    private val running = HashMap<String, Service>()

    fun session(guestPath: String): Session = Session(guestPath)

    /**
     * Запустить службу для файла [session] (вызывать вне потока интерфейса):
     * найти проект вверх до [stopDir], проверить clangd, запустить и
     * поздороваться. Проект уже обслуживается — файл подключается к его службе.
     */
    fun start(context: Context, session: Session, stopDir: String) {
        fun state(s: State) {
            main.post { session.state = s }
        }
        state(State.Starting("ищу проект"))
        val host = LinuxSetup.guestHost(context) ?: return state(State.Failed("Linux в приложении не установлен: «AI» → «Настройки AI» → «Linux в приложении»"))
        val streams = LinuxSetup.guestStreams(context) ?: return state(State.Failed("Linux в приложении не установлен"))
        val dir = session.file.substringBeforeLast('/').ifEmpty { "/" }
        val project = when (val r = host.run(ProcessSpec(Clangd.locateCommand(dir, stopDir)), 20.seconds)) {
            is Outcome.Failure -> return state(State.Failed(r.diagnostics.first().message))
            is Outcome.Success -> Clangd.parseProject(r.value.stdout) ?: Clangd.Project(dir, null)
        }
        synchronized(lock) {
            running[project.root]?.let { service ->
                if (service.client.isRunning) {
                    service.files++
                    session.client = service.client
                    session.root = project.root
                    main.post {
                        owners.getOrPut(project.root) { mutableListOf() } += session
                        session.state = service.ready
                    }
                    return
                }
                running.remove(project.root)
            }
        }
        state(State.Starting("проверяю clangd"))
        when (val r = host.run(ProcessSpec(listOf("sh", "-c", "command -v ${Clangd.PROGRAM}")), 20.seconds)) {
            is Outcome.Failure -> return state(State.Failed(r.diagnostics.first().message))
            is Outcome.Success -> if (!r.value.ok) return state(State.Missing)
        }
        state(State.Starting("запускаю clangd"))
        val process = when (val r = streams.open(ProcessSpec(Clangd.command(project.compileCommandsDir), workDir = project.root))) {
            is Outcome.Failure -> return state(State.Failed(r.diagnostics.first().message))
            is Outcome.Success -> r.value
        }
        val root = project.root
        val client = LspClient(
            process.stdout, process.stdin, DocumentUri.ofPath(root),
            object : LspClient.Listener {
                override fun diagnostics(uri: String, version: Int?, items: List<LspDiagnostic>) {
                    main.post { owners[root].orEmpty().forEach { it.diagnostics[uri] = items } }
                }

                override fun stopped(reason: Diagnostic) {
                    process.stop(2.seconds)
                    synchronized(lock) { running.remove(root) }
                    main.post {
                        owners.remove(root).orEmpty().forEach { s ->
                            if (s.state !is State.Off) s.state = State.Failed("служба остановилась: ${reason.message}")
                            s.client = null
                        }
                    }
                }
            },
        )
        val info = when (val r = client.start()) {
            is Outcome.Failure -> {
                process.stop(2.seconds)
                return state(State.Failed("clangd не ответил: ${r.diagnostics.first().message}"))
            }
            is Outcome.Success -> r.value
        }
        val ready = State.Ready(listOfNotNull(info.name, info.version).joinToString(" ").ifEmpty { Clangd.PROGRAM }.take(60), project.compileCommandsDir)
        session.client = client
        session.root = root
        synchronized(lock) { running[root] = Service(client, process, ready, 1) }
        main.post {
            owners.getOrPut(root) { mutableListOf() } += session
            session.state = ready
        }
    }

    /** Сеансы по корню проекта — только в потоке интерфейса: им приходят диагностика и остановка. */
    private val owners = HashMap<String, MutableList<Session>>()

    /** Файл закрыт или служба выключена человеком (вне потока интерфейса); последний файл проекта останавливает clangd. */
    fun release(session: Session) {
        val root = session.root
        val client = session.client
        session.client = null
        main.post {
            session.state = State.Off
            session.diagnostics.clear()
            if (root != null) owners[root]?.remove(session)
        }
        if (root == null || client == null) return
        client.close(session.uri)
        val last = synchronized(lock) {
            val service = running[root]?.takeIf { it.client === client } ?: return
            if (--service.files > 0) return
            running.remove(root)
            service
        }
        client.stop()
        last.process.stop(2.seconds)
    }

    /** Поставить clangd в Linux приложения — явное действие человека (DPmetaApp:D-105). */
    fun install(context: Context): Outcome<Unit> = when (val r = LinuxSetup.apk(context, LinuxEnvironment.Apk.ADD, listOf(PACKAGE))) {
        is Outcome.Failure -> r
        is Outcome.Success -> if (r.value.ok) {
            Outcome.success(Unit)
        } else {
            Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.PROCESS_EXIT_NONZERO, (r.value.stderr + r.value.stdout).lastOrNull { it.isNotBlank() } ?: "apk: код ${r.value.exitCode}")
        }
    }
}
