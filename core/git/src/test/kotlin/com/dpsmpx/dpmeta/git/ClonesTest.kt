package com.dpsmpx.dpmeta.git

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Клоны (D-65) на локальных репозиториях, без сети: клон и обновление,
 * ветки сборок мимо, файлы — из веток GitHub, расхождения — конфликтами,
 * адрес не из списка — отказ до обращения.
 */
class ClonesTest {
    private val tmp: File = Files.createTempDirectory("clones").toFile()
    private val remoteDir = File(tmp, "remote.git")
    private val work = File(tmp, "work")
    private val who = PersonIdent("dpsmpx", "dpsmpx@users.noreply.github.com")
    private val open = Clones.Gate { Outcome.success(Unit) }
    private val build = { b: String -> b == "apk_debug" }

    init {
        // Репозитории теста создаются до первого Clones: и им — без чужих настроек git.
        Clones.isolate()
    }

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    /** «GitHub»: голый репозиторий с основной веткой main, рабочей веткой и веткой сборок. */
    private fun remote(): URI {
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remoteDir).call().close()
        Git.init().setInitialBranch("main").setDirectory(work).call().use { git ->
            git.repository.config.apply { setString("remote", "origin", "url", remoteDir.toURI().toString()) }.save()
            commit(git, "TODO.md", "- [ ] пункт\n", "Начало")
            git.push().setRemote("origin").add("main").call()
            git.checkout().setCreateBranch(true).setName("work").call()
            commit(git, "work.txt", "шаг\n", "Шаг\n\nCo-Authored-By: Claude <noreply@anthropic.com>")
            git.push().setRemote("origin").add("work").call()
            git.checkout().setOrphan(true).setName("apk_debug").call()
            commit(git, "app.apk", "двоичное\n", "Сборка")
            git.push().setRemote("origin").add("apk_debug").call()
            git.checkout().setName("main").call()
        }
        return remoteDir.toURI()
    }

    private fun commit(git: Git, path: String, text: String, message: String) {
        File(git.repository.workTree, path).apply { parentFile.mkdirs() }.writeText(text)
        git.add().addFilepattern(path).call()
        git.commit().setMessage(message).setAuthor(who).setCommitter(who).call()
    }

    private fun clones() = Clones(File(tmp, "clones"), open)

    @Test
    fun clonesWithoutBuildBranchesAndReadsFilesFromGitHubBranches() {
        val uri = remote()
        val c = clones()
        val r = (c.sync("Alpha", uri, null, build) as Outcome.Success).value
        assertEquals(Clones.Action.CLONED, r.action)
        assertEquals("main", r.main)
        assertEquals(listOf("main", "work"), r.branches, "ветка сборок не клонируется")
        assertEquals(listOf("main", "work"), (c.heads("Alpha") as Outcome.Success).value.map { it.name })
        assertEquals("- [ ] пункт\n", String((c.file("Alpha", "main", "TODO.md") as Outcome.Success).value!!))
        assertNull((c.file("Alpha", "main", "ROADMAP.md") as Outcome.Success).value, "нет файла — null, а не отказ")
        assertTrue(r.conflicts.isEmpty())
    }

    @Test
    fun fetchFastForwardsCleanMainAndDropsDeletedBranches() {
        val uri = remote()
        val c = clones()
        c.sync("Alpha", uri, null, build)
        Git.open(work).use { git ->
            commit(git, "TODO.md", "- [x] пункт\n", "Сделано")
            git.push().setRemote("origin").add("main").call()
            git.push().setRemote("origin").add(":refs/heads/work").call()
        }
        val r = (c.sync("Alpha", uri, null, build) as Outcome.Success).value
        assertEquals(Clones.Action.FETCHED, r.action)
        assertEquals(listOf("main"), (c.heads("Alpha") as Outcome.Success).value.map { it.name }, "удалённая на GitHub ветка уходит")
        assertEquals("- [x] пункт\n", String((c.file("Alpha", "main", "TODO.md") as Outcome.Success).value!!))
        assertEquals("- [x] пункт\n", File(c.dir("Alpha"), "TODO.md").readText(), "чистое дерево — перемотка")
        assertTrue(r.conflicts.isEmpty())
    }

    @Test
    fun divergenceIsAConflictAndTheCloneIsNotTouched() {
        val uri = remote()
        val c = clones()
        c.sync("Alpha", uri, null, build)
        // Правка ИИ в клоне: свой коммит и незакоммиченное.
        Git.open(c.dir("Alpha")).use { git -> commit(git, "local.txt", "своё\n", "Своё") }
        File(c.dir("Alpha"), "TODO.md").writeText("правка\n")
        // На GitHub — перезапись истории рабочей ветки.
        Git.open(work).use { git ->
            git.checkout().setName("work").call()
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef("HEAD~1").call()
            commit(git, "other.txt", "иначе\n", "Иначе")
            git.push().setRemote("origin").add("work").setForce(true).call()
        }
        val r = (c.sync("Alpha", uri, null, build) as Outcome.Success).value
        assertEquals(
            setOf(Clones.ConflictKind.DIRTY to "main", Clones.ConflictKind.AHEAD to "main", Clones.ConflictKind.REWRITTEN to "work"),
            r.conflicts.map { it.kind to it.branch }.toSet(),
        )
        assertEquals("правка\n", File(c.dir("Alpha"), "TODO.md").readText(), "клон с конфликтом не перезаписывается")
    }

    @Test
    fun gateRefusesBeforeAnyNetworkCall() {
        val refused = Clones(File(tmp, "clones"), { Outcome.failure(DiagCode.HOST_NOT_ALLOWED, "адреса нет в списке разрешённых", it.host ?: "") })
        val r = refused.sync("Alpha", Clones.github("example/Alpha"), null, build)
        assertIs<Outcome.Failure>(r)
        assertEquals(DiagCode.HOST_NOT_ALLOWED, r.diagnostics.first().code)
        assertTrue(!File(tmp, "clones/Alpha").exists(), "отказ — без каталога клона")
    }

    @Test
    fun missingCloneIsNotFoundAndNamesAreSafe() {
        val c = clones()
        assertEquals(DiagCode.ENTITY_NOT_FOUND, (c.heads("Нет") as Outcome.Failure).diagnostics.first().code)
        assertEquals("DP_AIM_Trainer", Clones.safe("DP AIM Trainer"))
        assertEquals("_.._x", Clones.safe("/../x"))
        assertEquals(URI("https://github.com/dpsmpx/DPmetaApp.git"), Clones.github("dpsmpx/DPmetaApp"))
    }
}
