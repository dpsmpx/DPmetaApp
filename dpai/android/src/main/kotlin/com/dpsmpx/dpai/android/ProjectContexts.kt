package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.contexts.ContextBinding
import com.dpsmpx.dpai.contexts.ContextSource
import com.dpsmpx.dpai.contexts.SourceLevel
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.SubTree
import com.dpsmpx.dpai.security.Origin
import com.dpsmpx.dpai.security.Sensitivity
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.tasks.TaskRecord
import com.dpsmpx.dpai.ui.AutomationContext
import com.dpsmpx.dpai.ui.ContextStore
import com.dpsmpx.dpai.ui.ContextWorkspace
import com.dpsmpx.dpai.ui.ProjectEntry
import com.dpsmpx.dpai.ui.TargetEntry
import com.dpsmpx.dpai.ui.WorkspacePaths

/** Только адаптеры штатных FileTree; приватные настройки не видны проектным инструментам. */
internal object ProjectContexts {
    fun store(context: Context, project: ProjectEntry, target: TargetEntry) = ContextStore(NioVfs(
        context.filesDir.resolve("context-engineering").resolve(project.project.value).toPath()), AutomationContext.revision(project, target))

    fun native(context: Context, project: ProjectEntry, target: TargetEntry, task: TaskRecord, root: String?): Outcome<List<ContextSource>> {
        val settings = when (val r = store(context, project, target).load()) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        if (settings.profile.restricted) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED,
            "нативный CLI не изолирует файловую область профиля; используйте API-чат или явно выберите PROJECT_WIDE")
        if (root == null) return Outcome.success(emptyList())
        val tree: FileTree = if (target.inApp) {
            val relative = WorkspacePaths.homeRelative(target, root) ?: return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "рабочее дерево вне домашней папки")
            SubTree(GuestPaths.homeTree(context), relative)
        } else when (val r = Controller.get(context.filesDir).pcFiles(target, root)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        try {
            val binding = ContextBinding(project.project, AutomationContext.revision(project, target) + ":" + root, "task:" + task.id.value, settings.profile.revision)
            val workspace = ContextWorkspace(tree, settings.profile, binding)
            val packet = when (val r = workspace.packet(task.objective, task.scope.orEmpty().sorted().take(20),
                related = "Зависимости задачи: " + task.dependencies.sortedBy { it.value }.joinToString { it.value },
                evidence = task.verification?.let { "Прошлый результат (не свежая проверка): verdict=${it.verdict}, reportHash=${it.reportHash ?: "UNKNOWN"}, waiver=${it.waivedBy ?: "NONE"}, reason=${it.reason.orEmpty()}" }
                    ?: "Свежего результата проверки задачи нет.")) {
                is Outcome.Failure -> return r; is Outcome.Success -> r.value
            }
            val now = when (val r = workspace.hashes(packet.stamps.keys)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
            if (!packet.fresh(binding, now, null)) return Outcome.failure(DiagCode.STATE_CONFLICT, "файлы изменились во время подготовки контекста задачи")
            return Outcome.success(packet.selection.included.map { s -> ContextSource("context:" + com.dpsmpx.dpai.core.hashing.Hashing.sha256(s.key).hex, s.level, project.project, s.sensitivity, s.origin, s.content) } +
                ContextSource("context:references", SourceLevel.PROJECT, project.project, Sensitivity.INTERNAL, Origin.REPOSITORY,
                    "Профиль ${settings.profile.mode}, ревизия ${settings.profile.revision}; снимок не атомарен; commit будет сверяться Git исполнителя.\n" +
                        packet.references.joinToString("\n") { it.path + " sha256=" + (it.sha256 ?: "ABSENT") } +
                        "\nВыбраны первые ${task.scope.orEmpty().size.coerceAtMost(20)} из ${task.scope.orEmpty().size} объявленных путей задачи; прочие доступны по запросу.\nНеполнота: " + packet.incomplete.joinToString() + "\nНе вошли: " + packet.selection.omitted.joinToString { it.key }))
        } finally { (tree as? AutoCloseable)?.close() }
    }
}
