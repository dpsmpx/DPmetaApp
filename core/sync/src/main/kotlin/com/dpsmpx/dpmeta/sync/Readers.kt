package com.dpsmpx.dpmeta.sync

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpmeta.github.Branch
import com.dpsmpx.dpmeta.github.CommitInfo
import com.dpsmpx.dpmeta.github.GitHub
import com.dpsmpx.dpmeta.github.PullRequest
import com.dpsmpx.dpmeta.model.ObservationSource
import com.dpsmpx.dpmeta.model.SyncProject
import com.dpsmpx.dpai.core.git.RepoRef
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException

private fun checkPath(path: String): Outcome<Unit> =
    if (path.isEmpty() || path.startsWith("/") || path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
        Outcome.failure(DiagCode.VALUE_INVALID, "путь файла — относительный, без «..»", path)
    } else {
        Outcome.success(Unit)
    }

/** GitHub проекта пользователя: все источники читаются из его собственного репозитория. */
class GitHubReader(private val gh: GitHub) : SourceReader {
    override val source: ObservationSource = ObservationSource.GITHUB

    override fun hasActivity(project: SyncProject): Boolean = true

    override fun pulls(project: SyncProject): Outcome<Got<List<PullRequest>>> = when (val r = gh.pulls(project.repo)) {
        is Outcome.Failure -> r
        is Outcome.Success -> Outcome.success(Got(r.value.value, r.value.fetched.rawSha256, r.value.fetched.notModified))
    }

    override fun closed(project: SyncProject): Outcome<Got<List<PullRequest>>> = when (val r = gh.closedPulls(project.repo)) {
        is Outcome.Failure -> r
        is Outcome.Success -> Outcome.success(Got(r.value.value, r.value.fetched.rawSha256, r.value.fetched.notModified))
    }

    override fun branches(project: SyncProject): Outcome<Got<List<Branch>>> = when (val r = gh.branches(project.repo)) {
        is Outcome.Failure -> r
        is Outcome.Success -> Outcome.success(Got(r.value.value, r.value.fetched.rawSha256, r.value.fetched.notModified))
    }

    override fun commit(project: SyncProject, sha: String): Outcome<CommitInfo> = gh.commit(project.repo, sha)

    // Issues, коммиты и CI (D-176) — у репозитория проекта; у подсистемы их нет (они у DPmetaApp).
    override fun issues(project: SyncProject): Outcome<Got<List<com.dpsmpx.dpmeta.github.Issue>>>? =
        gh.issues(project.repo).map { Got(it.value, it.fetched.rawSha256, it.fetched.notModified) }

    override fun recentCommits(project: SyncProject): Outcome<Got<List<com.dpsmpx.dpmeta.github.RecentCommit>>>? =
        gh.recentCommits(project.repo).map { Got(it.value, it.fetched.rawSha256, it.fetched.notModified) }

    override fun ci(project: SyncProject, sha: String): Outcome<Got<com.dpsmpx.dpmeta.github.CiState>>? =
        gh.ci(project.repo, sha).map { Got(it.value, it.fetched.rawSha256) }

    private inline fun <T, R> Outcome<T>.map(f: (T) -> R): Outcome<R> = when (this) {
        is Outcome.Failure -> this
        is Outcome.Success -> Outcome.success(f(value), diagnostics)
    }

    /** Собственный репозиторий проекта; префиксов подсистем больше нет (D-176). */
    fun where(project: SyncProject): Pair<RepoRef, String> = project.repo to ""

    override fun file(project: SyncProject, path: String): Outcome<Got<String>> {
        val (repo, prefix) = where(project)
        val r = gh.file(repo, prefix + path)
        return when (r) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(Got(r.value.value, r.value.fetched.rawSha256, r.value.fetched.notModified))
        }
    }
}

/**
 * Файловый источник для синтетического корпуса: каталог `владелец/репозиторий`
 * в нижнем регистре. PR и ветки читаются из pulls.json/branches.json, если они есть.
 * Имена проектов не задают каталоги, ролей и перенаправлений нет (D-176).
 */
class DirectoryReader(private val root: Path) : SourceReader {
    override val source: ObservationSource = ObservationSource.DIRECTORY
    private val json = JsonParser()

    private fun dir(project: SyncProject): Path = root.resolve(project.repo.owner.lowercase()).resolve(project.repo.name.lowercase())

    override fun hasActivity(project: SyncProject): Boolean = Files.isRegularFile(dir(project).resolve(PULLS))

    private fun read(path: Path, subject: String): Outcome<Pair<String, ByteArray>> = try {
        if (!Files.isRegularFile(path)) {
            Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "файла нет", subject)
        } else {
            val bytes = Files.readAllBytes(path)
            Outcome.success(bytes.toString(Charsets.UTF_8) to bytes)
        }
    } catch (e: IOException) {
        Outcome.failure(DiagCode.STORAGE_IO_FAILED, "файл не читается: ${e.javaClass.simpleName}", subject)
    }

    private fun array(project: SyncProject, name: String): Outcome<Pair<List<JsonObject>, ByteArray>> {
        val (text, bytes) = when (val r = read(dir(project).resolve(name), "${project.name}:$name")) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val v = when (val p = json.parse(text)) {
            is Outcome.Failure -> return p
            is Outcome.Success -> p.value
        }
        val items = (v as? JsonArray)?.items?.map { it as? JsonObject ?: return bad(project, name) } ?: return bad(project, name)
        return Outcome.success(items to bytes)
    }

    private fun bad(project: SyncProject, name: String) = Outcome.failure(DiagCode.FORMAT_UNRECOGNIZED, "ожидался массив объектов", "${project.name}:$name")

    private fun time(o: JsonObject, k: String): Timestamp? = (o[k] as? JsonString)?.value?.let {
        try {
            Timestamp(Instant.parse(it).toEpochMilli())
        } catch (e: DateTimeParseException) {
            null
        }
    }

    override fun pulls(project: SyncProject): Outcome<Got<List<PullRequest>>> = pullsFrom(project, PULLS)

    /** `merged.json` — те же поля и `mergedAt`; нет файла — пусто (в корпусе не у всех). */
    override fun closed(project: SyncProject): Outcome<Got<List<PullRequest>>> =
        if (Files.isRegularFile(dir(project).resolve(MERGED))) pullsFrom(project, MERGED) else Outcome.success(Got(emptyList(), Hashing.sha256(ByteArray(0))))

    private fun pullsFrom(project: SyncProject, name: String): Outcome<Got<List<PullRequest>>> {
        val (items, bytes) = when (val r = array(project, name)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val out = items.map { o ->
            PullRequest(
                (o["number"] as? JsonNumber)?.toLongExact()?.toInt() ?: return bad(project, name),
                (o["title"] as? JsonString)?.value ?: return bad(project, name),
                (o["draft"] as? JsonBool)?.value ?: false,
                (o["head"] as? JsonString)?.value ?: return bad(project, name),
                (o["author"] as? JsonString)?.value ?: "?",
                time(o, "updatedAt") ?: return bad(project, name),
                (o["body"] as? JsonString)?.value,
                time(o, "mergedAt"),
                time(o, "createdAt"),
            )
        }
        return Outcome.success(Got(out, Hashing.sha256(bytes)))
    }

    private fun commits(project: SyncProject): Outcome<Pair<List<Pair<Branch, CommitInfo>>, ByteArray>> {
        val (items, bytes) = when (val r = array(project, BRANCHES)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val out = items.map { o ->
            val sha = (o["sha"] as? JsonString)?.value ?: return bad(project, BRANCHES)
            Branch((o["name"] as? JsonString)?.value ?: return bad(project, BRANCHES), sha) to
                CommitInfo(sha, time(o, "date") ?: return bad(project, BRANCHES), (o["author"] as? JsonString)?.value)
        }
        return Outcome.success(out to bytes)
    }

    override fun branches(project: SyncProject): Outcome<Got<List<Branch>>> = when (val r = commits(project)) {
        is Outcome.Failure -> r
        is Outcome.Success -> Outcome.success(Got(r.value.first.map { it.first }, Hashing.sha256(r.value.second)))
    }

    override fun commit(project: SyncProject, sha: String): Outcome<CommitInfo> = when (val r = commits(project)) {
        is Outcome.Failure -> r
        is Outcome.Success -> r.value.first.firstOrNull { it.second.sha == sha }?.second?.let { Outcome.success(it) }
            ?: Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "коммита нет в branches.json", "${project.name}@$sha")
    }

    override fun file(project: SyncProject, path: String): Outcome<Got<String>> {
        checkPath(path).let { if (it is Outcome.Failure) return it }
        return when (val r = read(dir(project).resolve(path), "${project.name}:$path")) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(Got(r.value.first, Hashing.sha256(r.value.second)))
        }
    }

    /** Имена файлов каталога проекта на диске (корпус, клоны); каталога нет — «нет». */
    override fun names(project: SyncProject, dir: String): Outcome<List<String>>? {
        checkPath(dir).let { if (it is Outcome.Failure) return it }
        val d = dir(project).resolve(dir)
        if (!Files.isDirectory(d)) return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "каталога нет", "${project.name}:$dir")
        return try {
            Files.list(d).use { s -> Outcome.success(s.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.sorted().toList()) }
        } catch (e: IOException) {
            Outcome.failure(DiagCode.STORAGE_IO_FAILED, "каталог не читается: ${e.javaClass.simpleName}", "${project.name}:$dir")
        }
    }

    companion object {
        const val PULLS = "pulls.json"
        const val MERGED = "merged.json"
        const val BRANCHES = "branches.json"
    }
}

/**
 * Клоны репозиториев (D-65): ветки, коммиты и файлы процесса — из
 * локального клона, без запросов REST; PR — по-прежнему [rest] (в git их
 * нет). Подсистема читается из клона DPmetaApp ([host]) в своём каталоге.
 * Клона нет или он не отмечен автором ([selected], D-70) — тот же ответ из
 * [rest]: синхронизация не беднеет, а снятый с выбора клон, где может
 * лежать работа ИИ, не читается и не удаляется. Ветки сборок ([excluded])
 * в ветки не попадают.
 */
class CloneReader(
    private val clones: com.dpsmpx.dpmeta.git.Clones,
    private val rest: SourceReader,
    private val excluded: (String) -> Boolean,
    private val history: HistoryRules? = null,
    private val selected: (String) -> Boolean = { true },
) : SourceReader {
    /**
     * История всех авторов (D-176): пояс дней и известные метки ИИ задаёт
     * оболочка из `Rules.kt`; прочие авторы не объявляются людьми.
     * `null` — история не читается.
     */
    data class HistoryRules(val zone: java.time.ZoneOffset, val ai: Set<String>)

    override val source: ObservationSource = rest.source

    /** Головы веток последнего чтения: `commit` по ним не спрашивает ни клон, ни REST. */
    private val heads = java.util.concurrent.ConcurrentHashMap<String, CommitInfo>()

    override fun hasActivity(project: SyncProject): Boolean = rest.hasActivity(project)

    override fun pulls(project: SyncProject): Outcome<Got<List<PullRequest>>> = rest.pulls(project)

    override fun closed(project: SyncProject): Outcome<Got<List<PullRequest>>> = rest.closed(project)

    // Issues, коммиты и CI (D-176) — с GitHub, как PR: в клоне их нет или они не свежие.
    override fun issues(project: SyncProject) = rest.issues(project)

    override fun recentCommits(project: SyncProject) = rest.recentCommits(project)

    override fun ci(project: SyncProject, sha: String) = rest.ci(project, sha)

    override fun branches(project: SyncProject): Outcome<Got<List<Branch>>> {
        // У подсистемы своих веток нет: ветки DPmetaApp — его, как у GitHubReader (hasActivity — нет).
        val key = key(project)
        if (!selected(key) || clones.main(key) == null) {
            return when (val r = rest.branches(project)) {
                is Outcome.Failure -> r
                is Outcome.Success -> Outcome.success(Got(r.value.value.filter { !excluded(it.name) }, r.value.rawSha256, r.value.notModified))
            }
        }
        return when (val r = clones.heads(key)) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                val list = r.value.filter { !excluded(it.name) }
                list.forEach { heads[it.sha] = CommitInfo(it.sha, it.time, it.author) }
                val raw = list.joinToString("\n") { "${it.name} ${it.sha}" }
                Outcome.success(Got(list.map { Branch(it.name, it.sha) }, Hashing.sha256(raw)))
            }
        }
    }

    override fun commit(project: SyncProject, sha: String): Outcome<CommitInfo> = heads[sha]?.let { Outcome.success(it) } ?: rest.commit(project, sha)

    override fun file(project: SyncProject, path: String): Outcome<Got<String>> {
        checkPath(path).let { if (it is Outcome.Failure) return it }
        val key = key(project)
        val main = clones.main(key)?.takeIf { selected(key) } ?: return rest.file(project, path)
        val full = path
        return when (val r = clones.file(key, main, full)) {
            is Outcome.Failure -> r
            is Outcome.Success -> r.value?.let { bytes -> Outcome.success(Got(bytes.toString(Charsets.UTF_8), Hashing.sha256(bytes))) }
                ?: Outcome.failure(DiagCode.GITHUB_NOT_FOUND, "файла нет в клоне: $full", "${project.name}:$path")
        }
    }

    /** История только собственного отмеченного клона; без основной ветки источник пропускается. */
    override fun history(project: SyncProject): Outcome<Got<com.dpsmpx.dpmeta.git.CommitHistory>>? {
        val rules = history ?: return null
        val key = key(project)
        val main = clones.main(key)?.takeIf { selected(key) } ?: return null
        return when (val r = clones.history(key, main, rules.zone, rules.ai, excluded)) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                val json = com.dpsmpx.dpai.core.serialization.CanonicalJson.write(Sync.historyJson(r.value))
                Outcome.success(Got(r.value, Hashing.sha256(json)))
            }
        }
    }

    /** Имена в каталоге [dir] проекта из основной ветки клона; клона нет — у REST каталогов нет (`null`). */
    override fun names(project: SyncProject, dir: String): Outcome<List<String>>? {
        checkPath(dir).let { if (it is Outcome.Failure) return it }
        val key = key(project)
        val main = clones.main(key)?.takeIf { selected(key) } ?: return null
        val full = dir
        return when (val r = clones.list(key, main, full)) {
            is Outcome.Failure -> r
            is Outcome.Success -> r.value?.let { l -> Outcome.success(l.filter { !it.dir }.map { it.name }) }
                ?: Outcome.failure(DiagCode.GITHUB_NOT_FOUND, "каталога нет в клоне: $full", "${project.name}:$dir")
        }
    }

    /** Ключ собственного клона проекта. */
    fun key(project: SyncProject): String = project.repo.toString()
}
