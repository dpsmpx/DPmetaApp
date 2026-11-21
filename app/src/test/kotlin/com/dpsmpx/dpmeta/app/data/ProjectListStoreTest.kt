package com.dpsmpx.dpmeta.app.data

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.model.ProjectList
import com.dpsmpx.dpai.core.git.RepoRef
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Проекты пульта (D-176): список пользователя в настройках пульта — вместо реестра DPmeta;
 * команды и синхронизация берут проекты из него, без сети. Репозитории — синтетические.
 */
class ProjectListStoreTest {
    private val root = Files.createTempDirectory("pult-projects-test").toFile()
    private fun repo() = PultRepository(NioVfs(java.io.File(root, "state").toPath()), Clock.SYSTEM, Executors.newSingleThreadExecutor(), java.io.File(root, "clones"))

    private fun ref(s: String) = RepoRef.parse(s).valueOrNull()!!

    @Test
    fun emptyWithoutFileAndKeptAfterSave() {
        val r = repo()
        assertEquals(ProjectList(), r.projectList().valueOrNull())
        val list = (ProjectList().add(ref("alice/app")) as Outcome.Success).value.add(ref("bob/lib")).valueOrNull()!!
        assertIs<Outcome.Success<*>>(r.saveProjectList(list))
        // Новый экземпляр — тот же список с диска (перезапуск приложения).
        assertEquals(listOf("alice/app", "bob/lib"), repo().projectList().valueOrNull()!!.projects.map { it.repo.toString() })
    }

    @Test
    fun commandsSeeTheListWithoutNetwork() {
        val r = repo()
        r.saveProjectList(ProjectList().add(ref("alice/app")).valueOrNull()!!)
        val reg = r.commandProjects().valueOrNull()!!
        assertEquals(listOf("app"), reg.projects.map { it.name })
        assertEquals(listOf("alice/app"), reg.projects.map { it.repo.toString() })
    }

    @Test
    fun pathsAreSavedByCardNameAndReachTheRegistry() {
        val r = repo()
        r.saveProjectList(ProjectList().add(ref("alice/app")).valueOrNull()!!.add(ref("bob/app")).valueOrNull()!!)
        // Одинаковые имена у разных владельцев — карточка «владелец/имя».
        assertIs<Outcome.Success<*>>(r.saveProjectPaths("bob/app", listOf("docs/plan.md"), listOf("TODO.md")))
        val again = repo()
        assertEquals(listOf("docs/plan.md"), again.userProject("bob/app")!!.docs)
        assertEquals(emptyList(), again.userProject("alice/app")!!.checklists)
        assertEquals(listOf("TODO.md"), again.commandProjects().valueOrNull()!!.projects.single { it.name == "bob/app" }.checklists)
        assertIs<Outcome.Failure>(r.saveProjectPaths("bob/app", listOf("/etc/passwd"), emptyList()))
        assertIs<Outcome.Failure>(r.saveProjectPaths("carol", emptyList(), emptyList()))
    }
    @Test
    fun cachedRowsFollowTheCurrentSelectionWithoutDeletingHistory() {
        val r = repo()
        r.saveProjectList(ProjectList().add(ref("alice/App")).valueOrNull()!!)
        val clock = com.dpsmpx.dpai.core.clock.ManualClock()
        val store = com.dpsmpx.dpmeta.metrics.Store(NioVfs(java.io.File(root, "state").toPath()), clock, com.dpsmpx.dpai.core.clock.RandomSource.secure())
        val now = clock.now()
        val rows = listOf("App", "Gone").map { com.dpsmpx.dpmeta.metrics.ProjectState(it, emptyMap(), emptyList()) }
        store.writeState(com.dpsmpx.dpmeta.metrics.EcosystemState(now, 9, rows))
        val points = listOf(com.dpsmpx.dpmeta.metrics.MetricPoint("repo.pr.open", "Gone", 2, now, 8))
        store.appendHistory(points)
        assertEquals(listOf("App"), r.state().valueOrNull()!!.projects.map { it.name })
        r.saveProjectList(ProjectList())
        assertTrue(r.state().valueOrNull()!!.projects.isEmpty())
        assertEquals(points, store.history().valueOrNull())
    }

}
