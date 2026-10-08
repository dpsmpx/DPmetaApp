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
import com.dpsmpx.dpai.ui.RequestCard
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
)

/**
 * Какая система сейчас показывается (D-34, D-35): есть target с каталогом
 * Claude — настоящий контроллер ([LiveSystem]), иначе демо. Смена targets
 * пересоздаёт контроллер. Все вызовы — под одним замком: шаг идёт с
 * фонового потока, действия — из интерфейса. Журнал демо не хранится
 * между запусками.
 */
class DemoModel(private val cacheDir: File, private val controller: Controller) {
    private var demo: DemoSystem? = null
    private var live: LiveSystem? = null
    private var liveFor: Pair<List<TargetEntry>, ProjectEntry?>? = null

    private fun current(): ControlView {
        val targets = controller.targets()
        if (targets.any { it.configDir != null }) {
            val key = targets to controller.project()
            if (live == null || liveFor != key) {
                live = controller.live(key.first, key.second)
                liveFor = key
            }
            return live!!
        }
        return demo ?: run {
            val dir = File(cacheDir, "demo").apply {
                deleteRecursively()
                mkdirs()
            }
            DemoSystem(Clock.SYSTEM, NioVfs(dir.toPath())).also { demo = it }
        }
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
        val shown = if (v is LiveSystem) o.pendingRequests() else o.pendingRequests().filter { it.purpose == RequestPurpose.PROBE.code }
        return Snapshot(
            v.mode, v is LiveSystem, v.dashboard(), v.board(), session?.let(v::session), notifications,
            RequestScreens.cards(shown, o.tasks, Clock.SYSTEM.now()),
        )
    }

    /** Такт и снимок; новые уведомления — в снимке, один раз. */
    @Synchronized
    fun step(selected: String?): Snapshot {
        val v = current()
        v.step()
        return snapshot(v, selected, drain(v))
    }

    /** Снимок без такта — когда такт делает служба контроллера (D-36). */
    @Synchronized
    fun view(selected: String?): Snapshot = snapshot(current(), selected, emptyList())

    /**
     * Ответ человека на запрос (D-44) — с кнопки уведомления, окна поверх
     * приложений или экрана; ссылку и хеш проверяет оркестратор.
     */
    @Synchronized
    fun answer(uri: String, text: String?): Outcome<RequestAnswer> = try {
        current().orchestrator.answer(uri, text, ACTOR).outcome
    } catch (e: Exception) {
        Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.INTERNAL_ERROR, "контроллер не принял ответ", e)))
    }

    /** Проверочный запрос вида [kind] (экран «Запросы», `docs/DEVICE_CHECK.md`). */
    @Synchronized
    fun probe(kind: RequestKind, ttl: Duration?): Outcome<RequestId> = try {
        current().orchestrator.probe(kind, ttl)
    } catch (e: Exception) {
        Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.INTERNAL_ERROR, "контроллер не создал запрос", e)))
    }

    /** Канал показал запрос: факт в журнал (D-44). */
    @Synchronized
    fun delivered(id: RequestId, channel: String) {
        runCatching { current().orchestrator.delivered(id, channel) }
    }

    /** Ждёт ли запрос ответа — оболочка снимает уведомление и окно закрытого. */
    @Synchronized
    fun pending(): Map<RequestId, RequestCard> = runCatching {
        val o = current().orchestrator
        RequestScreens.cards(o.pendingRequests(), o.tasks, Clock.SYSTEM.now()).associateBy { it.id }
    }.getOrDefault(emptyMap())

    /** Последняя сессия задачи — куда ведёт ссылка `dpai://task/…` из уведомления. */
    @Synchronized
    fun sessionOf(task: TaskId): SessionId? = runCatching {
        current().orchestrator.sessions.sessions.values.filter { it.task == task }.maxByOrNull { it.createdAt.epochMillis }?.id
    }.getOrNull()

    /** Есть ли настоящий контроллер: тогда такт — в службе переднего плана. */
    fun hasLive(): Boolean = controller.targets().any { it.configDir != null }

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

    @Synchronized
    fun addTask(title: String, objective: String): Outcome<TaskId> = liveSystem().then { v ->
        v?.addTask(title.trim(), objective.trim())
            ?: Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "задачи ставятся только контроллеру: заведите target с каталогом Claude")
    }

    /** Кнопка уведомления; без контроллера — отказ. */
    @Synchronized
    fun respond(uri: String): Outcome<Boolean> = liveSystem().then { v ->
        v?.respond(uri) ?: Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "одобрения — только у контроллера")
    }

    /** Проверить вход слота target заново — после входа автора в Claude. */
    @Synchronized
    fun probe(target: ExecutionTargetId) {
        liveSystem().valueOrNull()?.probeSlot(target)
    }

    companion object {
        /** Кто отвечает с телефона: владелец приложения. */
        const val ACTOR = "автор"

        @Volatile
        private var instance: DemoModel? = null

        /** Одна система на процесс: поворот экрана не перезапускает её. */
        fun get(cacheDir: File, controller: Controller): DemoModel = instance ?: synchronized(this) {
            instance ?: DemoModel(cacheDir, controller).also { instance = it }
        }
    }
}
