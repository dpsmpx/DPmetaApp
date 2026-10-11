package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.then
import com.dpsmpx.dpai.core.ids.RequestId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.notifications.Notification
import com.dpsmpx.dpai.notifications.NotificationDecision
import com.dpsmpx.dpai.notifications.RequestAnswer
import com.dpsmpx.dpai.notifications.RequestKind
import com.dpsmpx.dpai.orchestrator.requests.RequestPurpose
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.ui.RequestCard
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import com.dpsmpx.dpai.ui.RequestScreens
import kotlin.time.Duration
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.ControlView
import com.dpsmpx.dpai.ui.DashboardScreen
import com.dpsmpx.dpai.ui.DemoSystem
import com.dpsmpx.dpai.ui.LiveSystem
import com.dpsmpx.dpai.ui.ProjectEntry
import com.dpsmpx.dpai.ui.SessionScreen
import com.dpsmpx.dpai.ui.TargetEntry
import com.dpsmpx.dpai.ui.TaskBoard
import java.io.File

/** Снимок для отрисовки: всё, что показывает приложение в один кадр. */
data class Snapshot(
    val mode: String,
    val live: Boolean,
    val dashboard: DashboardScreen,
    val board: TaskBoard,
    val session: SessionScreen?,
    /** Новые уведомления контроллера; у демо — только проверочные запросы: телефон не шумит демо-событиями. */
    val notifications: List<Notification> = emptyList(),
    /** Ждущие запросы человеку (D-44). */
    val requests: List<RequestCard> = emptyList(),
    /** Сколько задач в архиве (D-42) — для переключателя на доске. */
    val archived: Int = 0,
)

/**
 * Какая система сейчас показывается (D-34, D-35): есть target с каталогом
 * Claude — настоящий контроллер ([LiveSystem]), иначе демо. Смена targets
 * пересоздаёт контроллер. Журнал демо не хранится между запусками.
 *
 * Один писатель (D-42, п. 1): такт ([step]) и сборка системы — под
 * замком; действия человека — команды в очередь контура ([command]): их
 * исполняет поток такта между вызовами target, а если такт не идёт —
 * сам вызывающий, взяв свободный замок. Экран при занятом такте читает
 * последний опубликованный снимок, а не ждёт замок весь такт (A-23).
 */
class DemoModel(
    private val cacheDir: File,
    private val controller: Controller,
    private val onEvents: (List<EventEnvelope>) -> Unit = {},
    private val onProblem: (Diagnostic) -> Unit = {},
) {
    private val lock = ReentrantLock()
    private var demo: DemoSystem? = null
    private var live: LiveSystem? = null
    private var liveFor: Pair<List<TargetEntry>, ProjectEntry?>? = null

    /** Система, собранная последней: команды кладутся в её очередь без замка. */
    @Volatile
    private var built: ControlView? = null

    /** Показывать ли архив на доске (D-42) — выбор экрана, не состояние контура. */
    @Volatile
    var showArchived: Boolean = false

    /** Последний снимок — для экрана, пока такт держит замок. */
    @Volatile
    var published: Snapshot? = null
        private set

    private fun current(): ControlView {
        val targets = controller.targets()
        if (targets.any { it.configDir != null }) {
            val key = targets to controller.project()
            if (live == null || liveFor != key) {
                live = controller.live(key.first, key.second, onEvents, onProblem)
                liveFor = key
            }
            return live!!.also { built = it }
        }
        return (
            demo ?: run {
                val dir = File(cacheDir, "demo").apply {
                    deleteRecursively()
                    mkdirs()
                }
                DemoSystem(Clock.SYSTEM, NioVfs(dir.toPath())).also { demo = it }
            }
            ).also { built = it }
    }

    private var drainedOf: ControlView? = null
    private var drained = 0

    /**
     * Уведомления, которые оболочка ещё не показала: у контроллера — все
     * новые и обновлённые (ТЗ, §36), у демо — только проверочные запросы.
     */
    private fun drain(v: ControlView): List<Notification> {
        val posted = v.orchestrator.posted
        if (drainedOf !== v) {
            drainedOf = v
            drained = 0
        }
        val fresh = posted.drop(drained)
        drained = posted.size
        return fresh.mapNotNull {
            when (it) {
                is NotificationDecision.Post -> it.notification
                is NotificationDecision.Update -> it.notification
                is NotificationDecision.Held -> null
            }
        }.filter { n -> v is LiveSystem || n.request?.let { v.orchestrator.requests.requests[it]?.purpose } == RequestPurpose.PROBE.code }
    }

    private fun snapshot(v: ControlView, selected: String?, notifications: List<Notification>): Snapshot {
        val session = selected?.let { IdKinds.SESSION.parse(it).valueOrNull() }
        val o = v.orchestrator
        return Snapshot(
            v.mode, v is LiveSystem, v.dashboard(), com.dpsmpx.dpai.ui.Screens.board(o.tasks, showArchived), session?.let(v::session), notifications,
            RequestScreens.cards(shown(v), o.tasks, Clock.SYSTEM.now()), o.tasks.tasks.values.count { it.archived },
        ).also { published = it }
    }

    /** Такт и снимок; новые уведомления — в снимке, один раз. */
    fun step(selected: String?): Snapshot = lock.withLock {
        val v = current()
        v.step()
        snapshot(v, selected, drain(v))
    }

    /**
     * Снимок без такта — когда такт делает служба контроллера (D-36). Такт
     * держит замок — последний опубликованный снимок: экран не ждёт SSH.
     */
    fun view(selected: String?): Snapshot? {
        if (!lock.tryLock()) return published
        try {
            val v = current()
            // Команды, пришедшие с экрана, пока такта нет, — сразу (поток экрана — единственный писатель в этот миг).
            v.orchestrator.runCommands()
            return snapshot(v, selected, emptyList())
        } finally {
            lock.unlock()
        }
    }

    /**
     * Команда контура (D-42): в очередь; замок свободен — исполнить сразу,
     * иначе её исполнит такт в ближайшей безопасной точке. Ждёт итог не
     * дольше [waitMs]: не дождались — честно «в очереди», команда не
     * теряется и применится тактом.
     */
    fun command(c: Command, waitMs: Long = WAIT_MS): Outcome<Any?> = try {
        val v = built ?: lock.withLock { current() }
        val f = v.orchestrator.submit(c)
        if (lock.tryLock()) {
            try {
                v.orchestrator.runCommands()
            } finally {
                lock.unlock()
            }
        }
        f.get(waitMs, TimeUnit.MILLISECONDS).outcome
    } catch (_: TimeoutException) {
        Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "контур занят вызовом target: команда в очереди и применится в ближайшем такте")
    } catch (e: Exception) {
        Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.INTERNAL_ERROR, "контроллер не принял команду", e)))
    }

    /**
     * Незавершённые сессии на target — перед его удалением (ревью 8 октября
     * 2026, A-32). Без замка модели: такт держит его минутами, а экран
     * читает уже построенный контроллер; `null` — контроллер ещё не построен.
     */
    fun activeSessionsOn(target: ExecutionTargetId): Int? =
        (built as? LiveSystem)?.orchestrator?.sessions?.sessions?.values
            ?.count { it.target == target && it.state !in com.dpsmpx.dpai.core.state.SessionLifecycle.table.terminal }

    /** Контроллер для канала `dpai` (D-47): только настоящий, не демо. */
    fun liveOrchestrator(): com.dpsmpx.dpai.orchestrator.Orchestrator? =
        (built as? LiveSystem)?.orchestrator ?: if (hasLive()) runCatching { lock.withLock { (current() as? LiveSystem)?.orchestrator } }.getOrNull() else null

    /** Команда с полным итогом (`OperationResult`) — для канала `dpai`; как [command]. */
    fun commandResult(c: Command, waitMs: Long = WAIT_MS): Outcome<com.dpsmpx.dpai.core.operation.OperationResult<*>> = try {
        val v = built ?: lock.withLock { current() }
        val f = v.orchestrator.submit(c)
        if (lock.tryLock()) {
            try {
                v.orchestrator.runCommands()
            } finally {
                lock.unlock()
            }
        }
        Outcome.success(f.get(waitMs, TimeUnit.MILLISECONDS))
    } catch (_: TimeoutException) {
        Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "контур занят вызовом target: команда в очереди и применится в ближайшем такте")
    } catch (e: Exception) {
        Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.INTERNAL_ERROR, "контроллер не принял команду", e)))
    }

    /**
     * Ответ человека на запрос (D-44) — с кнопки уведомления, окна поверх
     * приложений или экрана; ссылку и хеш проверяет оркестратор.
     */
    fun answer(uri: String, text: String?, channel: String): Outcome<RequestAnswer> =
        command(Command.AnswerRequest(uri, text, ACTOR, channel)).then { v -> Outcome.success(v as RequestAnswer) }

    /** Проверочный запрос вида [kind] (экран «Запросы», `docs/DEVICE_CHECK.md`); [ttl] `null` — срок контура по умолчанию. */
    fun probe(kind: RequestKind, ttl: Duration?): Outcome<RequestId> =
        command(Command.Probe(kind, ttl)).then { v -> Outcome.success(v as RequestId) }

    /** Показанные в этом процессе по каналам: факт показа — один на канал, а не на каждую перерисовку. */
    private val deliveredHere = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Канал показал запрос: факт в журнал (D-44), один раз на канал за процесс; итог не ждём. */
    fun delivered(id: RequestId, channel: String) {
        if (!deliveredHere.add("$channel:${id.value}")) return
        runCatching { (built ?: lock.withLock { current() }).orchestrator.submit(Command.Delivered(id, channel)) }
    }

    /** Ждущие запросы — оболочка показывает новые и снимает закрытые; такт занят — по опубликованному снимку. */
    fun pending(): Map<RequestId, RequestCard> {
        if (!lock.tryLock()) return published?.requests?.associateBy { it.id }.orEmpty()
        return try {
            runCatching {
                val v = current()
                RequestScreens.cards(shown(v), v.orchestrator.tasks, Clock.SYSTEM.now()).associateBy { it.id }
            }.getOrDefault(emptyMap())
        } finally {
            lock.unlock()
        }
    }

    /** Запросы, которые оболочка показывает: у контроллера — все, у демо — только проверочные (демо не шумит). */
    private fun shown(v: ControlView) =
        if (v is LiveSystem) v.orchestrator.pendingRequests() else v.orchestrator.pendingRequests().filter { it.purpose == RequestPurpose.PROBE.code }

    /** Последняя сессия задачи — куда ведёт ссылка `dpai://task/…` из уведомления. Чтение неизменяемого состояния — без замка. */
    fun sessionOf(task: TaskId): SessionId? = runCatching {
        built?.orchestrator?.sessions?.sessions?.values?.filter { it.task == task }?.maxByOrNull { it.createdAt.epochMillis }?.id
    }.getOrNull()

    /** Задачи, после которых можно поставить новую (D-42, п. 1) — без замка. */
    fun dependencyChoices(): List<Pair<TaskId, String>> =
        runCatching { (built as? LiveSystem)?.orchestrator?.tasks?.let(com.dpsmpx.dpai.ui.TaskScreens::dependencyChoices) }.getOrNull().orEmpty()

    /** Лист задачи (D-42): состояние и доступные действия — из неизменяемого состояния контура, без замка. */
    fun taskDetail(task: TaskId): com.dpsmpx.dpai.ui.TaskDetail? = runCatching {
        val o = built?.orchestrator ?: return null
        val labels = controller.targets().associate { it.id to it.label }
        val handoffs = o.handoffs.all().filter { it.task == task }
        o.tasks.tasks[task]?.let { com.dpsmpx.dpai.ui.TaskScreens.detail(it, o.sessions, o.waitingReason(task), o.accounts, { id -> labels[id] ?: id.value }, handoffs) }
    }.getOrNull()

    /** Итоговые сообщения ходов задачи (D-42) — целиком; из памяти контура или документа задачи, без замка. */
    fun taskMessages(task: TaskId): List<com.dpsmpx.dpai.orchestrator.TurnMessage> =
        runCatching { built?.orchestrator?.turnMessages(task) }.getOrNull().orEmpty()

    /**
     * Изменения задачи (D-42, п. 3) — по кнопке, не по таймеру: это Git на
     * target (по SSH — секунды). Без замка: контур читает неизменяемый
     * снимок деревьев; звать — с потока ввода-вывода.
     */
    fun taskChanges(task: TaskId): Outcome<com.dpsmpx.dpai.orchestrator.git.TaskChanges> =
        runCatching { built?.orchestrator?.taskChanges(task) }.getOrElse { e -> Outcome.failure(DiagCode.GIT_COMMAND_FAILED, "изменения не прочитаны: ${e.javaClass.simpleName}") }
            ?: Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "контроллер не запущен")

    /** Настройки контура (D-42, п. 5, 6) — для вкладки Targets; без замка. */
    fun control(): com.dpsmpx.dpai.orchestrator.commands.ControlState? = runCatching { (built as? LiveSystem)?.orchestrator?.control }.getOrNull()

    /** Слоты аккаунтов на target — их включение и состояние. */
    fun slotsOf(target: ExecutionTargetId): List<com.dpsmpx.dpai.accounts.SlotState> =
        runCatching { (built as? LiveSystem)?.orchestrator?.accounts?.slots?.values?.filter { it.targetId == target }?.sortedBy { it.id.value } }.getOrNull().orEmpty()

    /** Есть ли настоящий контроллер: тогда такт — в службе переднего плана. */
    fun hasLive(): Boolean = controller.targets().any { it.configDir != null }

    /**
     * Новая задача контроллеру (D-42): команда с ключом нажатия [key] —
     * повтор того же нажатия задачу не дублирует. Демо задач не берёт.
     */
    fun addTask(title: String, objective: String, key: String, dependencies: Set<TaskId> = emptySet(), scope: Set<String>? = null): Outcome<TaskId> {
        if (!hasLive()) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "задачи ставятся только контроллеру: заведите target с каталогом Claude")
        return command(Command.AddTask(title.trim(), objective.trim(), dependencies = dependencies, scope = scope, key = key)).then { v ->
            when (v) {
                is TaskId -> Outcome.success(v)
                is String -> IdKinds.TASK.parse(v)
                else -> Outcome.failure(DiagCode.INTERNAL_ERROR, "итог постановки задачи неизвестен")
            }
        }
    }

    /**
     * Контроллер для действия кнопки; сбой его сборки (журнал не открылся,
     * target недоступен при запуске) — отказ с причиной, а не падение
     * приложения из кнопки или уведомления (ревью 8 октября 2026).
     */
    private fun liveSystem(): Outcome<LiveSystem?> = try {
        Outcome.success(current() as? LiveSystem)
    } catch (e: Exception) {
        Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.INTERNAL_ERROR, "контроллер не запустился", e)))
    }

    /** Обновить загрузку окон подписок через `/usage` (D-48): без хода, лимит не тратится. */
    fun refreshUsage() = lock.withLock {
        liveSystem().valueOrNull()?.refreshUsage()
        Unit
    }

    /** Идёт ли обновление окон и когда оно последний раз пришло (без замка: поля потокобезопасны). */
    fun usageState(): Pair<Boolean, com.dpsmpx.dpai.core.clock.Timestamp?> =
        live?.let { it.usageRefreshing to it.usageRefreshedAt } ?: (false to null)

    /** Проверить вход слота target заново — после входа автора в Claude. */
    fun probe(target: ExecutionTargetId) = lock.withLock {
        liveSystem().valueOrNull()?.probeSlot(target)
        Unit
    }

    companion object {
        /** Кто отвечает с телефона: владелец приложения. */
        const val ACTOR = "автор"

        /** Сколько ждать итога команды: такт с вызовом target идёт секунды, не минуты. */
        private const val WAIT_MS = 30_000L

        @Volatile
        private var instance: DemoModel? = null

        /** Одна система на процесс: поворот экрана не перезапускает её; события контура — в журнал приложения (D-43). */
        fun get(context: android.content.Context): DemoModel = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val controller = Controller.get(app.filesDir, app.packageManager.getPackageInfo(app.packageName, 0).versionName)
                // Уведомления — у Android: отключены человеком — «недоступны», иначе работают.
                controller.notificationsOk = { app.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() }
                DemoModel(app.cacheDir, controller, AppLogs.events(app), AppLogs.problems(app)).also { instance = it }
            }
        }
    }
}
