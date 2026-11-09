package com.dpsmpx.dpmeta.github

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.then
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpmeta.model.RepoRef
import com.dpsmpx.dpmeta.net.Fetched
import com.dpsmpx.dpmeta.net.HttpRequest
import com.dpsmpx.dpmeta.net.Net
import java.net.URI

/** Итог записи: ссылка на результат (комментарий, ревью, PR) и SHA коммита слияния, если есть. */
data class Written(val link: String?, val sha: String? = null)

/**
 * PR перед действием (F-12, F-13): черновик, состояние слияния и голова — то, что
 * показывается на экране; «Влить» сливает именно [headSha] (иначе GitHub откажет).
 */
data class PullDetail(
    val number: Int,
    val title: String,
    val state: String,
    val draft: Boolean,
    val merged: Boolean,
    /** `true`, `false` или `null` — GitHub ещё считает (тогда — предупреждение, F-13). */
    val mergeable: Boolean?,
    val mergeableState: String,
    val headSha: String,
    val headRef: String,
    val baseRef: String,
    val nodeId: String,
    val url: String,
    /** Карточка PR (F-11): описание, автор и размер — из того же ответа, без запросов сверху. */
    val body: String = "",
    val author: String = "",
    val additions: Int? = null,
    val deletions: Int? = null,
    val changedFiles: Int? = null,
    val commits: Int? = null,
)

/** Коммит PR (F-11): SHA, первая строка сообщения, автор, время ISO 8601. */
data class PullCommit(val sha: String, val message: String, val author: String, val date: String)

/** Файл PR (F-11): путь, вид изменения (`added`, `modified`, `removed`, `renamed`…), строки плюс и минус. */
data class PullFile(val path: String, val status: String, val additions: Int, val deletions: Int)

/** Реплика обсуждения PR (F-11): комментарий или отзыв ([review] — его вид), автор, время, текст. */
data class PullNote(val author: String, val date: String, val text: String, val review: String? = null)

/** Файл для правки (F-69): текст и SHA блоба — запись с ним не затрёт чужую правку. */
data class FileBlob(val path: String, val sha: String, val text: String, val ref: String)

/** Ветка перед действием (F-14): голова и защищена ли правилами (такую GitHub не удалит). */
data class BranchDetail(val name: String, val sha: String, val protected: Boolean)

/** Ветка против основной (F-14): [aheadBy] — коммитов, которых нет в основной; 0 — влита. */
data class Comparison(val status: String, val aheadBy: Int, val behindBy: Int) {
    val merged: Boolean get() = aheadBy == 0
}

/**
 * Запись в репозитории автора через GitHub REST и GraphQL (D-36, F-12…F-14): комментарий,
 * ревью, перевод из черновика и закрытие (GraphQL), слияние merge-коммитом показанного
 * коммита, удаление ветки. Методы — POST, PUT, DELETE и GET: PATCH телефон не отправит.
 * Уровни и подтверждения — у `:core:actions` и экранов; здесь — только
 * запросы. Кэша ETag нет: перед действием нужно свежее, а запись не повторяется из кэша.
 *
 * Отказы — как у [GitHub] (401, 403, 404, 429, 5xx), плюс 405, 409 и 422 — действие
 * GitHub не принял: `OPERATION_NOT_ALLOWED` с его объяснением.
 */
class GitHubWrite(private val net: Net, private val token: Secret, private val api: URI = URI("https://api.github.com/")) {
    private val json = JsonParser()

    /** PR перед действием: свежий, без кэша. */
    fun pull(repo: RepoRef, number: Int): Outcome<PullDetail> = call("GET", "repos/$repo/pulls/$number", null, "$repo#$number").then { v ->
        val o = v as? JsonObject ?: return@then invalid("$repo#$number")
        val head = o["head"] as? JsonObject
        val base = o["base"] as? JsonObject
        Outcome.success(
            PullDetail(
                number = int(o, "number") ?: return@then missing("number", "$repo#$number"),
                title = str(o, "title").orEmpty(),
                state = str(o, "state").orEmpty(),
                draft = (o["draft"] as? JsonBool)?.value == true,
                merged = (o["merged"] as? JsonBool)?.value == true,
                mergeable = (o["mergeable"] as? JsonBool)?.value,
                mergeableState = str(o, "mergeable_state").orEmpty(),
                headSha = head?.let { str(it, "sha") }?.takeIf { SHA.matches(it) } ?: return@then missing("head.sha", "$repo#$number"),
                headRef = str(head, "ref").orEmpty(),
                baseRef = base?.let { str(it, "ref") }.orEmpty(),
                nodeId = str(o, "node_id").orEmpty(),
                url = str(o, "html_url").orEmpty(),
                body = text(str(o, "body").orEmpty(), MAX_BODY),
                author = (o["user"] as? JsonObject)?.let { str(it, "login") }.orEmpty(),
                additions = int(o, "additions"),
                deletions = int(o, "deletions"),
                changedFiles = int(o, "changed_files"),
                commits = int(o, "commits"),
            ),
        )
    }

    /** Коммиты PR (F-11): первые [MAX_LIST] — одна страница GitHub. */
    fun pullCommits(repo: RepoRef, number: Int): Outcome<List<PullCommit>> =
        list("repos/$repo/pulls/$number/commits?per_page=$MAX_LIST", "$repo#$number:commits") { o ->
            val c = o["commit"] as? JsonObject
            val sha = str(o, "sha")?.takeIf { SHA.matches(it) } ?: return@list null
            val a = c?.get("author") as? JsonObject
            PullCommit(sha, text(str(c, "message").orEmpty().lineSequence().firstOrNull().orEmpty(), MAX_LINE), (o["author"] as? JsonObject)?.let { str(it, "login") } ?: str(a, "name").orEmpty(), str(a, "date").orEmpty())
        }

    /** Файлы PR (F-11): путь, вид, строки — первые [MAX_LIST]. */
    fun pullFiles(repo: RepoRef, number: Int): Outcome<List<PullFile>> =
        list("repos/$repo/pulls/$number/files?per_page=$MAX_LIST", "$repo#$number:files") { o ->
            val path = str(o, "filename") ?: return@list null
            PullFile(path, str(o, "status").orEmpty(), int(o, "additions") ?: 0, int(o, "deletions") ?: 0)
        }

    /** Обсуждение PR (F-11): комментарии и отзывы с текстом или вердиктом — по времени, первые [MAX_LIST] каждого вида. */
    fun discussion(repo: RepoRef, number: Int): Outcome<List<PullNote>> {
        val comments = when (val r = list("repos/$repo/issues/$number/comments?per_page=$MAX_LIST", "$repo#$number:comments") { o ->
            PullNote((o["user"] as? JsonObject)?.let { str(it, "login") }.orEmpty(), str(o, "created_at").orEmpty(), text(str(o, "body").orEmpty(), MAX_NOTE))
        }) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val reviews = when (val r = list("repos/$repo/pulls/$number/reviews?per_page=$MAX_LIST", "$repo#$number:reviews") { o ->
            val state = str(o, "state").orEmpty()
            val body = text(str(o, "body").orEmpty(), MAX_NOTE)
            if (body.isEmpty() && state == "COMMENTED") null else PullNote((o["user"] as? JsonObject)?.let { str(it, "login") }.orEmpty(), str(o, "submitted_at").orEmpty(), body, state)
        }) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        // Время ISO 8601 в UTC сравнивается строкой.
        return Outcome.success((comments + reviews).sortedBy { it.date })
    }

    private fun <T> list(path: String, subject: String, item: (JsonObject) -> T?): Outcome<List<T>> = call("GET", path, null, subject).then { v ->
        val a = v as? JsonArray ?: return@then invalid(subject)
        Outcome.success(a.items.mapNotNull { (it as? JsonObject)?.let(item) })
    }

    /** Основная ветка репозитория (`default_branch`): с ней сравниваются ветки (F-14). */
    fun defaultBranch(repo: RepoRef): Outcome<String> = call("GET", "repos/$repo", null, "$repo").then { v ->
        val o = v as? JsonObject ?: return@then invalid("$repo")
        str(o, "default_branch")?.takeIf { it.isNotEmpty() }?.let { Outcome.success(it) } ?: missing("default_branch", "$repo")
    }

    /** Ветка: голова и защита — свежие, без кэша. */
    fun branch(repo: RepoRef, name: String): Outcome<BranchDetail> = call("GET", "repos/$repo/branches/${ref(name)}", null, "$repo@$name").then { v ->
        val o = v as? JsonObject ?: return@then invalid("$repo@$name")
        val sha = (o["commit"] as? JsonObject)?.let { str(it, "sha") }?.takeIf { SHA.matches(it) } ?: return@then missing("commit.sha", "$repo@$name")
        Outcome.success(BranchDetail(str(o, "name") ?: name, sha, (o["protected"] as? JsonBool)?.value == true))
    }

    /** Ветка [head] против [base] (F-14): сколько коммитов впереди и позади. */
    fun compare(repo: RepoRef, base: String, head: String): Outcome<Comparison> =
        call("GET", "repos/$repo/compare/${ref(base)}...${ref(head)}", null, "$repo $base...$head").then { v ->
            val o = v as? JsonObject ?: return@then invalid("$repo $head")
            Outcome.success(Comparison(str(o, "status").orEmpty(), int(o, "ahead_by") ?: return@then missing("ahead_by", head), int(o, "behind_by") ?: 0))
        }

    /** Комментарий к PR или issue (уровень 1). */
    fun comment(repo: RepoRef, number: Int, body: String): Outcome<Written> =
        call("POST", "repos/$repo/issues/$number/comments", JsonObject.of("body" to JsonString(body)), "$repo#$number").then { link(it) }

    /**
     * Новый issue (уровень 1, F-26): задача, записанная с телефона, — с меткой [labels]
     * (по ТЗ — `from-dpmetaapp`; нет такой метки в репозитории — GitHub её создаёт).
     */
    fun issue(repo: RepoRef, title: String, body: String, labels: List<String>): Outcome<Written> {
        val payload = JsonObject.of("title" to JsonString(title), "body" to JsonString(body), "labels" to JsonArray(labels.map(::JsonString)))
        return call("POST", "repos/$repo/issues", payload, "$repo").then { link(it) }
    }

    /**
     * Файл для правки (F-69): текст UTF-8 и SHA блоба на ветке [ref]. Каталог, двоичный
     * файл или больше [MAX_EDIT_BYTES] — отказ: правка с телефона — для текстов процесса.
     */
    fun fileForEdit(repo: RepoRef, path: String, ref: String): Outcome<FileBlob> =
        call("GET", "repos/$repo/contents/${ref(path)}?ref=${java.net.URLEncoder.encode(ref, Charsets.UTF_8)}", null, "$repo:$path").then { v ->
            val o = v as? JsonObject ?: return@then Outcome.failure(DiagCode.VALUE_INVALID, "это каталог, а не файл", path)
            if (str(o, "type") != "file") return@then Outcome.failure(DiagCode.VALUE_INVALID, "это не файл", path)
            val sha = str(o, "sha")?.takeIf { SHA.matches(it) } ?: return@then missing("sha", path)
            val size = int(o, "size") ?: 0
            if (size > MAX_EDIT_BYTES) return@then Outcome.failure(DiagCode.VALUE_INVALID, "файл больше ${MAX_EDIT_BYTES / 1024} КБ — правьте на ПК", path)
            val bytes = try {
                java.util.Base64.getMimeDecoder().decode(str(o, "content").orEmpty())
            } catch (e: IllegalArgumentException) {
                return@then Outcome.failure(DiagCode.CONNECTOR_REPLY_INVALID, "содержимое файла не прочитано", path)
            }
            val text = try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                return@then Outcome.failure(DiagCode.VALUE_INVALID, "файл не текст UTF-8", path)
            }
            Outcome.success(FileBlob(path, sha, text, ref))
        }

    /** Новая ветка [name] от коммита [fromSha] (F-69: правка — в своей ветке). Есть такая — отказ. */
    fun createBranch(repo: RepoRef, name: String, fromSha: String): Outcome<Written> {
        if (!SHA.matches(fromSha)) return Outcome.failure(DiagCode.VALUE_INVALID, "SHA — 40 знаков hex", fromSha)
        val payload = JsonObject.of("ref" to JsonString("refs/heads/$name"), "sha" to JsonString(fromSha))
        return call("POST", "repos/$repo/git/refs", payload, "$repo@$name").then { Outcome.success(Written(null, fromSha)) }
    }

    /**
     * Записать файл коммитом в [branch] (F-69): поверх блоба [blobSha] — если на GitHub файл
     * успели изменить, GitHub ответит 409, и правка не затрёт чужую.
     */
    fun putFile(repo: RepoRef, path: String, branch: String, blobSha: String, text: String, message: String): Outcome<Written> {
        val payload = JsonObject.of(
            "message" to JsonString(message),
            "content" to JsonString(java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))),
            "sha" to JsonString(blobSha),
            "branch" to JsonString(branch),
        )
        return call("PUT", "repos/$repo/contents/${ref(path)}", payload, "$repo:$path").then { v ->
            val commit = (v as? JsonObject)?.get("commit") as? JsonObject ?: return@then invalid("$repo:$path")
            Outcome.success(Written(str(commit, "html_url"), str(commit, "sha")))
        }
    }

    /** Открыть PR из [head] в [base] (F-69: правка в ветке — через PR). */
    fun openPull(repo: RepoRef, head: String, base: String, title: String, body: String): Outcome<Written> {
        val payload = JsonObject.of("title" to JsonString(title), "head" to JsonString(head), "base" to JsonString(base), "body" to JsonString(body))
        return call("POST", "repos/$repo/pulls", payload, "$repo $head→$base").then { link(it) }
    }

    /** Ревью PR (уровень 1): одобрить или запросить изменения. */
    fun review(repo: RepoRef, number: Int, approve: Boolean, body: String): Outcome<Written> {
        val event = if (approve) "APPROVE" else "REQUEST_CHANGES"
        val payload = JsonObject.of("event" to JsonString(event), "body" to JsonString(body))
        return call("POST", "repos/$repo/pulls/$number/reviews", payload, "$repo#$number").then { link(it) }
    }

    /** Перевести PR из черновика в готовые (уровень 1): у REST этого нет — мутация GraphQL. */
    fun ready(pull: PullDetail): Outcome<Written> =
        graphql(pull, "markPullRequestReadyForReview", "GitHub не перевёл PR из черновика").then { Outcome.success(Written(pull.url.ifEmpty { null })) }

    /**
     * Закрыть PR без слияния (уровень 2) — мутацией GraphQL: PATCH REST телефон не
     * отправит (`HttpURLConnection` его не знает), а POST есть везде.
     */
    fun close(pull: PullDetail): Outcome<Written> =
        graphql(pull, "closePullRequest", "GitHub не закрыл PR").then { Outcome.success(Written(pull.url.ifEmpty { null })) }

    /** Мутация GraphQL над PR по его `node_id`; ошибка в ответе с кодом 200 — отказ, а не успех. */
    private fun graphql(pull: PullDetail, mutation: String, refused: String): Outcome<JsonObject> {
        if (pull.nodeId.isEmpty()) return missing("node_id", "#${pull.number}")
        val query = "mutation(\$id: ID!) { $mutation(input: {pullRequestId: \$id}) { pullRequest { url } } }"
        val payload = JsonObject.of("query" to JsonString(query), "variables" to JsonObject.of("id" to JsonString(pull.nodeId)))
        return call("POST", "graphql", payload, "#${pull.number}").then { v ->
            val o = v as? JsonObject ?: return@then invalid("#${pull.number}")
            val errors = (o["errors"] as? JsonArray)?.items.orEmpty()
            if (errors.isNotEmpty()) {
                val message = (errors.first() as? JsonObject)?.let { str(it, "message") } ?: "ошибка GraphQL"
                return@then Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "$refused: ${clean(message)}", "#${pull.number}")
            }
            Outcome.success(o)
        }
    }

    /**
     * Влить PR (уровень 2): merge-коммитом — история основной ветки не переписывается
     * (F-13, `DPmeta:C-04`), и только коммит [headSha], что был на экране: пришёл новый —
     * GitHub отвечает 409, и ничего не вливается.
     */
    fun merge(repo: RepoRef, number: Int, headSha: String): Outcome<Written> {
        if (!SHA.matches(headSha)) return Outcome.failure(DiagCode.VALUE_INVALID, "SHA головы PR — 40 знаков hex", headSha)
        val payload = JsonObject.of("merge_method" to JsonString("merge"), "sha" to JsonString(headSha))
        return call("PUT", "repos/$repo/pulls/$number/merge", payload, "$repo#$number").then { v ->
            val o = v as? JsonObject ?: return@then invalid("$repo#$number")
            if ((o["merged"] as? JsonBool)?.value != true) return@then Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "GitHub не влил PR: ${clean(str(o, "message").orEmpty())}", "$repo#$number")
            Outcome.success(Written("https://github.com/$repo/pull/$number", str(o, "sha")))
        }
    }

    /** Удалить ветку (влитую — уровень 2, невлитую — 3, F-14): ссылку `heads/<ветка>`. */
    fun deleteBranch(repo: RepoRef, branch: String): Outcome<Written> =
        call("DELETE", "repos/$repo/git/refs/heads/${ref(branch)}", null, "$repo@$branch").then { Outcome.success(Written(null)) }

    private fun link(v: JsonValue): Outcome<Written> = Outcome.success(Written((v as? JsonObject)?.let { str(it, "html_url") }))

    private fun call(method: String, path: String, body: JsonObject?, subject: String): Outcome<JsonValue> {
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", GitHub.API_VERSION)
            put("User-Agent", GitHub.USER_AGENT)
            if (body != null) put("Content-Type", "application/json")
        }
        val bytes = body?.let { CanonicalJson.write(it).toByteArray(Charsets.UTF_8) }
        val r = when (val s = net.send(HttpRequest(method, api.resolve(path), headers, bytes, token))) {
            is Outcome.Failure -> return s
            is Outcome.Success -> s.value
        }
        if (r.status == 405 || r.status == 409 || r.status == 422) {
            val message = (json.parse(r.text).valueOrNull() as? JsonObject)?.let { str(it, "message") }.orEmpty()
            val why = when (r.status) {
                409 -> "на GitHub изменилось с тех пор, как было на экране (новый коммит или конфликт) — действие не выполнено"
                405 -> "GitHub не разрешил"
                else -> "GitHub не принял"
            }
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "$why: ${clean(message)}".trimEnd(':', ' '), subject)
        }
        val checked = GitHub.status(Fetched(r.status, r.body, false, r.headers), subject)
        if (checked is Outcome.Failure) return checked
        if (r.status == 204 || r.body.isEmpty()) return Outcome.success(JsonObject(emptyMap()))
        return when (val v = json.parse(r.text)) {
            is Outcome.Failure -> Outcome.failure(DiagCode.CONNECTOR_REPLY_INVALID, "ответ GitHub — не JSON", subject)
            is Outcome.Success -> Outcome.success(v.value)
        }
    }

    companion object {
        private val SHA = Regex("[0-9a-f]{40}")

        /** Карточка PR (F-11): одна страница списков, пределы текста описания, реплики и строки. */
        const val MAX_LIST: Int = 100
        const val MAX_BODY: Int = 20_000
        const val MAX_NOTE: Int = 4_000
        private const val MAX_LINE = 200

        /**
         * Текст GitHub на экран (F-11): чужие данные (T3) — без управляющих знаков, кроме
         * перевода строки, с пределом длины; похожее на секрет вырезается.
         */
        private fun text(s: String, max: Int): String {
            val t = Redactor.STANDARD.redact(s.replace("\r\n", "\n").filter { it == '\n' || it == '\t' || !it.isISOControl() }).trim()
            return if (t.length > max) t.take(max) + "…" else t
        }

        /** Предел файла для правки с телефона (F-69): тексты процесса меньше. */
        const val MAX_EDIT_BYTES: Int = 512 * 1024

        /** Имя ветки в пути: сегменты кодируются, косая черта остаётся (как у ссылок GitHub). */
        private fun ref(name: String) = name.split('/').joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }

        /** Текст GitHub на экран и в журнал: одна строка, без секретов, не длиннее 300 знаков. */
        private fun clean(s: String) = Redactor.STANDARD.redact(s.replace(Regex("\\s+"), " ").trim()).take(300)

        private fun str(o: JsonObject?, k: String): String? = (o?.get(k) as? JsonString)?.value

        private fun int(o: JsonObject, k: String): Int? = (o[k] as? JsonNumber)?.toLongExact()?.toInt()

        private fun <T> missing(field: String, subject: String): Outcome<T> =
            Outcome.failure(DiagCode.FIELD_MISSING, "в ответе GitHub нет поля $field", subject)

        private fun <T> invalid(subject: String): Outcome<T> = Outcome.failure(DiagCode.CONNECTOR_REPLY_INVALID, "неожиданный ответ GitHub", subject)
    }
}
