package com.dpsmpx.dpmeta.git

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.net.AllowList
import com.dpsmpx.dpmeta.net.HttpRequest
import com.dpsmpx.dpmeta.net.Secrets
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
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
import java.security.KeyManagementException
import java.security.SecureRandom
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManager
import javax.net.ssl.TrustManager

/**
 * Локальные клоны репозиториев (D-65): полная история основной и рабочих
 * веток, ветки сборок — мимо ([sync], `excluded`). Есть клон — обновление,
 * нет — клонирование. Данные для приложения читаются из веток GitHub в клоне
 * (`refs/remotes/origin/…`), а не из рабочего дерева: правки ИИ в клоне
 * числа не меняют. Рабочее дерево и локальные ветки — работа ИИ (D-70):
 * обновление их не трогает и конфликтом не считает; перезаписанная на
 * GitHub история — сведение [Conflict] (`REWRITTEN`), а не ошибка.
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

    /** Сведение обновления (D-70): история ветки на GitHub перезаписана — клон взял её; локальная работа — не расхождение. */
    enum class ConflictKind(val title: String) {
        REWRITTEN("история ветки на GitHub перезаписана"),
    }

    data class Conflict(val kind: ConflictKind, val branch: String) {
        val message: String get() = "${kind.title}: $branch"
    }

    /** Итог синхронизации клона: что сделано, основная ветка, ветки GitHub в клоне, конфликты. */
    /**
     * Итог клонирования или обновления. [mainSha] — голова основной ветки по
     * ответу GitHub (`ls-remote`), а не по ссылке клона: ссылки клона может
     * переписать Linux-среда (D-72), ответ GitHub — нет
     * (`DP_AI_Orcestrator:D-114`, T19).
     */
    data class Report(val project: String, val action: Action, val main: String, val branches: List<String>, val conflicts: List<Conflict>, val mainSha: String? = null)

    /** Голова ветки GitHub в клоне: SHA, время коммита (коммиттера), автор коммита. */
    data class Head(val name: String, val sha: String, val time: Timestamp, val author: String?)

    /**
     * Каталог клона: `владелец/имя` — вложенными каталогами, имена как на
     * GitHub (D-72: в Linux-среде — `/root/repos/<владелец>/<имя>`, там же,
     * куда клонирует модель). Часть, которой нет в `[A-Za-z0-9._-]` или
     * которая `.` либо `..`, — [safe]; другое имя — целиком [safe].
     */
    fun dir(project: String): File {
        val parts = project.split('/')
        fun part(p: String) = if (PART.matches(p) && p != "." && p != "..") p else safe(p)
        return if (parts.size == 2) File(File(root, part(parts[0])), part(parts[1])) else File(root, safe(project))
    }

    /**
     * Клонировать или обновить клон [project] из [remote]. [excluded] —
     * ветки, которые не берутся (сборки); основная ветка не исключается
     * никогда. Рабочее дерево и локальные ветки не трогаются (D-70).
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
        // Каталог владельца доступен гостю на запись (D-72): прежний «.part» удаляется без перехода по ссылкам.
        val tmp = File(dir.parentFile, ".${dir.name}.part").also { deleteTree(it) }
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
        return Outcome.success(Report(project, Action.CLONED, main, keep, emptyList(), refs[Constants.R_HEADS + main]?.objectId?.name))
    }

    private fun fetch(project: String, remote: URI, dir: File, creds: CredentialsProvider?, excluded: (String) -> Boolean): Outcome<Report> = open(dir).use { repo ->
        Git(repo).use { git ->
            val main = mainOf(repo)
            // Адрес — тот, что прошёл пропуск: переименованный в реестре репозиторий не тянется со старого адреса.
            // Пишется, только если другой: конфиг клона правит и git гостя (D-72) — лишняя запись спорит с ним.
            if (repo.config.getString("remote", "origin", "url") != remote.toString()) {
                repo.config.apply { setString("remote", "origin", "url", remote.toString()) }.save()
            }
            val remoteRefs = Git.lsRemoteRepository().setRemote(remote.toString()).setHeads(true).setCredentialsProvider(creds).call()
            val mainSha = remoteRefs.firstOrNull { it.name == Constants.R_HEADS + main }?.objectId?.name
            val refs = remoteRefs.map { it.name.removePrefix(Constants.R_HEADS) }
            val keep = refs.filter { it == main || !excluded(it) }.sorted()
            val before = keep.associateWith { repo.resolve(Constants.R_REMOTES + "origin/" + it) }
            git.fetch()
                .setRemote("origin")
                .setRefSpecs(keep.map { RefSpec("+${Constants.R_HEADS}$it:${Constants.R_REMOTES}origin/$it") })
                .setCredentialsProvider(creds)
                .call()
            // Ветки, которых на GitHub больше нет или которые стали исключёнными, — мимо. Символьная
            // `origin/HEAD` (её пишет `git clone` гостя) не трогается: удаление по ней сняло бы её цель — `origin/main`.
            for (r in repo.refDatabase.getRefsByPrefix(Constants.R_REMOTES + "origin/")) {
                if (r.isSymbolic) continue
                val name = r.name.removePrefix(Constants.R_REMOTES + "origin/")
                if (name !in keep) repo.updateRef(r.name, true).apply { isForceUpdate = true }.delete()
            }
            // Рабочее дерево и локальные ветки — работа ИИ (D-70): синхронизация их не трогает, только `origin/*`.
            Outcome.success(Report(project, Action.FETCHED, main, keep, rewritten(repo, keep, before), mainSha))
        }
    }

    /**
     * Сведения обновления: ветка GitHub, история которой перезаписана
     * (прежняя голова больше не её предок), — клон взял историю GitHub.
     * Локальная работа в клоне (ветки, коммиты, правки) — не конфликт (D-70).
     */
    private fun rewritten(repo: Repository, keep: List<String>, before: Map<String, ObjectId?>): List<Conflict> {
        val out = mutableListOf<Conflict>()
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
        }
        return out
    }

    /**
     * Основная ветка клона (GitHub `HEAD` при клонировании); клона нет —
     * `null`. Клон, сделанный в Linux-среде моделью (D-70), отметки не
     * имеет: тогда — цель `origin/HEAD`, затем `main`, и только потом текущая
     * ветка (её могла взять работа ИИ).
     */
    fun main(project: String): String? {
        val dir = dir(project)
        if (!File(dir, ".git").isDirectory) return null
        // Испорченный клон (конфиг правит и гость, D-72) — как нет клона: читается REST, а не падение экрана.
        return try {
            open(dir).use(::mainOf)
        } catch (e: IOException) {
            null
        } catch (e: JGitInternalException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Основная ветка — одно правило для чтения и обновления: отметка, цель `origin/HEAD`, `main`, текущая ветка. */
    private fun mainOf(repo: Repository): String {
        // Кандидат годится, только если его ветка GitHub в клоне есть: основную на GitHub могли переименовать.
        fun present(b: String?) = b?.takeIf { repo.exactRef(Constants.R_REMOTES + "origin/" + it) != null }
        return present(repo.config.getString("dpmeta", null, "main"))
            ?: present(repo.exactRef(Constants.R_REMOTES + "origin/HEAD")?.takeIf { it.isSymbolic }?.target?.name?.removePrefix(Constants.R_REMOTES + "origin/"))
            ?: present("main")
            ?: repo.config.getString("dpmeta", null, "main")
            ?: repo.branch
    }

    /** Ветки GitHub в клоне с головами; клона нет — `ENTITY_NOT_FOUND`. Символьная `origin/HEAD` — не ветка. */
    fun heads(project: String): Outcome<List<Head>> = withRepo(project) { repo ->
        RevWalk(repo).use { walk ->
            repo.refDatabase.getRefsByPrefix(Constants.R_REMOTES + "origin/").filter { !it.isSymbolic }.sortedBy { it.name }.map { r ->
                val c: RevCommit = walk.parseCommit(r.objectId)
                Head(r.name.removePrefix(Constants.R_REMOTES + "origin/"), c.name, Timestamp(c.commitTime * 1000L), c.authorIdent?.name)
            }
        }
    }

    /**
     * Последний коммит ветки GitHub [branch], менявший [path] (пусто — весь
     * репозиторий, иначе — каталог подсистемы): короткий хеш и дата коммита в
     * поясе коммитившего, `ГГГГ-ММ-ДД` — как `git log -1 --format='%h %cs'`
     * у `DPmeta:tools/manifest.py` (`DP_AI_Orcestrator:D-114`); нет — `null`.
     */
    fun last(project: String, branch: String, path: String = ""): Outcome<Pair<String, String>?> = withRepo(project) { repo ->
        val head = repo.resolve("${Constants.R_REMOTES}origin/$branch") ?: return@withRepo null
        RevWalk(repo).use { walk ->
            walk.markStart(walk.parseCommit(head))
            val p = path.trim('/')
            if (p.isNotEmpty()) walk.treeFilter = org.eclipse.jgit.treewalk.filter.AndTreeFilter.create(org.eclipse.jgit.treewalk.filter.PathFilter.create(p), org.eclipse.jgit.treewalk.filter.TreeFilter.ANY_DIFF)
            val c = walk.next() ?: return@use null
            val zone = c.committerIdent?.timeZone?.toZoneId() ?: java.time.ZoneOffset.UTC
            c.abbreviate(7).name() to java.time.Instant.ofEpochSecond(c.commitTime.toLong()).atZone(zone).toLocalDate().toString()
        }
    }

    /**
     * Файл на коммите [commit] (полный SHA) — с проверкой содержимого
     * (`DP_AI_Orcestrator:D-114`, T19): коммит, каждое дерево по пути и
     * сам файл читаются по одному, и у каждого id пересчитывается по байтам.
     * JGit при чтении хеш не сверяет, а гость Linux может подложить в клон
     * свой объект под доверенный id — тогда отказ, а не чужое содержимое.
     * Нет файла (или это каталог) — `null`; нет коммита или объект подменён —
     * отказ.
     */
    fun fileAt(project: String, commit: String, path: String): Outcome<ByteArray?> {
        if (!Regex("[0-9a-f]{40}").matches(commit)) return failure(DiagCode.VALUE_INVALID, "не SHA коммита", project)
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Outcome.success(null)
        return withRepo(project) { repo ->
            val commitBytes = verified(repo, ObjectId.fromString(commit), Constants.OBJ_COMMIT)
            var tree: ObjectId = RevCommit.parse(commitBytes).tree.copy()
            for ((i, part) in parts.withIndex()) {
                val parser = org.eclipse.jgit.treewalk.CanonicalTreeParser()
                parser.reset(verified(repo, tree, Constants.OBJ_TREE))
                var found: Pair<ObjectId, org.eclipse.jgit.lib.FileMode>? = null
                while (!parser.eof()) {
                    if (parser.entryPathString == part) {
                        found = parser.entryObjectId.copy() to parser.entryFileMode
                        break
                    }
                    parser.next()
                }
                val (id, mode) = found ?: return@withRepo null
                val last = i == parts.lastIndex
                when {
                    !last && mode == org.eclipse.jgit.lib.FileMode.TREE -> tree = id
                    // Каталог, подмодуль или путь сквозь файл — не файл.
                    !last || mode == org.eclipse.jgit.lib.FileMode.TREE || mode == org.eclipse.jgit.lib.FileMode.GITLINK -> return@withRepo null
                    else -> return@withRepo verified(repo, id, Constants.OBJ_BLOB)
                }
            }
            null
        }
    }

    /** Байты объекта [id] вида [type], если id совпадает с хешем содержимого; иначе — `CorruptObjectException`. */
    private fun verified(repo: Repository, id: ObjectId, type: Int): ByteArray {
        val bytes = repo.open(id, type).bytes
        val actual = ObjectInserter.Formatter().idFor(type, bytes)
        if (actual != id) throw org.eclipse.jgit.errors.CorruptObjectException(id, "содержимое не совпадает с id")
        return bytes
    }

    /** Файл ветки GitHub [branch] по пути от корня; нет файла (или это каталог) — `null`, нет клона или ветки — отказ. */
    fun file(project: String, branch: String, path: String): Outcome<ByteArray?> = withRepo(project) { repo ->
        val tree = repo.resolve("${Constants.R_REMOTES}origin/$branch^{tree}") ?: return@withRepo null
        // Каталог — не файл: байты объекта-дерева на экран не идут (проверка PR #173).
        TreeWalk.forPath(repo, path, tree)?.use { tw -> if (tw.isSubtree) null else repo.open(tw.getObjectId(0)).bytes }
    }

    /** Размер файла ветки [branch] без чтения его в память; нет файла — `null`. */
    fun size(project: String, branch: String, path: String): Outcome<Long?> = withRepo(project) { repo ->
        val tree = repo.resolve("${Constants.R_REMOTES}origin/$branch^{tree}") ?: return@withRepo null
        TreeWalk.forPath(repo, path, tree)?.use { tw -> if (tw.isSubtree) null else repo.open(tw.getObjectId(0)).size }
    }

    /** Строка каталога клона: имя и вид; каталоги — первыми, как в REST (F-09). */
    data class Entry(val name: String, val dir: Boolean)

    /**
     * Каталог ветки GitHub [branch] по пути от корня ([path] пустой — корень),
     * без сети и токена (UX-9: пульт читает склонированное из клона); нет
     * такого каталога — `null`, нет клона или ветки — отказ. Каталоги — первыми,
     * затем по имени.
     */
    fun list(project: String, branch: String, path: String): Outcome<List<Entry>?> = withRepo(project) { repo ->
        val tree = repo.resolve("${Constants.R_REMOTES}origin/$branch^{tree}") ?: return@withRepo null
        val at = if (path.isEmpty()) {
            tree
        } else {
            TreeWalk.forPath(repo, path, tree)?.use { tw -> if (tw.isSubtree) tw.getObjectId(0) else null } ?: return@withRepo null
        }
        TreeWalk(repo).use { tw ->
            tw.addTree(at)
            tw.isRecursive = false
            buildList { while (tw.next()) add(Entry(tw.nameString, tw.isSubtree)) }
        }.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
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
            return Verified(if (proxy == null) inner.create(url) else inner.create(url, proxy))
        }
    }

    /**
     * Соединение JGit без права отключить проверку TLS: свой `TrustManager`
     * и `HostnameVerifier` JGit ставит только при `http.sslVerify=false`
     * (`HttpSupport.disableSslVerify` — пустой `X509TrustManager`, его видит
     * Lint). Настройки git клоны и так не читают ([Isolated]), а здесь это
     * закрыто и кодом: такая попытка — отказ, а не тихое доверие любому
     * сертификату (D-10).
     */
    private class Verified(private val c: HttpConnection) : HttpConnection by c {
        override fun configure(km: Array<out KeyManager>?, tm: Array<out TrustManager>?, random: SecureRandom?) {
            throw KeyManagementException("своя проверка TLS для git запрещена: только системная")
        }

        override fun setHostnameVerifier(hostnameverifier: HostnameVerifier?) {
            throw KeyManagementException("своя проверка имени хоста для git запрещена: только системная")
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

        /** Часть адреса GitHub, которую можно взять именем каталога как есть. */
        private val PART = Regex("[A-Za-z0-9._-]+")

        /**
         * Удалить каталог целиком, не переходя по символьным ссылкам: ссылка
         * удаляется сама, а не то, на что указывает. Каталог клонов виден гостю
         * Linux на запись (D-72): подложенная ссылка не уводит удаление наружу.
         */
        fun deleteTree(dir: File) {
            val start = dir.toPath()
            if (!java.nio.file.Files.exists(start, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            java.nio.file.Files.walkFileTree(
                start,
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                        java.nio.file.Files.delete(file)
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(d: java.nio.file.Path, exc: IOException?): java.nio.file.FileVisitResult {
                        if (exc != null) throw exc
                        java.nio.file.Files.delete(d)
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                },
            )
        }

        /** Пропуск по списку адресов `:core:net` (D-10): тот же, что у запросов REST. */
        fun allowList(list: AllowList = AllowList.CORE): Gate = Gate { uri -> list.check(HttpRequest("GET", uri)) }

        /** Адрес клона на GitHub: только `https://github.com/<владелец>/<имя>.git`, токен — не в адресе. */
        fun github(slug: String): URI = URI("https://github.com/$slug.git")
    }
}
