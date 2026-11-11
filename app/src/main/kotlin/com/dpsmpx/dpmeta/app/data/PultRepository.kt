package com.dpsmpx.dpmeta.app.data

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.Vfs
import com.dpsmpx.dpmeta.app.screens.Links
import com.dpsmpx.dpmeta.formats.RegistryFormat
import com.dpsmpx.dpmeta.github.GitHub
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.Feed
import com.dpsmpx.dpmeta.metrics.FeedEvent
import com.dpsmpx.dpmeta.metrics.MetricPoint
import com.dpsmpx.dpmeta.metrics.Rules
import com.dpsmpx.dpmeta.metrics.Store
import com.dpsmpx.dpmeta.model.RepoRef
import com.dpsmpx.dpmeta.model.Registry
import com.dpsmpx.dpmeta.model.SourceFailure
import com.dpsmpx.dpmeta.net.Net
import com.dpsmpx.dpmeta.net.UrlTransport
import com.dpsmpx.dpmeta.sync.CloneReader
import com.dpsmpx.dpmeta.sync.GitHubReader
import com.dpsmpx.dpmeta.sync.Parallel
import com.dpsmpx.dpmeta.sync.StoredEtagCache
import com.dpsmpx.dpmeta.sync.Sync
import java.util.concurrent.ExecutorService

/**
 * Данные пульта (этап 3, только чтение): синхронизация с GitHub по реестру
 * DPmeta из репозитория DPmetaApp (D-05, D-50), состояние и история — на
 * `dpai/storage` в каталоге приложения (D-34). Запускается по кнопке; фон по
 * расписанию — этап 7 (WorkManager, D-18).
 */
class PultRepository(
    private val vfs: Vfs,
    private val clock: Clock,
    private val pool: ExecutorService,
    private val clonesDir: java.io.File,
    /** Пул клонов (D-65): первое клонирование не занимает потоки [pool]. */
    private val clonePool: ExecutorService = pool,
) {
    private val store = Store(vfs, clock, RandomSource.secure())

    /** Итог синхронизации для экрана: строка «что вышло» и отказы источников. */
    /** Итог синхронизации: строка замера, отказы источников и новые события ленты — для тревог (D-85). */
    data class SyncReport(val line: String, val failures: List<SourceFailure>, val events: List<com.dpsmpx.dpmeta.metrics.FeedEvent> = emptyList())

    /**
     * Строка окна выбора клонов (D-70): репозиторий, из реестра ли, закрытый ли;
     * [gone] — отмечен прежде, а в списке аккаунта его нет (доступ, имя, архив):
     * виден, чтобы его можно было снять.
     */
    data class CloneOption(val slug: String, val registry: Boolean, val private: Boolean, val gone: Boolean = false, val bytes: Long? = null)

    /** Сколько занимает клон [slug] на телефоне; клона нет — `null` (UX-11: «склонирован · 120 МБ»). Не на главном потоке. */
    fun cloneBytes(slug: String): Long? {
        val dir = com.dpsmpx.dpmeta.git.Clones(clonesDir, com.dpsmpx.dpmeta.git.Clones.allowList()).dir(cloneKey(slug))
        if (!java.io.File(dir, ".git").isDirectory) return null
        return dir.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Удалить клон [slug] (UX-11; D-70): место — назад, работа ИИ в нём —
     * тоже, поэтому только по вопросу на экране и только не отмеченного.
     * Ссылки внутри не проходятся (`Clones.deleteTree`).
     */
    fun deleteClone(slug: String): Outcome<Unit> {
        // Не во время синхронизации (проверка PR #173): удаление рядом с fetch JGit оставило бы полуудалённый `.git`.
        if (running.get()) return Outcome.failure(DiagCode.STATE_CONFLICT, "идёт синхронизация — удалите клон после неё", slug)
        return try {
            com.dpsmpx.dpmeta.git.Clones.deleteTree(com.dpsmpx.dpmeta.git.Clones(clonesDir, com.dpsmpx.dpmeta.git.Clones.allowList()).dir(cloneKey(slug)))
            Outcome.success(Unit)
        } catch (e: java.io.IOException) {
            Outcome.failure(DiagCode.STORAGE_IO_FAILED, "клон удалён не весь: ${e.javaClass.simpleName}", slug)
        }
    }

    /**
     * Репозитории для окна выбора клонов (D-70): все, что видны токену, без
     * архивных; репозитории реестра (и DPmetaApp — его подсистем) — сверху.
     */
    fun cloneOptions(token: Secret): Outcome<List<CloneOption>> {
        val (gh, etags) = client(token)
        val reg = when (val r = registry(gh)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val repos = when (val r = gh.repos()) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        etags.save()
        val host = RepoRef.parse(HOST).valueOrNull()!!
        // Адрес реестра и `full_name` GitHub сверяются без регистра: в реестре могут написать иначе.
        val ours = reg.shown.map { (if (it.dir != null) host.toString() else it.slug.toString()).lowercase() }.toSet()
        return Outcome.success(
            repos.filter { !it.archived }
                .map { CloneOption(it.slug.toString(), it.slug.toString().lowercase() in ours, it.private) }
                .sortedWith(compareBy({ !it.registry }, { it.slug.lowercase() })),
        )
    }

    fun state(): Outcome<EcosystemState?> = store.readState()

    fun feed(): Outcome<List<FeedEvent>> = store.readFeed()

    /** История чисел (D-34) — графики и плитки (F-34); читается целиком, фильтрует экран. */
    fun history(): Outcome<List<MetricPoint>> = store.history()

    /**
     * Журнал действий (F-44) и политика уровней (`:core:actions`, D-07) — одни на
     * репозиторий: одобрение экрана однократно в пределах одного [Actions].
     */
    private val actionLog = com.dpsmpx.dpmeta.actions.ActionLog(vfs, clock, RandomSource.secure())
    private val actions = com.dpsmpx.dpmeta.actions.Actions(actionLog, clock, RandomSource.secure())

    /** Журнал действий по порядку; порча — отказ `JOURNAL_CORRUPTED`. Не на главном потоке. */
    fun journal(): Outcome<List<com.dpsmpx.dpmeta.actions.ActionEntry>> = actionLog.entries()

    /** Репозиторий проекта для действий с PR — тот же, откуда синхронизация берёт его PR. */
    private fun repoOf(gh: GitHub, project: String): Outcome<RepoRef> {
        val reg = when (val r = registry(gh)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val entry = reg.byName(project) ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "проекта нет в реестре", project)
        return Outcome.success(GitHubReader(gh, RepoRef.parse(HOST).valueOrNull()!!).where(entry).first)
    }

    /** Репозиторий проекта — для действий над ним (issue, F-26). */
    fun repoFor(token: Secret, project: String): Outcome<RepoRef> {
        val (gh, etags) = client(token)
        val r = repoOf(gh, project)
        etags.save()
        return r
    }

    /** PR перед действием (F-12): свежий, с репозиторием проекта. */
    /** Карточка PR (F-11): коммиты, файлы и обсуждение — свежие, три списка по одной странице. */
    fun pullCard(token: Secret, project: String, number: Int): Outcome<com.dpsmpx.dpmeta.app.screens.PullActions.Card> {
        val repo = when (val r = repoFor(token, project)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val w = com.dpsmpx.dpmeta.github.GitHubWrite(Net(UrlTransport()), token)
        val commits = when (val r = w.pullCommits(repo, number)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val files = when (val r = w.pullFiles(repo, number)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return when (val r = w.discussion(repo, number)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(com.dpsmpx.dpmeta.app.screens.PullActions.Card(commits, files, r.value))
        }
    }

    fun pull(token: Secret, project: String, number: Int): Outcome<Pair<RepoRef, com.dpsmpx.dpmeta.github.PullDetail>> {
        val (gh, etags) = client(token)
        val repo = repoOf(gh, project)
        etags.save()
        return when (repo) {
            is Outcome.Failure -> repo
            is Outcome.Success -> when (val r = com.dpsmpx.dpmeta.github.GitHubWrite(Net(UrlTransport()), token).pull(repo.value, number)) {
                is Outcome.Failure -> r
                is Outcome.Success -> Outcome.success(repo.value to r.value)
            }
        }
    }

    /** Ветка перед действием (F-14): основная ветка, голова, защита и сравнение с основной — свежие. */
    fun branch(token: Secret, project: String, name: String): Outcome<com.dpsmpx.dpmeta.app.screens.BranchActions.View> {
        val (gh, etags) = client(token)
        val repo = when (val r = repoOf(gh, project)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        etags.save()
        val w = com.dpsmpx.dpmeta.github.GitHubWrite(Net(UrlTransport()), token)
        val base = when (val r = w.defaultBranch(repo)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val branch = when (val r = w.branch(repo, name)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val cmp = if (name == base) {
            null
        } else {
            when (val r = w.compare(repo, base, name)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
        }
        return Outcome.success(com.dpsmpx.dpmeta.app.screens.BranchActions.View(repo, base, branch, cmp))
    }

    /**
     * Действие (F-12, F-14): политика уровня, журнал, исполнение (D-07, A-06). Подтверждение
     * создаёт [confirm] — после окна экрана, PIN устройства (уровень 2) или ввода имени (3).
     */
    fun act(
        token: Secret,
        command: com.dpsmpx.dpmeta.actions.ActionCommand,
        confirm: (com.dpsmpx.dpmeta.actions.Actions) -> com.dpsmpx.dpmeta.actions.Confirmation?,
        /** Новый текст файла для правки (F-69); в журнал не идёт. */
        text: String? = null,
    ): Outcome<com.dpsmpx.dpmeta.actions.ActionResult> =
        actions.run(command, confirm(actions)) { ActionExecutor.execute(com.dpsmpx.dpmeta.github.GitHubWrite(Net(UrlTransport()), token), command, text) }

    /**
     * Запуск маршрута (F-22): та же политика и журнал (D-07), исполнитель — [fire] оболочки,
     * мост к подсистеме «AI»; токен GitHub не нужен. [text] — поручение; в журнал не идёт.
     */
    fun fire(
        command: com.dpsmpx.dpmeta.actions.ActionCommand,
        confirm: (com.dpsmpx.dpmeta.actions.Actions) -> com.dpsmpx.dpmeta.actions.Confirmation?,
        text: String,
        fire: (String, String) -> Outcome<com.dpsmpx.dpmeta.app.screens.RouteActions.Fired>,
    ): Outcome<com.dpsmpx.dpmeta.actions.ActionResult> =
        actions.run(command, confirm(actions)) { com.dpsmpx.dpmeta.app.screens.RouteActions.execute(command, text, fire) }

    /**
     * Реестр проектов для команд (D-82): прочитанный при синхронизации, иначе — из клона
     * DPmetaApp, без сети и токена.
     */
    fun commandRegistry(selected: Set<String>): Outcome<Registry> {
        registry?.let { return Outcome.success(it) }
        val host = RepoRef.parse(HOST).valueOrNull() ?: return Outcome.failure(DiagCode.INTERNAL_ERROR, "адрес DPmetaApp", HOST)
        val clones = com.dpsmpx.dpmeta.git.Clones(clonesDir, com.dpsmpx.dpmeta.git.Clones.allowList())
        return localRegistry(clones, host, selected)?.let { Outcome.success(it) }
            ?: Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "реестра проектов ещё нет: синхронизируйте пульт или отметьте клон DPmetaApp", REGISTRY)
    }

    /** Каталог клона репозитория [repo] на телефоне — тот же, что у гостя в `/root/repos` (D-72). */
    fun cloneDir(repo: RepoRef): java.io.File = com.dpsmpx.dpmeta.git.Clones(clonesDir, com.dpsmpx.dpmeta.git.Clones.allowList()).dir("$repo")

    /** Рабочее дерево клона (D-70): ветка и коммит рядом с `origin/<ветка>`; клона нет — `null`. */
    fun worktree(repo: RepoRef): com.dpsmpx.dpmeta.git.Clones.Worktree? =
        com.dpsmpx.dpmeta.git.Clones(clonesDir, com.dpsmpx.dpmeta.git.Clones.allowList()).worktree("$repo").valueOrNull()

    /**
     * Команда реестра уровня 1 (D-82): та же политика и журнал (D-07), исполнитель — [run],
     * запуск в Linux-среде мостом оболочки.
     */
    fun runCommand(
        command: com.dpsmpx.dpmeta.actions.ActionCommand,
        confirm: (com.dpsmpx.dpmeta.actions.Actions) -> com.dpsmpx.dpmeta.actions.Confirmation?,
        run: () -> Outcome<com.dpsmpx.dpmeta.app.screens.CommandActions.Exit>,
    ): Outcome<com.dpsmpx.dpmeta.actions.ActionResult> =
        actions.run(command, confirm(actions)) { com.dpsmpx.dpmeta.app.screens.CommandActions.execute(command, run) }

    /** Репозиторий проекта и его основная ветка — для мест шаблона поручения (F-23). */
    fun base(token: Secret, project: String): Outcome<Pair<RepoRef, String>> {
        val repo = when (val r = repoFor(token, project)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return when (val b = com.dpsmpx.dpmeta.github.GitHubWrite(Net(UrlTransport()), token).defaultBranch(repo)) {
            is Outcome.Failure -> b
            is Outcome.Success -> Outcome.success(repo to b.value)
        }
    }

    /** Файл к правке (F-69): репозиторий и путь — как у документа проекта, блоб — с основной ветки. */
    fun editable(token: Secret, project: String, path: String): Outcome<com.dpsmpx.dpmeta.app.screens.EditActions.Edit> {
        val (gh, etags) = client(token)
        val reg = when (val r = registry(gh)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        etags.save()
        val entry = reg.byName(project) ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "проекта нет в реестре", project)
        val (repo, prefix) = GitHubReader(gh, RepoRef.parse(HOST).valueOrNull()!!).where(entry)
        val w = com.dpsmpx.dpmeta.github.GitHubWrite(Net(UrlTransport()), token)
        val base = when (val r = w.defaultBranch(repo)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return when (val r = w.fileForEdit(repo, prefix + path, base)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(com.dpsmpx.dpmeta.app.screens.EditActions.Edit(project, repo, base, r.value))
        }
    }

    @Volatile private var registry: Registry? = null

    private fun client(token: Secret): Pair<GitHub, StoredEtagCache> {
        val etags = StoredEtagCache(DocumentStore(vfs, "dpmeta/cache"))
        return GitHub(Net(UrlTransport(), cache = etags), token) to etags
    }

    private fun registry(gh: GitHub): Outcome<Registry> {
        registry?.let { return Outcome.success(it) }
        val host = RepoRef.parse(HOST).valueOrNull() ?: return Outcome.failure(DiagCode.INTERNAL_ERROR, "адрес DPmetaApp", HOST)
        val text = when (val f = gh.file(host, REGISTRY)) {
            is Outcome.Failure -> return f
            is Outcome.Success -> f.value.value
        }
        return RegistryFormat.parse(text, REGISTRY).also { r -> (r as? Outcome.Success)?.let { registry = it.value } }
    }

    /** Имена, прежние имена и другие написания проектов → имя: ссылки `Проект:путь` (F-09). */
    fun names(): Map<String, String> = registry?.projects.orEmpty().flatMap { p -> (listOf(p.name) + p.aliases + p.names).map { it to p.name } }.toMap()

    /** Каталог проекта (F-09): пути — от корня проекта, у подсистемы — без её каталога в DPmetaApp. */
    fun directory(token: Secret, project: String, dir: String): Outcome<List<GitHub.Entry>> {
        val (gh, etags) = client(token)
        val reg = when (val r = registry(gh)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val entry = reg.byName(project) ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "проекта нет в реестре", project)
        val host = RepoRef.parse(HOST).valueOrNull()!!
        val (repo, prefix) = GitHubReader(gh, host).where(entry)
        val r = gh.dir(repo, (prefix + dir).trimEnd('/'))
        etags.save()
        return when (r) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(r.value.map { it.copy(path = it.path.removePrefix(prefix)) })
        }
    }

    /** Файл проекта по запросу (F-09): тексты процесса — с кэшем ETag, код — без сохранения. */
    fun document(token: Secret, project: String, path: String): Outcome<String> {
        val (gh, etags) = client(token)
        val reg = when (val r = registry(gh)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val entry = reg.byName(project) ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "проекта нет в реестре", project)
        val host = RepoRef.parse(HOST).valueOrNull()!!
        val (repo, prefix) = GitHubReader(gh, host).where(entry)
        val r = gh.file(repo, prefix + path, store = Links.cacheable(path))
        etags.save()
        return when (r) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(r.value.value)
        }
    }

    /** Клон проекта для чтения: ключ клона, путь подсистемы в нём, основная ветка. */
    private class Local(val clones: com.dpsmpx.dpmeta.git.Clones, val key: String, val prefix: String, val main: String)

    /**
     * Склонированный и отмеченный автором репозиторий проекта (D-65, D-70);
     * `null` — клона нет, он не отмечен или реестра нет ни в памяти, ни в
     * отмеченном клоне DPmetaApp: тогда — REST.
     */
    private fun local(project: String, selected: Set<String>): Local? {
        val clones = com.dpsmpx.dpmeta.git.Clones(clonesDir, com.dpsmpx.dpmeta.git.Clones.allowList())
        val host = RepoRef.parse(HOST).valueOrNull() ?: return null
        val reg = registry ?: localRegistry(clones, host, selected) ?: return null
        val entry = reg.byName(project) ?: return null
        val key = if (entry.dir != null) host.toString() else entry.slug.toString()
        // Выбор — как `full_name` GitHub, ключ — как в реестре: сверка без регистра, как у синхронизации.
        if (selected.none { it.equals(key, ignoreCase = true) }) return null
        val main = clones.main(key) ?: return null
        return Local(clones, key, entry.dir?.let { "$it/" } ?: "", main)
    }

    /**
     * Манифест `dpai.ecosystem` v1 для «AI» (`DP_AI_Orcestrator:D-114`): реестр
     * (в памяти или из отмеченного клона DPmetaApp) и факты отмеченных клонов
     * — ветка, последний коммит (у подсистемы — её каталога), документы;
     * предложения — из очереди DPmeta в клоне DPmetaApp. Без сети и токена.
     * Реестра нет — отказ словами: «AI» предложит файл с ПК.
     */
    fun ecosystemManifest(selected: Set<String>): Outcome<String> {
        val clones = com.dpsmpx.dpmeta.git.Clones(clonesDir, com.dpsmpx.dpmeta.git.Clones.allowList())
        val host = RepoRef.parse(HOST).valueOrNull() ?: return Outcome.failure(DiagCode.INTERNAL_ERROR, "адрес DPmetaApp не разбирается")
        fun chosen(key: String) = selected.any { it.equals(key, ignoreCase = true) }
        // Реестр и очередь — из клона DPmetaApp на голове, которую назвал GitHub при последней синхронизации
        // (T19): ссылки клона может переписать Linux-среда, объекты на этом SHA — нет.
        if (!chosen(host.toString())) return Outcome.failure(DiagCode.ECOSYSTEM_UNAVAILABLE, "клон DPmetaApp не отмечен: отметьте его в пульте («Ещё» → «Клоны…») или выберите файл манифеста")
        val sha = trustedHost() ?: return Outcome.failure(DiagCode.ECOSYSTEM_UNAVAILABLE, "пульт ещё не синхронизировал клон DPmetaApp: «Обновить» в пульте — или выберите файл манифеста")
        val regText = when (val r = clones.fileAt(host.toString(), sha, REGISTRY)) {
            is Outcome.Failure -> return Outcome.failure(DiagCode.ECOSYSTEM_UNAVAILABLE, "реестр не читается из клона DPmetaApp: ${r.diagnostics.first().message}")
            is Outcome.Success -> r.value?.toString(Charsets.UTF_8) ?: return Outcome.failure(DiagCode.ECOSYSTEM_UNAVAILABLE, "в клоне DPmetaApp нет реестра $REGISTRY")
        }
        val reg = when (val r = RegistryFormat.parse(regText, REGISTRY)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val facts = linkedMapOf<String, com.dpsmpx.dpmeta.formats.DpaiManifest.Clone>()
        for (e in reg.shown) {
            val key = if (e.dir != null) host.toString() else e.slug.toString()
            if (!chosen(key)) continue
            val main = clones.main(key) ?: continue
            // Ветки GitHub в клоне нет — «без основной ветки», как у DPmeta:tools/manifest.py.
            if ((clones.heads(key) as? Outcome.Success)?.value?.any { it.name == main } != true) {
                facts[e.name] = com.dpsmpx.dpmeta.formats.DpaiManifest.Clone(null)
                continue
            }
            val prefix = e.dir?.let { "$it/" } ?: ""
            val last = (clones.last(key, main, e.dir.orEmpty()) as? Outcome.Success)?.value
            val docs = com.dpsmpx.dpmeta.formats.DpaiManifest.wanted(reg, e.name).filter { p ->
                // Подсистема — на проверенной голове DPmetaApp; свой репозиторий — по своей ветке.
                if (key == host.toString()) (clones.fileAt(key, sha, prefix + p) as? Outcome.Success)?.value != null else (clones.size(key, main, prefix + p) as? Outcome.Success)?.value != null
            }
            facts[e.name] = com.dpsmpx.dpmeta.formats.DpaiManifest.Clone(main, last?.first, last?.second, docs)
        }
        // Очередь не читается (подложенный объект, удалённые паки) — отказ всего манифеста, а не тихое «предложений нет»:
        // окно и журнал прохода политики это покажут (T19). Очереди нет в основной ветке — предложений нет.
        val queue = when (val q = clones.fileAt(host.toString(), sha, com.dpsmpx.dpmeta.formats.DpaiManifest.QUEUE)) {
            is Outcome.Failure -> return Outcome.failure(DiagCode.ECOSYSTEM_UNAVAILABLE, "очередь DPmeta не читается из клона DPmetaApp: ${q.diagnostics.first().message}")
            is Outcome.Success -> q.value?.toString(Charsets.UTF_8)
        }
        return com.dpsmpx.dpmeta.formats.DpaiManifest.build(reg, facts, queue.orEmpty())
    }

    /** Голова DPmetaApp по ответу GitHub при последней синхронизации (T19): в хранилище пульта, не в клоне. */
    private fun trustHost(sha: String) {
        runCatching {
            vfs.mkdirs(TRUSTED_HOST.substringBeforeLast('/'))
            vfs.write(TRUSTED_HOST, sha.toByteArray(Charsets.UTF_8))
        }
    }

    private fun trustedHost(): String? = runCatching { vfs.read(TRUSTED_HOST)?.toString(Charsets.UTF_8)?.trim() }.getOrNull()?.takeIf { Regex("[0-9a-f]{40}").matches(it) }

    /** Реестр из отмеченного клона DPmetaApp — без сети и токена (окно открыли до первой синхронизации). */
    private fun localRegistry(clones: com.dpsmpx.dpmeta.git.Clones, host: RepoRef, selected: Set<String>): Registry? {
        if (selected.none { it.equals(host.toString(), ignoreCase = true) }) return null
        val main = clones.main(host.toString()) ?: return null
        val bytes = (clones.file(host.toString(), main, REGISTRY) as? Outcome.Success)?.value ?: return null
        // Только для чтения из клона: в общий реестр (REST, окно клонов) он не кладётся — клон доступен гостю Linux на запись.
        return (RegistryFormat.parse(bytes.toString(Charsets.UTF_8), REGISTRY) as? Outcome.Success)?.value
    }

    /**
     * Каталог склонированного проекта — из клона (UX-9): без сети, токена и
     * входа по PIN; `null` — клона нет или он не отмечен, тогда [directory].
     */
    fun localDirectory(project: String, dir: String, selected: Set<String>): Outcome<List<GitHub.Entry>>? {
        val l = local(project, selected) ?: return null
        return when (val r = l.clones.list(l.key, l.main, (l.prefix + dir).trimEnd('/'))) {
            is Outcome.Failure -> r
            is Outcome.Success -> r.value?.let { list -> Outcome.success(list.map { GitHub.Entry(it.name, if (dir.isEmpty()) it.name else "${dir.trimEnd('/')}/${it.name}", it.dir) }) }
                ?: Outcome.failure(DiagCode.GITHUB_NOT_FOUND, "каталога нет в клоне", "$project:$dir")
        }
    }

    /** Файл склонированного проекта — из клона (UX-9); `null` — клона нет или он не отмечен, тогда [document]. */
    fun localDocument(project: String, path: String, selected: Set<String>): Outcome<String>? {
        val l = local(project, selected) ?: return null
        // Предел — до чтения в память (проверка PR #173): большой файл — словами, а не нехваткой памяти.
        val size = (l.clones.size(l.key, l.main, l.prefix + path) as? Outcome.Success)?.value
        if (size != null && size > MAX_DOCUMENT) return Outcome.failure(DiagCode.JSON_TOO_LARGE, "файл больше ${MAX_DOCUMENT / 1_048_576} МБ — на телефоне не открывается", "$project:$path")
        return when (val r = l.clones.file(l.key, l.main, l.prefix + path)) {
            is Outcome.Failure -> r
            is Outcome.Success -> r.value?.let { Outcome.success(it.toString(Charsets.UTF_8)) } ?: run {
                val dir = (l.clones.list(l.key, l.main, l.prefix + path.trimEnd('/')) as? Outcome.Success)?.value != null
                Outcome.failure(DiagCode.GITHUB_NOT_FOUND, if (dir) "это каталог — откройте его в «Все файлы»" else "файла нет в клоне", "$project:$path")
            }
        }
    }

    /** Ключ клона по `full_name` GitHub: у репозитория реестра — написание реестра (каталог клона создан по нему). */
    private fun cloneKey(slug: String): String {
        val host = RepoRef.parse(HOST).valueOrNull()?.toString()
        val keys = registry?.shown.orEmpty().map { if (it.dir != null) host.orEmpty() else it.slug.toString() } + listOfNotNull(host)
        return keys.firstOrNull { it.equals(slug, ignoreCase = true) } ?: slug
    }

    /** Идёт ли синхронизация (на процесс): окно после поворота не запускает вторую и говорит «идёт». */
    fun syncing(): Boolean = running.get()

    /**
     * Синхронизация целиком; зовётся не из главного потока. Вторая разом (вход
     * и «Обновить», окно после поворота) не начинается: клоны и состояние —
     * одни на процесс.
     */
    fun sync(token: Secret, selected: Set<String> = emptySet(), progress: (String) -> Unit = {}): Outcome<SyncReport> {
        if (!running.compareAndSet(false, true)) return Outcome.failure(DiagCode.STATE_CONFLICT, "синхронизация уже идёт", "sync")
        cancel.set(false)
        try {
            return syncNow(token, selected, progress)
        } finally {
            running.set(false)
        }
    }

    /**
     * «Отменить» (UX-10): клоны, ещё не начатые, не трогаются; начатый
     * доходит до конца — между репозиториями, а не посреди записи клона.
     * Остальная синхронизация идёт с тем, что есть.
     */
    fun cancelClones() = cancel.set(true)

    /**
     * Есть ли среди отмеченных [selected] клон, которого ещё нет на телефоне:
     * такая синхронизация клонирует — и идёт под службой (D-78). Читает диск —
     * не на главном потоке.
     */
    fun missingClones(selected: Set<String>): Boolean {
        val clones = com.dpsmpx.dpmeta.git.Clones(clonesDir, com.dpsmpx.dpmeta.git.Clones.allowList())
        return selected.any { !java.io.File(clones.dir(cloneKey(it)), ".git").isDirectory }
    }

    /** [selected] — отмеченные автором клоны (D-70), `владелец/имя`; остальные — через REST. */
    private fun syncNow(token: Secret, selected: Set<String>, progress: (String) -> Unit): Outcome<SyncReport> {
        val started = clock.monotonicNanos()
        val previous = when (val r = store.readState()) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val etags = StoredEtagCache(DocumentStore(vfs, "dpmeta/cache"))
        val net = Net(UrlTransport(), cache = etags)
        val gh = GitHub(net, token)
        val host = RepoRef.parse(HOST).valueOrNull() ?: return Outcome.failure(DiagCode.INTERNAL_ERROR, "адрес DPmetaApp", HOST)
        val text = when (val f = gh.file(host, REGISTRY)) {
            is Outcome.Failure -> return f
            is Outcome.Success -> f.value.value
        }
        val reg = when (val r = RegistryFormat.parse(text, REGISTRY)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value.also { registry = it }
        }
        val known = previous?.projects.orEmpty().mapNotNull { p ->
            ((p.sections["roadmap"]?.payload as? JsonObject)?.get("path") as? JsonString)?.value?.let { (p.name to "roadmap") to it }
        }.toMap()
        val commits = Sync.knownCommits(previous?.projects.orEmpty().mapNotNull { it.sections["branches"]?.payload })
        val recheckAfter = clock.now() - Rules.MISSING_RECHECK
        val carried = previous?.projects.orEmpty().flatMap { p ->
            // Клоны (D-65) обновляются каждый раз: их отказ не переносится, а пишется заново.
            p.errors.filter { it.code in MISSING && it.at >= recheckAfter && it.kind != CLONE }.map { e ->
                SourceFailure(p.name, e.kind, Diagnostic.error(DiagCode.valueOf(e.code), e.message, p.name), e.at)
            }
        }
        val parallel = object : Parallel {
            override fun <T> all(jobs: List<() -> T>): List<T> = jobs.map { job -> pool.submit<T> { job() } }.map { it.get() }
        }
        // Клоны (D-65): каждый репозиторий реестра — клонировать или обновить; подсистемы — в клоне DPmetaApp.
        val clones = com.dpsmpx.dpmeta.git.Clones(clonesDir, com.dpsmpx.dpmeta.git.Clones.allowList())
        // Выбор записан как `full_name` GitHub, ключи клонов реестра — как в реестре: один каталог на репозиторий, сверка без регистра.
        val regKeys = reg.shown.map { if (it.dir != null) host.toString() else it.slug.toString() }.toSet()
        val chosen = selected.map { s -> regKeys.firstOrNull { it.equals(s, ignoreCase = true) } ?: s }.toSet()
        val reader = CloneReader(clones, GitHubReader(gh, host), host, Rules::isBuildBranch, CloneReader.HistoryRules(Rules.HISTORY_ZONE, Rules.AI_AUTHORS, Rules.HUMAN_AUTHORS)) { it in chosen }
        val cloned = cloneAll(reg, clones, reader, token, chosen, progress)
        progress("Источники…")
        val result = Sync.run(Sync.plan(reg, known, carried.map { it.project to it.kind }.toSet()), reader, clock, commits, parallel)
        val state = EcosystemState.derive(reg, result.observations, result.failures + cloned.failures + carried, previous, clock.now())
        store.writeState(state).let { if (it is Outcome.Failure) return it }
        store.appendHistory(state.points()).let { if (it is Outcome.Failure) return it }
        val fresh = Feed.diff(previous, state)
        store.appendFeed(fresh).let { if (it is Outcome.Failure) return it }
        etags.save()
        val s = net.stats
        // Числа замера A-02 (ТЗ; как строка `dpmeta-cli sync --live`): запросы, доля 304, время.
        val share = if (s.requests == 0) 0 else s.notModified * 100 / s.requests
        val seconds = (clock.monotonicNanos() - started) / 1_000_000_000.0
        val line = "запросов ${s.requests}, без изменений ${s.notModified} ($share %), отказов источников ${result.failures.size + cloned.failures.size}, " +
            "не спрашивалось ${carried.size}, клонов ${cloned.ok} (новых ${cloned.created}" +
            (if (cloned.lost > 0) ", не обновлено ${cloned.lost}" else "") +
            (if (cloned.skipped > 0) ", отменено ${cloned.skipped}" else "") +
            (if (cloned.rewritten > 0) ", с перезаписанной историей ${cloned.rewritten}" else "") +
            "), ${"%.1f".format(java.util.Locale.ROOT, seconds)} с"
        return Outcome.success(SyncReport(line, result.failures + cloned.failures, fresh))
    }

    /**
     * Итог клонов: сколько готово, сколько склонировано впервые, сколько не
     * обновилось, у скольких GitHub перезаписал историю (сведение, D-70);
     * отказы клонов репозиториев реестра — отказами источника «clone».
     */
    private class Cloned(val ok: Int, val created: Int, val lost: Int, val rewritten: Int, val failures: List<SourceFailure>, val skipped: Int = 0)

    /**
     * Клонировать или обновить отмеченные автором клоны (D-70), по одному на
     * репозиторий. Отказ обновления клона реестра — отказ источника `clone`
     * проекта («Ошибки источников»; клон DPmetaApp — отказом DPmetaApp);
     * работа ИИ в клоне — не конфликт, перезаписанная на GitHub история —
     * сведение в строке итога.
     */
    private fun cloneAll(reg: Registry, clones: com.dpsmpx.dpmeta.git.Clones, reader: CloneReader, token: Secret, selected: Set<String>, progress: (String) -> Unit): Cloned {
        val byKey = reg.shown.groupBy { reader.key(it) }
        val now = clock.now()
        // Только отмеченные автором (D-70) — из реестра и любые другие репозитории аккаунта.
        val jobs = selected.sorted().map { key ->
            clonePool.submit<Pair<String, Outcome<com.dpsmpx.dpmeta.git.Clones.Report>>> {
                // «Отменить» (UX-10) — между репозиториями: ещё не начатый клон не трогается.
                if (cancel.get()) return@submit key to Outcome.failure(DiagCode.CANCELLED_BY_USER, "отменено", key)
                // Сбой одного клона — его отказ, а не исключение из get(): иначе остальные клоны шли бы без защиты running.
                key to runCatching { clones.sync(key, com.dpsmpx.dpmeta.git.Clones.github(key), token, Rules::isBuildBranch) }
                    .getOrElse { Outcome.failure(DiagCode.INTERNAL_ERROR, "клон: ${it.javaClass.simpleName}", key) }
            }
        }
        var ok = 0
        var created = 0
        var lost = 0
        var rewritten = 0
        var skipped = 0
        val failures = mutableListOf<SourceFailure>()
        if (jobs.isNotEmpty()) progress("Клоны: 0 из ${jobs.size}")
        for ((done, job) in jobs.withIndex()) {
            val (key, r) = job.get()
            progress("Клоны: ${done + 1} из ${jobs.size}")
            // Отменённый (UX-10): у репозитория реестра — отказ источника «не обновлён»: срез читает прежний клон,
            // и это видно в «Ошибках источников», а не выдаётся за свежее (проверка PR #173).
            if (r is Outcome.Failure && r.diagnostics.first().code == DiagCode.CANCELLED_BY_USER) {
                skipped++
                byKey[key]?.let { ps -> ps.firstOrNull { it.dir == null } ?: ps.first() }?.name?.let { owner ->
                    failures += SourceFailure(owner, CLONE, com.dpsmpx.dpai.core.diagnostics.Diagnostic.error(DiagCode.CANCELLED_BY_USER, "клон не обновлён: отменено", key), now)
                }
                continue
            }
            // Отказ клона репозитория реестра — его отказ источника; не из реестра — только счёт в строке итога.
            val owner = byKey[key]?.let { ps -> ps.firstOrNull { it.dir == null } ?: ps.first() }?.name
            when (r) {
                is Outcome.Failure -> {
                    lost++
                    if (owner != null) failures += SourceFailure(owner, CLONE, r.diagnostics.first(), now)
                }
                is Outcome.Success -> {
                    ok++
                    // Голова DPmetaApp по ответу GitHub — в данных пульта: гостю Linux они не смонтированы (D-114, T19; обход proot — T13).
                    if (key.equals(HOST, ignoreCase = true)) r.value.mainSha?.let(::trustHost)
                    if (r.value.action == com.dpsmpx.dpmeta.git.Clones.Action.CLONED) created++
                    // Перезаписанная на GitHub история — сведение (D-70): клон взял её; работа ИИ в клоне — не расхождение.
                    if (r.value.conflicts.isNotEmpty()) rewritten++
                }
            }
        }
        return Cloned(ok, created, lost, rewritten, failures, skipped)
    }

    companion object {
        const val HOST = "dpsmpx/DPmetaApp"
        const val REGISTRY = "dpmeta/tools/ecosystem.toml"

        /** Голова DPmetaApp от GitHub для манифеста «AI» (T19, D-114) — файл хранилища пульта. */
        private const val TRUSTED_HOST = "trusted/dpmetaapp-main"
        private val MISSING = setOf(DiagCode.GITHUB_NOT_FOUND.name, DiagCode.ENTITY_NOT_FOUND.name)

        /** Вид отказа источника для клона (D-65): «Ошибки источников» показывают его рядом с файлами. */
        const val CLONE = "clone"

        /** Синхронизация идёт (на процесс): клоны и состояние одни. */
        private val running = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Предел документа из клона: больше — не читается в память (журнал решений DPmetaApp — около 0,3 МБ). */
        private const val MAX_DOCUMENT = com.dpsmpx.dpmeta.git.Clones.MAX_DOCUMENT

        /** «Отменить» клоны текущей синхронизации (UX-10). */
        private val cancel = java.util.concurrent.atomic.AtomicBoolean(false)

        /** «Отменить» из уведомления службы клонов (D-78): та же отмена, что на экране. */
        fun cancelRunningClones() = cancel.set(true)
    }
}
