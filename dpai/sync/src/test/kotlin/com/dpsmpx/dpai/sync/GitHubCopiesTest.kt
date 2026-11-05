package com.dpsmpx.dpai.sync

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.providers.Cancel
import com.dpsmpx.dpai.providers.HttpCall
import com.dpsmpx.dpai.providers.HttpReply
import com.dpsmpx.dpai.providers.Transport
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Клиент копий в GitHub (D-107, T20) на фальшивом транспорте: порядок
 * запросов Git Data API, только `api.github.com`, токен — только в
 * заголовке, отказ публичному репозиторию и токену без записи.
 */
class GitHubCopiesTest {
    private val token = "github_pat_" + "x".repeat(30)

    private class Reply(override val status: Int, body: String, private val headers: Map<String, String> = emptyMap()) : HttpReply {
        private val lines = ArrayDeque(body.split('\n'))

        override fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        override fun readLine(): String? = lines.removeFirstOrNull()

        override fun close() {}
    }

    private class Fake(val routes: (HttpCall) -> Pair<Int, String>) : Transport {
        val calls = mutableListOf<HttpCall>()

        override fun open(call: HttpCall, cancel: Cancel): Outcome<HttpReply> {
            calls += call
            val (status, body) = routes(call)
            return Outcome.success(Reply(status, body))
        }
    }

    private val sha = { c: Char -> c.toString().repeat(40) }

    private fun repo(private: Boolean = true, push: Boolean = true) =
        "{\n  \"private\": $private,\n  \"default_branch\": \"main\",\n  \"permissions\": {\"push\": $push}\n}"

    private fun routes(call: HttpCall): Pair<Int, String> {
        val p = call.url.path.removePrefix("/repos/me/copies")
        return when ("${call.method} $p") {
            "GET " -> 200 to repo()
            "GET /git/ref/heads/main" -> 200 to "{\"object\": {\"sha\": \"${sha('a')}\"}}"
            "GET /git/commits/${sha('a')}" -> 200 to "{\"tree\": {\"sha\": \"${sha('b')}\"}}"
            "POST /git/blobs" -> 201 to "{\"sha\": \"${sha('c')}\"}"
            "POST /git/trees" -> 201 to "{\"sha\": \"${sha('d')}\"}"
            "POST /git/commits" -> 201 to "{\"sha\": \"${sha('e')}\"}"
            "PATCH /git/refs/heads/main" -> 200 to "{\"object\": {\"sha\": \"${sha('e')}\"}}"
            "GET /contents/dpai-cloud/redmi.bin" -> 200 to "{\"type\": \"file\", \"sha\": \"${sha('c')}\", \"size\": 5}"
            // GitHub отдаёт base64 с переводами строк внутри строки JSON.
            "GET /git/blobs/${sha('c')}" -> 200 to "{\"content\": \"${Base64.getEncoder().encodeToString("копия".toByteArray()).chunked(4).joinToString("\\n")}\", \"encoding\": \"base64\"}"
            "GET /contents/dpai-cloud" -> 200 to "[{\"type\": \"file\", \"name\": \"redmi.bin\"}, {\"type\": \"dir\", \"name\": \"x\"}]"
            else -> 404 to "{\"message\": \"Not Found\"}"
        }
    }

    @Test
    fun uploadGoesBlobTreeCommitRefWithTheTokenOnlyInTheHeader() {
        val t = Fake(::routes)
        val r = GitHubCopies(t, "me", "copies", Secret(token)).upload("dpai-cloud/new.bin", "шифр".toByteArray(), "Копия контура", expected = null)
        assertEquals(Uploaded(sha('e'), sha('c')), (r as Outcome.Success).value)
        assertEquals(
            listOf("GET ", "GET /git/ref/heads/main", "GET /git/commits/${sha('a')}", "GET /contents/dpai-cloud/new.bin", "POST /git/blobs", "POST /git/trees", "POST /git/commits", "PATCH /git/refs/heads/main"),
            t.calls.map { "${it.method} ${it.url.path.removePrefix("/repos/me/copies")}" },
        )
        assertTrue(t.calls.all { it.url.scheme == "https" && it.url.host == "api.github.com" })
        assertTrue(t.calls.all { it.secret?.name == "Authorization" && token !in it.url.toString() && it.headers.values.none { v -> token in v } })
        val tree = t.calls.single { it.url.path.endsWith("/git/trees") }.body!!.toString(Charsets.UTF_8)
        assertTrue("\"base_tree\":\"${sha('b')}\"" in tree && "dpai-cloud/new.bin" in tree, tree)
        assertEquals("application/vnd.github.object+json", t.calls.single { it.url.path.contains("/contents/") }.headers["Accept"], "файлы больше 1 МиБ — только видом object")
        assertTrue("\"force\":false" in t.calls.last().body!!.toString(Charsets.UTF_8), "ветка — без силы")
    }

    @Test
    fun foreignCopyIsNotOverwrittenWithoutTheWord() {
        // По пути уже лежит блоб c: новая установка его не знает.
        val t = Fake(::routes)
        val c = GitHubCopies(t, "me", "copies", Secret(token))
        val foreign = (c.upload("dpai-cloud/redmi.bin", byteArrayOf(1), "m", expected = null) as Outcome.Failure).diagnostics.first()
        assertEquals(DiagCode.STATE_CONFLICT to GitHubCopies.FOREIGN, foreign.code to foreign.subject, "чужая копия — свой предмет, не гонка за ветку")
        assertTrue(t.calls.none { it.method == "POST" || it.method == "PATCH" }, "ни одной записи")
        assertTrue(c.upload("dpai-cloud/redmi.bin", byteArrayOf(1), "m", expected = sha('c')) is Outcome.Success, "своя прошлая копия — заменяется")
        assertTrue(c.upload("dpai-cloud/redmi.bin", byteArrayOf(1), "m", expected = null, overwrite = true) is Outcome.Success, "слово человека — заменяется")
    }

    @Test
    fun copyAtTheSizeLimitIsReadBack() {
        val big = ByteArray(GitHubCopies.MAX_COPY_BYTES) { (it * 31).toByte() }
        // Как у GitHub: base64 с переводом строки через 60 знаков — экранированным в строке JSON.
        val b64 = Base64.getEncoder().encodeToString(big).chunked(60).joinToString("\\n")
        val c = GitHubCopies(Fake { call -> if (call.url.path.endsWith("/git/blobs/${sha('c')}")) 200 to "{\"content\": \"$b64\", \"encoding\": \"base64\"}" else routes(call) }, "me", "copies", Secret(token))
        assertContentEquals(big, (c.download("dpai-cloud/redmi.bin") as Outcome.Success).value.second)
    }

    @Test
    fun publicRepositoryOrReadOnlyTokenIsRefusedBeforeAnyWrite() {
        val public = Fake { c -> if (c.url.path == "/repos/me/copies") 200 to repo(private = false) else routes(c) }
        val r = GitHubCopies(public, "me", "copies", Secret(token)).upload("dpai-cloud/redmi.bin", byteArrayOf(1), "m", expected = null)
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, (r as Outcome.Failure).diagnostics.first().code)
        assertEquals(1, public.calls.size, "после отказа — ни одного запроса записи")
        val readOnly = Fake { c -> if (c.url.path == "/repos/me/copies") 200 to repo(push = false) else routes(c) }
        assertEquals(DiagCode.GITHUB_AUTH_EXPIRED, (GitHubCopies(readOnly, "me", "copies", Secret(token)).upload("dpai-cloud/redmi.bin", byteArrayOf(1), "m", expected = null) as Outcome.Failure).diagnostics.first().code)
    }

    @Test
    fun badNamesAndLargeCopiesNeverReachTheNetwork() {
        val t = Fake(::routes)
        for ((owner, repo) in listOf("me/../x" to "copies", "me" to "copies?x=1", "me" to "..")) {
            assertEquals(DiagCode.VALUE_INVALID, (GitHubCopies(t, owner, repo, Secret(token)).check() as Outcome.Failure).diagnostics.first().code, "$owner/$repo")
        }
        val c = GitHubCopies(t, "me", "copies", Secret(token))
        assertTrue(c.upload("../x", byteArrayOf(1), "m", expected = null) is Outcome.Failure)
        assertTrue(c.upload("dpai-cloud/x.bin", ByteArray(GitHubCopies.MAX_COPY_BYTES + 1), "m", expected = null) is Outcome.Failure)
        assertTrue(t.calls.isEmpty())
    }

    @Test
    fun downloadDecodesTheBlobAndListShowsFiles() {
        val c = GitHubCopies(Fake(::routes), "me", "copies", Secret(token))
        val (blob, bytes) = (c.download("dpai-cloud/redmi.bin") as Outcome.Success).value
        assertContentEquals("копия".toByteArray(), bytes)
        assertEquals(sha('c'), blob, "блоб копии — чтобы первая отправка после восстановления узнала её своей")
        assertEquals(listOf("redmi.bin"), (c.list("dpai-cloud") as Outcome.Success).value)
        assertEquals(DiagCode.GITHUB_NOT_FOUND, (c.download("dpai-cloud/none.bin") as Outcome.Failure).diagnostics.first().code)
        val empty = GitHubCopies(Fake { call -> if (call.url.path.endsWith("/contents/dpai-cloud")) 404 to "{}" else routes(call) }, "me", "copies", Secret(token))
        assertEquals(emptyList(), (empty.list("dpai-cloud") as Outcome.Success).value, "каталога ещё нет — копий нет")
    }

    @Test
    fun statusCodesBecomeReasons() {
        fun with(status: Int) = GitHubCopies(Fake { status to "{}" }, "me", "copies", Secret(token)).check()
        assertEquals(DiagCode.GITHUB_AUTH_EXPIRED, (with(401) as Outcome.Failure).diagnostics.first().code)
        assertEquals(DiagCode.GITHUB_NOT_FOUND, (with(404) as Outcome.Failure).diagnostics.first().code)
        assertEquals(DiagCode.GITHUB_RATE_LIMITED, (with(429) as Outcome.Failure).diagnostics.first().code)
        assertFalse((with(500) as Outcome.Failure).diagnostics.first().message.contains(token))
    }
}
