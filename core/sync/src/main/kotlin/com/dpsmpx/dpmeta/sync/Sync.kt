package com.dpsmpx.dpmeta.sync

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpmeta.formats.Checklist
import com.dpsmpx.dpmeta.formats.Checks
import com.dpsmpx.dpmeta.formats.StatusBlocks
import com.dpsmpx.dpmeta.github.Branch
import com.dpsmpx.dpmeta.github.CiState
import com.dpsmpx.dpmeta.github.CommitInfo
import com.dpsmpx.dpmeta.github.Issue
import com.dpsmpx.dpmeta.github.PullRequest
import com.dpsmpx.dpmeta.github.RecentCommit
import com.dpsmpx.dpmeta.model.Observation
import com.dpsmpx.dpmeta.model.HistorySchema
import com.dpsmpx.dpmeta.model.ObservationSource
import com.dpsmpx.dpmeta.model.SyncProject
import com.dpsmpx.dpmeta.model.ProjectCatalog
import com.dpsmpx.dpmeta.model.SourceFailure

/**
 * Вид источника и его приоритет при нехватке бюджета (ТЗ, §6.4): сводка и
 * открытые PR → последние влитые → ветки → документы.
 */
enum class SourceKind(val code: String) {
    PULLS("pulls"),
    MERGED("merged"),
    BRANCHES("branches"),

    /** Открытые issues (D-176): у источников без них (корпус, каталог) задача пропускается молча. */
    ISSUES("issues"),

    /** Последние коммиты основной ветки и CI её головы (D-176). */
    COMMITS("commits"),
    README("readme"),

    /** Чекбоксы файлов, выбранных пользователем (D-176): все пути проекта одним наблюдением. */
    CHECKLISTS("checklists"),

    /** История из клона (D-66): только у читателя клонов, последней — она тяжелее остальных. */
    HISTORY("history"),
}

/** Получено из источника: значение, отпечаток исходного ответа, признак 304. */
class Got<T>(val value: T, val rawSha256: ContentHash, val notModified: Boolean = false)

/**
 * Откуда берутся данные проекта: GitHub (вживую) или каталог (корпус,
 * клоны). Путь файла — от корня проекта; подсистемы DPmetaApp
 * подставляют свой каталог сами.
 */
interface SourceReader {
    val source: ObservationSource

    /** Есть ли у проекта PR и ветки в этом источнике; нет — «кто работает» неизвестно, а не «свободен». */
    fun hasActivity(project: SyncProject): Boolean

    fun pulls(project: SyncProject): Outcome<Got<List<PullRequest>>>

    /** Последние закрытые PR (влитые — с [PullRequest.mergedAt]). */
    fun closed(project: SyncProject): Outcome<Got<List<PullRequest>>>

    fun branches(project: SyncProject): Outcome<Got<List<Branch>>>

    fun commit(project: SyncProject, sha: String): Outcome<CommitInfo>

    fun file(project: SyncProject, path: String): Outcome<Got<String>>

    /** Открытые issues (D-176); `null` — у этого источника их нет: задача пропускается молча. */
    fun issues(project: SyncProject): Outcome<Got<List<Issue>>>? = null

    /** Последние коммиты основной ветки (D-176); `null` — у этого источника их нет. */
    fun recentCommits(project: SyncProject): Outcome<Got<List<RecentCommit>>>? = null

    /** CI коммита [sha] (D-176); `null` — у этого источника CI нет (тогда его поля нет — неизвестно). */
    fun ci(project: SyncProject, sha: String): Outcome<Got<CiState>>? = null

    /**
     * История проекта из клона (D-66); `null` — у этого источника её нет
     * (REST, корпус, подсистема, клона нет): задача пропускается молча, без
     * отказа в «Ошибках источников».
     */
    fun history(project: SyncProject): Outcome<Got<com.dpsmpx.dpmeta.git.CommitHistory>>? = null

    /**
     * Имена файлов каталога [dir] проекта (от корня проекта); `null` — этот
     * источник каталоги не показывает (тогда счёт по каталогу неизвестен).
     */
    fun names(project: SyncProject, dir: String): Outcome<List<String>>? = null
}

/** Задача плана: проект, вид источника, пути-кандидаты файла (первый найденный). */
data class SourceTask(val project: SyncProject, val kind: SourceKind, val paths: List<String>)

/** Итог прогона: наблюдения, отказы по паре «проект → вид», отложенное из-за бюджета. */
class SyncResult(val observations: List<Observation>, val failures: List<SourceFailure>, val deferred: Int)

/**
 * Как выполнить независимые запросы (даты коммитов веток): по очереди или
 * пулом потоков оболочки. Порядок итогов — порядок задач.
 */
interface Parallel {
    fun <T> all(jobs: List<() -> T>): List<T>

    companion object {
        val SEQUENTIAL: Parallel = object : Parallel {
            override fun <T> all(jobs: List<() -> T>): List<T> = jobs.map { it() }
        }
    }
}

/**
 * План и прогон синхронизации (ТЗ, §6.4). План детерминирован: по виду
 * источника, затем по порядку реестра. Отказ источника — его диагностика,
 * остальные идут дальше. Бюджет или лимит исчерпан — оставшиеся задачи
 * откладываются с той же диагностикой («на следующий раз»).
 */
object Sync {
    /** Источники активности: у подсистем их нет (общие с DPmetaApp), у клонов — тоже. */
    private val ACTIVITY = setOf(SourceKind.PULLS, SourceKind.MERGED, SourceKind.BRANCHES, SourceKind.ISSUES, SourceKind.COMMITS)

    /** У скольких последних открытых PR спрашивать CI (D-176): каждый — два запроса. */
    const val CI_PULLS_KEEP = 10

    /** Сколько последних влитых PR хранить: итог проверок последнего и «влит» в ленте. */
    const val MERGED_KEEP = 5

    /** Скрыто от токена (403 без лимита или 404): у прав «по желанию» — «нет прав», а не отказ. */
    private val HIDDEN = setOf(DiagCode.GITHUB_AUTH_EXPIRED, DiagCode.GITHUB_NOT_FOUND)

    /** «Файла нет» у GitHub и у каталога: следующий путь-кандидат. */
    private val NOT_FOUND = setOf(DiagCode.GITHUB_NOT_FOUND, DiagCode.ENTITY_NOT_FOUND)

    /** [skip] — пары «проект → вид», которых недавно не было. */
    fun plan(registry: ProjectCatalog, skip: Set<Pair<String, String>> = emptySet()): List<SourceTask> {
        val tasks = mutableListOf<SourceTask>()
        for (kind in SourceKind.entries) {
            for (p in registry.projects) {
                val paths = when (kind) {
                    SourceKind.PULLS, SourceKind.MERGED, SourceKind.BRANCHES, SourceKind.ISSUES, SourceKind.COMMITS, SourceKind.HISTORY -> emptyList()
                    SourceKind.README -> listOf("README.md")
                    SourceKind.CHECKLISTS -> p.checklists.ifEmpty { continue }
                }
                if ((p.name to kind.code) !in skip) tasks += SourceTask(p, kind, paths)
            }
        }
        return tasks
    }

    fun run(
        tasks: List<SourceTask>,
        reader: SourceReader,
        clock: Clock,
        knownCommits: Map<String, CommitInfo> = emptyMap(),
        parallel: Parallel = Parallel.SEQUENTIAL,
    ): SyncResult {
        val observations = mutableListOf<Observation>()
        val failures = mutableListOf<SourceFailure>()
        var stop: Diagnostic? = null
        var deferred = 0
        val commits = HashMap(knownCommits)
        for (t in tasks) {
            val name = t.project.name
            stop?.let {
                failures += SourceFailure(name, t.kind.code, it, clock.now())
                deferred++
                continue
            }
            if (t.kind in ACTIVITY && !reader.hasActivity(t.project)) continue
            val r = when (t.kind) {
                SourceKind.HISTORY -> {
                    val h = reader.history(t.project) ?: continue
                    h.map { g -> historyJson(g.value) to g.rawSha256 }
                }
                SourceKind.CHECKLISTS -> checklists(t, reader)
                SourceKind.ISSUES -> when (val i = reader.issues(t.project) ?: continue) {
                    // Токену не дали Issues (D-176): «нет прав», а не ошибка источника — права по желанию.
                    is Outcome.Failure -> if (i.diagnostics.first().code in HIDDEN) Outcome.success(JsonObject.of("issues" to JsonArray(emptyList()), "access" to JsonBool(false)) to com.dpsmpx.dpai.core.hashing.Hashing.sha256("issues:hidden")) else i
                    is Outcome.Success -> Outcome.success(issuesJson(i.value.value) to i.value.rawSha256)
                }
                SourceKind.COMMITS -> commits(t, reader) ?: continue
                else -> fetch(t, reader, commits, parallel)
            }
            when (r) {
                is Outcome.Success -> {
                    val (payload, raw) = r.value
                    val o = Observation.of(reader.source, name, t.kind.code, clock.now(), schemaOf(t.kind, payload), payload, raw)
                    when (o) {
                        is Outcome.Success -> observations += o.value
                        is Outcome.Failure -> failures += SourceFailure(name, t.kind.code, o.diagnostics.first(), clock.now())
                    }
                }
                is Outcome.Failure -> {
                    val d = r.diagnostics.first()
                    failures += SourceFailure(name, t.kind.code, d, clock.now())
                    if (d.code == DiagCode.RUN_BUDGET_EXHAUSTED || d.code == DiagCode.GITHUB_RATE_LIMITED) stop = d
                }
            }
        }
        return SyncResult(observations, failures, deferred)
    }

    private fun schemaOf(kind: SourceKind, payload: JsonObject): String = (payload["schema"] as? JsonString)?.value ?: when (kind) {
        SourceKind.PULLS -> "github.pulls.v1"
        SourceKind.MERGED -> "github.merged.v1"
        SourceKind.BRANCHES -> "github.branches.v1"
        SourceKind.HISTORY -> HISTORY_SCHEMA
        SourceKind.CHECKLISTS -> CHECKLISTS_SCHEMA
        SourceKind.ISSUES -> "github.issues.v1"
        SourceKind.COMMITS -> "github.commits.v1"
        else -> "unknown"
    }

    private fun fetch(t: SourceTask, reader: SourceReader, commits: MutableMap<String, CommitInfo>, parallel: Parallel): Outcome<Pair<JsonObject, ContentHash>> = when (t.kind) {
        SourceKind.PULLS -> when (val p = reader.pulls(t.project)) {
            is Outcome.Failure -> p
            is Outcome.Success -> {
                // CI последних PR (D-176): по голове; источник без CI или его сбой (лимит, 5xx) — поля нет:
                // список PR важнее, а лимит остановит следующую задачу.
                val heads = p.value.value.filter { it.headSha != null }.sortedByDescending { it.updatedAt }.take(CI_PULLS_KEEP)
                val got = parallel.all(heads.map { pr -> { pr.number to reader.ci(t.project, pr.headSha!!) } })
                val ci = HashMap<Int, CiState>()
                val raws = StringBuilder(p.value.rawSha256.toString()).append('\n')
                for ((number, r) in got) {
                    when (r) {
                        null, is Outcome.Failure -> Unit
                        is Outcome.Success -> {
                            ci[number] = r.value.value
                            raws.append(number).append('\u0000').append(r.value.rawSha256.toString()).append('\n')
                        }
                    }
                }
                val raw = if (ci.isEmpty()) p.value.rawSha256 else com.dpsmpx.dpai.core.hashing.Hashing.sha256(raws.toString())
                Outcome.success(pullsJson(p.value.value, ci) to raw)
            }
        }
        SourceKind.MERGED -> reader.closed(t.project).map { g -> pullsJson(g.value.filter { it.mergedAt != null }.sortedByDescending { it.mergedAt }.take(MERGED_KEEP)) to g.rawSha256 }
        SourceKind.BRANCHES -> when (val b = reader.branches(t.project)) {
            is Outcome.Failure -> b
            is Outcome.Success -> {
                val branches = b.value.value.sortedBy { it.name }
                val unknown = branches.map { it.sha }.distinct().filter { it !in commits }
                val got = parallel.all(unknown.map { sha -> { sha to reader.commit(t.project, sha) } })
                for ((sha, c) in got) {
                    when (c) {
                        is Outcome.Failure -> return c
                        is Outcome.Success -> commits[sha] = c.value
                    }
                }
                val items = branches.map { br -> br to commits.getValue(br.sha) }
                Outcome.success(branchesJson(items) to b.value.rawSha256)
            }
        }
        else -> file(t, reader)
    }

    private fun file(t: SourceTask, reader: SourceReader): Outcome<Pair<JsonObject, ContentHash>> {
        var last: Outcome.Failure? = null
        for (path in t.paths) {
            val got = when (val r = reader.file(t.project, path)) {
                is Outcome.Failure -> {
                    last = r
                    if (r.diagnostics.first().code in NOT_FOUND) continue else return r
                }
                is Outcome.Success -> r.value
            }
            return StatusBlocks.parse(got.value, "${t.project.name}:$path").map { d ->
                JsonObject(LinkedHashMap<String, JsonValue>(d.toJson().fields).apply {
                    put("schema", JsonString(d.schema))
                    put("path", JsonString(path))
                }) to got.rawSha256
            }
        }
        return last ?: Outcome.failure(DiagCode.INTERNAL_ERROR, "у задачи нет путей", t.project.name)
    }

    private fun pullsJson(pulls: List<PullRequest>, ci: Map<Int, CiState> = emptyMap()): JsonObject = JsonObject.of(
        "pulls" to JsonArray(
            pulls.sortedBy { it.number }.map { p ->
                val checks = Checks.of(p.body)
                JsonObject(
                    linkedMapOf<String, JsonValue>(
                        "number" to JsonNumber.of(p.number),
                        "title" to JsonString(p.title),
                        "draft" to JsonBool(p.draft),
                        "head" to JsonString(p.head),
                        "author" to JsonString(p.author),
                        "updatedAt" to JsonNumber.of(p.updatedAt.epochMillis),
                        "checks" to JsonString(checks.verdict.code),
                    ).apply {
                        checks.line?.let { put("checksLine", JsonString(it)) }
                        // Сессия Claude Code, открывшая PR (F-58; D-161): только id, не описание.
                        com.dpsmpx.dpmeta.formats.SessionLink.of(p.body)?.let { put("session", JsonString(it)) }
                        p.mergedAt?.let { put("mergedAt", JsonNumber.of(it.epochMillis)) }
                        p.createdAt?.let { put("createdAt", JsonNumber.of(it.epochMillis)) }
                        ci[p.number]?.let { put("ci", ciJson(it)) }
                        if (p.reviewers.isNotEmpty()) put("reviewers", JsonArray(p.reviewers.map(::JsonString)))
                    },
                )
            },
        ),
    )

    /** CI коммита (D-176): итог, сколько проверок, упавшие по имени, видно ли токену. */
    fun ciJson(c: CiState): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>(
            "verdict" to JsonString(c.verdict.code),
            "total" to JsonNumber.of(c.total),
        ).apply {
            if (c.failing.isNotEmpty()) put("failing", JsonArray(c.failing.take(CI_FAILING_KEEP).map(::JsonString)))
            if (!c.access) put("access", JsonBool(false))
        },
    )

    /** Сколько имён упавших проверок хранить. */
    const val CI_FAILING_KEEP = 10

    private fun issuesJson(issues: List<Issue>): JsonObject = JsonObject.of(
        "issues" to JsonArray(
            issues.map { i ->
                JsonObject(
                    linkedMapOf<String, JsonValue>(
                        "number" to JsonNumber.of(i.number),
                        "title" to JsonString(i.title),
                        "author" to JsonString(i.author),
                        "updatedAt" to JsonNumber.of(i.updatedAt.epochMillis),
                        "comments" to JsonNumber.of(i.comments),
                    ).apply {
                        if (i.assignees.isNotEmpty()) put("assignees", JsonArray(i.assignees.map(::JsonString)))
                        if (i.labels.isNotEmpty()) put("labels", JsonArray(i.labels.map(::JsonString)))
                    },
                )
            },
        ),
    )

    /**
     * Последние коммиты основной ветки и CI её головы (D-176) одним наблюдением: CI меняется и
     * без новых коммитов (проверки доходят), поэтому отпечаток — из обоих ответов.
     */
    private fun commits(t: SourceTask, reader: SourceReader): Outcome<Pair<JsonObject, ContentHash>>? {
        val got = when (val r = reader.recentCommits(t.project) ?: return null) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val head = got.value.firstOrNull()
        // Сбой CI головы — без поля CI (неизвестно), коммиты остаются.
        val ciState = (head?.let { reader.ci(t.project, it.sha) } as? Outcome.Success)?.value
        val payload = JsonObject(
            linkedMapOf<String, JsonValue>(
                "commits" to JsonArray(
                    got.value.map { c ->
                        JsonObject(
                            linkedMapOf<String, JsonValue>(
                                "sha" to JsonString(c.sha),
                                "title" to JsonString(c.title),
                                "date" to JsonNumber.of(c.date.epochMillis),
                            ).apply { c.author?.let { put("author", JsonString(it)) } },
                        )
                    },
                ),
            ).apply { ciState?.let { put("ci", ciJson(it.value)) } },
        )
        val raw = ciState?.let { com.dpsmpx.dpai.core.hashing.Hashing.sha256(got.rawSha256.toString() + "\n" + it.rawSha256) } ?: got.rawSha256
        return Outcome.success(payload to raw)
    }

    /** Схема чекбоксов файлов пользователя (D-176). */
    const val CHECKLISTS_SCHEMA = "checklists.v1"

    /**
     * Чекбоксы всех файлов проекта, которые выбрал пользователь (D-176), — одним наблюдением,
     * в порядке его списка. Файла нет — запись `missing`: путь мог быть набран с ошибкой, и
     * карточка это покажет; другой отказ источника — отказ всей задачи.
     */
    private fun checklists(t: SourceTask, reader: SourceReader): Outcome<Pair<JsonObject, ContentHash>> {
        val files = mutableListOf<JsonValue>()
        val raws = StringBuilder()
        for (path in t.paths) {
            when (val r = reader.file(t.project, path)) {
                is Outcome.Failure -> {
                    if (r.diagnostics.first().code !in NOT_FOUND) return r
                    files += JsonObject.of("path" to JsonString(path), "missing" to JsonBool(true))
                    raws.append(path).append('\u0000').append('-').append('\n')
                }
                is Outcome.Success -> {
                    val c = (Checklist.parse(r.value.value) as Outcome.Success).value
                    files += JsonObject(linkedMapOf<String, JsonValue>("path" to JsonString(path)).apply { putAll(c.toJson().fields) })
                    raws.append(path).append('\u0000').append(r.value.rawSha256.toString()).append('\n')
                }
            }
        }
        val payload = JsonObject.of("schema" to JsonString(CHECKLISTS_SCHEMA), "files" to JsonArray(files))
        return Outcome.success(payload to com.dpsmpx.dpai.core.hashing.Hashing.sha256(raws.toString()))
    }

    /** Схема наблюдения истории из клона (D-66). */
    const val HISTORY_SCHEMA = HistorySchema.CURRENT

    /**
     * История из клона одной строкой JSON: дни — `[дата, коммиты, ИИ, прочие,
     * в основной, только в ветках, слияния PR]`, журнал — `[дата, записей]`
     * в дни перемен, ветки впереди основной — имя, коммитов сверх неё, время
     * последнего. Ни авторов, ни сообщений, ни путей.
     */
    fun historyJson(h: com.dpsmpx.dpmeta.git.CommitHistory): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>(
            "schema" to JsonString(HISTORY_SCHEMA),
            "complete" to JsonBool(h.complete),
            "days" to JsonArray(h.days.map { d -> JsonArray(listOf(JsonString(d.date)) + listOf(d.commits, d.ai, d.other, d.mainline, d.branchOnly, d.merges).map { JsonNumber.of(it.toLong()) }) }),
            "decisions" to JsonArray(h.decisions.map { (d, n) -> JsonArray(listOf(JsonString(d), JsonNumber.of(n.toLong()))) }),
            "ahead" to JsonArray(h.ahead.map { a -> JsonObject.of("name" to JsonString(a.name), "commits" to JsonNumber.of(a.commits.toLong()), "last" to JsonNumber.of(a.last.epochMillis)) }),
        ).apply { h.first?.let { put("first", JsonString(it)) } },
    )

    private fun branchesJson(items: List<Pair<Branch, CommitInfo>>): JsonObject = JsonObject.of(
        "branches" to JsonArray(
            items.map { (b, c) ->
                JsonObject(
                    linkedMapOf<String, JsonValue>(
                        "name" to JsonString(b.name),
                        "sha" to JsonString(b.sha),
                        "date" to JsonNumber.of(c.date.epochMillis),
                    ).apply { c.author?.let { put("author", JsonString(it)) } },
                )
            },
        ),
    )

    /** Известные коммиты из прежнего состояния: SHA неизменяем — второй раз не спрашивается. */
    fun knownCommits(branchesPayloads: List<JsonValue>): Map<String, CommitInfo> {
        val out = HashMap<String, CommitInfo>()
        for (p in branchesPayloads) {
            for (b in ((p as? JsonObject)?.get("branches") as? JsonArray)?.items.orEmpty()) {
                val o = b as? JsonObject ?: continue
                val sha = (o["sha"] as? JsonString)?.value ?: continue
                val date = (o["date"] as? JsonNumber)?.toLongExact() ?: continue
                out[sha] = CommitInfo(sha, Timestamp(date), (o["author"] as? JsonString)?.value)
            }
        }
        return out
    }

    private inline fun <T, R> Outcome<T>.map(f: (T) -> R): Outcome<R> = when (this) {
        is Outcome.Failure -> this
        is Outcome.Success -> Outcome.success(f(value), diagnostics)
    }
}
