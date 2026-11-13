package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.ids.RepositoryId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.ids.WorktreeId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.git.Worktree
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.TaskRecord
import com.dpsmpx.dpai.tasks.VerificationRecord
import java.util.concurrent.locks.ReentrantLock

/** Идентичность открытой папки: смена проекта/дерева не переиспользует старый черновик (D-132). */
data class WorkspaceLocation(
    val project: ProjectId,
    val repository: RepositoryId,
    val target: ExecutionTargetId,
    val task: TaskId?,
    val tree: WorktreeId?,
    val path: String,
    val branch: String,
) {
    val key: String get() = listOf(project.value, repository.value, target.value, task?.value, tree?.value, path, branch).joinToString("\u0001")
}

data class WorkspaceTask(
    val id: TaskId,
    val title: String,
    val state: String,
    val session: SessionId?,
    val location: WorkspaceLocation?,
    val owner: SessionId?,
    val canVerify: Boolean,
    val verification: VerificationRecord?,
)

data class ProjectWorkspaceView(
    val project: ProjectEntry,
    val target: TargetEntry?,
    val ready: Boolean,
    val tasks: List<WorkspaceTask>,
    val paused: Boolean = false,
) {
    val main: WorkspaceLocation get() = WorkspaceLocation(project.project, project.repository, project.target, null, null, project.root, project.integrationBranch)

    fun location(task: TaskId?): WorkspaceLocation? = if (task == null) main else tasks.firstOrNull { it.id == task }?.location

    /** Проверять заново под замком контура перед действием, а не доверять снимку экрана. */
    fun allow(expectedProject: ProjectEntry, location: WorkspaceLocation, write: Boolean): Outcome<Unit> {
        if (!ready || target == null || project != expectedProject || location != this.location(location.task)) {
            return Outcome.failure(DiagCode.STATE_CONFLICT, "проект или рабочее дерево изменились; откройте папку заново")
        }
        if (write && paused) return Outcome.failure(DiagCode.WORKTREE_OWNED, "проект на паузе: выполняется ручной профиль")
        if (write && location.task != null && tasks.first { it.id == location.task }.owner != null) {
            return Outcome.failure(DiagCode.WORKTREE_OWNED, "дерево занято сессией ИИ; доступно только чтение")
        }
        return Outcome.success(Unit)
    }

    companion object {
        fun of(project: ProjectEntry, targets: List<TargetEntry>, ready: Boolean, tasks: Collection<TaskRecord> = emptyList(),
               sessions: SessionsState = SessionsState.EMPTY, trees: Map<TaskId, Worktree> = emptyMap()): ProjectWorkspaceView {
            val target = targets.firstOrNull { it.id == project.target }
            val byTask = sessions.sessions.values.filter { it.review == null }.groupBy { it.task }
            val rows = tasks.filter { !it.archived }.sortedWith(compareByDescending<TaskRecord> { it.updatedAt }.thenBy { it.id.value }).map { t ->
                val tree = trees[t.id]?.takeIf { !it.removed && it.repository == project.repository }
                WorkspaceTask(t.id, t.title, t.state.name, byTask[t.id]?.maxByOrNull { it.createdAt }?.id,
                    tree?.let { WorkspaceLocation(project.project, project.repository, project.target, t.id, it.id, it.path, it.branch) },
                    tree?.owner, TaskAction.VERIFY in TaskScreens.actions(t), t.verification)
            }
            return ProjectWorkspaceView(project, target, ready && target != null, rows)
        }
    }
}

/** Только домашняя папка in-app Linux: остальные target не отображаются как локальные файлы. */
object WorkspacePaths {
    fun homeRelative(target: TargetEntry?, guest: String): String? {
        if (target?.inApp != true) return null
        val path = guest.trimEnd('/')
        if (path == "/root") return ""
        if (!path.startsWith("/root/")) return null
        val relative = path.removePrefix("/root/")
        return relative.takeIf { it.split('/').all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".." && segment.none { c -> c == '\\' || c.code < 32 || c.code == 127 }
        } }
    }
}

/** Свежая проверка и действие под одним замком с запуском ИИ, без ожидания занятого такта (D-132). */
class WorkspaceWriteGate(private val lock: ReentrantLock, private val current: () -> ProjectWorkspaceView?) {
    fun <T> perform(project: ProjectEntry, location: WorkspaceLocation, action: () -> Outcome<T>): Outcome<T> {
        if (!lock.tryLock()) return Outcome.failure(DiagCode.WORKTREE_OWNED, "контроллер занят; повторите действие")
        return try {
            val view = current() ?: return Outcome.failure(DiagCode.STATE_CONFLICT, "проект отключён")
            when (val allowed = view.allow(project, location, true)) {
                is Outcome.Failure -> allowed
                is Outcome.Success -> action()
            }
        } finally { lock.unlock() }
    }
}
