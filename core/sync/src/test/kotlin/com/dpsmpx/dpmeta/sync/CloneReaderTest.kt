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
import com.dpsmpx.dpmeta.model.Place
import com.dpsmpx.dpmeta.model.ProjectEntry
import com.dpsmpx.dpmeta.model.RepoRef
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

        override fun hasActivity(project: ProjectEntry) = true

        override fun pulls(project: ProjectEntry): Outcome<Got<List<PullRequest>>> = Outcome.success(Got(emptyList<PullRequest>(), Hashing.sha256("p"))).also { calls += "pulls" }

        override fun closed(project: ProjectEntry): Outcome<Got<List<PullRequest>>> = Outcome.success(Got(emptyList<PullRequest>(), Hashing.sha256("c"))).also { calls += "closed" }

        override fun branches(project: ProjectEntry): Outcome<Got<List<Branch>>> =
            Outcome.success(Got(listOf(Branch("main", "r".repeat(40)), Branch("apk_debug", "s".repeat(40))), Hashing.sha256("b"))).also { calls += "branches" }

        override fun commit(project: ProjectEntry, sha: String): Outcome<CommitInfo> = Outcome.success(CommitInfo(sha, Timestamp(0), null)).also { calls += "commit" }

        override fun file(project: ProjectEntry, path: String): Outcome<Got<String>> = Outcome.success(Got("rest:$path", Hashing.sha256(path))).also { calls += "file" }
    }

    private fun project(slug: String, dir: String? = null) =
        ProjectEntry(slug.substringAfter('/'), RepoRef.parse(slug).valueOrNull()!!, null, Place.CORE, emptyList(), emptyList(), null, emptyList(), dir)

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
        val r = CloneReader(clones, rest, host, build)
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
    fun subsystemReadsTheHostCloneInItsDirectory() {
        val clones = Clones(File(tmp, "clones"), { Outcome.success(Unit) }, setOf("file"))
        clones.sync(host.toString(), remote("host", mapOf("dpai/TODO.md" to "- [ ] «AI»\n", "TODO.md" to "- [ ] корень\n")), null, build)
        val r = CloneReader(clones, Rest(), host, build)
        val ai = project("dpsmpx/DP_AI_Orcestrator", dir = "dpai")
        assertEquals("dpsmpx/DPmetaApp", r.key(ai))
        assertEquals("- [ ] «AI»\n", (r.file(ai, "TODO.md") as Outcome.Success).value.value)
        assertTrue(r.file(ai, "../TODO.md") is Outcome.Failure, "путь вне каталога подсистемы — отказ")
    }

    @Test
    fun anUnselectedCloneIsNeitherReadNorRemoved() {
        val clones = Clones(File(tmp, "clones"), { Outcome.success(Unit) }, setOf("file"))
        clones.sync("dpsmpx/Alpha", remote("alpha", mapOf("TODO.md" to "- [ ] из клона\n")), null, build)
        val rest = Rest()
        // Автор снял отметку (D-70): клон, где может быть работа ИИ, остаётся, но пульт читает GitHub.
        val r = CloneReader(clones, rest, host, build) { false }
        assertEquals("rest:TODO.md", (r.file(project("dpsmpx/Alpha"), "TODO.md") as Outcome.Success).value.value)
        assertEquals(listOf("main"), (r.branches(project("dpsmpx/Alpha")) as Outcome.Success).value.value.map { it.name })
        assertTrue(File(clones.dir("dpsmpx/Alpha"), ".git").isDirectory, "клон не удалён")
    }

    @Test
    fun withoutACloneTheAnswerIsRestsWithoutBuildBranches() {
        val clones = Clones(File(tmp, "clones"), { Outcome.success(Unit) }, setOf("file"))
        val rest = Rest()
        val r = CloneReader(clones, rest, host, build)
        val beta = project("dpsmpx/Beta")
        assertEquals("rest:TODO.md", (r.file(beta, "TODO.md") as Outcome.Success).value.value)
        assertEquals(listOf("main"), (r.branches(beta) as Outcome.Success).value.value.map { it.name }, "ветки сборок мимо и у REST")
        assertEquals(listOf("file", "branches"), rest.calls)
    }
}
