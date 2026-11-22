package com.dpsmpx.dpmeta.git

import com.dpsmpx.dpai.core.result.Outcome
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.Date
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * История из клона (D-66) на синтетическом репозитории, теми же
 * определениями D-176: все авторы, без слияний,
 * копия после cherry-pick — один раз, день — по поясу +03:00, слияния PR —
 * отдельно, журнал решений — на конец дня, ветки впереди основной.
 */
class CommitHistoryTest {
    private val tmp: File = Files.createTempDirectory("history").toFile()
    private val remoteDir = File(tmp, "remote.git")
    private val work = File(tmp, "work")
    private val open = Clones.Gate { Outcome.success(Unit) }
    private val zone = ZoneOffset.ofHours(3)
    private val msk = TimeZone.getTimeZone("GMT+03:00")

    init {
        Clones.isolate()
    }

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    /** Время «ГГГГ-ММ-ДД ЧЧ:ММ» по +03:00. */
    private fun at(s: String) = Date(java.time.LocalDateTime.parse(s.replace(' ', 'T')).toInstant(zone).toEpochMilli())

    private fun commit(git: Git, who: String, time: String, path: String, text: String, message: String = "Шаг") {
        File(git.repository.workTree, path).apply { parentFile.mkdirs() }.writeText(text)
        git.add().addFilepattern(path).call()
        val id = PersonIdent(who, "$who@users.noreply.github.com", at(time), msk)
        git.commit().setMessage(message).setAuthor(id).setCommitter(id).call()
    }

    private fun journal(n: Int) = (1..n).joinToString("\n\n") { "### D-%02d — Решение $it".format(it) } + "\n"

    @Test
    fun countsEveryAuthorWithoutPersonalAllowlist() {
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remoteDir).call().close()
        Git.init().setInitialBranch("main").setDirectory(work).call().use { git ->
            git.repository.config.apply { setString("remote", "origin", "url", remoteDir.toURI().toString()) }.save()
            // 1 ноября: человек — две записи журнала; 23:30 по Москве — всё ещё 1-е (в UTC — 20:30).
            commit(git, "dpsmpx", "2026-11-01 10:00", "docs/DECISIONS.md", journal(2), "Начало")
            commit(git, "Claude", "2026-11-01 23:30", "a.txt", "a\n")
            // Автор вне прежнего личного списка тоже входит в историю пользователя (D-176).
            commit(git, "stranger", "2026-11-02 09:00", "b.txt", "b\n")
            git.push().setRemote("origin").add("main").call()
            // Ветка work: два коммита ИИ 2 ноября; один из них потом перенесён в main cherry-pick'ом — копия.
            git.checkout().setCreateBranch(true).setName("work").call()
            commit(git, "Claude", "2026-11-02 12:00", "c.txt", "c\n")
            val picked = git.repository.resolve("HEAD")
            commit(git, "Claude", "2026-11-02 13:00", "d.txt", "d\n")
            git.push().setRemote("origin").add("work").call()
            git.checkout().setName("main").call()
            git.cherryPick().include(picked).call()
            // Копия — со временем автора исходного; коммиттер — тот же день (как на GitHub в тот же день).
            val original = git.repository.parseCommit(picked).authorIdent
            git.commit().setAmend(true).setMessage("Шаг").setAuthor(original)
                .setCommitter(PersonIdent("Claude", "Claude@users.noreply.github.com", at("2026-11-02 14:00"), msk)).call()
            // 3 ноября: журнал — четыре записи.
            commit(git, "Claude", "2026-11-03 11:00", "docs/DECISIONS.md", journal(4))
            git.push().setRemote("origin").add("main").call()
            // Ветка сборок — мимо.
            git.checkout().setOrphan(true).setName("apk_debug").call()
            commit(git, "Claude", "2026-11-03 12:00", "app.apk", "apk\n", "Сборка")
            git.push().setRemote("origin").add("apk_debug").call()
            git.checkout().setName("main").call()
        }
        val c = Clones(File(tmp, "clones"), open, setOf("file"))
        val build = { b: String -> b == "apk_debug" }
        c.sync("Alpha", remoteDir.toURI(), null, build)
        val count = { text: String -> Regex("^### D-\\d+", RegexOption.MULTILINE).findAll(text).count() }
        val h = (c.history("Alpha", "main", zone, setOf("Claude"), build, "docs/DECISIONS.md" to count) as Outcome.Success).value

        assertEquals("2026-11-01", h.first)
        val days = h.days.associateBy { it.date }
        assertEquals(CommitHistory.Day("2026-11-01", 2, 1, 1, 2, 0, 0), days["2026-11-01"], "23:30 по +03:00 — тот же день")
        // 2 ноября: c — в main копией, d — только в ветке; вклад другого автора — тоже в основной.
        assertEquals(CommitHistory.Day("2026-11-02", 3, 2, 1, 2, 1, 0), days["2026-11-02"])
        assertEquals(CommitHistory.Day("2026-11-03", 1, 1, 0, 1, 0, 0), days["2026-11-03"], "ветка сборок — мимо")
        assertEquals(listOf("2026-11-01" to 2, "2026-11-03" to 4), h.decisions, "журнал — только в дни перемен")
        // Впереди main — одна ветка с одним коммитом: копия влитого не в счёте.
        assertEquals(listOf("work" to 1), h.ahead.map { it.name to it.commits })
        assertTrue(h.complete)
    }

    @Test
    fun branchOfUnlistedContributorIsStillAhead() {
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remoteDir).call().close()
        Git.init().setInitialBranch("main").setDirectory(work).call().use { git ->
            git.repository.config.apply { setString("remote", "origin", "url", remoteDir.toURI().toString()) }.save()
            commit(git, "Maintainer", "2026-11-01 10:00", "a.txt", "a\n")
            git.push().setRemote("origin").add("main").call()
            git.checkout().setCreateBranch(true).setName("contributor").call()
            commit(git, "Contributor", "2026-11-01 11:00", "b.txt", "b\n")
            git.push().setRemote("origin").add("contributor").call()
        }
        val clones = Clones(File(tmp, "clones"), open, setOf("file"))
        clones.sync("Alpha", remoteDir.toURI(), null) { false }
        val history = (clones.history("Alpha", "main", zone, setOf("Claude"), { false }) as Outcome.Success).value
        assertEquals(listOf("contributor" to 1), history.ahead.map { it.name to it.commits })
        assertEquals(2, history.days.sumOf { it.commits }, "коммиты не зависят от личных имён")
        assertEquals(2, history.days.sumOf { it.other }, "прочие — без предположения, что это люди")
    }

    @Test
    fun copiesNeverMergeIndependentAuthorAndTimeGroups() = pairedCopies("Two", "2026-11-02 10:00")

    @Test
    fun copiesWithChangedAuthorTimeRemainIndependent() = pairedCopies("One", "2026-11-02 11:00")

    private fun pairedCopies(other: String, time: String) {
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remoteDir).call().close()
        Git.init().setInitialBranch("main").setDirectory(work).call().use { git ->
            git.repository.config.apply { setString("remote", "origin", "url", remoteDir.toURI().toString()) }.save()
            commit(git, "Maintainer", "2026-11-01 10:00", "base.txt", "base\n")
            val base = git.repository.resolve("HEAD")
            commit(git, "One", "2026-11-02 10:00", "x.txt", "same\n", "One main")
            git.push().setRemote("origin").add("main").call()
            for ((branch, who, time) in listOf(
                Triple("one-copy", "One", "2026-11-02 10:00"),
                Triple("two-a", other, time),
                Triple("two-b", other, time),
            )) {
                git.checkout().setCreateBranch(true).setName(branch).setStartPoint(base.name).call()
                commit(git, who, time, "x.txt", "same\n", branch)
                git.push().setRemote("origin").add(branch).call()
            }
        }
        val clones = Clones(File(tmp, "clones"), open, setOf("file"))
        clones.sync("Alpha", remoteDir.toURI(), null) { false }
        val h = (clones.history("Alpha", "main", zone, setOf("Claude"), { false }) as Outcome.Success).value
        val day = h.days.single { it.date == "2026-11-02" }
        assertEquals(2, day.commits, "одинаковый patch-id разных авторов/времени — независимые изменения")
        assertEquals(1, day.mainline)
        assertEquals(1, day.branchOnly, "две копии одного автора в ветках — один раз")
        assertEquals(1, h.ahead.size)
        assertEquals(1, h.ahead.single().commits)
    }

    @Test
    fun prMergesAreCountedApartFromWork() {
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remoteDir).call().close()
        Git.init().setInitialBranch("main").setDirectory(work).call().use { git ->
            git.repository.config.apply { setString("remote", "origin", "url", remoteDir.toURI().toString()) }.save()
            commit(git, "dpsmpx", "2026-11-01 10:00", "a.txt", "a\n")
            git.checkout().setCreateBranch(true).setName("feature").call()
            commit(git, "Claude", "2026-11-01 11:00", "b.txt", "b\n")
            git.checkout().setName("main").call()
            commit(git, "dpsmpx", "2026-11-01 12:00", "c.txt", "c\n")
            val me = PersonIdent("dpsmpx", "dpsmpx@users.noreply.github.com", at("2026-11-02 09:00"), msk)
            git.merge().include(git.repository.resolve("feature")).setCommit(false).call()
            git.commit().setMessage("Merge pull request #7 from dpsmpx/feature").setAuthor(me).setCommitter(me).call()
            git.push().setRemote("origin").add("main").add("feature").call()
        }
        val c = Clones(File(tmp, "clones"), open, setOf("file"))
        c.sync("Alpha", remoteDir.toURI(), null) { false }
        val h = (c.history("Alpha", "main", zone, setOf("Claude"), { false }) as Outcome.Success).value
        val days = h.days.associateBy { it.date }
        assertEquals(CommitHistory.Day("2026-11-01", 3, 1, 2, 3, 0, 0), days["2026-11-01"], "коммит ветки влит — он в основной")
        assertEquals(CommitHistory.Day("2026-11-02", 0, 0, 0, 0, 0, 1), days["2026-11-02"], "слияние — не коммит работы, а слияние PR")
        assertEquals(emptyList(), h.ahead, "влитая ветка — не впереди")
        assertEquals(emptyList(), h.decisions, "журнала нет — пусто")
    }
}
