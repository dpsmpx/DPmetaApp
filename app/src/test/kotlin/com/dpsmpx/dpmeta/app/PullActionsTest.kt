package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpmeta.actions.ActionLog
import com.dpsmpx.dpmeta.actions.ActionResult
import com.dpsmpx.dpmeta.actions.ActionStatus
import com.dpsmpx.dpmeta.actions.Actions
import com.dpsmpx.dpmeta.app.data.ActionExecutor
import com.dpsmpx.dpmeta.app.screens.BranchActions
import com.dpsmpx.dpmeta.app.screens.IssueActions
import com.dpsmpx.dpmeta.app.screens.PullActions
import com.dpsmpx.dpmeta.github.BranchDetail
import com.dpsmpx.dpmeta.github.Comparison
import com.dpsmpx.dpmeta.github.GitHubWrite
import com.dpsmpx.dpmeta.github.PullDetail
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpmeta.model.RepoRef
import com.dpsmpx.dpmeta.net.HttpRequest
import com.dpsmpx.dpmeta.net.HttpResponse
import com.dpsmpx.dpmeta.net.Net
import com.dpsmpx.dpmeta.net.Transport
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Действия с PR в пульте (F-12, F-13; D-07, D-36): что можно, о чём предупредить, что
 * уходит на GitHub — и что без подтверждения своего уровня запрос не уходит вовсе.
 */
class PullActionsTest {
    @TempDir
    lateinit var dir: Path

    private val repo = (RepoRef.of("dpsmpx", "DPmetaApp") as Outcome.Success).value
    private val head = "d".repeat(40)

    private fun pr(draft: Boolean = false, mergeable: Boolean? = true, state: String = "clean", open: String = "open") =
        PullDetail(183, "AI: полный цикл", open, draft, false, mergeable, state, head, "ccr-273c59ab-oj8683", "main", "PR_kw1", "https://github.com/dpsmpx/DPmetaApp/pull/183")

    /** F-13: черновик и конфликт — «Влить» недоступна; чистый PR с зелёными проверками — без предупреждений. */
    @Test
    fun mergeIsBlockedOrWarnedByF13() {
        assertNull(PullActions.mergeBlocked(pr()))
        assertEquals(emptyList(), PullActions.mergeWarnings(pr(), "green"))
        assertTrue(PullActions.mergeBlocked(pr(draft = true))!!.contains("черновик"))
        assertTrue(PullActions.mergeBlocked(pr(mergeable = false, state = "dirty"))!!.contains("конфликт"))
        assertTrue(PullActions.mergeBlocked(pr(open = "closed"))!!.contains("не открыт"))
        assertEquals(listOf("раздел «Проверки» описания — красный"), PullActions.mergeWarnings(pr(), "red"))
        assertEquals(2, PullActions.mergeWarnings(pr(mergeable = null), null).size, "не распознан и GitHub ещё считает")
        assertTrue(PullActions.mergeWarnings(pr(state = "blocked"), "green").single().contains("защищена"))
    }

    /** «Влить» — команда уровня 2 с SHA показанного коммита; в окне — способ, коммит и «только revert». */
    @Test
    fun mergeCommandCarriesShownHeadAndExplainsItself() {
        val c = PullActions.merge(repo, pr())
        assertEquals(ActionLevel.SIGNIFICANT, c.level)
        assertEquals(mapOf("number" to "183", "head" to head), c.params)
        val text = PullActions.preview(c, pr()).joinToString("\n")
        assertTrue("merge-коммит" in text && head.take(7) in text && "revert" in text, text)
        assertEquals(ActionLevel.REVERSIBLE, PullActions.comment(repo, pr(), "ок").level)
        assertEquals(ActionLevel.SIGNIFICANT, PullActions.close(repo, pr()).level)
        assertEquals("Черновик", PullActions.lines(pr(draft = true), null)[1])
    }

    private class Fake : Transport {
        val seen = mutableListOf<HttpRequest>()

        override fun send(request: HttpRequest): Outcome<HttpResponse> {
            seen += request
            val body = when ("${request.method} ${request.url.rawPath}") {
                "POST /repos/dpsmpx/DPmetaApp/issues/183/comments" -> 201 to """{"html_url":"https://github.com/dpsmpx/DPmetaApp/pull/183#issuecomment-1"}"""
                "PUT /repos/dpsmpx/DPmetaApp/pulls/183/merge" -> 200 to """{"sha":"${"e".repeat(40)}","merged":true}"""
                else -> 404 to """{"message":"Not Found"}"""
            }
            return Outcome.success(HttpResponse(body.first, emptyMap(), body.second.toByteArray()))
        }
    }

    /** Сквозь политику: «Влить» с подтверждением показа не уходит на GitHub; с PIN — уходит PUT с SHA. */
    @Test
    fun actionsReachGitHubOnlyWithTheirConfirmation() {
        val clock = ManualClock()
        val actions = Actions(ActionLog(NioVfs(dir), clock, RandomSource.seeded(1)), clock, RandomSource.seeded(2))
        val fake = Fake()
        val write = GitHubWrite(Net(fake), Secret("t"))
        fun run(c: com.dpsmpx.dpmeta.actions.ActionCommand, conf: com.dpsmpx.dpmeta.actions.Confirmation): Outcome<ActionResult> =
            actions.run(c, conf) { ActionExecutor.execute(write, c) }

        val comment = PullActions.comment(repo, pr(), "Проверю на телефоне")
        assertEquals("комментарий отправлен", run(comment, actions.shown(comment)).valueOrNull()?.detail)
        val merge = PullActions.merge(repo, pr())
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, assertIs<Outcome.Failure>(run(merge, actions.shown(merge))).errors.single().code)
        assertEquals(listOf("POST"), fake.seen.map { it.method }, "без PIN «Влить» на GitHub не ушло")
        assertEquals("PR влит", run(merge, actions.deviceConfirmed(merge)).valueOrNull()?.detail)
        assertTrue(String(fake.seen.last().body!!).contains(head))
        val log = ActionLog(NioVfs(dir), clock, RandomSource.seeded(3)).entries().valueOrNull()!!
        assertEquals(listOf(ActionKind.COMMENT.code to ActionStatus.DONE, ActionKind.PR_MERGE.code to ActionStatus.REFUSED, ActionKind.PR_MERGE.code to ActionStatus.DONE), log.map { it.kind to it.status })
    }

    private fun branchView(ahead: Int, name: String = "claude/x", protected: Boolean = false) =
        BranchActions.View(repo, "main", BranchDetail(name, head, protected), if (name == "main") null else Comparison(if (ahead == 0) "behind" else "diverged", ahead, 1))

    /** F-14: влитая — уровень 2, невлитая — 3 с именем ветки; основную и защищённую не удаляем. */
    @Test
    fun branchDeleteLevelFollowsMergedState() {
        assertEquals(ActionKind.BRANCH_DELETE_MERGED, BranchActions.delete(branchView(0)).kind)
        val unmerged = BranchActions.delete(branchView(3))
        assertEquals(ActionLevel.DANGEROUS to "claude/x", unmerged.level to unmerged.nameToType)
        assertEquals(head, unmerged.params["head"])
        assertTrue(BranchActions.losses(branchView(3), listOf(12)).joinToString().let { "3" in it && head in it && "#12" in it })
        assertTrue(BranchActions.deleteBlocked(branchView(0, name = "main"))!!.contains("основную"))
        assertTrue(BranchActions.deleteBlocked(branchView(0, protected = true))!!.contains("защищена"))
        assertNull(BranchActions.deleteBlocked(branchView(3)))
    }

    private class Branches(val ahead: Int, val sha: String) : Transport {
        val seen = mutableListOf<String>()

        override fun send(request: HttpRequest): Outcome<HttpResponse> {
            val key = "${request.method} ${request.url.rawPath}"
            seen += key
            val body = when (key) {
                "GET /repos/dpsmpx/DPmetaApp" -> 200 to """{"default_branch":"main"}"""
                "GET /repos/dpsmpx/DPmetaApp/branches/claude/x" -> 200 to """{"name":"claude/x","commit":{"sha":"$sha"},"protected":false}"""
                "GET /repos/dpsmpx/DPmetaApp/compare/main...claude/x" -> 200 to """{"status":"x","ahead_by":$ahead,"behind_by":0}"""
                "DELETE /repos/dpsmpx/DPmetaApp/git/refs/heads/claude/x" -> 204 to ""
                else -> 404 to """{"message":"Not Found"}"""
            }
            return Outcome.success(HttpResponse(body.first, emptyMap(), body.second.toByteArray()))
        }
    }

    /** Удаление ветки: уровень 3 без верного имени до GitHub не доходит; исполнитель сверяет всё заново. */
    @Test
    fun branchDeleteChecksAgainBeforeDeleting() {
        val clock = ManualClock()
        val actions = Actions(ActionLog(NioVfs(dir), clock, RandomSource.seeded(1)), clock, RandomSource.seeded(2))
        fun run(t: Branches, c: com.dpsmpx.dpmeta.actions.ActionCommand, conf: com.dpsmpx.dpmeta.actions.Confirmation) =
            actions.run(c, conf) { ActionExecutor.execute(GitHubWrite(Net(t), Secret("t")), c) }

        val unmerged = BranchActions.delete(branchView(3))
        val t1 = Branches(ahead = 3, sha = head)
        assertIs<Outcome.Failure>(run(t1, unmerged, actions.typedName(unmerged, "claude/y")))
        assertEquals(emptyList(), t1.seen, "без верного имени — ни одного запроса")
        val done = run(t1, unmerged, actions.typedName(unmerged, "claude/x")).valueOrNull()!!
        assertTrue(head in done.detail, "SHA головы — в итоге и журнале")
        assertEquals("DELETE /repos/dpsmpx/DPmetaApp/git/refs/heads/claude/x", t1.seen.last())

        val merged = BranchActions.delete(branchView(0))
        val grew = Branches(ahead = 2, sha = head)
        val r = run(grew, merged, actions.deviceConfirmed(merged))
        assertTrue(assertIs<Outcome.Failure>(r).errors.single().message.contains("невлитых"))
        assertTrue(grew.seen.none { it.startsWith("DELETE") }, "влитая на экране, а сейчас нет — не удаляем по PIN")
        val moved = Branches(ahead = 0, sha = "f".repeat(40))
        assertIs<Outcome.Failure>(run(moved, merged, actions.deviceConfirmed(merged)))
        assertTrue(moved.seen.none { it.startsWith("DELETE") }, "новый коммит в ветке — не удаляем")
    }

    /** F-26: issue — уровень 1, метка `from-dpmetaapp` — всегда, из кода. */
    @Test
    fun issueIsLevelOneWithOwnLabel() {
        assertEquals("нужен заголовок", IssueActions.invalid(" "))
        assertNull(IssueActions.invalid("Сверить ROADMAP"))
        val c = IssueActions.command(repo, " Сверить ROADMAP ", "с телефона")
        assertEquals(ActionLevel.REVERSIBLE to "Сверить ROADMAP", c.level to c.params["title"])
        val sent = mutableListOf<String>()
        val t = Transport { r ->
            sent += String(r.body!!)
            Outcome.success(HttpResponse(201, emptyMap(), """{"html_url":"https://github.com/dpsmpx/DPmetaApp/issues/9"}""".toByteArray()))
        }
        val clock = ManualClock()
        val actions = Actions(ActionLog(NioVfs(dir), clock, RandomSource.seeded(1)), clock, RandomSource.seeded(2))
        val r = actions.run(c, actions.shown(c)) { ActionExecutor.execute(GitHubWrite(Net(t), Secret("t")), c) }
        assertEquals("https://github.com/dpsmpx/DPmetaApp/issues/9", r.valueOrNull()?.link)
        assertTrue(sent.single().contains("from-dpmetaapp"))
    }
}
