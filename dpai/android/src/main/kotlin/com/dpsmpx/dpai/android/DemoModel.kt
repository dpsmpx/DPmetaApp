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
import com.dpsmpx.dpai.core.operation.OperationResult
import java.util.concurrent.CompletableFuture
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
    /** Быстрые действия сводки (ТЗ, §73). */
    val quick: com.dpsmpx.dpai.ui.QuickActions = com.dpsmpx.dpai.ui.QuickActions.NONE,
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
    /** Контекст приложения — для намерений о настройках (D-57); в тестах без Android — `null`. */
    private val context: android.content.Context? = null,
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
            // Журнал закрыт после отказа записи (кончилось место, A-4): раз в минуту — контур заново, журнал
            // открывается с проверкой хвоста; места всё ещё нет — следующая запись снова закроет его.
            val broken = live?.orchestrator?.storageBroken == true && Clock.SYSTEM.monotonicNanos() - builtAt >= REOPEN_NANOS
            if (live == null || liveFor != key || broken) {
                // Потоки событий прежней системы (D-76) не переживают её: каналы SSH закрываются.
                live?.close()
                val started = Clock.SYSTEM.monotonicNanos()
                builtAt = Clock.SYSTEM.monotonicNanos()
                // Target «в приложении» (D-75, шаг 5): агент — в процессе, ходы — в Alpine приложения.
                val inApp = context?.takeIf { key.first.any { it.inApp } }?.let { com.dpsmpx.dpai.android.linux.LinuxSetup.agentHost(it, controller.version) }
                val guest = inApp?.let { context.let(com.dpsmpx.dpai.android.linux.LinuxSetup::guestHost) }
                live = controller.live(key.first, key.second, onEvents, onProblem, inApp, guest)
                liveFor = key
                perf("контроллер построен", started)
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

    /** Когда контур построен последний раз — для пересборки после отказа журнала не чаще раза в минуту. */
    private var builtAt = 0L

    private var drainedOf: ControlView? = null
    private var drained = 0L

    /**
     * Уведомления, которые оболочка ещё не показала: у контроллера — все
     * новые и обновлённые (ТЗ, §36), у демо — только проверочные запросы.
     */
    private fun drain(v: ControlView): List<Notification> {
        if (drainedOf !== v) {
            drainedOf = v
            drained = 0
        }
        val (fresh, next) = v.orchestrator.postedSince(drained)
        drained = next
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
            com.dpsmpx.dpai.ui.QuickActions.of(o.tasks),
        ).also {
            published = it
            publishedOf = v
        }
    }

    /** Такт и снимок; новые уведомления — в снимке, один раз. */
    fun step(selected: String?): Snapshot = lock.withLock {
        val v = current()
        // Снимок — до такта (D-63): такт ходит по SSH секундами, экран не должен его ждать.
        if (published == null || publishedOf !== v) {
            val started = Clock.SYSTEM.monotonicNanos()
            snapshot(v, selected, emptyList())
            perf("снимок до такта", started)
        }
        // Намерения о настройках (D-57) — в очередь этого контура до такта: такт их и исполнит.
        context?.let { SettingIntents.reconcile(it, v.orchestrator) }
        val started = Clock.SYSTEM.monotonicNanos()
        v.step()
        perf("такт", started)
        context?.let { SettingIntents.reconcile(it, v.orchestrator) }
        snapshot(v, selected, drain(v))
    }

    /** Контур, по которому опубликован последний снимок: новый контур — новый снимок до такта. */
    @Volatile
    private var publishedOf: ControlView? = null

    /** Замер для настройки скорости (D-63): в logcat с тегом `dpai-perf`, только длительность. */
    private fun perf(what: String, startedNanos: Long) {
        val ms = (Clock.SYSTEM.monotonicNanos() - startedNanos) / 1_000_000
        runCatching { android.util.Log.i("dpai-perf", "$what: $ms мс") }
    }

    /**
     * Снимок без такта — когда такт делает служба контроллера (D-36). Такт
     * держит замок — последний опубликованный снимок: экран не ждёт SSH.
     */
    fun view(selected: String?): Snapshot? {
        if (!lock.tryLock()) {
            // Такт держит замок (SSH, проверка лимитов) — снимок из уже построенного контура без замка (D-63):
            // состояния контура неизменяемы, а сбой чтения посреди записи такта — прежний снимок, не ошибка.
            val v = built ?: return published
            return runCatching { snapshot(v, selected, emptyList()) }.getOrNull() ?: published
        }
        try {
            val v = current()
            // Команды, пришедшие с экрана, пока такта нет, — сразу (поток экрана — единственный писатель в этот миг).
            context?.let { SettingIntents.reconcile(it, v.orchestrator) }
            v.orchestrator.runCommands()
            context?.let { SettingIntents.reconcile(it, v.orchestrator) }
            return snapshot(v, selected, emptyList())
        } finally {
            lock.unlock()
        }
    }

    /**
     * Команды в контур (D-42, D-94). Замок свободен — исполнить сразу на
     * текущем контуре (под замком: пересборка не подменит его посреди
     * исполнения). Занят такт — каждую сначала в журнал «принята»: так она
     * переживёт смерть процесса и пересборку, а исполнит её такт.
     */
    private fun dispatch(cs: List<Command>): List<Outcome<CompletableFuture<OperationResult<*>>>> {
        if (lock.tryLock()) {
            try {
                val o = current().orchestrator
                val fs = cs.map { o.submit(it) }
                o.runCommands()
                return fs.map { Outcome.success(it) }
            } finally {
                lock.unlock()
            }
        }
        val o = (built ?: lock.withLock { current() }).orchestrator
        return cs.map { o.accept(it) }
    }

    /**
     * Итог команды за [waitMs]. Не дождались — честно: команда в журнале и
     * применится после текущего шага (D-94); [onLater] получит итог, когда
     * такт её исполнит.
     */
    private fun <T> await(f: CompletableFuture<OperationResult<*>>, waitMs: Long, onLater: ((Outcome<T>) -> Unit)?, map: (OperationResult<*>) -> Outcome<T>): Outcome<T> = try {
        map(f.get(waitMs, TimeUnit.MILLISECONDS))
    } catch (_: TimeoutException) {
        onLater?.let { later ->
            f.whenCompleteAsync({ r, e ->
                runCatching { later(if (r != null) map(r) else Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.INTERNAL_ERROR, "контроллер не исполнил команду", e)))) }
            }, laterExecutor)
        }
        Outcome.failure(DiagCode.COMMAND_QUEUED, QUEUED)
    } catch (e: Exception) {
        Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.INTERNAL_ERROR, "контроллер не принял команду", e)))
    }

    /**
     * Команда контура (D-42): замок свободен — исполнить сразу, иначе — в
     * журнал и очередь (D-94). Ждёт итог не дольше [waitMs]: не дождались —
     * «принято, применится после текущего шага», итог — в [onLater].
     */
    fun command(c: Command, waitMs: Long = WAIT_MS, onLater: ((Outcome<Any?>) -> Unit)? = null): Outcome<Any?> = intent(c) ?: try {
        when (val f = dispatch(listOf(c)).single()) {
            is Outcome.Failure -> f
            is Outcome.Success -> await(f.value, waitMs, onLater) { it.outcome }
        }
    } catch (e: Exception) {
        Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.INTERNAL_ERROR, "контроллер не принял команду", e)))
    }

    /**
     * Настройка слота или подписки (D-57): намерение записано сразу и видно
     * на экране; контур исполнит его, когда свободен (сейчас — если замок
     * свободен, иначе в своём такте). Не настройка — `null`: команда идёт как
     * раньше.
     */
    private fun intent(c: Command): Outcome<Any?>? {
        val ctx = context ?: return null
        SettingIntents.key(c) ?: return null
        SettingIntents.record(ctx, c)
        val v = built ?: return Outcome.success(null)
        SettingIntents.reconcile(ctx, v.orchestrator)
        if (lock.tryLock()) {
            try {
                v.orchestrator.runCommands()
                SettingIntents.reconcile(ctx, v.orchestrator)
            } finally {
                lock.unlock()
            }
        }
        return Outcome.success(null)
    }

    /** Желаемое значение настройки, ещё не применённое (D-57): экран показывает его, а не прежнее. */
    fun desired(c: Command): Command? = context?.let { ctx -> SettingIntents.key(c)?.let { SettingIntents.desired(ctx, it) } }

    /** Почему намерение отклонено контуром (слот удалён и т. п.); нет — `null`. */
    fun refusal(c: Command): String? = SettingIntents.key(c)?.let(SettingIntents::refusal)

    /**
     * Несколько команд разом (ТЗ, §73: «Пауза всем», «Продолжить все»): все —
     * в очередь контура, итог — каждой, общий срок [waitMs]. По одной с
     * ожиданием каждой серия шла бы такт за тактом — секунды на задачу.
     */
    fun commandAll(cs: List<Command>, waitMs: Long = WAIT_MS): List<Outcome<Any?>> {
        if (cs.isEmpty()) return emptyList()
        val fs = try {
            dispatch(cs)
        } catch (e: Exception) {
            val f = Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.INTERNAL_ERROR, "контроллер не принял команды", e)))
            return cs.map { f }
        }
        val deadline = Clock.SYSTEM.monotonicNanos() + waitMs * 1_000_000
        return fs.map { f ->
            when (f) {
                is Outcome.Failure -> f
                is Outcome.Success -> await(f.value, ((deadline - Clock.SYSTEM.monotonicNanos()) / 1_000_000).coerceAtLeast(0), null) { it.outcome }
            }
        }
    }

    /**
     * Что видел Claude в последнем ходе задачи (ТЗ, §74): версия контекста
     * для окна «Контекст» — или почему её нет. Контексты в памяти дописывает
     * такт, поэтому чтение — под замком, если он освободится за
     * [SEARCH_WAIT_MS]. Не из главного потока.
     */
    fun context(task: TaskId): Pair<com.dpsmpx.dpai.ui.ContextView?, String?> {
        val o = built?.orchestrator ?: return null to "контур ещё не запущен"
        if (!lock.tryLock(SEARCH_WAIT_MS, TimeUnit.MILLISECONDS)) return null to CONTEXT_BUSY
        // Под замком — только чтение версии (она неизменна); показ с Redactor по всему тексту — уже без замка такта.
        val bundle = try {
            o.lastContext(task)
        } finally {
            lock.unlock()
        }
        return bundle?.let { com.dpsmpx.dpai.ui.ContextScreens.view(it) to null } ?: (null to "у задачи ещё не было хода — контекст не собирался")
    }

    /** Быстрые действия по текущему состоянию контура (ТЗ, §73) — в миг нажатия, а не по снимку, который такт мог задержать. */
    fun quickNow(): com.dpsmpx.dpai.ui.QuickActions? = runCatching { built?.orchestrator?.tasks?.let(com.dpsmpx.dpai.ui.QuickActions::of) }.getOrNull()

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
    fun commandResult(c: Command, waitMs: Long = WAIT_MS): Outcome<OperationResult<*>> = try {
        when (val f = dispatch(listOf(c)).single()) {
            is Outcome.Failure -> f
            is Outcome.Success -> await(f.value, waitMs, null) { Outcome.success(it) }
        }
    } catch (e: Exception) {
        Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.INTERNAL_ERROR, "контроллер не принял команду", e)))
    }

    /**
     * Ответ человека на запрос (D-44) — с кнопки уведомления, окна поверх
     * приложений или экрана; ссылку и хеш проверяет оркестратор.
     */
    fun answer(uri: String, text: String?, channel: String, onLater: ((Outcome<RequestAnswer>) -> Unit)? = null): Outcome<RequestAnswer> =
        command(Command.AnswerRequest(uri, text, ACTOR, channel), onLater = onLater?.let { later -> { r: Outcome<Any?> -> later(r.then { v -> Outcome.success(v as RequestAnswer) }) } })
            .then { v -> Outcome.success(v as RequestAnswer) }

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
        // Рабочая сессия, а не ревью (аудит удобства, A-14): ревью только читает, писать туда нечего.
        val own = built?.orchestrator?.sessions?.sessions?.values?.filter { it.task == task }.orEmpty()
        (own.filter { it.review == null }.ifEmpty { own }).maxByOrNull { it.createdAt.epochMillis }?.id
    }.getOrNull()

    /** Кто чем занят (D-56): target → его идущие задачи с проектом — без замка. */
    fun work(): Map<com.dpsmpx.dpai.core.ids.ExecutionTargetId, List<String>> = runCatching {
        val o = (built as? LiveSystem)?.orchestrator ?: return emptyMap()
        val project = controller.project()?.root?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')
        com.dpsmpx.dpai.ui.TaskScreens.work(o.sessions, o.tasks, project)
    }.getOrNull().orEmpty()

    /** Слоты для ручного переноса задачи (D-66): targets с каталогом Claude — id и имя. */
    fun slotTargets(): List<Pair<com.dpsmpx.dpai.core.ids.ExecutionTargetId, String>> =
        controller.targets().filter { it.configDir != null }.map { it.id to it.label }

    /** Слот, выбранный человеком для следующего хода задачи (D-66) — без замка. */
    fun pinned(task: TaskId): com.dpsmpx.dpai.core.ids.ExecutionTargetId? = runCatching { built?.orchestrator?.pinned(task) }.getOrNull()

    /** Лист задачи (D-42): состояние и доступные действия — из неизменяемого состояния контура, без замка. */
    fun taskDetail(task: TaskId): com.dpsmpx.dpai.ui.TaskDetail? = runCatching {
        val o = built?.orchestrator ?: return null
        val labels = controller.targets().associate { it.id to it.label }
        val handoffs = o.handoffs.all().filter { it.task == task }
        o.tasks.tasks[task]?.let { com.dpsmpx.dpai.ui.TaskScreens.detail(it, o.sessions, o.waitingReason(task), o.accounts, { id -> labels[id] ?: id.value }, handoffs, o.tasks.tasks) }
    }.getOrNull()

    /** Задачи, от которых можно зависеть (D-42, п. 1): не завершённые, кроме [except]; без замка. */
    fun dependencyChoices(except: TaskId? = null): List<com.dpsmpx.dpai.ui.DependencyLine> =
        runCatching { built?.orchestrator?.tasks?.tasks?.let { com.dpsmpx.dpai.ui.TaskScreens.dependencyChoices(it, except) } }.getOrNull().orEmpty()

    /** Изменения задачи (D-42): Git по SSH — вызывать не из главного потока; без замка такта. */
    fun taskDiff(task: TaskId): Outcome<com.dpsmpx.dpai.orchestrator.git.TaskDiff> =
        runCatching { built?.orchestrator?.taskDiff(task) }.getOrNull()
            ?: Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.UNSUPPORTED, "контроллер не запущен", "task $task")

    /** Итоговые сообщения ходов задачи (D-42) — целиком; из памяти контура или документа задачи, без замка. */
    fun taskMessages(task: TaskId): List<com.dpsmpx.dpai.orchestrator.TurnMessage> =
        runCatching { built?.orchestrator?.turnMessages(task) }.getOrNull().orEmpty()

    /**
     * Глобальный поиск (ТЗ, §75). Проекции неизменяемы и читаются без замка;
     * события, контексты и отказы дописывает такт — их копия берётся под
     * замком, если он освободится за [SEARCH_WAIT_MS], иначе поиск идёт без
     * них, и итог это говорит (D-05). Не из главного потока; контура нет — `null`.
     */
    fun search(query: String): com.dpsmpx.dpai.ui.GlobalSearch.Result? {
        val o = built?.orchestrator ?: return null
        fun projections() = com.dpsmpx.dpai.ui.GlobalSearch.Source(o.tasks, o.sessions, o.accounts, o.worktrees, o.handoffs.all(), o.projectConfig, o.mergesState.commits)
        // Под замком — всё из одного промежутка между тактами: иначе задача и её события были бы из разных тактов.
        val source = if (lock.tryLock(SEARCH_WAIT_MS, TimeUnit.MILLISECONDS)) {
            try {
                projections().copy(events = o.recentEvents(), eventsFrom = o.eventsFrom, contexts = o.contexts(), problems = o.problems.toList())
            } finally {
                lock.unlock()
            }
        } else {
            projections()
        }
        return com.dpsmpx.dpai.ui.GlobalSearch.search(source, query)
    }

    /** Настройки контура (D-42, п. 5, 6) — для вкладки Targets; без замка. */
    fun control(): com.dpsmpx.dpai.orchestrator.commands.ControlState? = runCatching { (built as? LiveSystem)?.orchestrator?.control }.getOrNull()

    /** Слоты аккаунтов на target — их включение и состояние. */
    fun slotsOf(target: ExecutionTargetId): List<com.dpsmpx.dpai.accounts.SlotState> =
        runCatching { (built as? LiveSystem)?.orchestrator?.accounts?.slots?.values?.filter { it.targetId == target }?.sortedBy { it.id.value } }.getOrNull().orEmpty()

    /** Есть ли настоящий контроллер: тогда такт — в службе переднего плана. */
    fun hasLive(): Boolean = controller.targets().any { it.configDir != null }

    /**
     * Новая задача контроллеру (D-42): команда с ключом нажатия — повтор
     * того же нажатия задачу не дублирует. Демо задач не берёт.
     */
    fun addTask(c: Command.AddTask): Outcome<TaskId> {
        if (!hasLive()) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "задачи ставятся только контроллеру: заведите target с каталогом Claude")
        return command(c).then { v ->
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

    /**
     * Замок модели — недолго (аудит стабильности, A-7): такт с проверкой держит
     * его десятки минут, и каждый заход на экран ставил ещё один поток IO
     * ждать без отмены. Занят — `false`, действие пропущено (повторит
     * следующий заход или такт).
     */
    private inline fun briefly(body: () -> Unit): Boolean {
        if (!lock.tryLock(BRIEF_WAIT_MS, TimeUnit.MILLISECONDS)) return false
        try {
            body()
        } finally {
            lock.unlock()
        }
        return true
    }

    /** Обновить загрузку окон подписок через `/usage` (D-48): без хода, лимит не тратится. Такт занят — пропуск. */
    fun refreshUsage(only: Set<com.dpsmpx.dpai.core.ids.ExecutionTargetId>? = null): Boolean = briefly {
        liveSystem().valueOrNull()?.refreshUsage(only)
    }

    /** Идёт ли обновление окон и когда оно последний раз пришло (без замка: поля потокобезопасны). */
    fun usageState(): Pair<Boolean, com.dpsmpx.dpai.core.clock.Timestamp?> =
        live?.let { it.usageRefreshing to it.usageRefreshedAt } ?: (false to null)

    /** Проверить вход слота target заново — после входа автора в Claude. Такт занят — пропуск (`false`). */
    fun probe(target: ExecutionTargetId): Boolean = briefly {
        liveSystem().valueOrNull()?.probeSlot(target)
    }

    companion object {
        /** Кто отвечает с телефона: владелец приложения. */
        const val ACTOR = "автор"

        /** Сколько ждать итога команды: такт с вызовом target идёт секунды, не минуты. */
        private const val WAIT_MS = 30_000L

        /** Пересборка контура со сломанным журналом — не чаще раза в минуту: сборка ходит по SSH. */
        private val REOPEN_NANOS = TimeUnit.MINUTES.toNanos(1)

        /** Сколько экран ждёт замок модели ради проверки входа или лимитов (A-7). */
        private const val BRIEF_WAIT_MS = 2_000L

        /**
         * Итоги команд, исполненных позже (D-94), — своим потоком (аудит стабильности, A-5):
         * в потоке такта колбэк звал `pending()` → `current()` и мог пересобрать контур посреди такта.
         */
        private val laterExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "dpai-later").apply { isDaemon = true } }

        /** Не дождались итога — команда в журнале (D-94): применится после текущего шага контура, переживёт перезапуск. */
        const val QUEUED = "принято: применится после текущего шага контура (идёт вызов target)"

        /** Сколько поиск ждёт конца такта ради событий: дольше — поиск без них, человек не ждёт. */
        private const val SEARCH_WAIT_MS = 1_500L

        /** Причина «контекст не прочитан: идёт такт» — окно повторяет попытку само. */
        const val CONTEXT_BUSY = "идёт такт контура — пробую ещё раз"

        @Volatile
        private var instance: DemoModel? = null

        /** Одна система на процесс: поворот экрана не перезапускает её; события контура — в журнал приложения (D-43). */
        fun get(context: android.content.Context): DemoModel = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val controller = Controller.get(app.filesDir, app.packageManager.getPackageInfo(app.packageName, 0).versionName)
                // Уведомления — у Android: отключены человеком — «недоступны», иначе работают.
                controller.notificationsOk = { app.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() }
                DemoModel(app.cacheDir, controller, AppLogs.events(app), AppLogs.problems(app), app).also { instance = it }
            }
        }
    }
}
