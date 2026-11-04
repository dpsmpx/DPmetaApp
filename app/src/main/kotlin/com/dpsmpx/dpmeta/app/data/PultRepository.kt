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
class PultRepository(private val vfs: Vfs, private val clock: Clock, private val pool: ExecutorService) {
    private val store = Store(vfs, clock, RandomSource.secure())

    /** Итог синхронизации для экрана: строка «что вышло» и отказы источников. */
    data class SyncReport(val line: String, val failures: List<SourceFailure>)

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

    /** Синхронизация целиком; зовётся не из главного потока. */
    fun sync(token: Secret): Outcome<SyncReport> {
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
            p.errors.filter { it.code in MISSING && it.at >= recheckAfter }.map { e ->
                SourceFailure(p.name, e.kind, Diagnostic.error(DiagCode.valueOf(e.code), e.message, p.name), e.at)
            }
        }
        val parallel = object : Parallel {
            override fun <T> all(jobs: List<() -> T>): List<T> = jobs.map { job -> pool.submit<T> { job() } }.map { it.get() }
        }
        val result = Sync.run(Sync.plan(reg, known, carried.map { it.project to it.kind }.toSet()), GitHubReader(gh, host), clock, commits, parallel)
        val state = EcosystemState.derive(reg, result.observations, result.failures + carried, previous, clock.now())
        store.writeState(state).let { if (it is Outcome.Failure) return it }
        store.appendHistory(state.points()).let { if (it is Outcome.Failure) return it }
        store.appendFeed(Feed.diff(previous, state)).let { if (it is Outcome.Failure) return it }
        etags.save()
        val s = net.stats
        // Числа замера A-02 (ТЗ; как строка `dpmeta-cli sync --live`): запросы, доля 304, время.
        val share = if (s.requests == 0) 0 else s.notModified * 100 / s.requests
        val seconds = (clock.monotonicNanos() - started) / 1_000_000_000.0
        val line = "запросов ${s.requests}, без изменений ${s.notModified} ($share %), отказов источников ${result.failures.size}, " +
            "не спрашивалось ${carried.size}, ${"%.1f".format(java.util.Locale.ROOT, seconds)} с"
        return Outcome.success(SyncReport(line, result.failures))
    }

    companion object {
        const val HOST = "dpsmpx/DPmetaApp"
        const val REGISTRY = "dpmeta/tools/ecosystem.toml"
        private val MISSING = setOf(DiagCode.GITHUB_NOT_FOUND.name, DiagCode.ENTITY_NOT_FOUND.name)
    }
}
