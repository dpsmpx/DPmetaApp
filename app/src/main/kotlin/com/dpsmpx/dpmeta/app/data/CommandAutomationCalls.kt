package com.dpsmpx.dpmeta.app.data

import android.content.Context
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.AutomationPlan
import com.dpsmpx.dpai.ui.ManualProfile
import com.dpsmpx.dpai.ui.ManualProfileKind
import com.dpsmpx.dpai.ui.ManualStep
import com.dpsmpx.dpai.ui.ProjectEntry
import com.dpsmpx.dpmeta.app.PultActivity
import com.dpsmpx.dpmeta.app.screens.CommandActions
import com.dpsmpx.dpmeta.formats.CommandFormat
import java.io.File

/** Прямой адаптер сохраняет каталог пользователя, готовность, политику и журнал команд (D-195). */
object CommandAutomationCalls {
    fun run(context: Context, project: ProjectEntry, id: String, linux: CommandActions.Linux,
        missing: (List<String>) -> List<String>, execute: (AutomationPlan) -> Outcome<JsonObject>): Outcome<JsonObject> {
        val text = try { context.assets.open(PultActivity.COMMANDS_ASSET).bufferedReader().use { it.readText() } }
            catch (_: java.io.IOException) { return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "реестр команд не прочитан") }
        val specs = when (val r = CommandFormat.parse(text)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value.commands }
        val spec = specs.firstOrNull { "pult." + it.id == id }
            ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "команда пульта удалена", id)
        val repo = PultRepository(NioVfs(context.filesDir.toPath().resolve("dpmeta")), Clock.SYSTEM,
            PultPool.work, File(context.filesDir, PultActivity.CLONES_DIR), PultPool.clones)
        val catalog = when (val r = repo.commandProjects()) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        val row = catalog.projects.firstOrNull { entry ->
            project.root.trimEnd('/') in setOf(CommandActions.GUEST_ROOT + "/" + entry.repo, CommandActions.LEGACY_GUEST_ROOT + "/" + entry.repo)
        } ?: return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "проект не является выбранным клоном пульта")
        val spot = when (val r = CommandActions.spot(spec, catalog, row.name)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        val clone = repo.cloneDir(spot.repo)
        val present = File(clone, ".git").isDirectory
        val script = CommandActions.scriptInClone(spec, spot)?.let { File(clone, it).isFile }
        val readiness = CommandActions.readiness(spec, spot, CommandActions.Facts(linux, missing(spec.packages),
            present, present && (spot.inClone.isEmpty() || File(clone, spot.inClone).isDirectory), script))
        if (!CommandActions.ready(readiness)) return Outcome.failure(DiagCode.TARGET_CHECK_FAILED,
            readiness.first { !it.ok }.hint ?: readiness.first { !it.ok }.title)
        // Данные передаются argv; shell выполняет только постоянный переход в каталог.
        val argv = if (spot.inClone.isEmpty()) CommandActions.argv(spec) else
            listOf("/bin/sh", "-c", "cd \"$1\" && shift && exec \"$@\"", "--", spot.inClone) + CommandActions.argv(spec)
        val plan = AutomationPlan(id, spec.title, if (spec.level == 0) com.dpsmpx.dpai.core.operations.ActionLevel.READ else com.dpsmpx.dpai.core.operations.ActionLevel.REVERSIBLE,
            ManualProfile(ManualProfileKind.DEPENDENCIES, listOf(ManualStep(spec.title.take(60), argv, spec.timeoutMinutes * 60L))),
            snapshot = false, scope = AutomationPlan.Scope.PROJECT)
        if (spec.level == 0) return execute(plan)
        var output: Outcome<JsonObject>? = null
        val command = CommandActions.command(spec, spot, repo.worktree(spot.repo))
        val logged = repo.runCommand(command, { it.shown(command) }) {
            val r = execute(plan).also { output = it }
            when (r) {
                is Outcome.Failure -> r
                is Outcome.Success -> Outcome.success(CommandActions.Exit(0))
            }
        }
        return when (logged) {
            is Outcome.Failure -> logged
            is Outcome.Success -> output ?: Outcome.failure(DiagCode.INTERNAL_ERROR, "нет результата исполнителя")
        }
    }
}
