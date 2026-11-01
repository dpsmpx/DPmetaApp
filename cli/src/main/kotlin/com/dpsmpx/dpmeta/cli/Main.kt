package com.dpsmpx.dpmeta.cli

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.formats.RegistryFormat
import com.dpsmpx.dpmeta.github.GitHub
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.Rules
import com.dpsmpx.dpmeta.model.SourceFailure
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpmeta.sync.Parallel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.dpsmpx.dpmeta.metrics.Store
import com.dpsmpx.dpmeta.model.RepoRef
import com.dpsmpx.dpmeta.model.Registry
import com.dpsmpx.dpmeta.net.JdkTransport
import com.dpsmpx.dpmeta.net.Net
import com.dpsmpx.dpmeta.sync.DirectoryReader
import com.dpsmpx.dpmeta.sync.GitHubReader
import com.dpsmpx.dpmeta.sync.SourceReader
import com.dpsmpx.dpmeta.sync.Sync
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `dpmeta-cli` (D-29; ТЗ, §6.2): синхронизация, состояние, история, дым.
 * Коды возврата: 0 — сделано (отказы отдельных источников напечатаны);
 * 2 — неверные аргументы или вход; 3 — сбой самого инструмента (хранилище).
 */
fun main(args: Array<String>) {
    // Вывод — UTF-8 при любой локали консоли: иначе кириллица — «?» (как у `dpai`).
    val utf8 = java.io.PrintStream(System.out, true, Charsets.UTF_8)
    exitProcess(Cli(Clock.SYSTEM, out = utf8::println).run(args.toList()))
}

class Cli(private val clock: Clock, private val env: (String) -> String? = System::getenv, private val out: (String) -> Unit = ::println) {
    private class Usage(message: String) : Exception(message)

    private class Args(list: List<String>) {
        val flags = HashMap<String, String?>()

        init {
            var i = 0
            while (i < list.size) {
                val a = list[i]
                if (!a.startsWith("--")) throw Usage("лишний аргумент «$a»")
                val v = list.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }
                flags[a.removePrefix("--")] = v
                i += if (v == null) 1 else 2
            }
        }

        fun path(name: String): Path? = flags[name]?.let { Path.of(it) }

        fun has(name: String) = name in flags
    }

    fun run(args: List<String>): Int = try {
        when (args.firstOrNull()) {
            "sync" -> sync(Args(args.drop(1)))
            "state" -> state(Args(args.drop(1)))
            "history" -> history(Args(args.drop(1)))
            "smoke" -> smoke(Args(args.drop(1)))
            null, "help", "--help" -> help().let { 0 }
            else -> throw Usage("неизвестная команда «${args.first()}»")
        }
    } catch (e: Usage) {
        out("dpmeta-cli: ${e.message}")
        help()
        2
    }

    private fun help() {
        out(
            """
            dpmeta-cli sync (--corpus КАТАЛОГ | --clones КАТАЛОГ | --live) [--registry ФАЙЛ] [--state КАТАЛОГ] [--budget N]
                --live — GitHub с токеном из переменной DPMETA_GITHUB_TOKEN (PAT только для чтения, D-17)
            dpmeta-cli state [--state КАТАЛОГ]
            dpmeta-cli history [--state КАТАЛОГ] [--metric ID] [--subject ИМЯ]
            dpmeta-cli smoke --corpus КАТАЛОГ
            """.trimIndent(),
        )
    }

    private fun stateDir(a: Args): Path = a.path("state") ?: Path.of("dpmeta-state")

    private fun store(dir: Path): Store {
        Files.createDirectories(dir)
        return Store(NioVfs(dir), clock, RandomSource.secure())
    }

    private fun <T> Outcome<T>.orExit(what: String, code: Int = 3): T = when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> {
            diagnostics.forEach { out("✗ $what: ${it.render()}") }
            throw Exit(code)
        }
    }

    private class Exit(val code: Int) : Exception()

    private fun registry(text: String, source: String): Registry = RegistryFormat.parse(text, source).orExit("реестр", 2)

    private fun sync(a: Args): Int = try {
        val modes = listOf("corpus", "clones", "live").filter { a.has(it) }
        if (modes.size != 1) throw Usage("нужен ровно один из --corpus, --clones, --live")
        val dir = stateDir(a)
        val store = store(dir)
        val previous = store.readState().orExit("прежнее состояние")
        val budget = a.flags["budget"]?.toIntOrNull() ?: Net.DEFAULT_BUDGET
        val started = clock.monotonicNanos()
        var net: Net? = null
        var etags: EtagFile? = null
        val (reg, reader) = when (modes.single()) {
            "corpus", "clones" -> {
                val root = a.path(modes.single()) ?: throw Usage("--${modes.single()} без каталога")
                val regFile = a.path("registry") ?: if (modes.single() == "corpus") root.resolve("registry.toml") else root.resolve("dpmetaapp/dpmeta/tools/ecosystem.toml")
                if (!Files.isRegularFile(regFile)) throw Usage("нет реестра $regFile")
                registry(Files.readString(regFile), regFile.toString()) to (DirectoryReader(root) as SourceReader)
            }
            else -> {
                val token = env(TOKEN_ENV)?.takeIf { it.isNotBlank() }?.let(::Secret)
                    ?: throw Usage("для --live нужен токен в переменной $TOKEN_ENV")
                etags = EtagFile(DocumentStore(NioVfs(dir), "dpmeta/cache"))
                val n = Net(JdkTransport(), cache = etags, budget = budget).also { net = it }
                val gh = GitHub(n, token)
                val host = RepoRef.parse(HOST).orExit("репозиторий DPmetaApp", 2)
                val text = a.path("registry")?.let { Files.readString(it) }
                    ?: gh.file(host, "dpmeta/tools/ecosystem.toml").orExit("реестр DPmeta с GitHub", 2).value
                registry(text, "ecosystem.toml") to (GitHubReader(gh, host) as SourceReader)
            }
        }
        val known = previous?.projects.orEmpty().mapNotNull { p ->
            ((p.sections["roadmap"]?.payload as? JsonObject)?.get("path") as? JsonString)?.value?.let { (p.name to "roadmap") to it }
        }.toMap()
        val commits = Sync.knownCommits(previous?.projects.orEmpty().mapNotNull { it.sections["branches"]?.payload })
        // Файла не было недавно — не спрашивать до срока правил; отказ переносится со своим временем.
        val recheckAfter = clock.now() - Rules.MISSING_RECHECK
        val carried = previous?.projects.orEmpty().flatMap { p ->
            p.errors.filter { it.code in MISSING && it.at >= recheckAfter }.map { e ->
                SourceFailure(p.name, e.kind, Diagnostic.error(DiagCode.valueOf(e.code), e.message, p.name), e.at)
            }
        }
        val pool = Executors.newFixedThreadPool(THREADS)
        val result = try {
            Sync.run(Sync.plan(reg, known, carried.map { it.project to it.kind }.toSet()), reader, clock, commits, poolOf(pool))
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.MINUTES)
        }
        val state = EcosystemState.derive(reg, result.observations, result.failures + carried, previous, clock.now())
        store.writeState(state).orExit("запись состояния")
        val points = store.appendHistory(state.points()).orExit("история чисел")
        etags?.save()?.orExit("кэш условных запросов")
        val seconds = (clock.monotonicNanos() - started) / 1_000_000_000.0
        out("синхронизация: проектов ${state.projects.size}, наблюдений ${result.observations.size}, отказов ${result.failures.size}, отложено ${result.deferred}")
        for (f in result.failures) out("  ✗ ${f.project}/${f.kind}: ${f.diagnostic.code.name} — ${f.diagnostic.message}")
        if (carried.isNotEmpty()) out("  не спрашивалось (файла не было за ${Rules.MISSING_RECHECK}): ${carried.joinToString(", ") { "${it.project}/${it.kind}" }}")
        for (p in state.projects) {
            val w = p.working(state.asOf).orNull()?.let { if (it) "работает" else "свободен" } ?: "неизвестно"
            out("  ${p.name} [${p.place}]: $w; решений ${p.decisions.orNull() ?: "?"}, TODO ${p.todoOpen.orNull() ?: "?"} (за автором ${p.forAuthor.orNull() ?: "?"}), ROADMAP ${p.roadmapPercent.orNull()?.let { "$it %" } ?: "?"}, PR ${p.openPulls.orNull() ?: "?"}")
        }
        out("работают: ${state.activeProjects.orNull() ?: "?"}; вопросов автору: ${state.questionsOpen.orNull() ?: "?"}; точек истории: $points")
        net?.stats?.let { s ->
            val share = if (s.requests == 0) 0 else s.notModified * 100 / s.requests
            out("A-02: запросов ${s.requests} (бюджет $budget), ответов 304 — ${s.notModified} ($share %), отказано ${s.refused}, ${"%.1f".format(seconds)} с")
        }
        0
    } catch (e: Exit) {
        e.code
    }

    private fun state(a: Args): Int = try {
        val s = store(stateDir(a)).readState().orExit("состояние") ?: run {
            out("состояния нет: сначала dpmeta-cli sync")
            return 2
        }
        out(CanonicalJson.write(s.toJson()))
        0
    } catch (e: Exit) {
        e.code
    }

    private fun history(a: Args): Int = try {
        for (p in store(stateDir(a)).history(a.flags["metric"], a.flags["subject"]).orExit("история")) {
            out(CanonicalJson.write(p.toJson()))
        }
        0
    } catch (e: Exit) {
        e.code
    }

    /** Дым: синхронизация корпуса во временный каталог; каждый показанный проект в состоянии, хранилище читается обратно. */
    private fun smoke(a: Args): Int {
        val corpus = a.path("corpus") ?: throw Usage("smoke без --corpus")
        val dir = Files.createTempDirectory("dpmeta-smoke")
        return try {
            val code = sync(Args(listOf("--corpus", corpus.toString(), "--state", dir.toString())))
            if (code != 0) return code
            val s = store(dir).readState().orExit("состояние после sync") ?: return 1.also { out("✗ состояние не записано") }
            val reg = registry(Files.readString(corpus.resolve("registry.toml")), "registry.toml")
            val missing = reg.shown.map { it.name } - s.projects.map { it.name }.toSet()
            if (missing.isNotEmpty()) {
                out("✗ нет в состоянии: $missing")
                1
            } else {
                out("✓ дым: ${s.projects.size} проектов, состояние и история читаются")
                0
            }
        } catch (e: Exit) {
            e.code
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun poolOf(pool: java.util.concurrent.ExecutorService): Parallel = object : Parallel {
        override fun <T> all(jobs: List<() -> T>): List<T> = jobs.map { job -> pool.submit<T> { job() } }.map { it.get() }
    }

    companion object {
        const val TOKEN_ENV = "DPMETA_GITHUB_TOKEN"

        /** Запросы дат коммитов веток — пулом: первая синхронизация в пределах A-02 (60 с). */
        const val THREADS = 8
        private val MISSING = setOf(DiagCode.GITHUB_NOT_FOUND.name, DiagCode.ENTITY_NOT_FOUND.name)
        const val HOST = "dpsmpx/DPmetaApp"
    }
}
