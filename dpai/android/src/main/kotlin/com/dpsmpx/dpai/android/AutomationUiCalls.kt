package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.operation.ActorKind
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.providers.Cancel
import com.dpsmpx.dpai.automation.AutomationEvent
import java.util.concurrent.ConcurrentHashMap

/** UI вызывает тот же контракт, что API; работа живёт в прежнем LongJobs вне экрана (D-215). */
internal object AutomationUiCalls {
    private class Running(val cancel: Cancel) { @Volatile var id: String = "" }
    private val cancellations = ConcurrentHashMap<String, Running>()
    fun key(project: ProjectId) = "automation:${project.value}"

    fun start(context: Context, project: ProjectId, id: String, revision: String, parameters: JsonObject, cli: Boolean = false,
        receipt: (String) -> Unit = {}): Boolean {
        val app = context.applicationContext
        val key = key(project)
        val cancel = Cancel()
        val running = Running(cancel)
        if (cancellations.putIfAbsent(key, running) != null) return false
        val jobs = LongJobs.get(app)
        val started = jobs.start(key, app.getString(R.string.automation_title), LongJobs.GUEST,
            onAccepted = { running.id = it; receipt(it) }, onFinished = { cancellations.remove(key, running) }) {
            val result = when (val session = ApiProjectRuntime.open(app, project, key, jobs.permit(key))) {
                is Outcome.Failure -> session
                is Outcome.Success -> session.value.use { it.invokeAutomation(id, revision, parameters, cancel, ActorKind.USER, confirmProfiles = cli) }
            }
            when (result) {
                is Outcome.Failure -> LongJobs.Done(result.diagnostics.joinToString("; ") { it.message }, failed = true)
                is Outcome.Success -> {
                    val failed = result.value["status"] == JsonString("FAILED")
                    LongJobs.Done(app.getString(if (failed) R.string.automation_failed else R.string.automation_finished),
                        listOf(CanonicalJson.write(result.value)), failed = failed)
                }
            }
        }
        if (!started) cancellations.remove(key, running)
        return started
    }

    fun stop(project: ProjectId, expected: String? = null) {
        val running = cancellations[key(project)] ?: return
        if (expected == null || expected == running.id) running.cancel.cancel()
    }

    fun startWorkflow(context: Context, project: ProjectId, id: String, revision: String, event: AutomationEvent? = null, cli: Boolean = false,
        receipt: (String) -> Unit = {}, onOutcome: (Outcome<JsonObject>) -> Unit = {},
        afterOutcome: (Outcome<JsonObject>) -> Unit = {}, approvalOwner: String? = null,
        actor: ActorKind = if (event == null) ActorKind.USER else ActorKind.SYSTEM, cancelled: () -> Boolean = { false }): Boolean {
        val app = context.applicationContext
        val key = key(project)
        val cancel = Cancel(cancelled)
        val running = Running(cancel)
        var outcome: Outcome<JsonObject>? = null
        if (cancellations.putIfAbsent(key, running) != null) return false
        val jobs = LongJobs.get(app)
        val started = jobs.start(key, app.getString(R.string.automation_title), LongJobs.GUEST,
            afterFinished = { outcome?.let(afterOutcome) },
            onAccepted = { running.id = it; receipt(it) }, onFinished = {
                cancellations.remove(key, running)
                if (outcome == null) outcome = Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.INTERNAL_ERROR, "исполнитель не вернул исход")
                onOutcome(outcome!!)
            }) {
            val actual = event ?: AutomationEvent(running.id,
                com.dpsmpx.dpai.automation.AutomationTrigger.MANUAL,
                AutomationWorkflows.store(app, project).workflow(id).valueOrNull()?.context.orEmpty(),
                com.dpsmpx.dpai.core.hashing.Hashing.sha256(revision).hex)
            when (val r = AutomationWorkflows.run(app, project, id, revision, cancel, actual, cli = cli,
                parent = jobs.permit(key), approvalOwner = approvalOwner, actor = actor,
                progress = { at, total -> LongJobs.get(app).report(key, running.id, app.getString(R.string.workflow_progress, at, total)) }).also { outcome = it }) {
                is Outcome.Failure -> LongJobs.Done(r.diagnostics.joinToString("; ") { it.message }, failed = true)
                is Outcome.Success -> LongJobs.Done(app.getString(R.string.automation_finished),
                    listOf(CanonicalJson.write(r.value)), failed = r.value["status"] != JsonString("PASSED"))
            }
        }
        if (!started) cancellations.remove(key, running)
        return started
    }

    /** API ждёт результат того же worker после освобождения ресурса, без собственного исполнителя. */
    fun awaitWorkflow(context: Context, project: ProjectId, id: String, revision: String, event: AutomationEvent,
        cancel: Cancel, approvalOwner: String?, actor: ActorKind): Outcome<JsonObject> {
        val done = java.util.concurrent.CompletableFuture<Outcome<JsonObject>>()
        if (!startWorkflow(context, project, id, revision, event, afterOutcome = { done.complete(it) },
            approvalOwner = approvalOwner, actor = actor, cancelled = { cancel.cancelled }))
            return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.WORKTREE_OWNED, "окружение занято; сценарий не запущен")
        while (true) {
            try { return done.get(1, java.util.concurrent.TimeUnit.SECONDS) } catch (_: java.util.concurrent.TimeoutException) {
                if (cancel.cancelled) return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.CANCELLED_BY_USER,
                    "остановка сценария запрошена; итог сохраняется у worker")
            }
        }
    }
}
