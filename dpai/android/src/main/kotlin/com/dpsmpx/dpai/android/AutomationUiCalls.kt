package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.operation.ActorKind
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.providers.Cancel
import java.util.concurrent.ConcurrentHashMap

/** UI вызывает тот же контракт, что API; работа живёт в прежнем LongJobs вне экрана (D-215). */
internal object AutomationUiCalls {
    private val cancellations = ConcurrentHashMap<String, Cancel>()
    fun key(project: ProjectId) = "automation:${project.value}"

    fun start(context: Context, project: ProjectId, id: String, revision: String, parameters: JsonObject): Boolean {
        val app = context.applicationContext
        val key = key(project)
        val cancel = Cancel()
        if (cancellations.putIfAbsent(key, cancel) != null) return false
        val started = LongJobs.get(app).start(key, app.getString(R.string.automation_title), onFinished = { cancellations.remove(key, cancel) }) {
            val result = when (val session = ApiProjectRuntime.open(app, project, key)) {
                is Outcome.Failure -> session
                is Outcome.Success -> session.value.use { it.invokeAutomation(id, revision, parameters, cancel, ActorKind.USER) }
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
        if (!started) cancellations.remove(key, cancel)
        return started
    }

    fun stop(project: ProjectId) { cancellations[key(project)]?.cancel() }
}
