package com.dpsmpx.dpmeta.github

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.model.RepoRef
import com.dpsmpx.dpmeta.net.HttpRequest
import com.dpsmpx.dpmeta.net.HttpResponse
import com.dpsmpx.dpmeta.net.Net
import com.dpsmpx.dpmeta.net.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** GitHub REST и device flow на синтетических ответах (ТЗ, I-09; D-17). */
class GitHubTest {
    private class Fake(val routes: Map<String, Pair<Int, String>>, val headers: Map<String, List<String>> = emptyMap()) : Transport {
        val seen = mutableListOf<HttpRequest>()

        override fun send(request: HttpRequest): Outcome<HttpResponse> {
            seen += request
            val key = request.url.rawPath + (request.url.rawQuery?.let { "?$it" } ?: "")
            val (status, body) = routes[key] ?: (404 to "{}")
            return Outcome.success(HttpResponse(status, headers, body.toByteArray()))
        }
    }

    private val repo = RepoRef.parse("example/Alpha").valueOrNull()!!
    private val sha = "a".repeat(40)

    @Test
    fun parsesPullsBranchesCommitsAndFiles() {
        val fake = Fake(
            mapOf(
                "/repos/example/Alpha/pulls?state=open&per_page=100" to (200 to """[{"number":7,"title":"Этап 2","draft":true,"head":{"ref":"claude/x"},"user":{"login":"bot"},"updated_at":"2026-11-01T10:00:00Z","created_at":"2026-11-01T08:00:00Z","extra":1}]"""),
                "/repos/example/Alpha/branches?per_page=100" to (200 to """[{"name":"main","commit":{"sha":"$sha"}}]"""),
                "/repos/example/Alpha/commits/$sha" to (200 to """{"sha":"$sha","commit":{"committer":{"date":"2026-11-01T09:30:00Z"}},"author":{"login":"dpsmpx"}}"""),
                "/repos/example/Alpha/contents/docs/%D0%BF%D1%83%D1%82%D1%8C%20x.md" to (200 to "# текст"),
            ),
        )
        val gh = GitHub(Net(fake), Secret("t"))
        val pr = (gh.pulls(repo) as Outcome.Success).value.value.single()
        assertEquals(PullRequest(7, "Этап 2", true, "claude/x", "bot", pr.updatedAt, createdAt = pr.createdAt), pr)
        assertEquals(1_793_527_200_000, pr.updatedAt.epochMillis)
        assertEquals(1_793_527_200_000 - 2 * 3_600_000, pr.createdAt?.epochMillis, "время открытия — из того же ответа (ТЗ, §8)")
        assertEquals(listOf(Branch("main", sha)), (gh.branches(repo) as Outcome.Success).value.value)
        assertEquals("dpsmpx", (gh.commit(repo, sha) as Outcome.Success).value.author)
        assertEquals("# текст", (gh.file(repo, "docs/путь x.md") as Outcome.Success).value.value)
        assertTrue(fake.seen.all { it.headers["X-GitHub-Api-Version"] == GitHub.API_VERSION })
    }

    @Test
    fun reposListsEveryPageSortedAndSkipsBadNames() {
        val page1 = (1..100).joinToString(",", "[", "]") { i -> "{\"full_name\":\"dpsmpx/r${"%03d".format(i)}\",\"private\":${i % 2 == 0},\"archived\":false}" }
        val page2 = "[{\"full_name\":\"dpsmpx/DPmetaApp\",\"private\":false,\"archived\":false},{\"full_name\":\"не адрес\"},{\"full_name\":\"dpsmpx/Old\",\"private\":true,\"archived\":true}]"
        val fake = Fake(
            mapOf(
                "/user/repos?per_page=100&sort=full_name&page=1" to (200 to page1),
                "/user/repos?per_page=100&sort=full_name&page=2" to (200 to page2),
            ),
        )
        val repos = (GitHub(Net(fake), Secret("t")).repos() as Outcome.Success).value
        assertEquals(102, repos.size, "обе страницы, без строки с плохим адресом")
        assertEquals("dpsmpx/DPmetaApp", repos.first().slug.toString(), "по алфавиту без регистра")
        assertTrue(repos.single { it.slug.toString() == "dpsmpx/Old" }.archived)
        assertEquals(2, fake.seen.size, "неполная страница — последняя")
    }

    @Test
    fun mapsStatusesToCodes() {
        fun code(status: Int, headers: Map<String, List<String>> = emptyMap()): DiagCode {
            val gh = GitHub(Net(Fake(mapOf("/repos/example/Alpha/branches?per_page=100" to (status to "{}")), headers)), null)
            return (gh.branches(repo) as Outcome.Failure).diagnostics.single().code
        }
        assertEquals(DiagCode.GITHUB_AUTH_EXPIRED, code(401))
        assertEquals(DiagCode.GITHUB_AUTH_EXPIRED, code(403))
        assertEquals(DiagCode.GITHUB_RATE_LIMITED, code(403, mapOf("X-RateLimit-Remaining" to listOf("0"))))
        assertEquals(DiagCode.GITHUB_RATE_LIMITED, code(429))
        assertEquals(DiagCode.GITHUB_NOT_FOUND, code(404))
        assertEquals(DiagCode.NETWORK_UNREACHABLE, code(502))
        assertEquals(DiagCode.CONNECTOR_REPLY_INVALID, code(418))
    }

    @Test
    fun refusesBadPathsAndShas() {
        val gh = GitHub(Net(Fake(emptyMap())), null)
        for (p in listOf("", "/abs", "a/../b", "a//b", "./a")) assertIs<Outcome.Failure>(gh.file(repo, p), p)
        assertIs<Outcome.Failure>(gh.commit(repo, "main"))
    }

    @Test
    fun deviceFlowStepsWithoutWaiting() {
        val fake = Fake(
            mapOf(
                "/login/device/code" to (200 to """{"device_code":"секрет","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","interval":5,"expires_in":900}"""),
            ),
        )
        val flow = DeviceFlow(Net(fake), "Iv1.client")
        val code = (flow.start() as Outcome.Success).value
        assertEquals("ABCD-1234", code.userCode)
        assertEquals("<redacted>", code.deviceCode.toString())
        fun poll(reply: String): DeviceFlow.Poll {
            val f = DeviceFlow(Net(Fake(mapOf("/login/oauth/access_token" to (200 to reply)))), "Iv1.client")
            return (f.poll(code) as Outcome.Success).value
        }
        assertEquals(DeviceFlow.Poll.Pending(5), poll("""{"error":"authorization_pending"}"""))
        assertEquals(DeviceFlow.Poll.Pending(10), poll("""{"error":"slow_down","interval":10}"""))
        assertIs<DeviceFlow.Poll.Stopped>(poll("""{"error":"access_denied"}"""))
        val token = poll("""{"access_token":"ghu_x","token_type":"bearer"}""")
        assertIs<DeviceFlow.Poll.Token>(token)
        assertEquals("<redacted>", token.token.toString(), "токен не печатается")
        val body = fake.seen.single().body!!.toString(Charsets.UTF_8)
        assertEquals("client_id=Iv1.client", body)
    }
}
