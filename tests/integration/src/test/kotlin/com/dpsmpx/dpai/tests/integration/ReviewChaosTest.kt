package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.operation.OperationStatus
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.git.GitCommands
import com.dpsmpx.dpai.git.Repository
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.orchestrator.git.GitService
import com.dpsmpx.dpai.security.ApprovalLedger
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.snapshot.SnapshotStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.tasks.ReviewRole
import com.dpsmpx.dpai.tasks.ReviewVerdict
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

/**
 * Ревью задачи (D-73) под хаосом ТЗ, §117 на модельном времени (этап 19):
 * настоящий Git, поток задач с ролями ревью, ответы ревью наугад —
 * прошло, блокирующее, не по схеме, падение, — перезапуски приложения и
 * потеря target, человек отвечает на запросы `task.review`. Инварианты
 * держатся после каждого окна тактов, задачи выполняются, коммиты задач
 * записаны, ревью не застревает: проверенная задача без открытой сессии
 * и без запроса человеку не висит.
 */
class ReviewChaosTest {
    @TempDir
    lateinit var dir: Path

    private val git = LocalProcessHost()

    private fun sh(vararg argv: String) {
        val r = git.run(ProcessSpec(listOf("git", *argv)), 1.minutes).valueOrNull()!!
        assertEquals(0, r.exitCode, (r.stderr + r.stdout).joinToString("\n"))
    }

    @Test
    fun reviewsSurviveRestartsTargetLossAndBadAnswers() {
        val clock = ManualClock()
        val random = RandomSource.seeded(259)
        val chaos = RandomSource.seeded(260)
        val ids = IdGenerator(clock, RandomSource.seeded(261))
        val root = dir.resolve("repo").toFile().apply { mkdirs() }.path
        sh("init", "-q", "-b", "main", root)
        sh("-C", root, "config", "user.email", "dpai-test@example.org")
        sh("-C", root, "config", "user.name", "dpai test")
        File(root, "README.md").writeText("проект\n")
        sh("-C", root, "add", "README.md")
        sh("-C", root, "commit", "-q", "-m", "начало")
        val repo = Repository(ids.next(IdKinds.REPOSITORY), ids.next(IdKinds.TARGET), root)
        val host = MockAgentHost(clock, IdGenerator(clock, RandomSource.seeded(262)))
        val targets = List(2) { TargetSlot(ids.next(IdKinds.TARGET), emptySet(), maxSessions = 2) }
        val logic = Accounts()
        var accounts = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        accounts = Accounts.applyAll(accounts, logic.registerSubscription(accounts, sub, "основная", clock.now(), maxParallelSessions = 64).valueOrNull()!!)
        for ((i, t) in targets.withIndex()) repeat(2) { k ->
            val slot = ids.next(IdKinds.ACCOUNT)
            accounts = Accounts.applyAll(accounts, logic.registerSlot(accounts, slot, sub, t.id, "/c/$i-$k", clock.now()).valueOrNull()!!)
            val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
            accounts = Accounts.applyAll(accounts, logic.observeAuthStatus(accounts, slot, status, clock.now()).valueOrNull()!!)
        }
        val profile = VerificationProfile(ids.next(IdKinds.PROJECT), mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("ок", listOf("ok")))))
        lateinit var o: Orchestrator
        fun boot(): MockRunner {
            val vfs = NioVfs(dir.resolve("data"))
            val journal = (EventJournal.open(vfs, "journal.log", segmentSize = 2_000) as Outcome.Success).value
            val runner = MockRunner.create(clock, RandomSource.seeded(clock.now().epochMillis), host) { session, _ ->
                if (o.sessions.sessions[session]?.review != null) {
                    when (random.nextInt(10)) {
                        0 -> MockScenario.REVIEW_BLOCK
                        1 -> MockScenario.REVIEW_NO_SCHEMA
                        2 -> MockScenario.CRASH
                        else -> MockScenario.REVIEW_PASS
                    }
                } else {
                    when (random.nextInt(30)) {
                        0 -> MockScenario.FAILURE
                        1 -> MockScenario.CRASH
                        else -> MockScenario.SLOW_OUTPUT
                    }
                }
            }
            o = Orchestrator(
                clock, ids, random, journal, runner,
                ProjectConfig(profile.project, repo, dir.resolve("trees").toString(), profile), targets,
                GitService(git, GitCommands(ApprovalLedger()), clock),
                Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), accounts,
                snapshots = SnapshotStore(DocumentStore(vfs, "snapshots")), snapshotEvery = 400, documents = DocumentStore(vfs, "docs"),
            )
            o.recover { AgentClient(host, listOf("dpai"), null, "target mock") }
            return runner
        }
        var runner = boot()
        var created = 0
        var downUntil = -1
        var answered = 0
        var lastIdle = emptySet<Pair<com.dpsmpx.dpai.core.ids.TaskId, com.dpsmpx.dpai.core.clock.Timestamp>>()
        val doneAt = mutableListOf<Int>()
        val roles = listOf(setOf(ReviewRole.CODE), setOf(ReviewRole.CODE, ReviewRole.SECURITY))
        var restarts = 0
        var losses = 0
        for (step in 0 until 600) {
            // Поток задач с ревью; ждущие человека (BLOCKED) места не занимают.
            while (o.tasks.tasks.values.count { !it.terminal && it.state != TaskState.BLOCKED } < 6) {
                o.addTask("задача ${created++}", "работа", policy = TaskPolicy(retryBackoff = 0.minutes, reviews = roles[created % 2]))
            }
            // «Claude» правит своё дерево: коммит задачи настоящий, ревью — по настоящему diff.
            for (w in o.worktrees.worktrees.values) if (w.owner != null && !w.removed) File(w.path, "work-${w.branch.substringAfterLast('/')}.txt").takeIf { !it.exists() }?.writeText("работа\n")
            for (s in host.runIds()) if (host.hasPending(s) && random.nextInt(3) == 0) runner.advance(s)
            o.tick()
            // Человек отвечает на «ревью без итога» — то повтором, то «принять без ревью».
            for (r in o.pendingRequests().filter { it.purpose == "task.review" }) {
                val option = if (answered++ % 2 == 0) "retry" else "waive"
                assertEquals(OperationStatus.SUCCEEDED, o.answer(r.id, option, null, r.hash, "автор").status, "ответ $option на ${r.title}")
            }
            if (chaos.nextInt(150) == 0) {
                runner = boot()
                restarts++
            }
            if (downUntil < 0 && chaos.nextInt(200) == 0) {
                host.reachable = false
                downUntil = step + 20
                losses++
            }
            if (step == downUntil) {
                downUntil = -1
                host.reachable = true
                o.recover { AgentClient(host, listOf("dpai"), null, "target mock") }
            }
            clock.advance(1.seconds)
            if (step % 100 == 99) {
                assertEquals(emptyList(), o.invariants(), "инварианты, шаг $step")
                // Проверенная задача с ревью ждёт сессию ревью, её запуск (слот, место) или ответ человека — но не висит молча:
                // между тактами она может быть без сессии (запуск не удался, ответ только что дан), но не два окна подряд.
                val idle = o.tasks.tasks.values.filter { t ->
                    t.state == TaskState.VERIFIED && !t.held && o.sessions.sessions.values.none { it.task == t.id && !it.terminal } &&
                        o.pendingRequests().none { it.task == t.id } && o.waitingReason(t.id) == null
                }.map { it.id to it.updatedAt }.toSet()
                assertEquals(emptySet(), idle intersect lastIdle, "проверенные задачи висят без ревью и без причины, шаг $step")
                lastIdle = idle
            }
            if (step % 200 == 199) {
                doneAt += o.tasks.tasks.values.count { it.state == TaskState.DONE }
                if (doneAt.size >= 2) assertTrue(doneAt.last() > doneAt[doneAt.size - 2], "контур встал на шаге $step: DONE $doneAt")
            }
        }
        val done = o.tasks.tasks.values.filter { it.state == TaskState.DONE }
        assertTrue(restarts >= 2 && losses >= 1, "хаос был: перезапусков $restarts, потерь target $losses")
        assertTrue(done.size >= 40, "выполнено ${done.size}")
        assertTrue(done.all { t -> t.reviewsLeft().isEmpty() }, "DONE — только с итогом каждой роли")
        assertTrue(done.any { t -> t.reviews.values.any { it.verdict == ReviewVerdict.WAIVED } }, "принятые без ревью — были")
        assertTrue(o.tasks.tasks.values.any { it.retries > 0 && it.state == TaskState.DONE }, "повтор после блокирующего ревью или сбоя — доведён до DONE")
        assertTrue(answered > 0, "запросы task.review были")
        assertTrue(done.count { o.mergesState.commits[it.id] != null } >= done.size / 2, "коммиты задач записаны: ${done.count { o.mergesState.commits[it.id] != null }} из ${done.size}")
    }
}
