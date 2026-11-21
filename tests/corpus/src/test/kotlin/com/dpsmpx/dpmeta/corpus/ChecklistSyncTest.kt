package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.github.Branch
import com.dpsmpx.dpmeta.github.CommitInfo
import com.dpsmpx.dpmeta.github.PullRequest
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.ProjectState
import com.dpsmpx.dpmeta.model.ObservationSource
import com.dpsmpx.dpmeta.model.SyncProject
import com.dpsmpx.dpmeta.model.ProjectList
import com.dpsmpx.dpai.core.git.RepoRef
import com.dpsmpx.dpmeta.model.UserProject
import com.dpsmpx.dpmeta.sync.Got
import com.dpsmpx.dpmeta.sync.SourceKind
import com.dpsmpx.dpmeta.sync.SourceReader
import com.dpsmpx.dpmeta.sync.Sync
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Чек-листы проектов пульта (D-176): файлы, выбранные пользователем, читаются одной задачей;
 * файла нет — запись «нет», а не отказ; снятые чек-листы не остаются в состоянии.
 * Репозитории и файлы — синтетические.
 */
class ChecklistSyncTest {
    private val now = Timestamp(Instant.parse("2026-11-16T10:00:00Z").toEpochMilli())

    private fun ref(s: String) = RepoRef.parse(s).valueOrNull()!!

    /** Файлы по пути; [fail] — путь, на котором источник отказывает не «файла нет». */
    private class Files(val files: Map<String, String>, val fail: String? = null) : SourceReader {
        override val source = ObservationSource.GITHUB
        override fun hasActivity(project: SyncProject) = false
        override fun pulls(project: SyncProject): Outcome<Got<List<PullRequest>>> = Outcome.failure(DiagCode.UNSUPPORTED, "нет", project.name)
        override fun closed(project: SyncProject): Outcome<Got<List<PullRequest>>> = Outcome.failure(DiagCode.UNSUPPORTED, "нет", project.name)
        override fun branches(project: SyncProject): Outcome<Got<List<Branch>>> = Outcome.failure(DiagCode.UNSUPPORTED, "нет", project.name)
        override fun commit(project: SyncProject, sha: String): Outcome<CommitInfo> = Outcome.failure(DiagCode.UNSUPPORTED, "нет", sha)
        override fun file(project: SyncProject, path: String): Outcome<Got<String>> = when {
            path == fail -> Outcome.failure(DiagCode.GITHUB_RATE_LIMITED, "лимит", path)
            else -> files[path]?.let { Outcome.success(Got(it, Hashing.sha256(it))) } ?: Outcome.failure(DiagCode.GITHUB_NOT_FOUND, "файла нет", path)
        }
    }

    private val list = ProjectList(
        listOf(
            UserProject(ref("alice/app"), docs = listOf("docs/plan.md"), checklists = listOf("TODO.md", "docs/plan.md", "missing.md")),
            UserProject(ref("bob/lib")),
        ),
    )

    private val files = mapOf(
        "TODO.md" to "- [x] сделано\n- [ ] открыто\n",
        "docs/plan.md" to "# План\n\n- [ ] первый\n  - [X] вложенный\n",
    )

    private fun checklistsOf(reader: SourceReader, l: ProjectList = list, previous: EcosystemState? = null): Pair<EcosystemState, com.dpsmpx.dpmeta.sync.SyncResult> {
        val reg = l.forSync()
        val tasks = Sync.plan(reg).filter { it.kind == SourceKind.CHECKLISTS }
        val r = Sync.run(tasks, reader, ManualClock(now))
        return EcosystemState.derive(reg, r.observations, r.failures, previous, now) to r
    }

    @Test
    fun onlyProjectsWithChecklistsArePlannedInTheirOrder() {
        val tasks = Sync.plan(list.forSync()).filter { it.kind == SourceKind.CHECKLISTS }
        assertEquals(listOf("app" to listOf("TODO.md", "docs/plan.md", "missing.md")), tasks.map { it.project.name to it.paths })
        assertEquals(listOf("docs/plan.md"), list.forSync().projects.first().docs)
    }

    @Test
    fun everyFileIsCountedAndMissingIsShownNotFailed() {
        val (state, result) = checklistsOf(Files(files))
        assertEquals(emptyList(), result.failures)
        val app = state.project("app")!!
        assertEquals(
            listOf(
                ProjectState.ChecklistFile("TODO.md", false, 1, 2, listOf("открыто" to 2L)),
                ProjectState.ChecklistFile("docs/plan.md", false, 1, 2, listOf("первый" to 3L)),
                ProjectState.ChecklistFile("missing.md", true, 0, 0, emptyList()),
            ),
            app.checklists,
        )
        assertEquals(Sync.CHECKLISTS_SCHEMA, app.sections.getValue("checklists").schema)
        assertNull(state.project("lib")!!.checklists, "у проекта без чек-листов секции нет")
    }

    @Test
    fun sourceFailureIsTheTaskFailureAndKeepsPreviousSection() {
        val (before, _) = checklistsOf(Files(files))
        val (after, result) = checklistsOf(Files(files, fail = "docs/plan.md"), previous = before)
        assertEquals(listOf("app" to "checklists"), result.failures.map { it.project to it.kind })
        assertEquals(before.project("app")!!.checklists, after.project("app")!!.checklists, "прежнее не перекрывается пустым")
    }

    @Test
    fun removedChecklistsLeaveNoStaleSection() {
        val (before, _) = checklistsOf(Files(files))
        val cleared = list.withPaths(ref("alice/app"), docs = emptyList(), checklists = emptyList()).valueOrNull()!!
        val (after, _) = checklistsOf(Files(files), cleared, previous = before)
        assertNull(after.project("app")!!.checklists)
        assertTrue("checklists" !in after.project("app")!!.sections)
    }
}
