package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.automation.AutomationEvent
import com.dpsmpx.dpai.automation.AutomationTrigger
import com.dpsmpx.dpai.automation.Workflow
import com.dpsmpx.dpai.automation.WorkflowEvents
import com.dpsmpx.dpai.automation.WorkflowSchedule
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.util.concurrent.ConcurrentHashMap

/** Нативные события уже записаны контуром; в очередь попадают только явно включённые подписки. */
internal object AutomationEvents {
    private val interests = ConcurrentHashMap<ProjectId, List<Workflow>>()
    private val failures = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<ProjectId, Any>()

    fun reload(context: Context, project: ProjectId) {
        when (val r = AutomationWorkflows.store(context, project).list()) {
            is Outcome.Failure -> { interests.remove(project); report(context, r) }
            is Outcome.Success -> interests[project] = r.value.filter { it.enabled }
        }
    }

    private fun queue(context: Context, project: ProjectId) = WorkflowEvents(NioVfs(
        context.noBackupFilesDir.resolve("automation").resolve(project.value).toPath()), Clock.SYSTEM)

    private fun <T> queued(context: Context, project: ProjectId, operation: (WorkflowEvents) -> T): T =
        synchronized(locks.getOrPut(project) { Any() }) { operation(queue(context, project)) }

    fun emit(context: Context, project: ProjectId, type: AutomationTrigger, id: String, fingerprint: String, name: String = "") {
        val wanted = interests[project].orEmpty().filter { w -> w.triggers.any {
            it.type == type && (type != AutomationTrigger.EVENT || it.event == name)
        } }
        if (wanted.isEmpty()) return
        queued(context, project) { events ->
            for (w in wanted) report(context, events.enqueue(w, AutomationEvent(id, type, w.context, fingerprint, name)))
        }
    }

    /** Имя события имеет стабильное соответствие существующему EventType, без свободного shell. */
    fun observe(context: Context, project: ProjectId, events: List<EventEnvelope>) {
        if (interests[project].orEmpty().none { w -> w.triggers.any { it.type == AutomationTrigger.EVENT } }) return
        for (event in events) emit(context, project, AutomationTrigger.EVENT, event.id.value, Hashing.sha256(event.toJson()).hex,
            "studio." + event.type.name.lowercase().replace('_', '.'))
    }

    fun fileChanged(context: Context, project: ProjectId, path: String, contentHash: String) {
        emit(context, project, AutomationTrigger.FILES_CHANGED, AutomationWorkflows.eventId(),
            Hashing.sha256(path + ":" + contentHash).hex)
    }

    /** API-проект без CLI-контура тоже получает расписания в прежней foreground-службе. */
    fun enabled(context: Context): Boolean {
        val controller = Controller.get(context.filesDir)
        val project = controller.project() ?: return false
        if (interests[project.project] == null) reload(context, project.project)
        val target = controller.targets().firstOrNull { it.id == project.target } ?: return false
        val revision = com.dpsmpx.dpai.ui.AutomationContext.revision(project, target)
        return interests[project.project].orEmpty().any { w -> w.context == revision && w.triggers.any {
            it.type in setOf(AutomationTrigger.SCHEDULE, AutomationTrigger.EVENT, AutomationTrigger.FILES_CHANGED)
        } }
    }

    /** Один активный проект и прежняя служба: очередь других проектов сохраняется до их открытия. */
    fun tick(context: Context) {
        val controller = Controller.get(context.filesDir)
        val project = controller.project() ?: return
        if (interests[project.project] == null) reload(context, project.project)
        val target = controller.targets().firstOrNull { it.id == project.target } ?: return
        val revision = com.dpsmpx.dpai.ui.AutomationContext.revision(project, target)
        val store = AutomationWorkflows.store(context, project.project)
        queued(context, project.project) { events ->
            for (w in interests[project.project].orEmpty().filter { it.context == revision }) {
                for (event in WorkflowSchedule.events(w, Hashing.sha256(revision).hex, Clock.SYSTEM.now())) {
                    when (val old = store.result(w.id, event.id)) {
                        is Outcome.Failure -> report(context, old)
                        is Outcome.Success -> if (old.value == null || old.value?.get("status") == JsonString("RUNNING"))
                            report(context, events.enqueue(w, event))
                    }
                }
            }
            val pending = when (val r = events.pending()) {
                is Outcome.Failure -> { report(context, r); return@queued }
                is Outcome.Success -> r.value
            }
            if (LongJobs.get(context).busyGuest() || controller.manualProfiles.blocked ||
                DemoModel.get(context).liveOrchestrator()?.manualProfileFree() == false) return@queued
            val next = pending.firstOrNull() ?: return@queued
            val definition = when (val r = store.workflow(next.workflow)) {
                is Outcome.Failure -> { report(context, r); return@queued }
                is Outcome.Success -> r.value
            }
            if (definition == null || !definition.enabled || definition.revision != next.revision) {
                // Смена определения не разрешает старому событию запустить новый план.
                report(context, events.acknowledge(next.key))
                return@queued
            }
            AutomationUiCalls.startWorkflow(context, project.project, next.workflow, next.revision, next.event,
                onOutcome = { result ->
                    // Только занятость до старта можно повторять без решения; эффект не начат.
                    if (result is Outcome.Failure && result.errors.any { it.code == DiagCode.WORKTREE_OWNED }) return@startWorkflow
                    val recorded = if (result is Outcome.Failure) store.refuse(definition, next.event,
                        result.errors.joinToString("; ") { it.message }, Clock.SYSTEM.now().epochMillis) else Outcome.success(Unit)
                    if (recorded is Outcome.Failure) report(context, recorded) else queued(context, project.project) {
                        report(context, it.acknowledge(next.key))
                    }
                })
        }
    }

    private fun report(context: Context, outcome: Outcome<*>) {
        if (outcome !is Outcome.Failure) return
        val message = outcome.diagnostics.joinToString("; ") { it.message }
        val now = Clock.SYSTEM.monotonicNanos() / 1_000_000
        val key = Hashing.sha256(message).hex
        synchronized(failures) {
            if (failures[key]?.let { now - it < 60_000 } == true) return
            if (failures.size >= 100) failures.clear()
            failures[key] = now
        }
        AppLogs.get(context).warn("automation", message)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, context.getString(R.string.workflow_event_failed), android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
