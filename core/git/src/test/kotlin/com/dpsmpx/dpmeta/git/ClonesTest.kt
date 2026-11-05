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

    // В тестах «GitHub» — локальный репозиторий: file: разрешён только здесь, в приложении — только https.
    private fun clones() = Clones(File(tmp, "clones"), open, setOf("file"))

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
    fun fetchUpdatesGitHubBranchesLeavesTheWorkingTreeAndDropsDeletedBranches() {
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
        assertEquals("- [ ] пункт\n", File(c.dir("Alpha"), "TODO.md").readText(), "рабочее дерево — ИИ (D-70): обновление его не трогает")
        assertTrue(r.conflicts.isEmpty())
    }

    @Test
    fun localWorkIsNotAConflictAndRewrittenHistoryIsNoted() {
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
        // И своя ветка ИИ, которой нет на GitHub.
        Git.open(c.dir("Alpha")).use { git -> git.branchCreate().setName("ai/task").call() }
        val r = (c.sync("Alpha", uri, null, build) as Outcome.Success).value
        assertEquals(listOf(Clones.ConflictKind.REWRITTEN to "work"), r.conflicts.map { it.kind to it.branch }, "работа ИИ в клоне — не конфликт (D-70)")
        assertEquals("правка\n", File(c.dir("Alpha"), "TODO.md").readText(), "правка ИИ цела")
        Git.open(c.dir("Alpha")).use { git ->
            assertEquals("Своё", git.log().setMaxCount(1).call().first().shortMessage, "коммит ИИ цел")
            assertTrue(git.repository.findRef("ai/task") != null, "ветка ИИ цела")
        }
        assertEquals("- [ ] пункт\n", String((c.file("Alpha", "main", "TODO.md") as Outcome.Success).value!!), "пульт читает GitHub, а не правку ИИ")
    }

    @Test
    fun aCloneMadeByAModelKeepsGitHubMainAsItsMain() {
        val uri = remote()
        val c = clones()
        // Модель клонировала сама, git-ом Linux-среды (D-72): отметки dpmeta.main нет, работа — в своей ветке.
        Git.cloneRepository().setURI(uri.toString()).setDirectory(c.dir("dpsmpx/Beta")).call().use { git ->
            // Как `git clone`: символьная origin/HEAD → origin/main (JGit её сам не пишет).
            git.repository.updateRef(org.eclipse.jgit.lib.Constants.R_REMOTES + "origin/HEAD").link(org.eclipse.jgit.lib.Constants.R_REMOTES + "origin/main")
            git.checkout().setCreateBranch(true).setName("ai/task").call()
        }
        assertEquals("main", c.main("dpsmpx/Beta"), "основная — ветка GitHub, а не ветка работы ИИ")
        // Обновление пультом: символьная origin/HEAD не стирает origin/main и не считается веткой.
        assertIs<Outcome.Success<*>>(c.sync("dpsmpx/Beta", uri, null, build))
        assertIs<Outcome.Success<*>>(c.sync("dpsmpx/Beta", uri, null, build), "и второй раз")
        assertEquals(listOf("main", "work"), (c.heads("dpsmpx/Beta") as Outcome.Success).value.map { it.name })
        assertEquals("main", c.main("dpsmpx/Beta"))
        assertEquals("- [ ] пункт\n", String((c.file("dpsmpx/Beta", c.main("dpsmpx/Beta")!!, "TODO.md") as Outcome.Success).value!!))
    }

    @Test
    fun namesAsOnGitHubAndDeletionDoesNotFollowLinks() {
        val c = clones()
        assertEquals(File(File(File(tmp, "clones"), "dpsmpx"), "AIM_Trainer_pas"), c.dir("dpsmpx/AIM_Trainer_pas"), "имя как на GitHub — там же, куда клонирует модель (D-72)")
        assertEquals("_2e_2e", c.dir("dpsmpx/..").name, "«..» — не родительский каталог")
        val outside = File(tmp, "outside").apply { mkdirs() }
        File(outside, "keep.txt").writeText("данные приложения")
        val victim = File(tmp, "victim").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(victim, "link").toPath(), outside.toPath())
        Clones.deleteTree(victim)
        assertTrue(!victim.exists(), "каталог удалён")
        assertTrue(File(outside, "keep.txt").isFile, "по ссылке удаление не ушло")
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
    fun onlyHttpsAndOnlyListedHosts() {
        val c = Clones(File(tmp, "clones"), Clones.allowList())
        for (bad in listOf("git://github.com/dpsmpx/DPmetaApp.git", "http://github.com/dpsmpx/DPmetaApp.git", "file:///etc", "https://example.org/x.git")) {
            val r = c.sync("X", URI(bad), null, build)
            assertEquals(DiagCode.HOST_NOT_ALLOWED, (r as Outcome.Failure).diagnostics.first().code, bad)
        }
        assertIs<Outcome.Success<Unit>>(Clones.allowList().check(Clones.github("dpsmpx/DPmetaApp")), "github.com по https — в списке")
    }

    @Test
    fun internalGitFailureIsAnOutcomeNotACrash() {
        val uri = remote()
        val blocked = File(tmp, "file-not-dir").apply { writeText("") }
        val r = Clones(File(blocked, "clones"), open, setOf("file")).sync("Alpha", uri, null, build)
        assertIs<Outcome.Failure>(r, "каталог клонов не создаётся — отказ, а не исключение")
    }

    @Test
    fun missingCloneIsNotFoundAndNamesAreSafe() {
        val c = clones()
        assertEquals(DiagCode.ENTITY_NOT_FOUND, (c.heads("Нет") as Outcome.Failure).diagnostics.first().code)
        assertEquals("DP_20AIM_20Trainer", Clones.safe("DP AIM Trainer"))
        assertEquals("DP_5fAIM_5fTrainer", Clones.safe("DP_AIM_Trainer"), "имена не совпадают — каталоги разные")
        assertEquals("_2f.._2fx", Clones.safe("/../x"))
        assertEquals("_2e_2e", Clones.safe(".."), "«..» — не родительский каталог")
        assertEquals(URI("https://github.com/dpsmpx/DPmetaApp.git"), Clones.github("dpsmpx/DPmetaApp"))
        // Владелец/имя — вложенными каталогами: в Linux-среде — /root/repos/<владелец>/<имя> (D-70).
        assertEquals(File(File(File(tmp, "clones"), "dpsmpx"), "DPmetaApp"), c.dir("dpsmpx/DPmetaApp"))
        assertEquals(File(File(tmp, "clones"), "_2e_2e"), c.dir("..").let { File(File(tmp, "clones"), it.name) })
    }
}
