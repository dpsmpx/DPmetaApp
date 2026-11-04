package com.dpsmpx.dpmeta.git

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.net.AllowList
import com.dpsmpx.dpmeta.net.HttpRequest
import com.dpsmpx.dpmeta.net.Secrets
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.HttpTransport
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.net.URL

/**
 * Локальные клоны репозиториев (D-65): полная история основной и рабочих
 * веток, ветки сборок — мимо ([sync], `excluded`). Есть клон — обновление,
 * нет — клонирование. Данные для приложения читаются из веток GitHub в клоне
 * (`refs/remotes/origin/…`), а не из рабочего дерева: правки ИИ в клоне
 * числа не меняют. Клон, разошедшийся с GitHub, сам не чинится и не
 * перезаписывается — расхождение возвращается [Conflict]ом.
 *
 * Сеть — только через [gate] (список адресов `:core:net`, D-10): адрес
 * проверяется до любого обращения JGit. Токен — PAT только для чтения (D-17):
 * в адрес и журнал не попадает, раскрывается только для JGit на время вызова.
 */
class Clones(private val root: File, private val gate: Gate, private val schemes: Set<String> = setOf("https")) {
    init {
        guard(gate)
    }

    /** Пропуск в сеть для адреса репозитория; отказ — без обращения к сети. */
    fun interface Gate {
        fun check(remote: URI): Outcome<Unit>
    }

    enum class Action { CLONED, FETCHED }

    /** Расхождение клона с GitHub (D-65): видно в «Ошибках источников», клон не трогается. */
    enum class ConflictKind(val title: String) {
        DIRTY("незакоммиченные правки в клоне"),
        AHEAD("коммиты клона, которых нет на GitHub"),
        REWRITTEN("история ветки на GitHub перезаписана"),
        LOCAL_ONLY("ветка есть только в клоне"),
    }

    data class Conflict(val kind: ConflictKind, val branch: String) {
        val message: String get() = "${kind.title}: $branch"
    }

    /** Итог синхронизации клона: что сделано, основная ветка, ветки GitHub в клоне, конфликты. */
    data class Report(val project: String, val action: Action, val main: String, val branches: List<String>, val conflicts: List<Conflict>)

    /** Голова ветки GitHub в клоне: SHA, время коммита (коммиттера), автор коммита. */
    data class Head(val name: String, val sha: String, val time: Timestamp, val author: String?)

    fun dir(project: String): File = File(root, safe(project))

    /**
     * Клонировать или обновить клон [project] из [remote]. [excluded] —
     * ветки, которые не берутся (сборки); основная ветка не исключается
     * никогда. Основная ветка рабочего дерева продвигается только
     * перемоткой и только при чистом дереве; иначе — конфликт.
     */
    fun sync(project: String, remote: URI, token: Secret?, excluded: (String) -> Boolean): Outcome<Report> {
        // Только https (D-10): у git://, file: и локальных путей JGit свои сокеты — мимо пропуска HTTP.
        if (remote.scheme?.lowercase() !in schemes) return failure(DiagCode.HOST_NOT_ALLOWED, "для клона разрешён только https", remote.host ?: project)
        gate.check(remote).let { if (it is Outcome.Failure) return it }
        val dir = dir(project)
        val creds = token?.let { UsernamePasswordCredentialsProvider("x-access-token", Secrets.gitPassword(it)) }
        return try {
            if (File(dir, ".git").isDirectory) fetch(project, remote, dir, creds, excluded) else clone(project, remote, dir, creds, excluded)
        } catch (e: InvalidRemoteException) {
            failure(DiagCode.GITHUB_NOT_FOUND, "репозиторий не найден или скрыт от токена", project)
        } catch (e: TransportException) {
            transport(e, project)
        } catch (e: GitAPIException) {
            failure(DiagCode.STORAGE_IO_FAILED, "git: ${e.javaClass.simpleName}", project)
        } catch (e: IOException) {
            failure(DiagCode.STORAGE_IO_FAILED, "клон не записан: ${e.javaClass.simpleName}", project)
        } catch (e: JGitInternalException) {
            // Внутренний отказ JGit (ввод-вывод, нет места) — RuntimeException: ожидаемый отказ, а не падение процесса.
            failure(DiagCode.STORAGE_IO_FAILED, "git: ${e.javaClass.simpleName}", project)
        } finally {
            creds?.clear()
        }
    }

    private fun clone(project: String, remote: URI, dir: File, creds: CredentialsProvider?, excluded: (String) -> Boolean): Outcome<Report> {
        // Без setHeads: нужен и HEAD — основная ветка GitHub.
        val refs = Git.lsRemoteRepository().setRemote(remote.toString()).setTags(false).setCredentialsProvider(creds).callAsMap()
        val head = refs[Constants.HEAD]?.target?.name?.removePrefix(Constants.R_HEADS)
        val heads = refs.keys.filter { it.startsWith(Constants.R_HEADS) }.map { it.removePrefix(Constants.R_HEADS) }
        val main = head?.takeIf { it in heads } ?: heads.firstOrNull { it == "main" } ?: heads.firstOrNull() ?: return failure(DiagCode.GITHUB_NOT_FOUND, "в репозитории нет веток", project)
        val keep = heads.filter { it == main || !excluded(it) }.sorted()
        val tmp = File(dir.parentFile, ".${dir.name}.part").also { it.deleteRecursively() }
        dir.parentFile.mkdirs()
        Git.cloneRepository()
            .setURI(remote.toString())
            .setDirectory(tmp)
            .setCloneAllBranches(false)
            .setBranchesToClone(keep.map { Constants.R_HEADS + it })
            .setBranch(Constants.R_HEADS + main)
            .setCredentialsProvider(creds)
            .call()
            .use { git -> git.repository.config.apply { setString("dpmeta", null, "main", main) }.save() }
        // Клон появляется целиком: прерванное клонирование не оставляет полуклона под именем проекта.
        if (!tmp.renameTo(dir)) throw IOException("rename")
        return Outcome.success(Report(project, Action.CLONED, main, keep, emptyList()))
    }

    private fun fetch(project: String, remote: URI, dir: File, creds: CredentialsProvider?, excluded: (String) -> Boolean): Outcome<Report> = open(dir).use { repo ->
        Git(repo).use { git ->
            val main = repo.config.getString("dpmeta", null, "main") ?: repo.branch
            // Адрес — тот, что прошёл пропуск: переименованный в реестре репозиторий не тянется со старого адреса.
            repo.config.apply { setString("remote", "origin", "url", remote.toString()) }.save()
            val refs = Git.lsRemoteRepository().setRemote(remote.toString()).setHeads(true).setCredentialsProvider(creds).call()
                .map { it.name.removePrefix(Constants.R_HEADS) }
            val keep = refs.filter { it == main || !excluded(it) }.sorted()
            val before = keep.associateWith { repo.resolve(Constants.R_REMOTES + "origin/" + it) }
            git.fetch()
                .setRemote("origin")
                .setRefSpecs(keep.map { RefSpec("+${Constants.R_HEADS}$it:${Constants.R_REMOTES}origin/$it") })
                .setCredentialsProvider(creds)
                .call()
            // Ветки, которых на GitHub больше нет или которые стали исключёнными, — мимо.
            for (r in repo.refDatabase.getRefsByPrefix(Constants.R_REMOTES + "origin/")) {
                val name = r.name.removePrefix(Constants.R_REMOTES + "origin/")
                if (name !in keep) repo.updateRef(r.name).apply { isForceUpdate = true }.delete()
            }
            val conflicts = conflicts(repo, git, keep, before)
            if (conflicts.none { it.branch == main } && repo.branch == main) {
                val target = repo.resolve(Constants.R_REMOTES + "origin/" + main)
                if (target != null && target != repo.resolve(Constants.R_HEADS + main)) {
                    git.merge().include(target).setFastForward(MergeCommand.FastForwardMode.FF_ONLY).call()
                }
            }
            Outcome.success(Report(project, Action.FETCHED, main, keep, conflicts))
        }
    }

    /**
     * Расхождения: незакоммиченное в дереве; локальная ветка с коммитами,
     * которых нет на GitHub; ветка GitHub, история которой перезаписана
     * (прежняя голова больше не её предок); ветка только в клоне.
     */
    private fun conflicts(repo: Repository, git: Git, keep: List<String>, before: Map<String, ObjectId?>): List<Conflict> {
        val out = mutableListOf<Conflict>()
        if (git.status().call().hasUncommittedChanges()) out += Conflict(ConflictKind.DIRTY, repo.branch)
        RevWalk(repo).use { walk ->
            fun ancestor(a: ObjectId, b: ObjectId): Boolean = try {
                walk.reset()
                walk.isMergedInto(walk.parseCommit(a), walk.parseCommit(b))
            } catch (e: MissingObjectException) {
                false
            }
            for (name in keep) {
                val old = before[name] ?: continue
                val now = repo.resolve(Constants.R_REMOTES + "origin/" + name) ?: continue
                if (old != now && !ancestor(old, now)) out += Conflict(ConflictKind.REWRITTEN, name)
            }
            for (r in repo.refDatabase.getRefsByPrefix(Constants.R_HEADS)) {
                val name = r.name.removePrefix(Constants.R_HEADS)
                val upstream = repo.resolve(Constants.R_REMOTES + "origin/" + name)
                when {
                    upstream == null -> out += Conflict(ConflictKind.LOCAL_ONLY, name)
                    r.objectId != upstream && !ancestor(r.objectId, upstream) -> out += Conflict(ConflictKind.AHEAD, name)
                }
            }
        }
        return out.distinct()
    }

    /** Основная ветка клона (GitHub `HEAD` при клонировании); клона нет — `null`. */
    fun main(project: String): String? {
        val dir = dir(project)
        if (!File(dir, ".git").isDirectory) return null
        return open(dir).use { it.config.getString("dpmeta", null, "main") ?: it.branch }
    }

    /** Ветки GitHub в клоне с головами; клона нет — `ENTITY_NOT_FOUND`. */
    fun heads(project: String): Outcome<List<Head>> = withRepo(project) { repo ->
        RevWalk(repo).use { walk ->
            repo.refDatabase.getRefsByPrefix(Constants.R_REMOTES + "origin/").sortedBy { it.name }.map { r ->
                val c: RevCommit = walk.parseCommit(r.objectId)
                Head(r.name.removePrefix(Constants.R_REMOTES + "origin/"), c.name, Timestamp(c.commitTime * 1000L), c.authorIdent?.name)
            }
        }
    }

    /** Файл ветки GitHub [branch] по пути от корня; нет файла — `null`, нет клона или ветки — отказ. */
    fun file(project: String, branch: String, path: String): Outcome<ByteArray?> = withRepo(project) { repo ->
        val tree = repo.resolve("${Constants.R_REMOTES}origin/$branch^{tree}") ?: return@withRepo null
        TreeWalk.forPath(repo, path, tree)?.use { tw -> repo.open(tw.getObjectId(0)).bytes }
    }

    private fun <T> withRepo(project: String, work: (Repository) -> T): Outcome<T> {
        val dir = dir(project)
        if (!File(dir, ".git").isDirectory) return failure(DiagCode.ENTITY_NOT_FOUND, "клона ещё нет", project)
        return try {
            open(dir).use { Outcome.success(work(it)) }
        } catch (e: IOException) {
            failure(DiagCode.STORAGE_IO_FAILED, "клон не читается: ${e.javaClass.simpleName}", project)
        } catch (e: JGitInternalException) {
            failure(DiagCode.STORAGE_IO_FAILED, "клон не читается: ${e.javaClass.simpleName}", project)
        }
    }

    private fun open(dir: File): Repository = FileRepositoryBuilder().setGitDir(File(dir, ".git")).setMustExist(true).build()

    private fun transport(e: TransportException, project: String): Outcome<Nothing> {
        val text = e.message.orEmpty().lowercase()
        if (generateSequence<Throwable>(e) { it.cause }.any { it is GateRefused }) {
            return failure(DiagCode.HOST_NOT_ALLOWED, "переадресация на адрес не из списка разрешённых", project)
        }
        return when {
            "not authorized" in text || "authentication" in text || "401" in text -> failure(DiagCode.GITHUB_AUTH_EXPIRED, "токен не подошёл для git", project)
            "not found" in text || "404" in text -> failure(DiagCode.GITHUB_NOT_FOUND, "репозиторий не найден или скрыт от токена", project)
            else -> failure(DiagCode.NETWORK_UNREACHABLE, "git: нет связи с GitHub", project)
        }
    }

    private fun failure(code: DiagCode, message: String, at: String): Outcome<Nothing> = Outcome.failure(code, message, at)

    /** Отказ пропуска внутри JGit — узнаётся по цепочке причин и показывается как `HOST_NOT_ALLOWED`. */
    private class GateRefused(message: String) : IOException(message)

    /**
     * Каждое HTTP-соединение JGit — и переадресации — через пропуск: адрес не
     * из списка не открывается, даже если на него переадресовал сервер (D-10).
     */
    private class GatedFactory(private val inner: HttpConnectionFactory) : HttpConnectionFactory {
        override fun create(url: URL): HttpConnection = create(url, null)

        override fun create(url: URL, proxy: Proxy?): HttpConnection {
            val g = current ?: throw GateRefused("сеть git закрыта")
            if (url.protocol.lowercase() != "https") throw GateRefused("разрешён только https: ${url.host}")
            val ok = runCatching { g.check(url.toURI()) }.getOrNull()
            if (ok !is Outcome.Success) throw GateRefused("адреса нет в списке разрешённых: ${url.host}")
            return if (proxy == null) inner.create(url) else inner.create(url, proxy)
        }
    }

    /**
     * Настройки git — только свои, в клоне: пользовательский и системный
     * `gitconfig` не читаются (на телефоне их нет, в среде разработки они
     * чужие — подпись, помощники входа, прокси).
     */
    private class Isolated(private val inner: SystemReader) : SystemReader() {
        override fun getHostname(): String = inner.hostname

        override fun getenv(variable: String?): String? = inner.getenv(variable)

        override fun getProperty(key: String?): String? = inner.getProperty(key)

        override fun getCurrentTime(): Long = inner.currentTime

        override fun getTimezone(time: Long): Int = inner.getTimezone(time)

        private fun empty(parent: Config?, fs: FS): FileBasedConfig = object : FileBasedConfig(parent, null, fs) {
            override fun load() = Unit

            override fun isOutdated(): Boolean = false
        }

        override fun openUserConfig(parent: Config?, fs: FS): FileBasedConfig = empty(parent, fs)

        override fun openSystemConfig(parent: Config?, fs: FS): FileBasedConfig = empty(parent, fs)

        override fun openJGitConfig(parent: Config?, fs: FS): FileBasedConfig = empty(parent, fs)
    }

    companion object {
        @Volatile
        private var current: Gate? = null

        @Synchronized
        private fun guard(gate: Gate) {
            if (current == null) {
                HttpTransport.setConnectionFactory(GatedFactory(HttpTransport.getConnectionFactory()))
                isolate()
            }
            current = gate
        }

        /** JGit — без чужих настроек git (см. [Isolated]); повторный вызов ничего не меняет. */
        @Synchronized
        fun isolate() {
            val r = SystemReader.getInstance()
            if (r !is Isolated) SystemReader.setInstance(Isolated(r))
        }

        /**
         * Каталог клона — однозначное имя без знаков, опасных для пути: всё,
         * кроме латиницы, цифр, «.» и «-», — `_` и код байта (`DP AIM Trainer` →
         * `DP_20AIM_20Trainer`, `DP_AIM` → `DP_5fAIM`); `.` и `..` — тоже кодом.
         */
        fun safe(project: String): String {
            val out = project.toByteArray(Charsets.UTF_8).joinToString("") { b ->
                val c = (b.toInt() and 0xFF).toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-') c.toString() else "_%02x".format(b.toInt() and 0xFF)
            }
            return if (out.isEmpty() || out.all { it == '.' }) out.replace(".", "_2e").ifEmpty { "_" } else out
        }

        /** Пропуск по списку адресов `:core:net` (D-10): тот же, что у запросов REST. */
        fun allowList(list: AllowList = AllowList.CORE): Gate = Gate { uri -> list.check(HttpRequest("GET", uri)) }

        /** Адрес клона на GitHub: только `https://github.com/<владелец>/<имя>.git`, токен — не в адресе. */
        fun github(slug: String): URI = URI("https://github.com/$slug.git")
    }
}
