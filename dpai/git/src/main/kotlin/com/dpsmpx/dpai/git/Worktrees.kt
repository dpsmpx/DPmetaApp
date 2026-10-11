package com.dpsmpx.dpai.git

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.RepositoryId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.WorktreeId
import com.dpsmpx.dpai.core.result.Outcome

/** Репозиторий на target: основное дерево [root]. */
data class Repository(val id: RepositoryId, val target: ExecutionTargetId, val root: String)

/**
 * Рабочее дерево (ТЗ, §29, §30): путь, ветка, владелец. Изменяемое дерево
 * в каждый момент принадлежит не больше чем одной сессии (инвариант ТЗ,
 * §118).
 */
data class Worktree(
    val id: WorktreeId,
    val repository: RepositoryId,
    val path: String,
    val branch: String,
    val owner: SessionId? = null,
    val removed: Boolean = false,
)

data class WorktreesState(
    val repositories: Map<RepositoryId, Repository> = emptyMap(),
    val worktrees: Map<WorktreeId, Worktree> = emptyMap(),
) {
    fun ownedBy(session: SessionId): List<Worktree> = worktrees.values.filter { it.owner == session && !it.removed }

    companion object {
        val EMPTY = WorktreesState()
    }
}

/** Факт о репозиториях и деревьях (D-12). */
sealed interface WorktreeEvent {
    val at: Timestamp

    data class RepositoryRegistered(val repository: Repository, override val at: Timestamp) : WorktreeEvent

    data class Created(val worktree: Worktree, override val at: Timestamp) : WorktreeEvent

    data class Claimed(val worktree: WorktreeId, val session: SessionId, override val at: Timestamp) : WorktreeEvent

    data class Released(val worktree: WorktreeId, val session: SessionId, override val at: Timestamp) : WorktreeEvent

    data class Removed(val worktree: WorktreeId, override val at: Timestamp) : WorktreeEvent
}

/**
 * Реестр деревьев: регистрация, создание, захват сессией, освобождение
 * (ТЗ, §29–§31). Решения возвращают события; состояние меняет [apply].
 */
object Worktrees {
    private val BRANCH = Regex("[A-Za-z0-9._/-]{1,200}")

    fun register(state: WorktreesState, repo: Repository, now: Timestamp): Outcome<List<WorktreeEvent>> {
        state.repositories[repo.id]?.let { return if (it == repo) Outcome.success(emptyList()) else Outcome.failure(DiagCode.VALUE_INVALID, "репозиторий с этим id уже другой", "repository ${repo.id}") }
        if (state.repositories.values.any { it.target == repo.target && it.root == repo.root }) {
            return Outcome.failure(DiagCode.WORKTREE_PATH_IN_USE, "этот каталог уже зарегистрирован", repo.root)
        }
        return Outcome.success(listOf(WorktreeEvent.RepositoryRegistered(repo, now)))
    }

    /** Новое дерево: путь и ветка не заняты другим живым деревом того же target и репозитория. */
    fun create(state: WorktreesState, id: WorktreeId, repository: RepositoryId, path: String, branch: String, now: Timestamp): Outcome<List<WorktreeEvent>> {
        val repo = state.repositories[repository] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого репозитория", "repository $repository")
        if (!BRANCH.matches(branch) || branch.startsWith("-") || ".." in branch) return Outcome.failure(DiagCode.VALUE_INVALID, "имя ветки недопустимо", branch)
        val live = state.worktrees.values.filter { !it.removed }
        live.firstOrNull { w -> state.repositories[w.repository]?.target == repo.target && w.path == path }?.let {
            return Outcome.failure(DiagCode.WORKTREE_PATH_IN_USE, "путь занят деревом ${it.id}", path)
        }
        live.firstOrNull { it.repository == repository && it.branch == branch }?.let {
            return Outcome.failure(DiagCode.BRANCH_IN_USE, "ветка уже выписана в дерево ${it.id}", branch)
        }
        return Outcome.success(listOf(WorktreeEvent.Created(Worktree(id, repository, path, branch), now)))
    }

    /** Захват дерева сессией: свободное — её; своё — без изменений; чужое — `WORKTREE_OWNED`. */
    fun claim(state: WorktreesState, id: WorktreeId, session: SessionId, now: Timestamp): Outcome<List<WorktreeEvent>> {
        val w = state.worktrees[id]?.takeIf { !it.removed } ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого дерева", "worktree $id")
        return when (w.owner) {
            session -> Outcome.success(emptyList())
            null -> Outcome.success(listOf(WorktreeEvent.Claimed(id, session, now)))
            else -> Outcome.failure(DiagCode.WORKTREE_OWNED, "дерево принадлежит сессии ${w.owner}", "worktree $id")
        }
    }

    fun release(state: WorktreesState, id: WorktreeId, session: SessionId, now: Timestamp): Outcome<List<WorktreeEvent>> {
        val w = state.worktrees[id] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого дерева", "worktree $id")
        return when (w.owner) {
            null -> Outcome.success(emptyList())
            session -> Outcome.success(listOf(WorktreeEvent.Released(id, session, now)))
            else -> Outcome.failure(DiagCode.WORKTREE_NOT_OWNED, "дерево принадлежит другой сессии", "worktree $id")
        }
    }

    /** Право записи: изменяет дерево только его владелец. */
    fun checkWriter(state: WorktreesState, id: WorktreeId, session: SessionId): Outcome<Worktree> {
        val w = state.worktrees[id]?.takeIf { !it.removed } ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого дерева", "worktree $id")
        return if (w.owner == session) Outcome.success(w) else Outcome.failure(DiagCode.WORKTREE_NOT_OWNED, "сессия $session не владеет деревом", "worktree $id")
    }

    fun remove(state: WorktreesState, id: WorktreeId, now: Timestamp): Outcome<List<WorktreeEvent>> {
        val w = state.worktrees[id] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого дерева", "worktree $id")
        if (w.owner != null) return Outcome.failure(DiagCode.WORKTREE_OWNED, "дерево занято сессией ${w.owner}", "worktree $id")
        return Outcome.success(if (w.removed) emptyList() else listOf(WorktreeEvent.Removed(id, now)))
    }

    fun apply(state: WorktreesState, e: WorktreeEvent): WorktreesState = when (e) {
        is WorktreeEvent.RepositoryRegistered -> state.copy(repositories = state.repositories + (e.repository.id to e.repository))
        is WorktreeEvent.Created -> state.copy(worktrees = state.worktrees + (e.worktree.id to e.worktree))
        is WorktreeEvent.Claimed -> state.update(e.worktree) {
            check(it.owner == null || it.owner == e.session) { "дерево ${e.worktree} уже чьё-то" }
            it.copy(owner = e.session)
        }
        is WorktreeEvent.Released -> state.update(e.worktree) { it.copy(owner = null) }
        is WorktreeEvent.Removed -> state.update(e.worktree) { it.copy(removed = true) }
    }

    fun applyAll(state: WorktreesState, events: List<WorktreeEvent>): WorktreesState = events.fold(state, ::apply)

    private fun WorktreesState.update(id: WorktreeId, f: (Worktree) -> Worktree): WorktreesState =
        worktrees[id]?.let { copy(worktrees = worktrees + (id to f(it))) } ?: this
}

/** Решение по пересечению затрагиваемых файлов (ТЗ, §31). */
enum class OverlapVerdict { ALLOW, WARN, SERIALIZE, REJECT }

/**
 * Политика пересечения: сессия собирается менять [touched]; другие живые
 * сессии того же репозитория меняют свои наборы (ТЗ, §31).
 *
 * - пересечение по файлу из [critical] (сборка, зависимости, миграции) — `REJECT`;
 * - пересечение по файлу — `SERIALIZE`: одна ждёт другую;
 * - общий каталог без общего файла — `WARN`;
 * - иначе — `ALLOW`.
 */
data class OverlapPolicy(val critical: List<Regex> = DEFAULT_CRITICAL) {
    data class Result(val verdict: OverlapVerdict, val with: Map<SessionId, Set<String>>)

    fun evaluate(touched: Set<String>, others: Map<SessionId, Set<String>>): Result {
        var verdict = OverlapVerdict.ALLOW
        val with = LinkedHashMap<SessionId, Set<String>>()
        for ((session, files) in others.toSortedMap(compareBy { it.value })) {
            val same = touched intersect files
            val v = when {
                same.any { f -> critical.any { it.matches(f) } } -> OverlapVerdict.REJECT
                same.isNotEmpty() -> OverlapVerdict.SERIALIZE
                touched.map(::dir).toSet().intersect(files.map(::dir).toSet()).isNotEmpty() -> OverlapVerdict.WARN
                else -> OverlapVerdict.ALLOW
            }
            if (v != OverlapVerdict.ALLOW) with[session] = if (same.isNotEmpty()) same else touched.filter { dir(it) in files.map(::dir) }.toSet()
            if (v.ordinal > verdict.ordinal) verdict = v
        }
        return Result(verdict, with)
    }

    private fun dir(path: String): String = path.substringBeforeLast('/', "")

    companion object {
        val DEFAULT_CRITICAL: List<Regex> = listOf(
            Regex("(.*/)?(settings\\.gradle\\.kts|build\\.gradle\\.kts|package\\.json|package-lock\\.json|Cargo\\.toml|go\\.mod)"),
            Regex(Regex.escape("gradle/libs.versions.toml")),
            Regex("(.*/)?migrations?/.*"),
        )
    }
}
