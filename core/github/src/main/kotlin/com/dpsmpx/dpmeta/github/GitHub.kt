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
    /** SHA головы PR — для CI (D-176); из того же ответа. */
    val headSha: String? = null,
)

/** Открытый issue (D-176): кто открыл, назначенные, метки, когда обновлён, комментариев. */
data class Issue(
    val number: Int,
    val title: String,
    val author: String,
    val assignees: List<String>,
    val labels: List<String>,
    val updatedAt: Timestamp,
    val comments: Int,
)

/** Коммит основной ветки (D-176): первая строка сообщения, автор (логин или имя), время. */
data class RecentCommit(val sha: String, val title: String, val author: String?, val date: Timestamp)

/**
 * CI коммита (D-176): check runs и статусы коммита вместе. Итог — [Verdict]; [failing] — имена
 * упавших; [access] — `false`, если токену не видно ни того, ни другого (нет прав Checks и
 * Commit statuses): итог тогда неизвестен, а не «зелёный».
 */
data class CiState(val verdict: Verdict, val total: Int, val failing: List<String>, val access: Boolean = true) {
    enum class Verdict(val code: String) { GREEN("green"), RED("red"), PENDING("pending"), NONE("none") }
}

data class Branch(val name: String, val sha: String)

/** Репозиторий, видный токену (D-70): адрес, закрытый ли, в архиве ли. */
data class RepoInfo(val slug: RepoRef, val private: Boolean, val archived: Boolean)

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

    fun pulls(repo: RepoRef): Outcome<Page<List<PullRequest>>> = pullsOf("repos/$repo/pulls?state=open&per_page=100", "$repo:pulls", complete = true)

    /** Последние закрытые PR (влитые — с `merged_at`): итог проверок последнего влитого (F-15), «влит / закрыт» ленты (F-05). */
    fun closedPulls(repo: RepoRef): Outcome<Page<List<PullRequest>>> =
        pullsOf("repos/$repo/pulls?state=closed&sort=updated&direction=desc&per_page=10", "$repo:closed")

    private fun pullsOf(path: String, subject: String, complete: Boolean = false): Outcome<Page<List<PullRequest>>> =
        (if (complete) allPages(path, subject) else get(path, subject)).then { p ->
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
                headSha = (o["head"] as? JsonObject)?.let { str(it, "sha") }?.takeIf { SHA.matches(it) },
            )
        }
        Outcome.success(Page(out, p.fetched))
    }

    /**
     * Открытые issues по обновлению (D-176), не больше [ISSUES_KEEP]: PR GitHub отдаёт тем же списком —
     * они отсеиваются. Нужны права токена Issues: read.
     */
    fun issues(repo: RepoRef): Outcome<Page<List<Issue>>> =
        get("repos/$repo/issues?state=open&sort=updated&direction=desc&per_page=$ISSUES_KEEP", "$repo:issues").then { p ->
            val items = (p.value as? JsonArray)?.items ?: return@then missing("[]", "$repo:issues")
            val out = items.mapNotNull { v ->
                val o = v as? JsonObject ?: return@then missing("issue", "$repo:issues")
                if (o["pull_request"] != null) return@mapNotNull null
                Issue(
                    number = int(o, "number") ?: return@then missing("number", "$repo:issues"),
                    title = str(o, "title") ?: return@then missing("title", "$repo:issues"),
                    author = (o["user"] as? JsonObject)?.let { str(it, "login") } ?: "?",
                    assignees = (o["assignees"] as? JsonArray)?.items.orEmpty().mapNotNull { a -> (a as? JsonObject)?.let { str(it, "login") } },
                    labels = (o["labels"] as? JsonArray)?.items.orEmpty().mapNotNull { l -> (l as? JsonObject)?.let { str(it, "name") } },
                    updatedAt = time(o, "updated_at") ?: return@then missing("updated_at", "$repo:issues"),
                    comments = int(o, "comments") ?: 0,
                )
            }
            Outcome.success(Page(out, p.fetched))
        }

    /** Последние коммиты основной ветки (D-176), не больше [COMMITS_KEEP]; первый — её голова. Пустой репозиторий (409) — пусто. */
    fun recentCommits(repo: RepoRef): Outcome<Page<List<RecentCommit>>> {
        val path = "repos/$repo/commits?per_page=$COMMITS_KEEP"
        val headers = mapOf("Accept" to JSON, "X-GitHub-Api-Version" to API_VERSION, "User-Agent" to USER_AGENT)
        val raw = when (val r = net.get(api.resolve(path), headers, token)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (raw.status == 409) return Outcome.success(Page(emptyList(), raw))
        return status(raw, "$repo:commits").then { f ->
            when (val v = json.parse(f.text)) {
                is Outcome.Failure -> Outcome.failure(DiagCode.CONNECTOR_REPLY_INVALID, "ответ GitHub — не JSON", "$repo:commits")
                is Outcome.Success -> Outcome.success(Page(v.value, f))
            }
        }.then { p ->
            val items = (p.value as? JsonArray)?.items ?: return@then missing("[]", "$repo:commits")
            val out = items.map { v ->
                val o = v as? JsonObject ?: return@then missing("commit", "$repo:commits")
                val sha = str(o, "sha")?.takeIf { SHA.matches(it) } ?: return@then missing("sha", "$repo:commits")
                val c = o["commit"] as? JsonObject ?: return@then missing("commit", "$repo:commits")
                val date = (c["committer"] as? JsonObject)?.let { time(it, "date") } ?: return@then missing("commit.committer.date", "$repo:commits")
                val login = (o["author"] as? JsonObject)?.let { str(it, "login") } ?: (c["author"] as? JsonObject)?.let { str(it, "name") }
                RecentCommit(sha, str(c, "message").orEmpty().lineSequence().firstOrNull().orEmpty().take(TITLE_MAX), login, date)
            }
            Outcome.success(Page(out, p.fetched))
        }
    }

    /**
     * CI коммита [sha] (D-176): check runs (права Checks: read) и статусы (Commit statuses: read).
     * Красный — что-то упало; идёт — что-то не закончено; зелёный — всё прошло; нет — проверок нет.
     * Нет прав на одно — считается по другому; ни на то, ни на другое — `access = false`.
     */
    fun ci(repo: RepoRef, sha: String): Outcome<Page<CiState>> {
        if (!SHA.matches(sha)) return Outcome.failure(DiagCode.VALUE_INVALID, "SHA коммита — 40 шестнадцатеричных знаков", sha)
        val subject = "$repo@${sha.take(12)}"
        data class Item(val name: String, val state: CiState.Verdict)
        val items = mutableListOf<Item>()
        var seen = 0
        val raws = StringBuilder()
        fun hidden(r: Outcome.Failure) = r.diagnostics.first().code in setOf(DiagCode.GITHUB_AUTH_EXPIRED, DiagCode.GITHUB_NOT_FOUND)
        when (val r = get("repos/$repo/commits/$sha/check-runs?per_page=100", "$subject:checks")) {
            is Outcome.Failure -> if (!hidden(r)) return r
            is Outcome.Success -> {
                seen++
                raws.append(r.value.fetched.rawSha256).append('\n')
                val runs = ((r.value.value as? JsonObject)?.get("check_runs") as? JsonArray)?.items.orEmpty()
                for (v in runs) {
                    val o = v as? JsonObject ?: continue
                    val state = when {
                        str(o, "status") != "completed" -> CiState.Verdict.PENDING
                        str(o, "conclusion") in setOf("success", "neutral", "skipped") -> CiState.Verdict.GREEN
                        else -> CiState.Verdict.RED
                    }
                    items += Item(str(o, "name") ?: "?", state)
                }
            }
        }
        when (val r = get("repos/$repo/commits/$sha/status", "$subject:status")) {
            is Outcome.Failure -> if (!hidden(r)) return r
            is Outcome.Success -> {
                seen++
                raws.append(r.value.fetched.rawSha256).append('\n')
                val statuses = ((r.value.value as? JsonObject)?.get("statuses") as? JsonArray)?.items.orEmpty()
                for (v in statuses) {
                    val o = v as? JsonObject ?: continue
                    val state = when (str(o, "state")) {
                        "success" -> CiState.Verdict.GREEN
                        "pending" -> CiState.Verdict.PENDING
                        else -> CiState.Verdict.RED
                    }
                    items += Item(str(o, "context") ?: "?", state)
                }
            }
        }
        val verdict = when {
            items.any { it.state == CiState.Verdict.RED } -> CiState.Verdict.RED
            items.any { it.state == CiState.Verdict.PENDING } -> CiState.Verdict.PENDING
            items.isNotEmpty() -> CiState.Verdict.GREEN
            else -> CiState.Verdict.NONE
        }
        val state = CiState(verdict, items.size, items.filter { it.state == CiState.Verdict.RED }.map { it.name }.distinct(), access = seen > 0)
        val body = raws.toString().toByteArray(Charsets.UTF_8)
        return Outcome.success(Page(state, Fetched(200, body, false, emptyMap())))
    }

    fun branches(repo: RepoRef): Outcome<Page<List<Branch>>> = allPages("repos/$repo/branches?per_page=100", "$repo:branches").then { p ->
        val items = (p.value as? JsonArray)?.items ?: return@then missing("[]", "$repo:branches")
        val out = items.map { v ->
            val o = v as? JsonObject ?: return@then missing("branch", "$repo:branches")
            val sha = (o["commit"] as? JsonObject)?.let { str(it, "sha") }
            Branch(str(o, "name") ?: return@then missing("name", "$repo:branches"), sha ?: return@then missing("commit.sha", "$repo:branches"))
        }
        Outcome.success(Page(out, p.fetched))
    }

    /**
     * Репозитории, видные токену (D-70: окно выбора клонов), — по 100 на
     * страницу, не больше [MAX_REPO_PAGES] страниц; по алфавиту.
     */
    fun repos(): Outcome<List<RepoInfo>> {
        val out = mutableListOf<RepoInfo>()
        for (page in 1..MAX_REPO_PAGES) {
            val items = when (val r = get("user/repos?per_page=100&sort=full_name&page=$page", "user:repos")) {
                is Outcome.Failure -> return r
                is Outcome.Success -> (r.value.value as? JsonArray)?.items ?: return missing("[]", "user:repos")
            }
            for (v in items) {
                val o = v as? JsonObject ?: return missing("repo", "user:repos")
                val name = str(o, "full_name") ?: return missing("full_name", "user:repos")
                val slug = RepoRef.parse(name).valueOrNull() ?: continue
                out += RepoInfo(slug, (o["private"] as? JsonBool)?.value ?: true, (o["archived"] as? JsonBool)?.value ?: false)
            }
            if (items.size < 100) break
        }
        return Outcome.success(out.distinctBy { it.slug.toString() }.sortedBy { it.slug.toString().lowercase() })
    }

    /**
     * Размер репозитория на GitHub в байтах — для защиты места перед клоном (D-113).
     * GitHub считает его в килобайтах и обновляет не сразу; поля нет — `null`:
     * неизвестное — не ноль.
     */
    fun repoSize(repo: RepoRef): Outcome<Long?> = get("repos/$repo", "$repo").then { p ->
        val o = p.value as? JsonObject ?: return@then missing("repo", "$repo")
        Outcome.success((o["size"] as? JsonNumber)?.toLongExact()?.takeIf { it >= 0 }?.let { it * 1024 })
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

    /** K-03: каждая страница учитывается бюджетом; неполный список не выдаётся за полный. */
    private fun allPages(path: String, subject: String): Outcome<Page<JsonValue>> {
        val values = mutableListOf<JsonValue>()
        var allCached = true
        for (page in 1..maxOf(1, net.remaining)) {
            // Значение Link не исполняется как URL: сохраняются наш origin, путь и фильтры.
            val request = if (page == 1) path else "$path&page=$page"
            val current = when (val response = get(request, subject)) {
                is Outcome.Failure -> return response
                is Outcome.Success -> response.value
            }
            val items = (current.value as? JsonArray)?.items ?: return missing("[]", subject)
            values += items
            allCached = allCached && current.fetched.notModified
            val links = current.fetched.headers["link"]
            val more = links?.any { NEXT_PAGE.containsMatchIn(it) }
                // Совместимость со старым кешем без Link: полную страницу нужно проверить дальше.
                ?: (current.fetched.notModified && items.size == 100)
            if (!more) {
                if (page == 1) return Outcome.success(current)
                val combined = JsonArray(values)
                val body = com.dpsmpx.dpai.core.serialization.CanonicalJson.write(combined).toByteArray(Charsets.UTF_8)
                return Outcome.success(Page(combined, Fetched(200, body, allCached, emptyMap())))
            }
        }
        return Outcome.failure(DiagCode.RUN_BUDGET_EXHAUSTED, "бюджета не хватило на полный список GitHub", subject)
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
        private val NEXT_PAGE = Regex("""\brel\s*=\s*"?next\b""", RegexOption.IGNORE_CASE)
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

        /** Сколько открытых issues и последних коммитов хранить (D-176): карточке хватает первых. */
        const val ISSUES_KEEP = 50
        const val COMMITS_KEEP = 10

        /** Предел заголовка коммита: строка карточки, а не сообщение целиком. */
        const val TITLE_MAX = 200

        /** Предел страниц списка репозиториев: 1000 репозиториев — с запасом для одного автора. */
        const val MAX_REPO_PAGES = 10

        private fun missing(field: String, subject: String) =
            Outcome.failure(DiagCode.FIELD_MISSING, "в ответе GitHub нет поля $field", subject)
    }
}
