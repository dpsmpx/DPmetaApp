package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpmeta.actions.ActionLog
import com.dpsmpx.dpmeta.actions.Actions
import com.dpsmpx.dpmeta.app.data.ActionExecutor
import com.dpsmpx.dpmeta.app.screens.EditActions
import com.dpsmpx.dpmeta.app.screens.LineDiff
import com.dpsmpx.dpmeta.github.FileBlob
import com.dpsmpx.dpmeta.github.GitHubWrite
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.git.RepoRef
import com.dpsmpx.dpmeta.net.HttpResponse
import com.dpsmpx.dpmeta.net.Net
import com.dpsmpx.dpmeta.net.Transport
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Правка файла из пульта (F-69; D-36): дифф, команда, сверка текста, ветка и PR или основная ветка. */
class EditActionsTest {
    @TempDir
    lateinit var dir: Path

    private val repo = (RepoRef.of("dpsmpx", "DPmetaApp") as Outcome.Success).value
    private val blob = "1".repeat(40)
    private val edit = EditActions.Edit("DPmetaApp", repo, "main", FileBlob("docs/ROADMAP.md", blob, "a\nb\nc\nd\n", "main"))
    private val at = Timestamp(Instant.parse("2026-11-09T21:30:05Z").toEpochMilli())

    @Test
    fun diffShowsChangedLinesWithContext() {
        val lines = LineDiff.diff("a\nb\nc\nd\ne\nf\ng\nh\ni\n", "a\nb\nC\nd\ne\nf\ng\nh\nI\n")
        assertEquals(2 to 2, LineDiff.stats(lines))
        // Две строки вокруг правки; «f» — дальше обеих правок и скрыта за «…».
        assertEquals(listOf("  a", "  b", "- c", "+ C", "  d", "  e", "…", "  g", "  h", "- i", "+ I", "  "), LineDiff.hunks(lines))
        assertEquals(0 to 0, LineDiff.stats(LineDiff.diff("x\n", "x\n")))
    }

    @Test
    fun commandLevelBranchAndNoTextInParams() {
        assertEquals("изменений нет", EditActions.invalid(edit, edit.blob.text, "m"))
        assertEquals("нужно сообщение коммита", EditActions.invalid(edit, "x", " "))
        val branch = EditActions.branchName(edit, at)
        assertEquals("dpmetaapp/edit-roadmap-md-20261109-213005", branch)
        val pr = EditActions.command(edit, "a\nB\nc\nd\n", "ROADMAP: B", toDefault = false, branch = branch)
        assertEquals(ActionKind.FILES_COMMIT_PR to ActionLevel.REVERSIBLE, pr.kind to pr.level)
        assertEquals(branch, pr.target.branch)
        assertEquals("+1 -1", pr.params["lines"])
        assertTrue(pr.params.values.none { "B\nc" in it }, "текст файла — не в параметрах (журнал)")
        val main = EditActions.command(edit, "x", "m", toDefault = true, branch = branch)
        assertEquals(ActionLevel.SIGNIFICANT to "main", main.level to main.target.branch)
    }

    private class Recorder(val replies: Map<String, Pair<Int, String>>) : Transport {
        val seen = mutableListOf<String>()

        override fun send(request: com.dpsmpx.dpmeta.net.HttpRequest): Outcome<HttpResponse> {
            val key = "${request.method} ${request.url.rawPath}"
            seen += key
            val (status, body) = replies[key] ?: (404 to """{"message":"Not Found"}""")
            return Outcome.success(HttpResponse(status, emptyMap(), body.toByteArray()))
        }
    }

    /** В ветку с PR: ветка от головы main, коммит поверх блоба, PR; текст не тот — ни одного запроса. */
    @Test
    fun editGoesToBranchAndPullOnlyWithTheShownText() {
        val head = "2".repeat(40)
        val t = Recorder(
            mapOf(
                "GET /repos/dpsmpx/DPmetaApp/branches/main" to (200 to """{"name":"main","commit":{"sha":"$head"},"protected":true}"""),
                "POST /repos/dpsmpx/DPmetaApp/git/refs" to (201 to "{}"),
                "PUT /repos/dpsmpx/DPmetaApp/contents/docs/ROADMAP.md" to (200 to """{"commit":{"sha":"${"3".repeat(40)}","html_url":"https://github.com/dpsmpx/DPmetaApp/commit/3"}}"""),
                "POST /repos/dpsmpx/DPmetaApp/pulls" to (201 to """{"html_url":"https://github.com/dpsmpx/DPmetaApp/pull/190"}"""),
            ),
        )
        val clock = ManualClock()
        val actions = Actions(ActionLog(NioVfs(dir), clock, RandomSource.seeded(1)), clock, RandomSource.seeded(2))
        val text = "a\nB\nc\nd\n"
        val c = EditActions.command(edit, text, "ROADMAP: B", toDefault = false, branch = EditActions.branchName(edit, at))
        val w = GitHubWrite(Net(t), Secret("t"))
        assertIs<Outcome.Failure>(actions.run(c, actions.shown(c)) { ActionExecutor.execute(w, c, "другой текст") })
        assertEquals(emptyList(), t.seen, "текст не тот, что был показан, — ничего не ушло")
        val r = actions.run(c, actions.shown(c)) { ActionExecutor.execute(w, c, text) }
        assertEquals("https://github.com/dpsmpx/DPmetaApp/pull/190", r.valueOrNull()?.link)
        assertEquals(
            listOf("GET /repos/dpsmpx/DPmetaApp/branches/main", "POST /repos/dpsmpx/DPmetaApp/git/refs", "PUT /repos/dpsmpx/DPmetaApp/contents/docs/ROADMAP.md", "POST /repos/dpsmpx/DPmetaApp/pulls"),
            t.seen,
        )
    }

    /** Коммит не лёг (файл изменили на GitHub) — пустая ветка правки удаляется. */
    @Test
    fun failedCommitRemovesTheEmptyBranch() {
        val t = Recorder(
            mapOf(
                "GET /repos/dpsmpx/DPmetaApp/branches/main" to (200 to """{"name":"main","commit":{"sha":"${"2".repeat(40)}"}}"""),
                "POST /repos/dpsmpx/DPmetaApp/git/refs" to (201 to "{}"),
                "PUT /repos/dpsmpx/DPmetaApp/contents/docs/ROADMAP.md" to (409 to """{"message":"does not match"}"""),
            ),
        )
        val text = "x\n"
        val c = EditActions.command(edit, text, "m", toDefault = false, branch = EditActions.branchName(edit, at))
        assertIs<Outcome.Failure>(ActionExecutor.execute(GitHubWrite(Net(t), Secret("t")), c, text))
        assertTrue(t.seen.last().startsWith("DELETE /repos/dpsmpx/DPmetaApp/git/refs/heads/dpmetaapp/edit-"), t.seen.toString())
    }
}
