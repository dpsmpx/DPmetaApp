package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.git.GitNames
import com.dpsmpx.dpai.core.git.RepoRef
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString

/** Последний открытый PR: фиксированная команда, долговечная пауза и привязка до её снятия (DPmetaApp:D-191). */
object LatestPullRequest {
    const val MARKER = "DPAI_LATEST_PR_READY "
    // Абсолютный путь: release R8 переименовывает класс, но не каталог Java-ресурса.
    private val script by lazy { checkNotNull(javaClass.getResource("/com/dpsmpx/dpai/ui/latest_pull_request.py")).readText() }

    fun profile() = ManualProfile(ManualProfileKind.DEPENDENCIES,
        listOf(ManualStep("Подготовить последний открытый PR", listOf("python3", "-c", script), 900)))

    fun isProfile(profile: ManualProfile): Boolean = profile == LatestPullRequest.profile()

    data class Ready(val number: Int, val branch: String, val head: String, val url: String)

    fun ready(output: String): Outcome<Ready> {
        val line = output.lineSequence().lastOrNull { it.startsWith(MARKER) }
        val obj = line?.removePrefix(MARKER)?.let { JsonParser().parse(it).valueOrNull() as? JsonObject }
        val number = (obj?.get("number") as? JsonNumber)?.toLongExact()?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
        val branch = (obj?.get("branch") as? JsonString)?.value
        val head = (obj?.get("head") as? JsonString)?.value
        val url = (obj?.get("url") as? JsonString)?.value
        if (number == null || branch == null || branch.length > 100 || !GitNames.cliBranch(branch) ||
            head == null || !GitNames.sha(head) || url == null || RepoRef.fromPullUrl(url).valueOrNull() == null ||
            !url.endsWith("/pull/$number")) {
            return Outcome.failure(DiagCode.GIT_OUTPUT_INVALID, "последний PR не подтвердил ветку и HEAD; привязка сохранена")
        }
        return Outcome.success(Ready(number, branch, head, url))
    }

    /** Идемпотентно после рестарта между записью привязки и снятием паузы. Остальные настройки неизменны. */
    fun rebind(projects: Projects, run: ManualRun, output: String): Outcome<Projects> {
        if (run.path != run.project.root || run.branch != run.project.integrationBranch || run.target.id != run.project.target) {
            return Outcome.failure(DiagCode.STATE_CONFLICT, "последний PR подготавливается только в основном дереве проекта")
        }
        val ready = when (val r = ready(output)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        val updated = run.project.copy(integrationBranch = ready.branch)
        val current = projects.projects.firstOrNull { it.project == run.project.project }
        if (current != run.project && current != updated) return Outcome.failure(DiagCode.STATE_CONFLICT, "привязка проекта изменилась во время подготовки PR")
        return Outcome.success(projects.copy(projects = projects.projects.map { if (it.project == updated.project) updated else it }))
    }
}
