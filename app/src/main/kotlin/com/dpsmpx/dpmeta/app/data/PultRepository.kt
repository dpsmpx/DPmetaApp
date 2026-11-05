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
    data class SyncReport(val line: String, val failures: List<SourceFailure>)

    /**
     * Строка окна выбора клонов (D-70): репозиторий, из реестра ли, закрытый ли;
     * [gone] — отмечен прежде, а в списке аккаунта его нет (доступ, имя, архив):
     * виден, чтобы его можно было снять.
     */
    data class CloneOption(val slug: String, val registry: Boolean, val private: Boolean, val gone: Boolean = false)

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

    /** Идёт ли синхронизация (на процесс): окно после поворота не запускает вторую и говорит «идёт». */
    fun syncing(): Boolean = running.get()

    /**
     * Синхронизация целиком; зовётся не из главного потока. Вторая разом (вход
     * и «Обновить», окно после поворота) не начинается: клоны и состояние —
     * одни на процесс.
     */
    fun sync(token: Secret, selected: Set<String> = emptySet()): Outcome<SyncReport> {
        if (!running.compareAndSet(false, true)) return Outcome.failure(DiagCode.STATE_CONFLICT, "синхронизация уже идёт", "sync")
        try {
            return syncNow(token, selected)
        } finally {
            running.set(false)
        }
    }

    /** [selected] — отмеченные автором клоны (D-70), `владелец/имя`; остальные — через REST. */
    private fun syncNow(token: Secret, selected: Set<String>): Outcome<SyncReport> {
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
        val reader = CloneReader(clones, GitHubReader(gh, host), host, Rules::isBuildBranch) { it in chosen }
        val cloned = cloneAll(reg, clones, reader, token, chosen)
        val result = Sync.run(Sync.plan(reg, known, carried.map { it.project to it.kind }.toSet()), reader, clock, commits, parallel)
        val state = EcosystemState.derive(reg, result.observations, result.failures + cloned.failures + carried, previous, clock.now())
        store.writeState(state).let { if (it is Outcome.Failure) return it }
        store.appendHistory(state.points()).let { if (it is Outcome.Failure) return it }
        store.appendFeed(Feed.diff(previous, state)).let { if (it is Outcome.Failure) return it }
        etags.save()
        val s = net.stats
        // Числа замера A-02 (ТЗ; как строка `dpmeta-cli sync --live`): запросы, доля 304, время.
        val share = if (s.requests == 0) 0 else s.notModified * 100 / s.requests
        val seconds = (clock.monotonicNanos() - started) / 1_000_000_000.0
        val line = "запросов ${s.requests}, без изменений ${s.notModified} ($share %), отказов источников ${result.failures.size + cloned.failures.size}, " +
            "не спрашивалось ${carried.size}, клонов ${cloned.ok} (новых ${cloned.created}" +
            (if (cloned.lost > 0) ", не обновлено ${cloned.lost}" else "") +
            (if (cloned.rewritten > 0) ", с перезаписанной историей ${cloned.rewritten}" else "") +
            "), ${"%.1f".format(java.util.Locale.ROOT, seconds)} с"
        return Outcome.success(SyncReport(line, result.failures + cloned.failures))
    }

    /**
     * Итог клонов: сколько готово, сколько склонировано впервые, сколько не
     * обновилось, у скольких GitHub перезаписал историю (сведение, D-70);
     * отказы клонов репозиториев реестра — отказами источника «clone».
     */
    private class Cloned(val ok: Int, val created: Int, val lost: Int, val rewritten: Int, val failures: List<SourceFailure>)

    /**
     * Клонировать или обновить отмеченные автором клоны (D-70), по одному на
     * репозиторий. Отказ обновления клона реестра — отказ источника `clone`
     * проекта («Ошибки источников»; клон DPmetaApp — отказом DPmetaApp);
     * работа ИИ в клоне — не конфликт, перезаписанная на GitHub история —
     * сведение в строке итога.
     */
    private fun cloneAll(reg: Registry, clones: com.dpsmpx.dpmeta.git.Clones, reader: CloneReader, token: Secret, selected: Set<String>): Cloned {
        val byKey = reg.shown.groupBy { reader.key(it) }
        val now = clock.now()
        // Только отмеченные автором (D-70) — из реестра и любые другие репозитории аккаунта.
        val jobs = selected.sorted().map { key ->
            clonePool.submit<Pair<String, Outcome<com.dpsmpx.dpmeta.git.Clones.Report>>> {
                // Сбой одного клона — его отказ, а не исключение из get(): иначе остальные клоны шли бы без защиты running.
                key to runCatching { clones.sync(key, com.dpsmpx.dpmeta.git.Clones.github(key), token, Rules::isBuildBranch) }
                    .getOrElse { Outcome.failure(DiagCode.INTERNAL_ERROR, "клон: ${it.javaClass.simpleName}", key) }
            }
        }
        var ok = 0
        var created = 0
        var lost = 0
        var rewritten = 0
        val failures = mutableListOf<SourceFailure>()
        for ((key, r) in jobs.map { it.get() }) {
            // Отказ клона репозитория реестра — его отказ источника; не из реестра — только счёт в строке итога.
            val owner = byKey[key]?.let { ps -> ps.firstOrNull { it.dir == null } ?: ps.first() }?.name
            when (r) {
                is Outcome.Failure -> {
                    lost++
                    if (owner != null) failures += SourceFailure(owner, CLONE, r.diagnostics.first(), now)
                }
                is Outcome.Success -> {
                    ok++
                    if (r.value.action == com.dpsmpx.dpmeta.git.Clones.Action.CLONED) created++
                    // Перезаписанная на GitHub история — сведение (D-70): клон взял её; работа ИИ в клоне — не расхождение.
                    if (r.value.conflicts.isNotEmpty()) rewritten++
                }
            }
        }
        return Cloned(ok, created, lost, rewritten, failures)
    }

    companion object {
        const val HOST = "dpsmpx/DPmetaApp"
        const val REGISTRY = "dpmeta/tools/ecosystem.toml"
        private val MISSING = setOf(DiagCode.GITHUB_NOT_FOUND.name, DiagCode.ENTITY_NOT_FOUND.name)

        /** Вид отказа источника для клона (D-65): «Ошибки источников» показывают его рядом с файлами. */
        const val CLONE = "clone"

        /** Синхронизация идёт (на процесс): клоны и состояние одни. */
        private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
