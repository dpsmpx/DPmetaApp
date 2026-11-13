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

    /** Новый коммит «GitHub» в main — и синхронизация клона, которая его скачивает (без перемотки дерева). */
    private fun pushAndSync(c: Clones, uri: URI, path: String, text: String) {
        Git.open(work).use { git ->
            git.checkout().setName("main").call()
            commit(git, path, text, "Ещё $path")
            git.push().setRemote("origin").add("main").call()
        }
        assertIs<Outcome.Success<Clones.Report>>(c.sync("Alpha", uri, null, build))
    }

    /**
     * Догон рабочего дерева (D-123, уточняет D-70): чистое дерево без своих коммитов перематывается
     * на `origin/main`; правки, не сохранённые в Git, и свои коммиты — дерево не трогается, причина
     * названа; HEAD отсоединён — догонять не с чем.
     */
    @Test
    fun cleanWorktreeCatchesUpAndLocalWorkIsNeverTouched() {
        val uri = remote()
        val c = clones()
        c.sync("Alpha", uri, null, build).valueOrNull()!!
        assertEquals(Clones.CatchUp.Even, c.catchUp("Alpha").valueOrNull())
        val dir = c.dir("Alpha")

        pushAndSync(c, uri, "a.md", "один\n")
        pushAndSync(c, uri, "b.md", "два\n")
        assertTrue(!File(dir, "a.md").exists(), "синхронизация сама дерево не трогает (D-70)")
        val moved = assertIs<Clones.CatchUp.Moved>(c.catchUp("Alpha").valueOrNull())
        assertEquals("main", moved.branch)
        assertEquals(2, moved.commits)
        assertEquals("два\n", File(dir, "b.md").readText())
        assertTrue(c.worktree("Alpha").valueOrNull()!!.even)

        // Правка, не сохранённая в Git, — дерево не трогается.
        File(dir, "TODO.md").writeText("правка ИИ\n")
        pushAndSync(c, uri, "c.md", "три\n")
        val dirty = assertIs<Clones.CatchUp.Held>(c.catchUp("Alpha").valueOrNull())
        assertTrue("правки" in dirty.why, dirty.why)
        assertEquals("правка ИИ\n", File(dir, "TODO.md").readText())
        assertTrue(!File(dir, "c.md").exists())

        // Свой коммит — ветка разошлась: тоже не трогается.
        Git.open(dir).use { git -> commit(git, "TODO.md", "правка ИИ\n", "Свой коммит") }
        val ahead = assertIs<Clones.CatchUp.Held>(c.catchUp("Alpha").valueOrNull())
        assertTrue("свои коммиты" in ahead.why, ahead.why)
        assertTrue(!File(dir, "c.md").exists())

        // HEAD отсоединён — догонять не с чем.
        Git.open(dir).use { git -> git.checkout().setName(git.repository.resolve("HEAD").name).call() }
        assertIs<Clones.CatchUp.Skipped>(c.catchUp("Alpha").valueOrNull())
        assertEquals(DiagCode.ENTITY_NOT_FOUND, assertIs<Outcome.Failure>(c.catchUp("Нет")).diagnostics.first().code)
    }

    @Test
    fun oversizedDocumentIsRefusedBeforeLoadingAndMalformedRevisionDoesNotEscapeOutcome() {
        val uri = remote()
        Git.open(work).use { git ->
            commit(git, "large.md", "x".repeat(Clones.MAX_DOCUMENT.toInt() + 1), "Большой документ")
            git.push().setRemote("origin").add("main").call()
        }
        val c = clones()
        val sha = c.sync("Alpha", uri, null, build).valueOrNull()!!.mainSha!!
        assertEquals(DiagCode.JSON_TOO_LARGE, assertIs<Outcome.Failure>(c.file("Alpha", "main", "large.md")).diagnostics.first().code)
        assertEquals(DiagCode.JSON_TOO_LARGE, assertIs<Outcome.Failure>(c.fileAt("Alpha", sha, "large.md")).diagnostics.first().code)
        assertIs<Outcome.Failure>(c.file("Alpha", "main@{", "TODO.md"))
    }

    @Test
    fun guestCannotChooseTrustedMainThroughConfigOrOriginHead() {
        val uri = remote()
        val c = clones()
        val expected = c.sync("Alpha", uri, null, build).valueOrNull()!!.mainSha
        Git.open(c.dir("Alpha")).use { git ->
            git.repository.config.apply { setString("dpmeta", null, "main", "work") }.save()
            git.repository.updateRef("refs/remotes/origin/HEAD").link("refs/remotes/origin/work")
        }
        val report = c.sync("Alpha", uri, null, build).valueOrNull()!!
        assertEquals("main", report.main)
        assertEquals(expected, report.mainSha)
    }

    @Test
    fun guestUrlRewriteCannotRedirectFetch() {
        val uri = remote()
        val c = clones()
        c.sync("Alpha", uri, null, build).valueOrNull()!!
        Git.open(c.dir("Alpha")).use { git ->
            git.repository.config.apply {
                setString("url", File(tmp, "not-a-repository").toURI().toString(), "insteadOf", uri.toString())
            }.save()
        }
        Git.open(work).use { git ->
            commit(git, "TODO.md", "- [ ] новый пункт\n", "Обновление")
            git.push().setRemote("origin").add("main").call()
        }
        assertIs<Outcome.Success<*>>(c.sync("Alpha", uri, null, build))
        assertEquals("- [ ] новый пункт\n", String(c.file("Alpha", "main", "TODO.md").valueOrNull()!!))
    }

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

    /** Каталог из клона (UX-9): корень и подкаталог ветки GitHub, без сети; нет каталога — null. */
    @Test
    fun listsDirectoriesOfGitHubBranchWithoutNetwork() {
        val uri = remote()
        Git.open(work).use { git ->
            commit(git, "docs/a.md", "a\n", "Документ")
            git.push().setRemote("origin").add("main").call()
        }
        val c = clones()
        c.sync("Alpha", uri, null, build)
        assertEquals(listOf(Clones.Entry("docs", true), Clones.Entry("TODO.md", false)), (c.list("Alpha", "main", "") as Outcome.Success).value)
        assertEquals(listOf(Clones.Entry("a.md", false)), (c.list("Alpha", "main", "docs") as Outcome.Success).value)
        assertNull((c.list("Alpha", "main", "нет") as Outcome.Success).value, "нет каталога — null")
        assertNull((c.list("Alpha", "main", "TODO.md") as Outcome.Success).value, "файл — не каталог")
        assertTrue(c.list("Beta", "main", "") is Outcome.Failure, "нет клона — отказ")
        assertNull((c.file("Alpha", "main", "docs") as Outcome.Success).value, "каталог — не файл: байты дерева не отдаются")
        assertEquals(2L, (c.size("Alpha", "main", "docs/a.md") as Outcome.Success).value)
    }

    /** Последний коммит каталога (DP_AI_Orcestrator:D-114): у подсистемы — менявший её каталог, а не весь репозиторий. */
    @Test
    fun lastCommitOfRepositoryAndOfADirectory() {
        val uri = remote()
        Git.open(work).use { git ->
            commit(git, "sub/a.md", "a\n", "Подсистема")
            commit(git, "other.md", "o\n", "Другое")
            git.push().setRemote("origin").add("main").call()
        }
        val c = clones()
        c.sync("Alpha", uri, null, build)
        val all = (c.last("Alpha", "main") as Outcome.Success).value!!
        val sub = (c.last("Alpha", "main", "sub") as Outcome.Success).value!!
        assertTrue(all.first != sub.first, "у каталога — свой последний коммит")
        assertEquals(7, sub.first.length)
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(sub.second), sub.second)
        assertNull((c.last("Alpha", "main", "нет") as Outcome.Success).value, "каталог без коммитов — null")
    }

    /**
     * Голова от GitHub (DP_AI_Orcestrator:D-114, T19): `mainSha` — ответ
     * `ls-remote`; подменённая в клоне ссылка `origin/main` не меняет файл,
     * прочитанный на этом SHA.
     */
    @Test
    fun mainShaIsGitHubsAndFileAtIgnoresTamperedRefs() {
        val uri = remote()
        val c = clones()
        val sha = (c.sync("Alpha", uri, null, build) as Outcome.Success).value.mainSha!!
        val github = Git.open(remoteDir).use { it.repository.resolve("refs/heads/main").name }
        assertEquals(github, sha)
        // Гость клона пишет свой коммит и переставляет origin/main на него.
        Git.open(c.dir("Alpha")).use { git ->
            commit(git, "TODO.md", "- [ ] подмена\n", "Подмена")
            git.repository.updateRef("refs/remotes/origin/main").apply { setNewObjectId(git.repository.resolve("HEAD")); isForceUpdate = true }.forceUpdate()
        }
        assertEquals("- [ ] подмена\n", String((c.file("Alpha", "main", "TODO.md") as Outcome.Success).value!!), "по ссылке — подмена")
        assertEquals("- [ ] пункт\n", String((c.fileAt("Alpha", sha, "TODO.md") as Outcome.Success).value!!), "по SHA от GitHub — прежнее")
        assertNull((c.fileAt("Alpha", sha, "нет.md") as Outcome.Success).value)
        assertTrue(c.fileAt("Alpha", "0".repeat(40), "TODO.md") is Outcome.Failure, "нет коммита — отказ")
        assertTrue(c.fileAt("Alpha", "main", "TODO.md") is Outcome.Failure, "не SHA — отказ")
        // Повторная синхронизация: снова голова GitHub, и ссылка возвращена.
        assertEquals(github, (c.sync("Alpha", uri, null, build) as Outcome.Success).value.mainSha)
    }

    /**
     * Подложенный объект (T19): JGit хеш при чтении не сверяет — гость пишет
     * loose-объект под доверенный id блоба и убирает паки. `fileAt`
     * пересчитывает id каждого объекта: подмена — отказ, без подмены — то же
     * содержимое.
     */
    @Test
    fun fileAtRefusesAForgedObjectUnderATrustedId() {
        val uri = remote()
        val c = clones()
        val sha = (c.sync("Alpha", uri, null, build) as Outcome.Success).value.mainSha!!
        val git = File(c.dir("Alpha"), ".git")
        val (commit, tree, blob) = Git.open(c.dir("Alpha")).use { g ->
            val r = g.repository
            val cm = r.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(sha))
            val b = org.eclipse.jgit.treewalk.TreeWalk.forPath(r, "TODO.md", cm.tree)!!.use { it.getObjectId(0) }
            Triple(cm.id to r.open(cm.id).bytes, cm.tree.id to r.open(cm.tree.id).bytes, b)
        }
        fun loose(id: org.eclipse.jgit.lib.ObjectId, type: String, data: ByteArray) {
            val f = File(git, "objects/${id.name.substring(0, 2)}/${id.name.substring(2)}").apply { parentFile.mkdirs(); delete() }
            java.util.zip.DeflaterOutputStream(f.outputStream()).use { it.write("$type ${data.size}\u0000".toByteArray() + data) }
        }
        // Паки — прочь, объекты — по одному: коммит и дерево настоящие.
        File(git, "objects/pack").listFiles()?.forEach { it.delete() }
        loose(commit.first, "commit", commit.second)
        loose(tree.first, "tree", tree.second)
        loose(blob, "blob", "- [ ] пункт\n".toByteArray())
        assertEquals("- [ ] пункт\n", String((c.fileAt("Alpha", sha, "TODO.md") as Outcome.Success).value!!), "без подмены — то же")
        loose(blob, "blob", "- [ ] подмена\n".toByteArray())
        assertTrue(c.fileAt("Alpha", sha, "TODO.md") is Outcome.Failure, "подложенный блоб — отказ")
        loose(blob, "blob", "- [ ] пункт\n".toByteArray())
        loose(tree.first, "tree", tree.second.copyOf(tree.second.size - 1) + 0)
        assertTrue(c.fileAt("Alpha", sha, "TODO.md") is Outcome.Failure, "подложенное дерево — отказ")
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

    /** Рабочее дерево для экрана команд (D-82): ветка, её коммит, голова GitHub; отставание видно. */
    @Test
    fun worktreeShowsBranchCommitAndGitHubHead() {
        val uri = remote()
        val c = clones()
        assertEquals(DiagCode.ENTITY_NOT_FOUND, assertIs<Outcome.Failure>(c.worktree("Alpha")).errors.single().code)
        c.sync("Alpha", uri, null, build)
        val fresh = (c.worktree("Alpha") as Outcome.Success).value
        assertEquals("main", fresh.branch)
        assertTrue(fresh.even)
        Git.open(work).use { git ->
            commit(git, "TODO.md", "- [x] пункт\n", "Сделано")
            git.push().setRemote("origin").add("main").call()
        }
        c.sync("Alpha", uri, null, build)
        val behind = (c.worktree("Alpha") as Outcome.Success).value
        assertEquals(fresh.sha, behind.sha, "синхронизация дерево не трогает")
        assertTrue(!behind.even && behind.upstream != null)
        Git.open(c.dir("Alpha")).use { git -> git.checkout().setName(behind.upstream).call() }
        val detached = (c.worktree("Alpha") as Outcome.Success).value
        assertNull(detached.branch)
        assertNull(detached.upstream)
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
