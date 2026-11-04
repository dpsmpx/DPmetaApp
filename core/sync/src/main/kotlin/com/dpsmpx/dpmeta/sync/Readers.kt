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
import com.dpsmpx.dpmeta.model.ProjectEntry
import com.dpsmpx.dpmeta.model.RepoRef
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

/**
 * GitHub вживую. Подсистема читается из репозитория DPmetaApp, из своего
 * каталога; её PR и ветки — общие с DPmetaApp, поэтому «кто работает» у
 * подсистемы не выводится (неизвестно, а не «свободен»).
 */
class GitHubReader(private val gh: GitHub, private val host: RepoRef) : SourceReader {
    override val source: ObservationSource = ObservationSource.GITHUB

    override fun hasActivity(project: ProjectEntry): Boolean = project.dir == null

    override fun pulls(project: ProjectEntry): Outcome<Got<List<PullRequest>>> = when (val r = gh.pulls(project.slug)) {
        is Outcome.Failure -> r
        is Outcome.Success -> Outcome.success(Got(r.value.value, r.value.fetched.rawSha256, r.value.fetched.notModified))
    }

    override fun closed(project: ProjectEntry): Outcome<Got<List<PullRequest>>> = when (val r = gh.closedPulls(project.slug)) {
        is Outcome.Failure -> r
        is Outcome.Success -> Outcome.success(Got(r.value.value, r.value.fetched.rawSha256, r.value.fetched.notModified))
    }

    override fun branches(project: ProjectEntry): Outcome<Got<List<Branch>>> = when (val r = gh.branches(project.slug)) {
        is Outcome.Failure -> r
        is Outcome.Success -> Outcome.success(Got(r.value.value, r.value.fetched.rawSha256, r.value.fetched.notModified))
    }

    override fun commit(project: ProjectEntry, sha: String): Outcome<CommitInfo> = gh.commit(project.slug, sha)

    /** Где лежат файлы проекта: репозиторий и каталог в нём (подсистема — в DPmetaApp). */
    fun where(project: ProjectEntry): Pair<RepoRef, String> = project.dir?.let { host to "$it/" } ?: (project.slug to "")

    override fun file(project: ProjectEntry, path: String): Outcome<Got<String>> {
        val (repo, prefix) = where(project)
        val r = gh.file(repo, prefix + path)
        return when (r) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(Got(r.value.value, r.value.fetched.rawSha256, r.value.fetched.notModified))
        }
    }
}

/**
 * Каталог на диске: синтетический корпус (`dpmeta-cli sync --corpus`) или
 * клоны репозиториев (`--clones`). Каталог проекта — `clone` из реестра,
 * иначе имя; подсистема — в каталоге клона DPmetaApp. PR и ветки — из
 * `pulls.json` и `branches.json` каталога проекта, если они есть (корпус):
 * у клонов их нет, и «кто работает» неизвестно.
 */
class DirectoryReader(private val root: Path, private val hostDir: String = "dpmetaapp") : SourceReader {
    override val source: ObservationSource = ObservationSource.DIRECTORY
    private val json = JsonParser()

    private fun dir(project: ProjectEntry): Path = project.dir?.let { root.resolve(hostDir).resolve(it) }
        ?: root.resolve(project.clone ?: project.name)

    override fun hasActivity(project: ProjectEntry): Boolean = Files.isRegularFile(dir(project).resolve(PULLS))

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

    private fun array(project: ProjectEntry, name: String): Outcome<Pair<List<JsonObject>, ByteArray>> {
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

    private fun bad(project: ProjectEntry, name: String) = Outcome.failure(DiagCode.FORMAT_UNRECOGNIZED, "ожидался массив объектов", "${project.name}:$name")

    private fun time(o: JsonObject, k: String): Timestamp? = (o[k] as? JsonString)?.value?.let {
        try {
            Timestamp(Instant.parse(it).toEpochMilli())
        } catch (e: DateTimeParseException) {
            null
        }
    }

    override fun pulls(project: ProjectEntry): Outcome<Got<List<PullRequest>>> = pullsFrom(project, PULLS)

    /** `merged.json` — те же поля и `mergedAt`; нет файла — пусто (в корпусе не у всех). */
    override fun closed(project: ProjectEntry): Outcome<Got<List<PullRequest>>> =
        if (Files.isRegularFile(dir(project).resolve(MERGED))) pullsFrom(project, MERGED) else Outcome.success(Got(emptyList(), Hashing.sha256(ByteArray(0))))

    private fun pullsFrom(project: ProjectEntry, name: String): Outcome<Got<List<PullRequest>>> {
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
            )
        }
        return Outcome.success(Got(out, Hashing.sha256(bytes)))
    }

    private fun commits(project: ProjectEntry): Outcome<Pair<List<Pair<Branch, CommitInfo>>, ByteArray>> {
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

    override fun branches(project: ProjectEntry): Outcome<Got<List<Branch>>> = when (val r = commits(project)) {
        is Outcome.Failure -> r
        is Outcome.Success -> Outcome.success(Got(r.value.first.map { it.first }, Hashing.sha256(r.value.second)))
    }

    override fun commit(project: ProjectEntry, sha: String): Outcome<CommitInfo> = when (val r = commits(project)) {
        is Outcome.Failure -> r
        is Outcome.Success -> r.value.first.firstOrNull { it.second.sha == sha }?.second?.let { Outcome.success(it) }
            ?: Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "коммита нет в branches.json", "${project.name}@$sha")
    }

    override fun file(project: ProjectEntry, path: String): Outcome<Got<String>> {
        checkPath(path).let { if (it is Outcome.Failure) return it }
        return when (val r = read(dir(project).resolve(path), "${project.name}:$path")) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(Got(r.value.first, Hashing.sha256(r.value.second)))
        }
    }

    companion object {
        const val PULLS = "pulls.json"
        const val MERGED = "merged.json"
        const val BRANCHES = "branches.json"
    }
}
