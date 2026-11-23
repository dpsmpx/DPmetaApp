package com.dpsmpx.dpai.android

import android.annotation.SuppressLint
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.RequestId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.operation.OperationResult
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.then
import com.dpsmpx.dpai.notifications.Notification
import com.dpsmpx.dpai.notifications.NotificationDecision
import com.dpsmpx.dpai.notifications.RequestAnswer
import com.dpsmpx.dpai.notifications.RequestKind
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.orchestrator.requests.RequestPurpose
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.ControlView
import com.dpsmpx.dpai.ui.DashboardScreen
import com.dpsmpx.dpai.ui.DemoSystem
import com.dpsmpx.dpai.ui.LiveSystem
import com.dpsmpx.dpai.ui.ProjectEntry
import com.dpsmpx.dpai.ui.RequestCard
import com.dpsmpx.dpai.ui.RequestScreens
import com.dpsmpx.dpai.ui.SessionScreen
import com.dpsmpx.dpai.ui.TargetEntry
import com.dpsmpx.dpai.ui.TaskBoard
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

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

/** Одна запись в общем окне чатов: текущая CLI-сессия задачи с исходным проектом. */
data class ExecutorChat(
    val session: com.dpsmpx.dpai.core.ids.SessionId,
    val task: com.dpsmpx.dpai.core.ids.TaskId,
    val project: com.dpsmpx.dpai.core.ids.ProjectId?,
    val title: String,
    val executor: com.dpsmpx.dpai.core.ids.Executor,
    val target: String,
    val updatedAt: Long,
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
    /** Контур выбранного рабочего проекта — его показывают экраны (D-179). */
    private var live: LiveSystem? = null

    /** Выбранный проект, для которого собран [live]. */
    private var liveFor: Pair<List<TargetEntry>, ProjectEntry?>? = null

    /**
     * Контуры всех рабочих проектов (`DPmetaApp:D-147`; D-179): ключ — проект, `null` — контур
     * без проекта (список пуст). Такт идёт всем по очереди под [lock]; места на target —
     * общие ([neighboursOf]).
     */
    private val contours = LinkedHashMap<com.dpsmpx.dpai.core.ids.ProjectId?, LiveSystem>()
    private var contoursFor: Pair<List<TargetEntry>, List<ProjectEntry>>? = null

    /** Сколько отдано уведомлений каждого фонового контура. */
    private val drainedBy = HashMap<LiveSystem, Long>()

    /** Незавершённые сессии контуров, кроме контура проекта [self] (D-179). */
    private fun neighboursOf(self: com.dpsmpx.dpai.core.ids.ProjectId?) = object : com.dpsmpx.dpai.orchestrator.NeighbourLoad {
        private fun others() = contours.filterKeys { it != self }.values.map { it.orchestrator.sessions.sessions.values.filter { s -> !s.terminal } }
        override fun sessions(target: com.dpsmpx.dpai.core.ids.ExecutionTargetId): Int = others().sumOf { list -> list.count { it.target == target } }
        override fun total(): Int = others().sumOf { it.size }
    }

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
            val projects = controller.projects()
            val all = targets to projects.projects
            // Журнал закрыт после отказа записи (кончилось место, A-4): раз в минуту — контур заново, журнал
            // открывается с проверкой хвоста; места всё ещё нет — следующая запись снова закроет его.
            val broken = contours.values.any { it.orchestrator.storageBroken } && Clock.SYSTEM.monotonicNanos() - builtAt >= REOPEN_NANOS
            if (contours.isEmpty() || contoursFor != all || broken) {
                // Потоки событий прежних систем (D-76) не переживают их: каналы SSH закрываются.
                contours.values.forEach { it.close() }
                contours.clear()
                drainedBy.clear()
                val started = Clock.SYSTEM.monotonicNanos()
                builtAt = Clock.SYSTEM.monotonicNanos()
                // Target «в приложении» (D-75, шаг 5): агент — в процессе, ходы — в Alpine приложения.
                val inApp = context?.takeIf { targets.any { it.inApp } }?.let { com.dpsmpx.dpai.android.linux.LinuxSetup.agentHost(it, controller.version) }
                val guest = inApp?.let { context.let(com.dpsmpx.dpai.android.linux.LinuxSetup::guestHost) }
                // Контур на рабочий проект (D-179); список пуст — один контур без проекта, как раньше.
                for (p in projects.projects.ifEmpty { listOf(null) }) {
                    contours[p?.project] = controller.live(targets, p, onEvents, onProblem, inApp, guest, neighbours = neighboursOf(p?.project))
                }
                contoursFor = all
                perf("контуры построены: ${contours.size}", started)
            }
            val selected = projects.current
            live = contours[selected?.project] ?: contours.values.first()
            liveFor = targets to selected
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
        // Контур проекта — свой счётчик, общий с фоновым показом (D-179): переключение проекта
        // не показывает заново уже отданные уведомления.
        if (v is LiveSystem) return drainBackground(v)
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
            // Виджет (DPmetaApp:D-153) — только когда его числа «AI» сменились, а не на каждом такте.
            val counts = listOf(it.requests.size, it.dashboard.active, it.dashboard.limits.count { l -> l.near })
            if (counts != widgetCounts) {
                widgetCounts = counts
                (context?.applicationContext as? DpaiApplication)?.studioChanged()
            }
        }
    }

    @Volatile private var widgetCounts: List<Int>? = null

    @Volatile private var workCached: List<com.dpsmpx.dpai.ui.ProjectWork>? = null

    @Volatile private var executorChatCache: List<ExecutorChat> = emptyList()

    /** Сессии всех рабочих проектов, включая фоновые; ожидание занятого такта ограничено. */
    fun executorChats(): List<ExecutorChat> {
        if (!lock.tryLock(200, TimeUnit.MILLISECONDS)) return executorChatCache
        return try {
            val targets = controller.targets().associate { it.id to it.label }
            contours.flatMap { (project, view) ->
                val o = view.orchestrator
                o.sessions.sessions.values.filter { it.review == null }.groupBy { it.task }.mapNotNull { (task, sessions) ->
                    val latest = sessions.maxByOrNull { it.updatedAt.epochMillis } ?: return@mapNotNull null
                    val record = o.tasks.tasks[task] ?: return@mapNotNull null
                    ExecutorChat(latest.id, task, project, record.title,
                        o.accounts.slots[latest.slot]?.executor ?: record.policy.executor,
                        targets[latest.target] ?: latest.target.value, latest.updatedAt.epochMillis)
                }
            }.sortedByDescending { it.updatedAt }.also { executorChatCache = it }
        } finally { lock.unlock() }
    }

    /**
     * Работа студии по каждому рабочему проекту — для пульта (DPmetaApp:D-176). Такт держит замок —
     * отдаётся прежний расчёт, а пульт не ждёт (A-23); его ещё не было — числа ИИ неизвестны.
     */
    fun projectWork(now: com.dpsmpx.dpai.core.clock.Timestamp): List<com.dpsmpx.dpai.ui.ProjectWork> {
        val profiles = controller.manualProfiles
        val active = profiles.snapshot().active
        val list = projects().projects
        // История ручных запусков — с диска, до замка: такт контура не ждёт чтения файлов.
        val manual = list.associate { p -> p.project to com.dpsmpx.dpai.ui.ProjectWork.manual(p, active, profiles.history(p), now) }
        val ai = if (lock.tryLock()) {
            try {
                list.map { p ->
                    val o = contours[p.project]?.orchestrator
                    com.dpsmpx.dpai.ui.ProjectWork.of(p, o?.tasks, o?.sessions, o?.pendingRequests()?.size, null)
                }.also { workCached = it }
            } finally {
                lock.unlock()
            }
        } else {
            list.map { p -> workCached?.firstOrNull { it.project == p.project.value } ?: com.dpsmpx.dpai.ui.ProjectWork.of(p, null, null, null, null) }
        }
        return ai.map { w -> w.copy(manual = list.firstOrNull { it.project.value == w.project }?.let { manual[it.project] }) }
    }

    /** Счётчики ручных сборок и проверок для виджета (DPmetaApp:D-153): идущий запуск и итоги за сутки. */
    fun manualCounts(now: com.dpsmpx.dpai.core.clock.Timestamp): com.dpsmpx.dpai.ui.ManualCounts =
        com.dpsmpx.dpai.ui.ManualCounts.of(controller.manualProfiles.snapshot().active, projects().projects.map { controller.manualProfiles.history(it) }, now)

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
        // Фоновые рабочие проекты (D-179): такт по очереди; сбой одного не останавливает остальные.
        val background = ArrayList<Notification>()
        for (b in contours.values.filter { it !== v }) {
            val t = Clock.SYSTEM.monotonicNanos()
            runCatching { b.step() }.onFailure { onProblem(Diagnostic.fromThrowable(com.dpsmpx.dpai.core.diagnostics.DiagCode.INTERNAL_ERROR, "такт фонового проекта не прошёл", it)) }
            perf("такт фонового проекта", t)
            background += drainBackground(b)
        }
        snapshot(v, selected, drain(v) + background)
    }

    /** Рабочие проекты (D-179): список и выбранный. */
    fun projects(): com.dpsmpx.dpai.ui.Projects = controller.projects()

    /** Показывать проект [id]: контуры не пересобираются, меняется только выбранный. */
    fun selectProject(id: com.dpsmpx.dpai.core.ids.ProjectId): Outcome<Unit> = controller.selectProject(id)

    /**
     * Убрать рабочий проект [id] (D-179) — под замком такта: между проверкой и записью
     * контур не начнёт новую сессию. Идут сессии — отказ: их ходы остались бы без контура.
     */
    fun removeProject(id: com.dpsmpx.dpai.core.ids.ProjectId): Outcome<Unit> = lock.withLock {
        val active = contours[id]?.orchestrator?.sessions?.sessions?.values?.count { !it.terminal } ?: 0
        if (active > 0) {
            Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.STATE_CONFLICT, "у проекта идут сессии ($active): дождитесь конца или остановите их", id.value)
        } else {
            controller.removeProject(id)
        }
    }

    /** Новые уведомления контура проекта [b] — выбранного или фонового; счётчик у каждого свой. */
    private fun drainBackground(b: LiveSystem): List<Notification> {
        val (fresh, next) = b.orchestrator.postedSince(drainedBy[b] ?: 0L)
        drainedBy[b] = next
        return fresh.mapNotNull {
            when (it) {
                is NotificationDecision.Post -> it.notification
                is NotificationDecision.Update -> it.notification
                is NotificationDecision.Held -> null
            }
        }
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

    @Volatile
    private var workspaceCached: com.dpsmpx.dpai.ui.ProjectWorkspaceView? = null

    /** Снимок без IO target; занятый такт не задерживает интерфейс (D-132). */
    fun workspace(): com.dpsmpx.dpai.ui.ProjectWorkspaceView? {
        val p = controller.project() ?: return null
        if (!lock.tryLock()) return workspaceCached?.takeIf { it.project == p }
            ?: com.dpsmpx.dpai.ui.ProjectWorkspaceView.of(p, controller.targets(), false)
        return try { workspaceLocked(p).also { workspaceCached = it } } finally { lock.unlock() }
    }

    /** Подготовить API-доступ без такта и запуска очереди CLI-задач (D-214). */
    fun apiWorkspace(p: ProjectEntry): Outcome<com.dpsmpx.dpai.ui.ProjectWorkspaceView> {
        if (!lock.tryLock()) return Outcome.failure(DiagCode.WORKTREE_OWNED, "контроллер занят; повторите запрос")
        return try {
            if (controller.project() != p) return Outcome.failure(DiagCode.STATE_CONFLICT, "выбран другой проект; откройте проект этого разговора")
            current()
            val view = workspaceLocked(p)
            if (!view.ready) Outcome.failure(DiagCode.STATE_CONFLICT, "место проекта не готово; запустите мастер подготовки") else Outcome.success(view)
        } finally { lock.unlock() }
    }

    private fun workspaceLocked(p: ProjectEntry): com.dpsmpx.dpai.ui.ProjectWorkspaceView {
        val targets = controller.targets()
        val o = (built as? LiveSystem)?.orchestrator?.takeIf {
            liveFor == (targets to p) && it.projectConfig.repository == p.repository() && it.worktrees.repositories[p.repository] == p.repository()
        }
        return com.dpsmpx.dpai.ui.ProjectWorkspaceView.of(p, targets, o != null,
            o?.tasks?.tasks?.values.orEmpty(), o?.sessions ?: com.dpsmpx.dpai.sessions.SessionsState.EMPTY,
            o?.tasks?.tasks?.keys?.mapNotNull { t -> o.taskWorktree(t)?.let { t to it } }?.toMap().orEmpty()).copy(
                paused = controller.manualProfiles.blocked, pendingEdit = controller.workspaceChanges.blocked(p.project.value))
    }

    private val workspaceWriteGate = com.dpsmpx.dpai.ui.WorkspaceWriteGate(lock) { controller.project()?.let(::workspaceLocked) }

    fun manualProfileView() = controller.manualProfiles.snapshot()
    fun manualProfile(p: ProjectEntry, kind: com.dpsmpx.dpai.ui.ManualProfileKind) = controller.manualProfiles.profile(p, kind)
    fun saveManualProfile(p: ProjectEntry, profile: com.dpsmpx.dpai.ui.ManualProfile) = controller.manualProfiles.save(p, profile)
    fun runProfile(p: ProjectEntry) = controller.manualProfiles.runProfile(p)
    fun manualHistory(p: ProjectEntry) = controller.manualProfiles.history(p)
    fun clearManualHistory(p: ProjectEntry) = controller.manualProfiles.clearHistory(p)
    fun saveRunProfile(p: ProjectEntry, profile: com.dpsmpx.dpai.ui.RunProfile) = controller.manualProfiles.saveRun(p, profile)
    fun aiRoute(p: ProjectEntry) = controller.manualProfiles.aiRoute(p)
    fun saveAiRoute(p: ProjectEntry, route: com.dpsmpx.dpai.ui.EditorAiRoute) = controller.manualProfiles.saveAiRoute(p, route)
    fun completion(p: ProjectEntry) = controller.manualProfiles.completion(p)
    fun saveCompletion(p: ProjectEntry, setting: com.dpsmpx.dpai.ui.CompletionSetting) = controller.manualProfiles.saveCompletion(p, setting)
    fun pcFiles(target: TargetEntry, root: String, writable: Boolean = false) = controller.pcFiles(target, root, writable)

    fun pcHost(target: TargetEntry) = controller.pcHost(target)
    @Volatile private var manualStop = false

    fun stopManualProfile() { manualStop = true; resumeManualProfile() }

    private fun manualClient(target: TargetEntry): com.dpsmpx.dpai.execution.agent.AgentClient? {
        val host = context?.takeIf { target.inApp }?.let { com.dpsmpx.dpai.android.linux.LinuxSetup.agentHost(it, controller.version) }
        return controller.profileAgent(target, host)
    }

    /** Единственный worker живёт вне экрана. После рестарта start не повторяется (D-134). */
    fun resumeManualProfile() {
        val app = context ?: return
        val active = controller.manualProfiles.snapshot().active ?: return
        if (!controller.manualProfiles.blocked) return
        LongJobs.get(app).start(LongJobs.WORKSPACE_PROFILE, "Ручной профиль", LongJobs.GUEST,
            onFinished = { (app.applicationContext as? DpaiApplication)?.studioChanged() }) {
            val client = manualClient(active.target)
                ?: return@start LongJobs.Done("target недоступен; пауза сохраняется", failed = true)
            watchManual(client)
        }
    }

    /** Последний выбор своих параметров профиля (D-183). */
    fun manualChoices(p: ProjectEntry, kind: com.dpsmpx.dpai.ui.ManualProfileKind) = controller.manualProfiles.choices(p, kind)

    fun saveManualChoices(p: ProjectEntry, kind: com.dpsmpx.dpai.ui.ManualProfileKind, values: Map<String, String>) =
        controller.manualProfiles.saveChoices(p, kind, values)

    /**
     * [choices] — выбранные варианты своих параметров (D-183); `null` — последний сохранённый
     * выбор (сборка перед запуском, предпросмотр). Профиль раскрывается здесь, до `prepare`.
     */
    fun startManualProfile(p: ProjectEntry, location: com.dpsmpx.dpai.ui.WorkspaceLocation, profile: com.dpsmpx.dpai.ui.ManualProfile,
        choices: Map<String, String>? = null,
        cancelled: () -> Boolean = { false },
        expectedTarget: TargetEntry? = null,
        onPrepared: ((com.dpsmpx.dpai.ui.ManualRun) -> Unit)? = null,
        environment: Boolean = false,
        beforePrepare: (() -> Outcome<Unit>)? = null,
        environmentSnapshot: Boolean = true,
        onFinished: ((LongJobs.Done, com.dpsmpx.dpai.ui.ManualRunView) -> Unit)? = null): Boolean {
        val app = context ?: return false
        if (environment && (profile.kind != com.dpsmpx.dpai.ui.ManualProfileKind.DEPENDENCIES || beforePrepare == null)) return false
        return LongJobs.get(app).start(LongJobs.WORKSPACE_PROFILE, "Ручной профиль", LongJobs.GUEST,
            onFinished = { done ->
                (app.applicationContext as? DpaiApplication)?.studioChanged()
                onFinished?.invoke(done, controller.manualProfiles.snapshot())
            }) {
            if (cancelled()) return@start LongJobs.Done("Запуск отменён до подготовки", failed = true)
            val target = controller.targets().firstOrNull { it.id == location.target }
                ?: return@start LongJobs.Done("target удалён", failed = true)
            if (expectedTarget != null && target != expectedTarget) return@start LongJobs.Done("подключение target изменилось; откройте проект заново", failed = true)
            // Параметры (D-183): встроенные и выбранные — в шаги и окружение; в якорь — уже раскрытый профиль.
            val chosen = choices ?: controller.manualProfiles.choices(p, profile.kind)
            val resolved = if (environment) profile else when (val r = profile.resolve(com.dpsmpx.dpai.ui.ManualVars.builtIns(p, target, location) + chosen)) {
                is Outcome.Failure -> return@start LongJobs.Done(r.errors.first().message, failed = true)
                is Outcome.Success -> r.value
            }
            if (choices != null && !environment) controller.manualProfiles.saveChoices(p, profile.kind, choices)
            // Что профилю нужно (D-186): в Linux приложения — проверка до запуска и ANDROID_HOME в окружение шагов;
            // на ПК-target SDK — его собственный.
            val ready = if (target.inApp && resolved.needs.isNotEmpty()) {
                val rootfs = java.io.File(app.filesDir, com.dpsmpx.dpai.android.linux.LinuxSetup.environment(app).rootfs)
                val guest = { path: String -> java.io.File(rootfs, path.removePrefix("/")) }
                val missing = com.dpsmpx.dpai.ui.AndroidSdk.missing(resolved.needs, { guest(it).exists() },
                    guest(com.dpsmpx.dpai.ui.AndroidSdk.HOME + "/ndk").list()?.isNotEmpty() == true)
                if (missing.isNotEmpty()) return@start LongJobs.Done(com.dpsmpx.dpai.ui.AndroidSdk.hint(missing), failed = true)
                resolved.copy(env = com.dpsmpx.dpai.ui.AndroidSdk.env(resolved.needs, resolved.env)).takeIf { it.valid }
                    ?: return@start LongJobs.Done("окружение шагов переполнено: добавьте ANDROID_HOME в профиль сами", failed = true)
            } else resolved
            // Проверка наличия SDK идёт ДО JVM: иначе отсутствующий SDK маскируется
            // отказом Alpine HotSpot и человек бесконечно повторяет apk add openjdk.
            // Для Android/Gradle используется только Bionic JDK, как у Termux.
            if (target.inApp && com.dpsmpx.dpai.ui.ToolchainDoctor.needsJvm(ready)) {
                val host = com.dpsmpx.dpai.android.linux.LinuxSetup.guestHost(app)
                    ?: return@start LongJobs.Done("PRECHECK_FAILED: Linux приложения недоступен; BUILD не запускался", failed = true)
                val androidBuild = ready.needs.any { it == com.dpsmpx.dpai.ui.ManualNeed.ANDROID_SDK || it == com.dpsmpx.dpai.ui.ManualNeed.ANDROID_NDK }
                when (val java = com.dpsmpx.dpai.ui.ToolchainDoctor.buildJvmPreflight(host, app.applicationInfo.targetSdkVersion, cancelled, nativeAndroid = androidBuild)) {
                    is Outcome.Failure -> return@start LongJobs.Done(java.diagnostics.first().message, failed = true)
                    is Outcome.Success -> Unit
                }
            }
            // Сборка в Linux приложения пишет на диск телефона — под защитой места (DPmetaApp:D-113); на ПК-target место — его.
            if (profile.kind != com.dpsmpx.dpai.ui.ManualProfileKind.CHECK && target.inApp) {
                (StorageSpace.check(app, com.dpsmpx.dpai.storage.space.SpaceGuard.Kind.BUILD, null, "ручная сборка проекта") as? Outcome.Failure)
                    ?.let { return@start LongJobs.Done(it.diagnostics.first().message, failed = true) }
            }
            val client = manualClient(target) ?: return@start LongJobs.Done("агент target недоступен", failed = true)
            val capability = client.profiles()
            if (capability is Outcome.Failure) return@start LongJobs.Done("обновите агент target для ручных профилей: ${capability.errors.first().message}", failed = true)
            if (beforePrepare != null) when (val allowed = beforePrepare()) {
                is Outcome.Failure -> return@start LongJobs.Done(allowed.diagnostics.first().message, failed = true)
                is Outcome.Success -> Unit
            }
            val prepared = lock.withLock {
                if (cancelled()) return@withLock Outcome.failure(DiagCode.CANCELLED_BY_USER, "запуск отменён до подготовки")
                val fresh = controller.project()
                val allowed = fresh?.let { workspaceLocked(it).allow(p, location, true) }
                    ?: return@withLock Outcome.failure(DiagCode.STATE_CONFLICT, "проект отключён")
                if (allowed is Outcome.Failure) return@withLock allowed
                if (controller.targets().firstOrNull { it.id == target.id } != target || (built as? LiveSystem)?.orchestrator?.manualProfileFree() != true) {
                    return@withLock Outcome.failure(DiagCode.WORKTREE_OWNED, "контур занят; дождитесь завершения сессий и команд")
                }
                if (!Drafts.recovery.value.ready) return@withLock Outcome.failure(DiagCode.STATE_CONFLICT, "сначала дождитесь восстановления черновиков")
                if (Drafts.dirtyIn(location, target.inApp)) return@withLock Outcome.failure(DiagCode.STATE_CONFLICT, "сначала сохраните или отмените правки редактора в этом дереве")
                controller.manualProfiles.prepare(p, target, location, ready)
            }
            if (prepared is Outcome.Failure) return@start LongJobs.Done(prepared.errors.first().message, failed = true)
            val ownRun = (prepared as Outcome.Success).value
            onPrepared?.invoke(ownRun)
            manualStop = false
            if (cancelled()) {
                controller.manualProfiles.stop(client)
                return@start LongJobs.Done("Запуск отменён до команды", failed = true)
            }
            if (environment) {
                // Bootstrap не требует работающего git. Снимок — уже под штатной долговечной паузой.
                if (environmentSnapshot) when (val snap = EnvSnapshotStore.take(app, "План окружения API")) {
                    is Outcome.Failure -> { controller.manualProfiles.failBeforeLaunch(client, snap.diagnostics.first().message); return@start LongJobs.Done(snap.diagnostics.first().message, failed = true) }
                    is Outcome.Success -> Unit
                }
                if (cancelled()) { controller.manualProfiles.stop(client); return@start LongJobs.Done("План окружения отменён до команды", failed = true) }
                controller.manualProfiles.launch(ownRun, client)
                return@start watchManual(client, cancelled)
            }
            // Проверка Git уже под долговечной паузой: такт не сменит дерево между status и start.
            val git = workspaceGit(p, location)
            val status = git.valueOrNull()
            val gitProblem = when {
                git is Outcome.Failure -> "Git перед CHECK/BUILD: ${git.diagnostics.first().message}; команды не запускались"
                status == null -> "не удалось получить состояние Git; команды CHECK/BUILD не запускались"
                status.branch != location.branch -> "ветка Git «${status.branch}» отличается от выбранной в IDE «${location.branch}»; обновите привязку проекта или переключите ветку — команды не запускались"
                status.head == null -> "Git не вернул HEAD выбранного дерева; команды не запускались"
                status.conflicted.isNotEmpty() -> "в Git есть неразрешённые конфликты: ${status.conflicted.take(5).joinToString()}; команды не запускались"
                else -> null
            }
            if (gitProblem != null) {
                controller.manualProfiles.failBeforeLaunch(client, gitProblem)
                return@start LongJobs.Done(gitProblem, failed = true)
            }
            // HEAD дерева — в историю запуска (D-156): какая версия кода собиралась.
            if (cancelled()) {
                controller.manualProfiles.stop(client)
                return@start LongJobs.Done("Запуск отменён до команды", failed = true)
            }
            controller.manualProfiles.launch(ownRun, client, status?.head)
            watchManual(client, cancelled)
        }
    }

    private suspend fun watchManual(client: com.dpsmpx.dpai.execution.agent.AgentClient, cancelled: () -> Boolean = { false }): LongJobs.Done {
        while (controller.manualProfiles.blocked) {
            val keep = if (manualStop || cancelled()) controller.manualProfiles.stop(client) else controller.manualProfiles.poll(client)
            if (!keep) break
            kotlinx.coroutines.delay(1500)
        }
        manualStop = false
        val final = controller.manualProfiles.snapshot()
        return LongJobs.Done(final.note, final.output.lines().takeLast(100), failed = !final.complete)
    }

    /** Только захват ссылки — под замком; сам Git работает вне него (D-133). */
    fun workspaceGit(p: ProjectEntry, location: com.dpsmpx.dpai.ui.WorkspaceLocation): Outcome<com.dpsmpx.dpai.git.GitStatus> {
        if (!lock.tryLock()) return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.WORKTREE_OWNED, "контроллер занят; повторите обновление Git")
        val read = try {
            val current = controller.project() ?: return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.STATE_CONFLICT, "проект отключён")
            when (val allowed = workspaceLocked(current).allow(p, location, false)) {
                is Outcome.Failure -> return allowed
                is Outcome.Success -> (built as LiveSystem).orchestrator.workspaceGitProbe(location.path)
            }
        } finally { lock.unlock() }
        return when (read) {
            is Outcome.Failure -> read
            is Outcome.Success -> read.value()
        }
    }

    /** Сверка идентичности и локальное действие под замком, общим с запуском хода. Не для UI-потока. */
    fun <T> workspaceAccess(p: ProjectEntry, location: com.dpsmpx.dpai.ui.WorkspaceLocation, write: Boolean, action: () -> Outcome<T>): Outcome<T> {
        if (!write) {
            val current = workspace() ?: return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.STATE_CONFLICT, "проект отключён")
            return when (val allowed = current.allow(p, location, false)) {
                is Outcome.Failure -> allowed
                is Outcome.Success -> action()
            }
        }
        return workspaceWriteGate.perform(p, location, action = action)
    }

    /** Весь набор под тем же замком, что запуск хода; recovery снимает только свою durable паузу. */
    fun workspaceChange(p: ProjectEntry, location: com.dpsmpx.dpai.ui.WorkspaceLocation, recovering: Boolean = false,
        action: (com.dpsmpx.dpai.ui.WorkspaceChanges) -> Outcome<Unit>): Outcome<Unit> =
        workspaceWriteGate.perform(p, location, recoveringEdit = recovering) {
            if ((built as? LiveSystem)?.orchestrator?.manualProfileFree() != true) return@perform Outcome.failure(DiagCode.WORKTREE_OWNED, "контур занят; дождитесь завершения сессий и команд")
            val target = controller.targets().firstOrNull { it.id == location.target }
                ?: return@perform Outcome.failure(DiagCode.STATE_CONFLICT, "target удалён")
            if (!Drafts.recovery.value.ready || Drafts.dirtyIn(location, target.inApp)) return@perform Outcome.failure(DiagCode.STATE_CONFLICT, "сначала сохраните или отмените правки редактора в этом дереве")
            action(com.dpsmpx.dpai.ui.WorkspaceChanges(controller.workspaceChanges))
        }

    fun workspaceCopy(project: ProjectEntry) = controller.workspaceChanges.load(project.project.value)

    /** PR проекта через `gh` на его target (D-108): `null` — контур не запущен или у него нет проекта с репозиторием. */
    fun github(): com.dpsmpx.dpai.orchestrator.github.GitHubDesk? = runCatching { (built as? LiveSystem)?.github }.getOrNull()

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

    /** Подписки Claude — для подписи маршрута (D-116): в какой подписке он создан. */
    fun subscriptions(): List<com.dpsmpx.dpai.accounts.SubscriptionState> =
        runCatching { (built as? LiveSystem)?.orchestrator?.accounts?.subscriptions?.values?.sortedBy { it.id.value } }.getOrNull().orEmpty()

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

    /**
     * Сделать [body], пока контур стоит (D-107: снимок файлов контура для
     * облачной копии — журнал и снимки не меняются посередине). Такт занят
     * дольше [waitMs] — `null`, ничего не сделано.
     */
    fun <T> whileStill(waitMs: Long, body: () -> T): T? {
        if (!lock.tryLock(waitMs, TimeUnit.MILLISECONDS)) return null
        try {
            return body()
        } finally {
            lock.unlock()
        }
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

        // get создаёт модель только с applicationContext, не с Activity.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: DemoModel? = null

        /** Одна система на процесс: поворот экрана не перезапускает её; события контура — в журнал приложения (D-43). */
        fun get(context: android.content.Context): DemoModel = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val controller = Controller.get(app.filesDir)
                // Уведомления — у Android: отключены человеком — «недоступны», иначе работают.
                controller.notificationsOk = { app.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() }
                DemoModel(app.cacheDir, controller, AppLogs.events(app), AppLogs.problems(app), app).also { instance = it }
            }
        }
    }
}
