package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.notifications.Notification
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
    /** Новые уведомления контроллера; у демо их нет — телефон не шумит демо-событиями. */
    val notifications: List<Notification> = emptyList(),
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

    /** Такт и снимок; новые уведомления контроллера — в снимке, один раз. */
    @Synchronized
    fun step(selected: String?): Snapshot {
        val v = current()
        v.step()
        val session = selected?.let { IdKinds.SESSION.parse(it).valueOrNull() }
        return Snapshot(v.mode, v is LiveSystem, v.dashboard(), v.board(), session?.let(v::session), (v as? LiveSystem)?.drainNotifications().orEmpty())
    }

    /** Снимок без такта — когда такт делает служба контроллера (D-36). */
    @Synchronized
    fun view(selected: String?): Snapshot {
        val v = current()
        val session = selected?.let { IdKinds.SESSION.parse(it).valueOrNull() }
        return Snapshot(v.mode, v is LiveSystem, v.dashboard(), v.board(), session?.let(v::session))
    }

    /** Есть ли настоящий контроллер: тогда такт — в службе переднего плана. */
    fun hasLive(): Boolean = controller.targets().any { it.configDir != null }

    @Synchronized
    fun addTask(title: String, objective: String): Outcome<TaskId> {
        val v = current() as? LiveSystem
            ?: return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "задачи ставятся только контроллеру: заведите target с каталогом Claude")
        return v.addTask(title.trim(), objective.trim())
    }

    /** Кнопка уведомления; без контроллера — отказ. */
    @Synchronized
    fun respond(uri: String): Outcome<Boolean> = (current() as? LiveSystem)?.respond(uri)
        ?: Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "одобрения — только у контроллера")

    /** Проверить вход слота target заново — после входа автора в Claude. */
    @Synchronized
    fun probe(target: ExecutionTargetId) {
        (current() as? LiveSystem)?.probeSlot(target)
    }

    companion object {
        @Volatile
        private var instance: DemoModel? = null

        /** Одна система на процесс: поворот экрана не перезапускает её. */
        fun get(cacheDir: File, controller: Controller): DemoModel = instance ?: synchronized(this) {
            instance ?: DemoModel(cacheDir, controller).also { instance = it }
        }
    }
}
