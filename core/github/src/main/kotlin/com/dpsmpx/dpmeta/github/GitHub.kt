package com.dpsmpx.dpmeta.github

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.then
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpmeta.model.RepoRef
import com.dpsmpx.dpmeta.net.Fetched
import com.dpsmpx.dpmeta.net.Net
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException

/** Ответ GitHub, разобранный в [value]; [fetched] — отпечаток и признак 304 для наблюдения. */
class Page<T>(val value: T, val fetched: Fetched)

/** PR: [body] — описание (раздел «Проверки», F-15); [mergedAt] — когда влит, у открытых — null. */
data class PullRequest(
    val number: Int,
    val title: String,
    val draft: Boolean,
    val head: String,
    val author: String,
    val updatedAt: Timestamp,
    val body: String? = null,
    val mergedAt: Timestamp? = null,
    /** Время открытия — для «ждёт автора» и времени до вливания (ТЗ, §8); из того же ответа, без запроса. */
    val createdAt: Timestamp? = null,
)

data class Branch(val name: String, val sha: String)

data class CommitInfo(val sha: String, val date: Timestamp, val author: String?)

data class RateLimit(val remaining: Int, val limit: Int, val reset: Timestamp)

/**
 * GitHub REST (ТЗ, §6.2, I-09) поверх [Net]: условные GET, список адресов и
 * бюджет — там. Ответы сверх нужного не разбираются: лишние поля GitHub
 * не ломают разбор, недостающие — `FIELD_MISSING`.
 *
 * Отказы: 401 и 403 без исчерпанного лимита — `GITHUB_AUTH_EXPIRED`; 403 с
 * нулевым остатком и 429 — `GITHUB_RATE_LIMITED`; 404 — `GITHUB_NOT_FOUND`
 * (у GitHub так же выглядит закрытый от токена репозиторий); 5xx —
 * `NETWORK_UNREACHABLE`.
 */
class GitHub(private val net: Net, private val token: Secret?, private val api: URI = URI("https://api.github.com/")) {
    private val json = JsonParser()

    fun rateLimit(): Outcome<RateLimit> = get("rate_limit", "rate_limit").then { p ->
        val core = (p.value as? JsonObject)?.get("resources")?.let { (it as? JsonObject)?.get("core") } as? JsonObject
            ?: return@then missing("resources.core", "rate_limit")
        val remaining = int(core, "remaining") ?: return@then missing("remaining", "rate_limit")
        val limit = int(core, "limit") ?: return@then missing("limit", "rate_limit")
        val reset = (core["reset"] as? JsonNumber)?.toLongExact() ?: return@then missing("reset", "rate_limit")
        Outcome.success(RateLimit(remaining, limit, Timestamp(reset * 1000)))
    }

    fun pulls(repo: RepoRef): Outcome<Page<List<PullRequest>>> = pullsOf("repos/$repo/pulls?state=open&per_page=100", "$repo:pulls")

    /** Последние закрытые PR (влитые — с `merged_at`): итог проверок последнего влитого (F-15), «влит / закрыт» ленты (F-05). */
    fun closedPulls(repo: RepoRef): Outcome<Page<List<PullRequest>>> =
        pullsOf("repos/$repo/pulls?state=closed&sort=updated&direction=desc&per_page=10", "$repo:closed")

    private fun pullsOf(path: String, subject: String): Outcome<Page<List<PullRequest>>> = get(path, subject).then { p ->
        val repo = subject.substringBefore(':')
        val items = (p.value as? JsonArray)?.items ?: return@then missing("[]", "$repo:pulls")
        val out = items.map { v ->
            val o = v as? JsonObject ?: return@then missing("pull", "$repo:pulls")
            val head = (o["head"] as? JsonObject)?.let { str(it, "ref") }
            val user = (o["user"] as? JsonObject)?.let { str(it, "login") }
            PullRequest(
                number = int(o, "number") ?: return@then missing("number", "$repo:pulls"),
                title = str(o, "title") ?: return@then missing("title", "$repo:pulls"),
                draft = (o["draft"] as? JsonBool)?.value ?: false,
                head = head ?: return@then missing("head.ref", "$repo:pulls"),
                author = user ?: "?",
                updatedAt = time(o, "updated_at") ?: return@then missing("updated_at", "$repo:pulls"),
                body = str(o, "body"),
                mergedAt = time(o, "merged_at"),
                createdAt = time(o, "created_at"),
            )
        }
        Outcome.success(Page(out, p.fetched))
    }

    fun branches(repo: RepoRef): Outcome<Page<List<Branch>>> = get("repos/$repo/branches?per_page=100", "$repo:branches").then { p ->
        val items = (p.value as? JsonArray)?.items ?: return@then missing("[]", "$repo:branches")
        val out = items.map { v ->
            val o = v as? JsonObject ?: return@then missing("branch", "$repo:branches")
            val sha = (o["commit"] as? JsonObject)?.let { str(it, "sha") }
            Branch(str(o, "name") ?: return@then missing("name", "$repo:branches"), sha ?: return@then missing("commit.sha", "$repo:branches"))
        }
        Outcome.success(Page(out, p.fetched))
    }

    /** Коммит по SHA — неизменяемый: вызывающий хранит уже известные и не спрашивает снова. */
    fun commit(repo: RepoRef, sha: String): Outcome<CommitInfo> {
        if (!SHA.matches(sha)) return Outcome.failure(DiagCode.VALUE_INVALID, "SHA коммита — 40 шестнадцатеричных знаков", sha)
        return get("repos/$repo/commits/$sha", "$repo@${sha.take(12)}").then { p ->
            val o = p.value as? JsonObject ?: return@then missing("commit", "$repo@$sha")
            val c = o["commit"] as? JsonObject ?: return@then missing("commit", "$repo@$sha")
            val date = (c["committer"] as? JsonObject)?.let { time(it, "date") } ?: return@then missing("commit.committer.date", "$repo@$sha")
            val login = (o["author"] as? JsonObject)?.let { str(it, "login") }
            Outcome.success(CommitInfo(sha, date, login))
        }
    }

    /** Содержимое файла как текст (`application/vnd.github.raw+json`); нет файла — `GITHUB_NOT_FOUND`. */
    fun file(repo: RepoRef, path: String, store: Boolean = true): Outcome<Page<String>> {
        if (path.isEmpty() || path.startsWith("/") || path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "путь файла — относительный, без «..»", path)
        }
        return fetch("repos/$repo/contents/${encode(path)}", "$repo:$path", RAW, store).then { f -> Outcome.success(Page(f.text, f)) }
    }

    /** Элемент каталога репозитория: имя, путь от корня, каталог ли (F-09). */
    data class Entry(val name: String, val path: String, val dir: Boolean)

    /** Содержимое каталога ([path] пустой — корень); каталоги — первыми, затем по имени. */
    fun dir(repo: RepoRef, path: String): Outcome<List<Entry>> {
        if (path.startsWith("/") || (path.isNotEmpty() && path.split('/').any { it.isEmpty() || it == "." || it == ".." })) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "путь каталога — относительный, без «..»", path)
        }
        val at = if (path.isEmpty()) "repos/$repo/contents" else "repos/$repo/contents/${encode(path)}"
        return get(at, "$repo:$path/").then { p ->
            val items = (p.value as? JsonArray)?.items ?: return@then Outcome.failure(DiagCode.VALUE_INVALID, "это файл, а не каталог", path)
            val out = items.mapNotNull { v ->
                val o = v as? JsonObject ?: return@mapNotNull null
                Entry(str(o, "name") ?: return@mapNotNull null, str(o, "path") ?: return@mapNotNull null, str(o, "type") == "dir")
            }
            Outcome.success(out.sortedWith(compareBy<Entry> { !it.dir }.thenBy { it.name.lowercase() }))
        }
    }

    private fun encode(path: String) = path.split('/').joinToString("/") { seg -> java.net.URLEncoder.encode(seg, Charsets.UTF_8).replace("+", "%20") }

    private fun get(path: String, subject: String): Outcome<Page<JsonValue>> = fetch(path, subject, JSON).then { f ->
        when (val v = json.parse(f.text)) {
            is Outcome.Failure -> Outcome.failure(DiagCode.CONNECTOR_REPLY_INVALID, "ответ GitHub — не JSON", subject)
            is Outcome.Success -> Outcome.success(Page(v.value, f))
        }
    }

    private fun fetch(path: String, subject: String, accept: String, store: Boolean = true): Outcome<Fetched> {
        val headers = mapOf("Accept" to accept, "X-GitHub-Api-Version" to API_VERSION, "User-Agent" to USER_AGENT)
        val f = when (val r = net.get(api.resolve(path), headers, token, store)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return status(f, subject)
    }

    companion object {
        const val API_VERSION = "2022-11-28"
        const val USER_AGENT = "DPmetaApp"
        private const val JSON = "application/vnd.github+json"
        private const val RAW = "application/vnd.github.raw+json"
        private val SHA = Regex("[0-9a-f]{40}")

        fun status(f: Fetched, subject: String): Outcome<Fetched> = when {
            f.status in 200..299 -> Outcome.success(f)
            f.status == 401 -> Outcome.failure(DiagCode.GITHUB_AUTH_EXPIRED, "GitHub не принял токен — нужен новый вход", subject)
            f.status == 429 || (f.status == 403 && f.header("x-ratelimit-remaining") == "0") ->
                Outcome.failure(DiagCode.GITHUB_RATE_LIMITED, "лимит запросов GitHub исчерпан" + (f.header("x-ratelimit-reset")?.let { " до $it" } ?: ""), subject)
            f.status == 403 -> Outcome.failure(DiagCode.GITHUB_AUTH_EXPIRED, "у токена нет прав на это", subject)
            f.status == 404 -> Outcome.failure(DiagCode.GITHUB_NOT_FOUND, "не найдено или скрыто от токена", subject)
            f.status >= 500 -> Outcome.failure(DiagCode.NETWORK_UNREACHABLE, "GitHub ответил ${f.status}", subject)
            else -> Outcome.failure(DiagCode.CONNECTOR_REPLY_INVALID, "неожиданный ответ GitHub ${f.status}", subject)
        }

        private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonString)?.value

        private fun int(o: JsonObject, k: String): Int? = (o[k] as? JsonNumber)?.toLongExact()?.toInt()

        private fun time(o: JsonObject, k: String): Timestamp? = str(o, k)?.let {
            try {
                Timestamp(Instant.parse(it).toEpochMilli())
            } catch (e: DateTimeParseException) {
                null
            }
        }

        private fun missing(field: String, subject: String) =
            Outcome.failure(DiagCode.FIELD_MISSING, "в ответе GitHub нет поля $field", subject)
    }
}
