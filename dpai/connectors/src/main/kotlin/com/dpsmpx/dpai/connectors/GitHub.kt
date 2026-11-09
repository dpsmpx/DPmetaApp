package com.dpsmpx.dpai.connectors

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString

/**
 * GitHub через официальный клиент `gh` (ТЗ, §30; `docs/ECOSYSTEM.md`): вход
 * — у самого `gh` пользователя, оркестратор токенов не хранит (D-14).
 * Здесь — только команды; исполняет их процесс в `execution` с рабочим
 * каталогом — деревом задачи. В V1 — PR и его состояние, не замена
 * GitHub (`docs/direction/V1_SCOPE.md`, §3).
 */
object GitHubCommands {
    private val BRANCH = Regex("[A-Za-z0-9._/-]{1,200}")

    /** `gh pr create`: база, ветка, заголовок, описание; запускать в дереве задачи. */
    fun createPr(base: String, head: String, title: String, body: String): Outcome<List<String>> {
        if (!validBranch(base) || !validBranch(head)) return Outcome.failure(DiagCode.VALUE_INVALID, "имя ветки недопустимо", "github")
        if (title.isBlank() || title.length > 256 || title.any { it.isISOControl() }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "заголовок PR — 1…256 знаков без управляющих", "github")
        }
        return Outcome.success(listOf("gh", "pr", "create", "--base", base, "--head", head, "--title", title, "--body", body))
    }

    /** `gh pr view` — состояние PR в машинно читаемом виде. */
    fun prState(head: String): Outcome<List<String>> =
        if (!validBranch(head)) {
            Outcome.failure(DiagCode.VALUE_INVALID, "имя ветки недопустимо", "github")
        } else {
            Outcome.success(listOf("gh", "pr", "view", head, "--json", "number,state,url,mergeable"))
        }

    /** Ссылка на созданный PR — строка `https://…` в выводе `gh pr create`. */
    fun prUrl(stdout: List<String>): Outcome<String> =
        stdout.lastOrNull { it.trim().startsWith("https://") }?.trim()?.let { Outcome.success(it) }
            ?: Outcome.failure(DiagCode.CONNECTOR_REPLY_INVALID, "gh не вернул ссылку на PR", "github")

    /** Поля списка PR (`gh pr list --json`): ровно их `gh` и вернёт — лишнее поле — ошибка разбора. */
    const val LIST_FIELDS: String = "number,title,state,isDraft,url,headRefName,baseRefName,author,updatedAt,reviewDecision,statusCheckRollup"

    /** Поля карточки PR (`gh pr view --json`): поля списка, описание, можно ли слить, комментарии и отзывы. */
    const val VIEW_FIELDS: String = "$LIST_FIELDS,body,mergeable,comments,reviews"

    /** Сколько PR в списке — экран телефона, а не выгрузка репозитория. */
    const val LIST_LIMIT: Int = 30

    /** Предел внешнего текста (описание, комментарий): данные со стороны (T3) не растут без предела в памяти. */
    const val MAX_TEXT: Int = 20_000

    /** `gh pr list` — открытые PR репозитория рабочего каталога (D-108). */
    fun prList(limit: Int = LIST_LIMIT): List<String> = listOf("gh", "pr", "list", "--state", "open", "--limit", "${limit.coerceIn(1, 100)}", "--json", LIST_FIELDS)

    /** `gh pr view N` — карточка PR (D-108). */
    fun prView(number: Int): Outcome<List<String>> =
        if (number < 1) Outcome.failure(DiagCode.VALUE_INVALID, "номер PR — с 1", "github") else Outcome.success(listOf("gh", "pr", "view", "$number", "--json", VIEW_FIELDS))

    /** Список PR из вывода `gh pr list --json` (D-108): строго по [LIST_FIELDS]; вывод — данные со стороны (T3). */
    fun parsePrList(stdout: List<String>): Outcome<List<GhPr>> {
        val d = DiagnosticCollector()
        val json = parse(stdout) ?: return invalid("gh pr list: вывод — не JSON")
        val arr = json as? JsonArray ?: return invalid("gh pr list: ожидался массив")
        val out = arr.items.mapIndexedNotNull { i, v ->
            (v as? JsonObject)?.let { o -> JsonFields(o, "$[$i]", d).let { f -> pr(f).also { f.finish() } } } ?: run {
                d.error(DiagCode.CONNECTOR_REPLY_INVALID, "ожидался объект PR", "$[$i]")
                null
            }
        }
        return if (d.hasErrors) Outcome.Failure(listOf(Diagnostic.of(DiagCode.CONNECTOR_REPLY_INVALID, "gh pr list: ответ не по формату", "github", causes = d.all))) else Outcome.success(out)
    }

    /** Карточка PR из вывода `gh pr view --json` (D-108): строго по [VIEW_FIELDS]. */
    fun parsePrView(stdout: List<String>): Outcome<GhPrDetail> {
        val d = DiagnosticCollector()
        val o = parse(stdout) as? JsonObject ?: return invalid("gh pr view: ожидался объект JSON")
        val f = JsonFields(o, "$", d)
        val pr = pr(f)
        val body = f.string("body")?.let(::external)
        val mergeable = f.string("mergeable")
        val notes = mutableListOf<GhNote>()
        f.array("comments")?.items?.forEachIndexed { i, v ->
            val c = (v as? JsonObject)?.let { JsonFields(it, "$.comments[$i]", d) } ?: return@forEachIndexed
            val who = login(c)
            val text = c.string("body")
            val at = c.string("createdAt")
            if (who != null && text != null && at != null) notes += GhNote(who, null, external(text), at)
        }
        f.array("reviews")?.items?.forEachIndexed { i, v ->
            val r = (v as? JsonObject)?.let { JsonFields(it, "$.reviews[$i]", d) } ?: return@forEachIndexed
            val who = login(r)
            val state = r.string("state")
            val text = r.string("body")
            val at = r.optString("submittedAt") ?: ""
            if (who != null && state != null && text != null) notes += GhNote(who, state, external(text), at)
        }
        f.finish()
        if (d.hasErrors || pr == null || body == null || mergeable == null) {
            return Outcome.Failure(listOf(Diagnostic.of(DiagCode.CONNECTOR_REPLY_INVALID, "gh pr view: ответ не по формату", "github", causes = d.all)))
        }
        return Outcome.success(GhPrDetail(pr, body, mergeable, notes.sortedBy { it.at }))
    }

    /**
     * Отказ `gh` — понятной причиной (D-05): нет входа, нет удалённого GitHub, предел запросов,
     * нет самого `gh`; иначе — код выхода и хвост вывода.
     */
    fun failure(command: String, exitCode: Int, stderr: List<String>, stdout: List<String> = emptyList()): Diagnostic {
        val text = (stderr + stdout).joinToString("\n")
        val low = text.lowercase()
        val tail = (stderr + stdout).lastOrNull { it.isNotBlank() }?.let(::external)?.take(300) ?: "без вывода"
        return when {
            exitCode == 127 || ("not found" in low && "gh" in low && "pull request" !in low) ->
                Diagnostic.error(DiagCode.PROCESS_EXIT_NONZERO, "$command: на target нет gh — поставьте пакет github-cli (Linux в приложении) или gh на target", "github")
            "gh auth login" in low || "not logged in" in low || "authentication" in low ->
                Diagnostic.error(DiagCode.GITHUB_AUTH_EXPIRED, "$command: gh на target не вошёл в GitHub — в терминале target: gh auth login", "github")
            "rate limit" in low -> Diagnostic.error(DiagCode.GITHUB_RATE_LIMITED, "$command: предел запросов GitHub — позже", "github")
            "no git remotes" in low || "none of the git remotes" in low || "not a git repository" in low || "could not resolve to a repository" in low ->
                Diagnostic.error(DiagCode.GITHUB_NOT_FOUND, "$command: у репозитория проекта нет удалённого на GitHub (origin) или он недоступен входу gh", "github")
            else -> Diagnostic.error(DiagCode.PROCESS_EXIT_NONZERO, "$command: код $exitCode: $tail", "github")
        }
    }

    /**
     * Внешний текст для экрана (T3): без управляющих знаков (кроме перевода строки и табуляции)
     * и без знаков смены направления письма — ими подменяют вид текста; не длиннее [MAX_TEXT].
     */
    fun external(s: String, max: Int = MAX_TEXT): String {
        val clean = s.filter { c -> (c == '\n' || c == '\t' || !c.isISOControl()) && c !in BIDI }
        return if (clean.length <= max) clean else clean.take(max) + "…"
    }

    private val BIDI: Set<Char> = (('\u202A'..'\u202E') + ('\u2066'..'\u2069') + listOf('\u200E', '\u200F', '\u061C')).toSet()

    private fun parse(stdout: List<String>) = JsonParser(JsonParser.Limits(maxChars = 4 * 1024 * 1024)).parse(stdout.joinToString("\n")).valueOrNull()

    private fun <T> invalid(message: String): Outcome<T> = Outcome.failure(DiagCode.CONNECTOR_REPLY_INVALID, message, "github")

    private fun login(f: JsonFields): String? = f.obj("author")?.let { a -> a.string("login") }?.let { external(it, 100) }

    private fun pr(f: JsonFields): GhPr? {
        val number = f.int("number")
        val title = f.string("title")
        val state = f.string("state")
        val draft = f.boolean("isDraft")
        val url = f.string("url")
        val head = f.string("headRefName")
        val base = f.string("baseRefName")
        val author = login(f)
        val updated = f.string("updatedAt")
        val review = f.string("reviewDecision")
        val checks = f.array("statusCheckRollup")?.items?.mapNotNull(::check)
        if (url != null && !url.startsWith("https://")) f.diagnostics.error(DiagCode.CONNECTOR_REPLY_INVALID, "адрес PR — не https", "$.url")
        if (number == null || title == null || state == null || draft == null || url == null || head == null || base == null ||
            author == null || updated == null || review == null || checks == null || !url.startsWith("https://")
        ) {
            return null
        }
        return GhPr(number, external(title, 300), state, draft, url, external(head, 200), external(base, 200), author, updated, review.ifEmpty { null }, checks)
    }

    /** Проверка из `statusCheckRollup`: у `CheckRun` — имя и итог (`conclusion`, пока идёт — `status`), у `StatusContext` — `context` и `state`. */
    private fun check(v: com.dpsmpx.dpai.core.serialization.JsonValue): GhCheck? {
        val o = v as? JsonObject ?: return null
        fun str(k: String) = (o[k] as? JsonString)?.value?.ifEmpty { null }
        val name = str("name") ?: str("context") ?: return null
        val state = str("conclusion") ?: str("state") ?: str("status") ?: "UNKNOWN"
        return GhCheck(external(name, 200), state)
    }

    private fun validBranch(b: String) = BRANCH.matches(b) && !b.startsWith("-") && ".." !in b
}

/** Проверка PR (CI): имя и итог — `SUCCESS`, `FAILURE`, `NEUTRAL`, `SKIPPED`…; пока идёт — `IN_PROGRESS`, `QUEUED`, `PENDING`. */
data class GhCheck(val name: String, val state: String) {
    val passed: Boolean get() = state in setOf("SUCCESS", "NEUTRAL", "SKIPPED")
    val failed: Boolean get() = state in setOf("FAILURE", "ERROR", "CANCELLED", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE")
}

/** PR в списке (D-108). [review] — решение отзывов (`APPROVED`, `CHANGES_REQUESTED`, `REVIEW_REQUIRED`), `null` — не требуется. */
data class GhPr(
    val number: Int,
    val title: String,
    val state: String,
    val draft: Boolean,
    val url: String,
    val head: String,
    val base: String,
    val author: String,
    val updatedAt: String,
    val review: String?,
    val checks: List<GhCheck>,
)

/** Комментарий ([review] = `null`) или отзыв с его итогом — для ленты карточки PR. */
data class GhNote(val author: String, val review: String?, val body: String, val at: String)

/** Карточка PR (D-108): [mergeable] — `MERGEABLE`, `CONFLICTING` или `UNKNOWN`, пока GitHub считает. */
data class GhPrDetail(val pr: GhPr, val body: String, val mergeable: String, val notes: List<GhNote>)
