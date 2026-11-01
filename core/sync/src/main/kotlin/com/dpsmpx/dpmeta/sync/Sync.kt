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
import com.dpsmpx.dpmeta.formats.DecisionLog
import com.dpsmpx.dpmeta.formats.ProcessDocument
import com.dpsmpx.dpmeta.formats.Roadmap
import com.dpsmpx.dpmeta.formats.StatusBlocks
import com.dpsmpx.dpmeta.formats.TodoList
import com.dpsmpx.dpmeta.github.Branch
import com.dpsmpx.dpmeta.github.CommitInfo
import com.dpsmpx.dpmeta.github.PullRequest
import com.dpsmpx.dpmeta.model.Observation
import com.dpsmpx.dpmeta.model.ObservationSource
import com.dpsmpx.dpmeta.model.ProjectEntry
import com.dpsmpx.dpmeta.model.Registry

/**
 * Вид источника и его приоритет при нехватке бюджета (ТЗ, §6.4): сводка и
 * открытые PR → ветки → файлы процесса.
 */
enum class SourceKind(val code: String) {
    PULLS("pulls"),
    BRANCHES("branches"),
    DECISIONS("decisions"),
    TODO("todo"),
    ROADMAP("roadmap"),
    README("readme"),
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
    fun hasActivity(project: ProjectEntry): Boolean

    fun pulls(project: ProjectEntry): Outcome<Got<List<PullRequest>>>

    fun branches(project: ProjectEntry): Outcome<Got<List<Branch>>>

    fun commit(project: ProjectEntry, sha: String): Outcome<CommitInfo>

    fun file(project: ProjectEntry, path: String): Outcome<Got<String>>
}

/** Задача плана: проект, вид источника, пути-кандидаты файла (первый найденный). */
data class SourceTask(val project: ProjectEntry, val kind: SourceKind, val paths: List<String>)

/** Итог прогона: наблюдения, отказы по паре «проект → вид», отложенное из-за бюджета. */
class SyncResult(val observations: List<Observation>, val failures: List<Triple<String, String, Diagnostic>>, val deferred: Int)

/**
 * План и прогон синхронизации (ТЗ, §6.4). План детерминирован: по виду
 * источника, затем по порядку реестра. Отказ источника — его диагностика,
 * остальные идут дальше. Бюджет или лимит исчерпан — оставшиеся задачи
 * откладываются с той же диагностикой («на следующий раз»).
 */
object Sync {
    /** Пути ROADMAP по порядку: у репозиториев экосистемы встречаются оба. */
    val ROADMAP_PATHS: List<String> = listOf("ROADMAP/ROADMAP.md", "docs/direction/ROADMAP/README.md")

    /** «Файла нет» у GitHub и у каталога: следующий путь-кандидат. */
    private val NOT_FOUND = setOf(DiagCode.GITHUB_NOT_FOUND, DiagCode.ENTITY_NOT_FOUND)

    fun plan(registry: Registry, known: Map<Pair<String, String>, String> = emptyMap()): List<SourceTask> {
        val tasks = mutableListOf<SourceTask>()
        for (kind in SourceKind.entries) {
            for (p in registry.shown) {
                val paths = when (kind) {
                    SourceKind.PULLS, SourceKind.BRANCHES -> emptyList()
                    SourceKind.DECISIONS -> p.decisions?.let { listOf(it.path) } ?: continue
                    SourceKind.TODO -> listOf("TODO.md")
                    SourceKind.ROADMAP -> known[p.name to kind.code]?.let { k -> listOf(k) + (ROADMAP_PATHS - k) } ?: ROADMAP_PATHS
                    SourceKind.README -> listOf("README.md")
                }
                tasks += SourceTask(p, kind, paths)
            }
        }
        return tasks
    }

    fun run(
        tasks: List<SourceTask>,
        reader: SourceReader,
        clock: Clock,
        knownCommits: Map<String, CommitInfo> = emptyMap(),
    ): SyncResult {
        val observations = mutableListOf<Observation>()
        val failures = mutableListOf<Triple<String, String, Diagnostic>>()
        var stop: Diagnostic? = null
        var deferred = 0
        val commits = HashMap(knownCommits)
        for (t in tasks) {
            val name = t.project.name
            stop?.let {
                failures += Triple(name, t.kind.code, it)
                deferred++
                continue
            }
            if ((t.kind == SourceKind.PULLS || t.kind == SourceKind.BRANCHES) && !reader.hasActivity(t.project)) continue
            val r = fetch(t, reader, commits)
            when (r) {
                is Outcome.Success -> {
                    val (payload, raw) = r.value
                    val o = Observation.of(reader.source, name, t.kind.code, clock.now(), schemaOf(t.kind, payload), payload, raw)
                    when (o) {
                        is Outcome.Success -> observations += o.value
                        is Outcome.Failure -> failures += Triple(name, t.kind.code, o.diagnostics.first())
                    }
                }
                is Outcome.Failure -> {
                    val d = r.diagnostics.first()
                    failures += Triple(name, t.kind.code, d)
                    if (d.code == DiagCode.RUN_BUDGET_EXHAUSTED || d.code == DiagCode.GITHUB_RATE_LIMITED) stop = d
                }
            }
        }
        return SyncResult(observations, failures, deferred)
    }

    private fun schemaOf(kind: SourceKind, payload: JsonObject): String = (payload["schema"] as? JsonString)?.value ?: when (kind) {
        SourceKind.PULLS -> "github.pulls.v1"
        SourceKind.BRANCHES -> "github.branches.v1"
        else -> "unknown"
    }

    private fun fetch(t: SourceTask, reader: SourceReader, commits: MutableMap<String, CommitInfo>): Outcome<Pair<JsonObject, ContentHash>> = when (t.kind) {
        SourceKind.PULLS -> reader.pulls(t.project).map { g -> pullsJson(g.value) to g.rawSha256 }
        SourceKind.BRANCHES -> when (val b = reader.branches(t.project)) {
            is Outcome.Failure -> b
            is Outcome.Success -> {
                val items = b.value.value.sortedBy { it.name }.map { br ->
                    val info = commits[br.sha] ?: when (val c = reader.commit(t.project, br.sha)) {
                        is Outcome.Failure -> return c
                        is Outcome.Success -> c.value.also { commits[br.sha] = it }
                    }
                    br to info
                }
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
            val doc: Outcome<ProcessDocument> = when (t.kind) {
                SourceKind.DECISIONS -> when (val rx = t.project.decisions!!.regex()) {
                    is Outcome.Failure -> rx
                    is Outcome.Success -> DecisionLog.parse(got.value, rx.value, "${t.project.name}:$path")
                }
                SourceKind.TODO -> TodoList.parse(got.value, "${t.project.name}:$path")
                SourceKind.ROADMAP -> Roadmap.parse(got.value, "${t.project.name}:$path")
                SourceKind.README -> StatusBlocks.parse(got.value, "${t.project.name}:$path")
                else -> error("не файл: ${t.kind}")
            }
            return doc.map { d ->
                JsonObject(LinkedHashMap<String, JsonValue>(d.toJson().fields).apply {
                    put("schema", JsonString(d.schema))
                    put("path", JsonString(path))
                }) to got.rawSha256
            }
        }
        return last ?: Outcome.failure(DiagCode.INTERNAL_ERROR, "у задачи нет путей", t.project.name)
    }

    private fun pullsJson(pulls: List<PullRequest>): JsonObject = JsonObject.of(
        "pulls" to JsonArray(
            pulls.sortedBy { it.number }.map { p ->
                JsonObject.of(
                    "number" to JsonNumber.of(p.number),
                    "title" to JsonString(p.title),
                    "draft" to JsonBool(p.draft),
                    "head" to JsonString(p.head),
                    "author" to JsonString(p.author),
                    "updatedAt" to JsonNumber.of(p.updatedAt.epochMillis),
                )
            },
        ),
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
