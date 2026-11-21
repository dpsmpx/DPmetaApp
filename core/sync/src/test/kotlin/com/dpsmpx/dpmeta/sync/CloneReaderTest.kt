package com.dpsmpx.dpmeta.sync

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.git.Clones
import com.dpsmpx.dpmeta.github.Branch
import com.dpsmpx.dpmeta.github.CommitInfo
import com.dpsmpx.dpmeta.github.PullRequest
import com.dpsmpx.dpmeta.model.ObservationSource
import com.dpsmpx.dpmeta.model.SyncProject
import com.dpsmpx.dpai.core.git.RepoRef
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Чтение из клонов (D-65): ветки без веток сборок, коммиты голов — без
 * запросов, файлы — из основной ветки клона, у подсистемы — из клона
 * DPmetaApp в её каталоге; клона нет — ответ REST; PR — всегда REST.
 */
class CloneReaderTest {
    private val tmp: File = Files.createTempDirectory("clone-reader").toFile()
    private val who = PersonIdent("dpsmpx", "dpsmpx@users.noreply.github.com")
    private val host = RepoRef.parse("dpsmpx/DPmetaApp").valueOrNull()!!

    init {
        Clones.isolate()
    }

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    /** REST-заглушка: считает вызовы; файлы и ветки — свои, чтобы было видно, откуда ответ. */
    private class Rest : SourceReader {
        val calls = mutableListOf<String>()
        override val source = ObservationSource.GITHUB

        override fun hasActivity(project: SyncProject) = true

        override fun pulls(project: SyncProject): Outcome<Got<List<PullRequest>>> = Outcome.success(Got(emptyList<PullRequest>(), Hashing.sha256("p"))).also { calls += "pulls" }

        override fun closed(project: SyncProject): Outcome<Got<List<PullRequest>>> = Outcome.success(Got(emptyList<PullRequest>(), Hashing.sha256("c"))).also { calls += "closed" }

        override fun branches(project: SyncProject): Outcome<Got<List<Branch>>> =
            Outcome.success(Got(listOf(Branch("main", "r".repeat(40)), Branch("apk_debug", "s".repeat(40))), Hashing.sha256("b"))).also { calls += "branches" }

        override fun commit(project: SyncProject, sha: String): Outcome<CommitInfo> = Outcome.success(CommitInfo(sha, Timestamp(0), null)).also { calls += "commit" }

        override fun file(project: SyncProject, path: String): Outcome<Got<String>> = Outcome.success(Got("rest:$path", Hashing.sha256(path))).also { calls += "file" }
    }

    private fun project(slug: String) = SyncProject(slug.substringAfter('/'), RepoRef.parse(slug).valueOrNull()!!)

    /** «GitHub» проекта: main с файлами, рабочая ветка и ветка сборок. */
    private fun remote(name: String, files: Map<String, String>): java.net.URI {
        val bare = File(tmp, "$name.git")
        val work = File(tmp, "$name-work")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare).call().close()
        Git.init().setInitialBranch("main").setDirectory(work).call().use { git ->
            git.repository.config.apply { setString("remote", "origin", "url", bare.toURI().toString()) }.save()
            for ((path, text) in files) {
                File(work, path).apply { parentFile.mkdirs() }.writeText(text)
                git.add().addFilepattern(path).call()
            }
            git.commit().setMessage("Начало").setAuthor(who).setCommitter(who).call()
            git.push().setRemote("origin").add("main").call()
            git.checkout().setCreateBranch(true).setName("work").call()
            git.commit().setAllowEmpty(true).setMessage("Шаг").setAuthor(who).setCommitter(who).call()
            git.push().setRemote("origin").add("work").call()
            git.checkout().setOrphan(true).setName("apk_debug").call()
            git.commit().setAllowEmpty(true).setMessage("Сборка").setAuthor(who).setCommitter(who).call()
            git.push().setRemote("origin").add("apk_debug").call()
        }
        return bare.toURI()
    }

    private val build = { b: String -> b == "apk_debug" }

    @Test
    fun branchesCommitsAndFilesComeFromTheCloneAndPullsFromRest() {
        val clones = Clones(File(tmp, "clones"), { Outcome.success(Unit) }, setOf("file"))
        val alpha = project("dpsmpx/Alpha")
        // Клон взял и ветку сборок (excluded — { false }): читатель её отсеивает сам.
        clones.sync("dpsmpx/Alpha", remote("alpha", mapOf("TODO.md" to "- [ ] пункт\n")), null, { false })
        val rest = Rest()
        val r = CloneReader(clones, rest, build)
        val branches = (r.branches(alpha) as Outcome.Success).value.value
        assertEquals(listOf("main", "work"), branches.map { it.name }, "ветка сборок — мимо")
        val info = (r.commit(alpha, branches.first().sha) as Outcome.Success).value
        assertEquals("dpsmpx", info.author)
        assertEquals("- [ ] пункт\n", (r.file(alpha, "TODO.md") as Outcome.Success).value.value)
        assertEquals(DiagCode.GITHUB_NOT_FOUND, (r.file(alpha, "ROADMAP.md") as Outcome.Failure).diagnostics.first().code, "файла нет — как 404 REST")
        r.pulls(alpha)
        assertEquals(listOf("pulls"), rest.calls, "ветки, коммиты и файлы — без REST")
    }

    @Test
    fun eachProjectUsesItsOwnCloneEvenWhenAnotherRepoHasASameNamedDirectory() {
        val clones = Clones(File(tmp, "clones"), { Outcome.success(Unit) }, setOf("file"))
        clones.sync(host.toString(), remote("host", mapOf("engine/TODO.md" to "чужой\n")), null, build)
        clones.sync("example/Engine", remote("engine", mapOf("TODO.md" to "свой\n", "docs/reports/report.md" to "# срез\n")), null, build)
        val r = CloneReader(clones, Rest(), build)
        val engine = project("example/Engine")
        assertEquals("example/Engine", r.key(engine))
        assertEquals("свой\n", (r.file(engine, "TODO.md") as Outcome.Success).value.value)
        assertTrue(r.file(engine, "../TODO.md") is Outcome.Failure)
        assertEquals(listOf("report.md"), (r.names(engine, "docs/reports") as Outcome.Success).value)
        assertTrue(r.names(engine, "nope") is Outcome.Failure)
        assertEquals(null, r.names(project("example/Beta"), "docs"))
    }

    @Test
    fun anUnselectedCloneIsNeitherReadNorRemoved() {
        val clones = Clones(File(tmp, "clones"), { Outcome.success(Unit) }, setOf("file"))
        clones.sync("dpsmpx/Alpha", remote("alpha", mapOf("TODO.md" to "- [ ] из клона\n")), null, build)
        val rest = Rest()
        // Автор снял отметку (D-70): клон, где может быть работа ИИ, остаётся, но пульт читает GitHub.
        val r = CloneReader(clones, rest, build, selected = { false })
        assertEquals("rest:TODO.md", (r.file(project("dpsmpx/Alpha"), "TODO.md") as Outcome.Success).value.value)
        assertEquals(listOf("main"), (r.branches(project("dpsmpx/Alpha")) as Outcome.Success).value.value.map { it.name })
        assertTrue(File(clones.dir("dpsmpx/Alpha"), ".git").isDirectory, "клон не удалён")
    }

    @Test
    fun historyComesOnlyFromASelectedCloneOfTheProjectItself() {
        val clones = Clones(File(tmp, "clones"), { Outcome.success(Unit) }, setOf("file"))
        clones.sync("dpsmpx/Alpha", remote("alpha", mapOf("TODO.md" to "- [ ] пункт\n")), null, build)
        clones.sync(host.toString(), remote("host", mapOf("dpai/TODO.md" to "- [ ] «AI»\n")), null, build)
        val rules = CloneReader.HistoryRules(java.time.ZoneOffset.ofHours(3), setOf("Claude"))
        val alpha = project("dpsmpx/Alpha")
        val h = (CloneReader(clones, Rest(), build, rules).history(alpha) as Outcome.Success).value.value
        assertEquals(2, h.days.sumOf { it.commits }, "main и work, без ветки сборок")
        assertEquals(listOf("work"), h.ahead.map { it.name })
        assertEquals(null, CloneReader(clones, Rest(), build).history(alpha), "без правил — истории нет")
        assertEquals(null, CloneReader(clones, Rest(), build, rules, selected = { false }).history(alpha), "клон не отмечен — истории нет")
        assertEquals(null, CloneReader(clones, Rest(), build, rules).history(project("example/Engine")), "чужой каталог не заменяет собственный клон")
        assertEquals(null, CloneReader(clones, Rest(), build, rules).history(project("dpsmpx/Beta")), "клона нет — истории нет, без отказа")
    }

    @Test
    fun withoutACloneTheAnswerIsRestsWithoutBuildBranches() {
        val clones = Clones(File(tmp, "clones"), { Outcome.success(Unit) }, setOf("file"))
        val rest = Rest()
        val r = CloneReader(clones, rest, build)
        val beta = project("dpsmpx/Beta")
        assertEquals("rest:TODO.md", (r.file(beta, "TODO.md") as Outcome.Success).value.value)
        assertEquals(listOf("main"), (r.branches(beta) as Outcome.Success).value.value.map { it.name }, "ветки сборок мимо и у REST")
        assertEquals(listOf("file", "branches"), rest.calls)
    }
}
