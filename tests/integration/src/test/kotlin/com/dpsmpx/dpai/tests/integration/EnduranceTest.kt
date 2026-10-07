package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.monitoring.DisplayState
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Выносливость (ТЗ, §116, §117; критерий этапа 19): контур в реальном
 * времени на синтетических сессиях (`MockRunner`, разрешено ТЗ, §116) со
 * случайными перезапусками приложения, потерей target и сбоями сессий.
 * Раз в период — замер: память после сборки мусора, размер журнала,
 * события, дубли, выполненные задачи (дрейф планировщика), устаревшие
 * сессии, уведомления, процессорное время, потоки; и инварианты ТЗ, §118.
 *
 * Включается только явно: `-Ddpai.endurance.minutes=1440` (24 ч). Отчёт —
 * `build/audits/final-stability/endurance-<минуты>m.md` (ТЗ, §123).
 */
class EnduranceTest {
    private val minutes = System.getProperty("dpai.endurance.minutes")?.toLongOrNull()

    private data class Sample(
        val minute: Long, val heapMiB: Long, val journalKiB: Long, val events: Long, val done: Int, val open: Int,
        val stale: Int, val notifications: Int, val cpuSec: Long, val threads: Int, val restarts: Int, val violations: Int, val orphans: Int,
    )

    @Test
    fun enduranceWithChaos() {
        assumeTrue(minutes != null, "dpai.endurance.minutes не задан: выносливость — отдельным долгим прогоном")
        val total = minutes!!
        val clock = Clock.SYSTEM
        val random = RandomSource.seeded(116)
        val ids = IdGenerator(clock, RandomSource.seeded(117))
        val root = File(System.getProperty("dpai.repoRoot"), "build/endurance/run-${clock.now().epochMillis}").apply { mkdirs() }
        val host = MockAgentHost(clock, IdGenerator(clock, RandomSource.seeded(118)))
        val targets = List(4) { TargetSlot(ids.next(IdKinds.TARGET), emptySet(), maxSessions = 2) }
        val accounts = accounts(ids, clock, targets)
        val project: ProjectId = ids.next(IdKinds.PROJECT)
        val profile = VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("ок", listOf("ok")))))

        fun boot(): Pair<Orchestrator, MockRunner> {
            val journal = (EventJournal.open(NioVfs(root.toPath()), "journal.log") as Outcome.Success).value
            val runner = MockRunner.create(clock, RandomSource.seeded(clock.now().epochMillis), host) { _, _ ->
                when (random.nextInt(40)) {
                    0 -> MockScenario.FAILURE
                    1 -> MockScenario.CRASH
                    2 -> MockScenario.PARTIAL_COMPLETION
                    else -> MockScenario.SLOW_OUTPUT
                }
            }
            val o = Orchestrator(
                clock, ids, random, journal, runner, ProjectConfig(project, null, root.path, profile), targets, null,
                Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), accounts,
            )
            o.recover { AgentClient(host, listOf("dpai"), null, "target mock") }
            return o to runner
        }

        var (o, runner) = boot()
        var restarts = 0
        var created = 0
        val samples = mutableListOf<Sample>()
        val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val begin = clock.monotonicNanos()
        val period = (total / 24).coerceIn(1, 60)
        var nextSample = 0L
        var targetDownUntil = -1L
        val pace = CountDownLatch(1)

        fun elapsedMin() = (clock.monotonicNanos() - begin) / 60_000_000_000L
        while (elapsedMin() < total) {
            val minute = elapsedMin()
            // Поток работы: держим ~20 открытых задач.
            while (o.tasks.tasks.values.count { !it.terminal } < 20) o.addTask("задача ${created++}", "синтетическая работа")
            for (s in host.runIds()) if (host.hasPending(s) && random.nextInt(4) == 0) runner.advance(s)
            o.tick()
            // Хаос (ТЗ, §117): перезапуск приложения ~раз в 20 мин, потеря target на 30 с ~раз в 15 мин.
            if (random.nextInt(20 * 60) == 0) {
                val (o2, r2) = boot()
                o = o2
                runner = r2
                restarts++
            }
            if (targetDownUntil < 0 && random.nextInt(15 * 60) == 0) {
                host.reachable = false
                targetDownUntil = clock.monotonicNanos() + 30_000_000_000L
            }
            if (targetDownUntil >= 0 && clock.monotonicNanos() >= targetDownUntil) {
                host.reachable = true
                targetDownUntil = -1
                o.recover { AgentClient(host, listOf("dpai"), null, "target mock") }
            }
            if (minute >= nextSample) {
                nextSample = minute + period
                samples += sample(o, host, root, minute, os, restarts)
                report(total, samples)
            }
            pace.await(1, TimeUnit.SECONDS)
        }
        host.reachable = true
        samples += sample(o, host, root, elapsedMin(), os, restarts)
        report(total, samples)

        assertEquals(0, samples.sumOf { it.violations }, "инварианты ТЗ, §118 нарушались: ${o.invariants()}")
        assertEquals(0, samples.last().orphans, "сироты на target в конце прогона")
        val doneSeries = samples.map { it.done }
        assertTrue(doneSeries.zipWithNext().all { (a, b) -> b > a }, "планировщик не продвигался между замерами: $doneSeries")
    }

    private fun accounts(ids: IdGenerator, clock: Clock, targets: List<TargetSlot>): AccountsState {
        val logic = Accounts()
        var acc = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        acc = Accounts.applyAll(acc, logic.registerSubscription(acc, sub, "основная", clock.now(), maxParallelSessions = 64).valueOrNull()!!)
        for ((i, t) in targets.withIndex()) {
            repeat(2) { k ->
                val slot = ids.next(IdKinds.ACCOUNT)
                acc = Accounts.applyAll(acc, logic.registerSlot(acc, slot, sub, t.id, "/c/$i-$k", clock.now()).valueOrNull()!!)
                val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
                acc = Accounts.applyAll(acc, logic.observeAuthStatus(acc, slot, status, clock.now()).valueOrNull()!!)
            }
        }
        return acc
    }

    private fun sample(o: Orchestrator, host: MockAgentHost, root: File, minute: Long, os: com.sun.management.OperatingSystemMXBean, restarts: Int): Sample {
        val rt = Runtime.getRuntime()
        rt.gc()
        val view = o.dashboard()
        return Sample(
            minute = minute,
            heapMiB = (rt.totalMemory() - rt.freeMemory()) shr 20,
            journalKiB = File(root, "journal.log").length() shr 10,
            events = o.eventCount(),
            done = o.tasks.tasks.values.count { it.state == TaskState.DONE },
            open = o.tasks.tasks.values.count { !it.terminal },
            stale = view.rows.count { it.state == DisplayState.STALE },
            notifications = o.posted.size,
            cpuSec = os.processCpuTime / 1_000_000_000L,
            threads = Thread.activeCount(),
            restarts = restarts,
            violations = o.invariants().size,
            // Сироты (ТЗ, §119): запуск на target идёт, а сессия по журналу уже завершена.
            orphans = host.runIds().count { r -> host.state(r) == RunState.RUNNING && o.sessions.sessions[r]?.terminal == true },
        )
    }

    private fun report(total: Long, samples: List<Sample>) {
        val f = File(System.getProperty("dpai.repoRoot"), "build/audits/final-stability/endurance-${total}m.md")
        f.parentFile.mkdirs()
        f.writeText(
            buildString {
                appendLine("# Выносливость — $total мин (ТЗ, §116, §117)")
                appendLine()
                appendLine("| мин | куча, МиБ | журнал, КиБ | событий | DONE | открыто | STALE | уведомлений | CPU, с | потоков | перезапусков | нарушений | сирот |")
                appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
                for (s in samples) {
                    appendLine("| ${s.minute} | ${s.heapMiB} | ${s.journalKiB} | ${s.events} | ${s.done} | ${s.open} | ${s.stale} | ${s.notifications} | ${s.cpuSec} | ${s.threads} | ${s.restarts} | ${s.violations} | ${s.orphans} |")
                }
            },
        )
    }
}
