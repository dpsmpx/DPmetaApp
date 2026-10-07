package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.contexts.ContextAssembler
import com.dpsmpx.dpai.contexts.ContextSource
import com.dpsmpx.dpai.contexts.SourceLevel
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.SequenceTracker
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.monitoring.Dashboard
import com.dpsmpx.dpai.notifications.NotificationCategory
import com.dpsmpx.dpai.notifications.NotificationEngine
import com.dpsmpx.dpai.notifications.NotificationRequest
import com.dpsmpx.dpai.orchestrator.EventQuery
import com.dpsmpx.dpai.orchestrator.EventSearch
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.security.Origin
import com.dpsmpx.dpai.security.Sensitivity
import com.dpsmpx.dpai.sessions.SessionRecord
import com.dpsmpx.dpai.sessions.Sessions
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.Tasks
import com.dpsmpx.dpai.tasks.TasksState
import com.dpsmpx.dpai.verification.Verifier
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Бюджеты `docs/PERFORMANCE.md`, §3 на хосте и нагрузка ТЗ, §93, §94
 * (этап 17). Замеры — настоящим монотонным временем; итоги пишутся в
 * `build/perf/results.txt` — оттуда числа переносятся в документ.
 */
class PerformanceTest {
    @TempDir
    lateinit var dir: Path

    private val real = Clock.SYSTEM
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(301))

    private data class Stat(val name: String, val avgMs: Double, val p99Ms: Double, val maxMs: Double, val n: Int)

    private val stats = mutableListOf<String>()

    /** [warmup] прогонов без учёта, затем [n] замеров. */
    private fun measure(name: String, n: Int, warmup: Int = 20, body: (Int) -> Unit): Stat {
        repeat(warmup) { body(-1 - it) }
        val xs = DoubleArray(n)
        for (i in 0 until n) {
            val t0 = real.monotonicNanos()
            body(i)
            xs[i] = (real.monotonicNanos() - t0) / 1_000_000.0
        }
        xs.sort()
        val s = Stat(name, xs.average(), xs[((n - 1) * 0.99).toInt()], xs.last(), n)
        stats += "%-28s n=%-6d avg=%8.3f мс  p99=%8.3f мс  max=%8.3f мс".format(s.name, s.n, s.avgMs, s.p99Ms, s.maxMs)
        return s
    }

    /**
     * Две операции попеременно в одном цикле: всплеск диска или
     * планировщика ОС приходится на обе серии, а не на одну — сравнение
     * «код против ОС» не зависит от того, какая серия шла во время всплеска.
     */
    private fun measurePair(a: String, b: String, n: Int, warmup: Int = 20, bodyA: () -> Unit, bodyB: () -> Unit): Pair<Stat, Stat> {
        repeat(warmup) {
            bodyA()
            bodyB()
        }
        val xa = DoubleArray(n)
        val xb = DoubleArray(n)
        for (i in 0 until n) {
            val t0 = real.monotonicNanos()
            bodyA()
            val t1 = real.monotonicNanos()
            bodyB()
            val t2 = real.monotonicNanos()
            xa[i] = (t1 - t0) / 1_000_000.0
            xb[i] = (t2 - t1) / 1_000_000.0
        }
        fun stat(name: String, xs: DoubleArray): Stat {
            xs.sort()
            val s = Stat(name, xs.average(), xs[((n - 1) * 0.99).toInt()], xs.last(), n)
            stats += "%-28s n=%-6d avg=%8.3f мс  p99=%8.3f мс  max=%8.3f мс".format(s.name, s.n, s.avgMs, s.p99Ms, s.maxMs)
            return s
        }
        return stat(a, xa) to stat(b, xb)
    }

    private fun note(line: String) {
        stats += line
    }

    @AfterTest
    fun report() {
        val root = System.getProperty("dpai.repoRoot") ?: return
        val f = File(root, "tests/integration/build/perf/results.txt")
        f.parentFile.mkdirs()
        f.appendText(stats.joinToString("\n", postfix = "\n"))
    }

    private fun journal(name: String): EventJournal = (EventJournal.open(NioVfs(Files.createTempDirectory(dir, name)), "journal.log") as Outcome.Success).value

    @Test
    fun taskCreationWithJournal() {
        val o = orchestrator(slots = 1, journal = journal("create"))
        val s = measure("создание задачи", 300) { o.addTask("задача $it", "цель") }
        assertTrue(s.p99Ms <= 50.0, "создание задачи p99 ${s.p99Ms} мс > 50")
    }

    @Test
    fun schedulerTick() {
        val tasks = Tasks()
        var st = TasksState.EMPTY
        repeat(1000) { i ->
            val id = ids.next(IdKinds.TASK)
            val deps = if (i % 10 == 0 || st.tasks.isEmpty()) emptySet() else setOf(st.tasks.keys.last())
            st = Tasks.applyAll(st, tasks.create(st, id, "т$i", "ц", Priority.entries[i % 5], clock.now(), deps).valueOrNull()!!)
            st = Tasks.applyAll(st, tasks.enqueue(st, id, clock.now()).valueOrNull()!!)
        }
        st = Tasks.applyAll(st, tasks.promote(st, clock.now()))
        val offers = List(50) { Tasks.TargetOffer(ids.next(IdKinds.TARGET), emptySet(), 2) }
        // Аккаунты: 100 слотов — выбор слота для каждого назначения.
        val (accountsLogic, accounts) = accounts(100, offers.map { it.target })
        val ready = Tasks.applyAll(st, tasks.promote(st, clock.now()))
        measure("  из них: promote", 200) { tasks.promote(st, clock.now()) }
        measure("  из них: план назначений", 200) { tasks.plan(ready, clock.now(), offers) }
        val plan0 = tasks.plan(ready, clock.now(), offers)
        measure("  из них: выбор слотов", 200) { for (a in plan0) accountsLogic.lease(accounts, ids.next(IdKinds.SESSION), a.task, clock.now(), a.target) }
        val s = measure("такт планировщика", 200) {
            val plan = tasks.plan(Tasks.applyAll(st, tasks.promote(st, clock.now())), clock.now(), offers)
            for (a in plan) accountsLogic.lease(accounts, ids.next(IdKinds.SESSION), a.task, clock.now(), a.target)
        }
        assertTrue(s.p99Ms <= 20.0, "такт планировщика p99 ${s.p99Ms} мс > 20 (1000 задач, 100 слотов, 50 targets)")
    }

    @Test
    fun eventIngest() {
        val j = journal("ingest")
        val tracker = SequenceTracker()
        val session = ids.next(IdKinds.SESSION)
        var seq = 0L
        val s = measure("приём события", 1000) {
            seq++
            val e = EventEnvelope(ids.next(IdKinds.EVENT), EventType.SESSION_OUTPUT, StreamRef.of(session), seq, clock.now(), clock.now(),
                payload = JsonObject.of("text" to JsonString("строка вывода $seq")))
            tracker.offer(e)
            check(j.append(e).isSuccess)
        }
        assertTrue(s.avgMs <= 2.0 && s.p99Ms <= 20.0, "приём события avg ${s.avgMs}, p99 ${s.p99Ms} (с fsync каждого)")
    }

    /**
     * Атомарная запись документа рядом с нижней границей ОС (создать, fsync,
     * переименовать) в тех же условиях: если сам диск или антивирус хоста
     * дают выбросы, бюджет 10 мс проверяется на доле своего кода сверх ОС,
     * а выбросы ОС записываются в отчёт.
     */
    @Test
    fun storageWrite() {
        val store = DocumentStore(NioVfs(Files.createTempDirectory(dir, "docs")), "d")
        val doc = JsonObject.of("payload" to JsonString("x".repeat(4096)))
        val raw = Files.createTempDirectory(dir, "raw")
        val bytes = ByteArray(4200) { 'x'.code.toByte() }
        val (os, s) = measurePair(
            "ОС: создать+fsync+rename", "запись в хранилище", 300,
            bodyA = {
                val tmp = raw.resolve("doc.tmp")
                FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use {
                    it.write(ByteBuffer.wrap(bytes))
                    it.force(true)
                }
                Files.move(tmp, raw.resolve("doc"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            },
            bodyB = { check(store.write("doc", doc).isSuccess) },
        )
        assertTrue(s.p99Ms <= 10.0 || s.p99Ms - os.p99Ms <= 10.0, "атомарная запись p99 ${s.p99Ms} мс, ОС p99 ${os.p99Ms} мс: доля кода > 10 мс")
        assertTrue(s.avgMs <= 10.0, "атомарная запись в среднем ${s.avgMs} мс > 10")
    }

    @Test
    fun sessionListAndDashboard() {
        val sessions = SessionsState((1..1000).associate {
            val r = SessionRecord(
                ids.next(IdKinds.SESSION), "k$it", ids.next(IdKinds.TASK), ids.next(IdKinds.ACCOUNT), ids.next(IdKinds.SUBSCRIPTION), ids.next(IdKinds.TARGET),
                "~/.claude", ProviderSessionId.newUuid(RandomSource.seeded(it.toLong())), null,
                if (it % 50 == 0) SessionState.RUNNING else SessionState.COMPLETED, createdAt = clock.now(), updatedAt = clock.now(), lastHeartbeat = clock.now(),
            )
            r.id to r
        })
        val logic = Sessions()
        val s = measure("список 1000 сессий", 200) { Dashboard.build(sessions, TasksState.EMPTY, AccountsState.EMPTY, emptyMap(), Measure.observed(0), logic, clock.now()) }
        assertTrue(s.p99Ms <= 30.0, "список сессий p99 ${s.p99Ms} мс > 30")
    }

    @Test
    fun searchHundredThousandEvents() {
        val stream = StreamRef.of(ids.next(IdKinds.SESSION))
        val events = (1L..100_000L).map { n ->
            EventEnvelope(ids.next(IdKinds.EVENT), if (n % 3 == 0L) EventType.SESSION_OUTPUT else EventType.SESSION_HEARTBEAT, stream, n, clock.now(), clock.now(),
                payload = JsonObject.of("text" to JsonString(if (n == 17L) "редкая ошибка компиляции" else "обычная строка $n")))
        }
        var found = emptyList<EventEnvelope>()
        val s = measure("поиск 100 тыс. событий", 30, warmup = 5) { found = EventSearch.search(events, EventQuery(text = "РЕДКАЯ ошибка")) }
        assertEquals(listOf(17L), found.map { it.sequence })
        assertTrue(s.p99Ms <= 200.0, "поиск p99 ${s.p99Ms} мс > 200")
    }

    @Test
    fun contextAssembly() {
        val project = ids.next(IdKinds.PROJECT)
        val sources = List(200) { ContextSource("doc:$it.md", SourceLevel.DOCUMENT, project, Sensitivity.INTERNAL, Origin.REPOSITORY, "строка документа $it\n".repeat(50)) }
        val s = measure("сборка контекста", 50, warmup = 5) {
            ContextAssembler.assemble(ids.next(IdKinds.CONTEXT), ids.next(IdKinds.TASK), project, "цель", "дальше", sources, clock.now()).valueOrNull()!!
        }
        assertTrue(s.p99Ms <= 500.0, "сборка контекста p99 ${s.p99Ms} мс > 500")
    }

    @Test
    fun notificationSubmit() {
        val engine = NotificationEngine()
        val s = measure("отправка уведомления", 2000) { engine.submit(NotificationRequest("k${it % 40}", NotificationCategory.WARNING, "т", "б", null, clock.now())) }
        assertTrue(s.p99Ms <= 50.0)
    }

    /** ТЗ, §93: 100+ задач, 50 слотов, 20 targets, 20+ параллельных сессий, тысячи событий. */
    @Test
    fun stress() = load("нагрузка ТЗ §93", tasks = 120, slots = 50, targets = 20, perTarget = 2)

    /** ТЗ, §94: 1000 задач, 100 слотов, 50 targets, 50 параллельных сессий — поиск архитектурных пределов. */
    @Test
    fun extremeStress() = load("экстремум ТЗ §94", tasks = 1000, slots = 100, targets = 50, perTarget = 1)

    /** Бюджет одного запуска сессии контуром (`docs/PERFORMANCE.md`, §3): аренда, сессия, контекст, вызов агента, журнал. */
    private val launchBudgetMs = 20.0

    private fun load(name: String, tasks: Int, slots: Int, targets: Int, perTarget: Int) {
        val j = journal("load")
        val o = orchestrator(slots, targets, perTarget, j)
        val all = List(tasks) { o.addTask("задача $it", "цель $it", Priority.entries[it % 5]).valueOrNull()!! }
        val rt = Runtime.getRuntime()
        System.gc()
        val heap0 = rt.totalMemory() - rt.freeMemory()
        var maxParallel = 0
        var worstTick = 0.0
        var totalMs = 0.0
        var ticks = 0
        while (all.any { o.tasks.tasks.getValue(it).state != TaskState.DONE } && ticks < 2000) {
            val t0 = real.monotonicNanos()
            o.tick()
            val ms = (real.monotonicNanos() - t0) / 1_000_000.0
            worstTick = maxOf(worstTick, ms)
            totalMs += ms
            maxParallel = maxOf(maxParallel, o.sessions.sessions.values.count { !it.terminal })
            ticks++
            // «Claude» продвигается между тактами: по шагу на сессию.
            o.sessions.sessions.keys.forEach { lastRunner!!.advance(it) }
        }
        System.gc()
        val heap1 = rt.totalMemory() - rt.freeMemory()
        val journalBytes = Files.walk(dir).filter { it.fileName.toString() == "journal.log" }.mapToLong { Files.size(it) }.sum()
        val launched = o.sessions.sessions.size
        val perLaunch = totalMs / launched
        note("$name: задач $tasks, слотов $slots, targets $targets; тактов $ticks, худший такт %.1f мс, на запуск сессии %.2f мс, параллельно до $maxParallel сессий, событий ${j.records().size}, журнал ${journalBytes / 1024} КиБ, прирост кучи ${(heap1 - heap0) / (1 shl 20)} МиБ".format(worstTick, perLaunch))
        assertTrue(all.all { o.tasks.tasks.getValue(it).state == TaskState.DONE }, "$name: не все задачи DONE")
        assertTrue(maxParallel >= minOf(slots, targets * perTarget, 20), "$name: параллельно только $maxParallel")
        assertTrue(o.problems.isEmpty(), o.problems.toString())
        assertTrue(perLaunch <= launchBudgetMs, "$name: на запуск сессии $perLaunch мс > $launchBudgetMs (все такты / число сессий)")
    }

    private var lastRunner: MockRunner? = null

    private fun accounts(slots: Int, targets: List<com.dpsmpx.dpai.core.ids.ExecutionTargetId>): Pair<Accounts, AccountsState> {
        val logic = Accounts()
        var acc = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        acc = Accounts.applyAll(acc, logic.registerSubscription(acc, sub, "основная", clock.now(), maxParallelSessions = 64).valueOrNull()!!)
        repeat(slots) { i ->
            val slot = ids.next(IdKinds.ACCOUNT)
            acc = Accounts.applyAll(acc, logic.registerSlot(acc, slot, sub, targets[i % targets.size], "/c/$i", clock.now()).valueOrNull()!!)
            val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
            acc = Accounts.applyAll(acc, logic.observeAuthStatus(acc, slot, status, clock.now()).valueOrNull()!!)
        }
        return logic to acc
    }

    private fun orchestrator(slots: Int, targetCount: Int = slots, perTarget: Int = 1, journal: EventJournal): Orchestrator {
        val targets = List(targetCount) { TargetSlot(ids.next(IdKinds.TARGET), emptySet(), perTarget) }
        val logic = Accounts()
        var acc = AccountsState.EMPTY
        val subs = (0 until (slots + 63) / 64).map { ids.next(IdKinds.SUBSCRIPTION).also { s -> acc = Accounts.applyAll(acc, logic.registerSubscription(acc, s, "п$s", clock.now(), maxParallelSessions = 64).valueOrNull()!!) } }
        repeat(slots) { i ->
            val slot = ids.next(IdKinds.ACCOUNT)
            val sub = subs[i / 64]
            acc = Accounts.applyAll(acc, logic.registerSlot(acc, slot, sub, targets[i % targets.size].id, "/c/$i", clock.now()).valueOrNull()!!)
            val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org$sub", "a$sub@example.org"), "max")
            acc = Accounts.applyAll(acc, logic.observeAuthStatus(acc, slot, status, clock.now()).valueOrNull()!!)
        }
        // Медленный сценарий: сессии живут несколько тактов, параллельность видна между тактами.
        val runner = MockRunner.create(clock, RandomSource.seeded(302)) { _, _ -> MockScenario.SLOW_OUTPUT }
        lastRunner = runner
        return Orchestrator(
            clock, ids, RandomSource.seeded(303), journal, runner,
            ProjectConfig(ids.next(IdKinds.PROJECT), null, dir.toString(), profile = null).let { c ->
                c.copy(profile = com.dpsmpx.dpai.verification.VerificationProfile(c.project, mapOf(com.dpsmpx.dpai.verification.VerifyLevel.NORMAL to listOf(com.dpsmpx.dpai.verification.VerifyStep("ок", listOf("ok"))))))
            },
            // Шаг проверки — мгновенный: меряется контур, а не запуск JVM на каждую задачу.
            targets, null, Verifier({ _, _, _ -> Outcome.success(com.dpsmpx.dpai.execution.process.Completed(0, emptyList(), emptyList())) }, clock), acc,
        )
    }
}
