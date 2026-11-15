package com.dpsmpx.dpmeta.github

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpmeta.model.RepoRef
import com.dpsmpx.dpmeta.net.HttpRequest
import com.dpsmpx.dpmeta.net.HttpResponse
import com.dpsmpx.dpmeta.net.Net
import com.dpsmpx.dpmeta.net.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Запись в репозитории через GitHub (D-36, F-12…F-14) на синтетических ответах. */
class GitHubWriteTest {
    private class Fake(val routes: Map<String, Pair<Int, String>>) : Transport {
        val seen = mutableListOf<HttpRequest>()

        override fun send(request: HttpRequest): Outcome<HttpResponse> {
            seen += request
            val (status, body) = routes["${request.method} ${request.url.rawPath}"] ?: (404 to """{"message":"Not Found"}""")
            return Outcome.success(HttpResponse(status, emptyMap(), body.toByteArray()))
        }
    }

    private val repo = RepoRef.parse("example/Alpha").valueOrNull()!!
    private val head = "b".repeat(40)

    private fun gh(vararg routes: Pair<String, Pair<Int, String>>) = Fake(routes.toMap()).let { it to GitHubWrite(Net(it), Secret("t")) }

    private fun body(r: HttpRequest): JsonObject = JsonParser().parse(r.body!!.toString(Charsets.UTF_8)).valueOrNull() as JsonObject

    private fun code(r: Outcome<*>) = assertIs<Outcome.Failure>(r).errors.single().code

    private val pullJson = """{"number":7,"title":"Этап 5","state":"open","draft":true,"merged":false,"mergeable":true,"mergeable_state":"clean",
        "head":{"sha":"$head","ref":"claude/x"},"base":{"ref":"main"},"node_id":"PR_kw1","html_url":"https://github.com/example/Alpha/pull/7"}"""

    /** Карточка PR (F-11): описание и размер — из того же ответа; коммиты, файлы, обсуждение — свежие, по одному запросу. */
    @Test
    fun pullCardReads() {
        val full = pullJson.trimEnd('}') + ""","body":"## Сделано\r\nвсё\u0007","user":{"login":"dpsmpx"},"additions":12,"deletions":3,"changed_files":2,"commits":4}"""
        val (fake, w) = gh(
            "GET /repos/example/Alpha/pulls/7" to (200 to full),
            "GET /repos/example/Alpha/pulls/7/commits" to (200 to """[{"sha":"$head","commit":{"message":"Этап 5: запись\n\nподробно","author":{"name":"Claude","date":"2026-11-09T10:00:00Z"}},"author":null},{"sha":"плохой"}]"""),
            "GET /repos/example/Alpha/pulls/7/files" to (200 to """[{"filename":"app/A.kt","status":"modified","additions":10,"deletions":2},{"status":"added"}]"""),
            "GET /repos/example/Alpha/issues/7/comments" to (200 to """[{"user":{"login":"dpsmpx"},"created_at":"2026-11-09T12:00:00Z","body":"Проверю"}]"""),
            "GET /repos/example/Alpha/pulls/7/reviews" to (200 to """[{"user":{"login":"bot"},"submitted_at":"2026-11-09T11:00:00Z","state":"COMMENTED","body":""},{"user":{"login":"dpsmpx"},"submitted_at":"2026-11-09T13:00:00Z","state":"APPROVED","body":""}]"""),
        )
        val p = w.pull(repo, 7).valueOrNull()!!
        assertEquals("## Сделано\nвсё", p.body, "перевод строки — один, управляющие знаки вырезаны")
        assertEquals(listOf("dpsmpx", "12", "3", "2", "4"), listOf(p.author, "${p.additions}", "${p.deletions}", "${p.changedFiles}", "${p.commits}"))
        assertEquals(listOf(PullCommit(head, "Этап 5: запись", "Claude", "2026-11-09T10:00:00Z")), w.pullCommits(repo, 7).valueOrNull())
        assertEquals(listOf(PullFile("app/A.kt", "modified", 10, 2)), w.pullFiles(repo, 7).valueOrNull())
        val notes = w.discussion(repo, 7).valueOrNull()!!
        assertEquals(listOf("Проверю" to null, "" to "APPROVED"), notes.map { it.text to it.review }, "пустой отзыв без вердикта пропущен; по времени")
        assertTrue(fake.seen.all { it.method == "GET" && it.url.query?.contains("per_page=100") != false })
    }

    @Test
    fun pullDetailIsFreshAndFull() {
        val (fake, w) = gh("GET /repos/example/Alpha/pulls/7" to (200 to pullJson))
        val p = w.pull(repo, 7).valueOrNull()!!
        assertEquals(PullDetail(7, "Этап 5", "open", true, false, true, "clean", head, "claude/x", "main", "PR_kw1", "https://github.com/example/Alpha/pull/7"), p)
        assertTrue(fake.seen.single().headers["If-None-Match"] == null, "без кэша ETag")
    }

    /** «Влить» — merge-коммитом и только показанного коммита (F-13); новый коммит — 409, отказ. */
    @Test
    fun mergeUsesMergeCommitOfShownHead() {
        val (fake, w) = gh("PUT /repos/example/Alpha/pulls/7/merge" to (200 to """{"sha":"${"c".repeat(40)}","merged":true,"message":"Pull Request successfully merged"}"""))
        val r = w.merge(repo, 7, head).valueOrNull()!!
        assertEquals(Written("https://github.com/example/Alpha/pull/7", "c".repeat(40)), r)
        val req = fake.seen.single()
        assertEquals("PUT", req.method)
        assertEquals(mapOf("merge_method" to "merge", "sha" to head), body(req).fields.mapValues { (it.value as JsonString).value })
        val (_, moved) = gh("PUT /repos/example/Alpha/pulls/7/merge" to (409 to """{"message":"Head branch was modified. Review and try the merge again."}"""))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, code(moved.merge(repo, 7, head)))
        val (_, blocked) = gh("PUT /repos/example/Alpha/pulls/7/merge" to (405 to """{"message":"Pull Request is not mergeable"}"""))
        val why = assertIs<Outcome.Failure>(blocked.merge(repo, 7, head)).errors.single().message
        assertTrue("not mergeable" in why, why)
        assertEquals(DiagCode.VALUE_INVALID, code(w.merge(repo, 7, "main")), "не SHA — запрос не уходит")
        assertEquals(1, fake.seen.size)
    }

    private val pull = PullDetail(7, "t", "open", true, false, true, "clean", head, "claude/x", "main", "PR_kw1", "https://github.com/example/Alpha/pull/7")

    @Test
    fun commentReviewCloseAndDeleteSendTheRightRequests() {
        val (fake, w) = gh(
            "POST /repos/example/Alpha/issues/7/comments" to (201 to """{"html_url":"https://github.com/example/Alpha/pull/7#issuecomment-1"}"""),
            "POST /repos/example/Alpha/pulls/7/reviews" to (200 to """{"html_url":"https://github.com/example/Alpha/pull/7#pullrequestreview-2"}"""),
            "POST /graphql" to (200 to """{"data":{"closePullRequest":{"pullRequest":{"url":"https://github.com/example/Alpha/pull/7"}}}}"""),
            "DELETE /repos/example/Alpha/git/refs/heads/claude/x" to (204 to ""),
        )
        assertEquals("https://github.com/example/Alpha/pull/7#issuecomment-1", w.comment(repo, 7, "Посмотрю").valueOrNull()?.link)
        assertIs<Outcome.Success<Written>>(w.review(repo, 7, approve = false, body = "Нужен тест"))
        assertIs<Outcome.Success<Written>>(w.close(pull))
        assertIs<Outcome.Success<Written>>(w.deleteBranch(repo, "claude/x"))
        assertEquals(listOf("POST", "POST", "POST", "DELETE"), fake.seen.map { it.method }, "без PATCH: его HttpURLConnection не отправит")
        assertEquals("Посмотрю", (body(fake.seen[0])["body"] as JsonString).value)
        assertEquals("REQUEST_CHANGES", (body(fake.seen[1])["event"] as JsonString).value)
        assertTrue((body(fake.seen[2])["query"] as JsonString).value.contains("closePullRequest"))
        assertTrue(fake.seen.all { it.auth != null && it.headers["Authorization"] == null }, "токен — только полем auth")
    }

    /** Из черновика — мутацией GraphQL по node_id; ошибка GraphQL при коде 200 — отказ, а не успех. */
    @Test
    fun readyForReviewGoesThroughGraphQl() {
        val (fake, w) = gh("POST /graphql" to (200 to """{"data":{"markPullRequestReadyForReview":{"pullRequest":{"url":"https://github.com/example/Alpha/pull/7"}}}}"""))
        assertIs<Outcome.Success<Written>>(w.ready(pull))
        assertEquals("PR_kw1", ((body(fake.seen.single())["variables"] as JsonObject)["id"] as JsonString).value)
        val (_, bad) = gh("POST /graphql" to (200 to """{"errors":[{"message":"Resource not accessible by integration"}]}"""))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, code(bad.ready(pull)))
    }

    /** Правка файла (F-69): текст и блоб, ветка, запись поверх блоба, PR; чужая правка — 409. */
    @Test
    fun fileEditRoundTrip() {
        val text = "# ROADMAP\nстрока\n"
        val b64 = java.util.Base64.getMimeEncoder().encodeToString(text.toByteArray())
        val blob = "1".repeat(40)
        val (fake, w) = gh(
            "GET /repos/example/Alpha/contents/docs/ROADMAP.md" to (200 to """{"type":"file","sha":"$blob","size":${text.toByteArray().size},"encoding":"base64","content":"${b64.replace("\r\n", "\\n")}"}"""),
            "POST /repos/example/Alpha/git/refs" to (201 to """{"ref":"refs/heads/dpmetaapp/edit"}"""),
            "PUT /repos/example/Alpha/contents/docs/ROADMAP.md" to (200 to """{"commit":{"sha":"${"2".repeat(40)}","html_url":"https://github.com/example/Alpha/commit/2"}}"""),
            "POST /repos/example/Alpha/pulls" to (201 to """{"html_url":"https://github.com/example/Alpha/pull/8"}"""),
        )
        val f = w.fileForEdit(repo, "docs/ROADMAP.md", "main").valueOrNull()!!
        assertEquals(FileBlob("docs/ROADMAP.md", blob, text, "main"), f)
        assertTrue(fake.seen.single().url.rawQuery == "ref=main")
        assertIs<Outcome.Success<Written>>(w.createBranch(repo, "dpmetaapp/edit", head))
        val put = w.putFile(repo, "docs/ROADMAP.md", "dpmetaapp/edit", blob, text + "ещё\n", "ROADMAP: строка").valueOrNull()!!
        assertEquals("2".repeat(40), put.sha)
        val sent = body(fake.seen[2])
        assertEquals(blob to "dpmetaapp/edit", (sent["sha"] as JsonString).value to (sent["branch"] as JsonString).value)
        assertEquals(text + "ещё\n", String(java.util.Base64.getDecoder().decode((sent["content"] as JsonString).value)))
        assertEquals("https://github.com/example/Alpha/pull/8", w.openPull(repo, "dpmetaapp/edit", "main", "t", "b").valueOrNull()?.link)

        val (_, conflict) = gh("PUT /repos/example/Alpha/contents/docs/ROADMAP.md" to (409 to """{"message":"docs/ROADMAP.md does not match $blob"}"""))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, code(conflict.putFile(repo, "docs/ROADMAP.md", "main", blob, "x", "m")))
        val (_, exists) = gh("POST /repos/example/Alpha/git/refs" to (422 to """{"message":"Reference already exists"}"""))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, code(exists.createBranch(repo, "dpmetaapp/edit", head)))
        val bin = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0))
        val (_, binary) = gh("GET /repos/example/Alpha/contents/logo.png" to (200 to """{"type":"file","sha":"$blob","size":3,"content":"$bin"}"""))
        assertEquals(DiagCode.VALUE_INVALID, code(binary.fileForEdit(repo, "logo.png", "main")))
    }

    @Test
    fun issueCarriesTitleBodyAndLabel() {
        val (fake, w) = gh("POST /repos/example/Alpha/issues" to (201 to """{"number":5,"html_url":"https://github.com/example/Alpha/issues/5"}"""))
        assertEquals("https://github.com/example/Alpha/issues/5", w.issue(repo, "Сверить ROADMAP", "С телефона", listOf("from-dpmetaapp")).valueOrNull()?.link)
        val b = body(fake.seen.single())
        assertEquals("Сверить ROADMAP", (b["title"] as JsonString).value)
        assertEquals("from-dpmetaapp", ((b["labels"] as com.dpsmpx.dpai.core.serialization.JsonArray).items.single() as JsonString).value)
    }

    /** «Опубликовать» (D-150): владелец — из токена, репозиторий — закрытый и пустой; чужой владелец в ответе — отказ. */
    @Test
    fun publishCreatesPrivateEmptyRepository() {
        val (_, me) = gh("GET /user" to (200 to """{"login":"example"}"""))
        assertEquals("example", me.viewer().valueOrNull())
        val (fake, w) = gh("POST /user/repos" to (201 to """{"full_name":"example/Alpha","html_url":"https://github.com/example/Alpha"}"""))
        assertEquals("https://github.com/example/Alpha", w.createRepository(repo, "С телефона").valueOrNull()?.link)
        val b = body(fake.seen.single())
        assertEquals(listOf("Alpha", "true", "false"), listOf((b["name"] as JsonString).value, "${(b["private"] as com.dpsmpx.dpai.core.serialization.JsonBool).value}",
            "${(b["auto_init"] as com.dpsmpx.dpai.core.serialization.JsonBool).value}"))
        val (_, other) = gh("POST /user/repos" to (201 to """{"full_name":"someone/Alpha"}"""))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, code(other.createRepository(repo, "")))
        val (_, taken) = gh("POST /user/repos" to (422 to """{"message":"Repository creation failed."}"""))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, code(taken.createRepository(repo, "")))
    }

    @Test
    fun defaultBranchAndBranchHead() {
        val (_, w) = gh(
            "GET /repos/example/Alpha" to (200 to """{"full_name":"example/Alpha","default_branch":"main"}"""),
            "GET /repos/example/Alpha/branches/claude/x" to (200 to """{"name":"claude/x","commit":{"sha":"$head"},"protected":false}"""),
            "GET /repos/example/Alpha/branches/main" to (200 to """{"name":"main","commit":{"sha":"$head"},"protected":true}"""),
        )
        assertEquals("main", w.defaultBranch(repo).valueOrNull())
        assertEquals(BranchDetail("claude/x", head, false), w.branch(repo, "claude/x").valueOrNull())
        assertTrue(w.branch(repo, "main").valueOrNull()!!.protected)
    }

    @Test
    fun compareTellsMergedBranchesAndNoRightsIsNamed() {
        val (_, w) = gh(
            "GET /repos/example/Alpha/compare/main...claude/x" to (200 to """{"status":"behind","ahead_by":0,"behind_by":3}"""),
            "GET /repos/example/Alpha/compare/main...claude/y" to (200 to """{"status":"diverged","ahead_by":2,"behind_by":1}"""),
        )
        assertTrue(w.compare(repo, "main", "claude/x").valueOrNull()!!.merged)
        assertEquals(false, w.compare(repo, "main", "claude/y").valueOrNull()!!.merged)
        val (_, ro) = gh("POST /repos/example/Alpha/issues/7/comments" to (403 to """{"message":"Resource not accessible by personal access token"}"""))
        assertEquals(DiagCode.GITHUB_AUTH_EXPIRED, code(ro.comment(repo, 7, "x")), "токен только на чтение — отказ «нет прав»")
    }
}
