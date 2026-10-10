package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.operation.OperationStatus
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.notifications.NotificationDecision
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

/**
 * Контроллер с проектом (D-35): репозиторий на target, дерево задачи,
 * ход «Claude», проверка через агента, коммит, `DONE`, слияние по
 * одобрению. Git — настоящий, на этой машине (как на target по SSH: только
 * `git -C`); агент — mock, «Claude» правит файл в рабочем дереве.
 */
class LiveProjectTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(91))
    private val agent = MockAgentHost(clock, ids)
    private val local = LocalProcessHost()

    /** Смерть процесса приложения: `Error`, а не исключение, — её не ловит ни один обработчик контура. */
    private class Killed : Error()

    /** На каком вызове target «убить» приложение (один раз). */
    private var crashOn: ((ProcessSpec) -> Boolean)? = null

    /** Сколько раз запускался «Claude». */
    private var claudeRuns = 0

    /** Хост target: Git — настоящий, остальное — агент. */
    private val targetHost = object : ProcessHost {
        override fun start(spec: ProcessSpec, onOutput: (com.dpsmpx.dpai.execution.process.OutputLine) -> Unit) = run {
            crashOn?.let {
                if (it(spec)) {
                    crashOn = null
                    throw Killed()
                }
            }
            if (spec.command.first() == "git") local.start(spec, onOutput) else agent.start(spec, onOutput)
        }
    }

    private fun git(vararg argv: String) {
        val r = local.run(ProcessSpec(listOf("git", *argv)), 1.minutes).valueOrNull()!!
        assertEquals(0, r.exitCode, (r.stderr + r.stdout).joinToString("\n"))
    }

    @Test
    fun taskRunsInWorktreeIsVerifiedCommittedAndMergedOnApproval() {
        val root = dir.resolve("repo").toFile().apply { mkdirs() }.path.replace('\\', '/')
        git("init", "-q", "-b", "main", root)
        git("-C", root, "config", "user.email", "dpai-test@example.org")
        git("-C", root, "config", "user.name", "dpai test")
        File(root, "README.md").writeText("проект\n")
        git("-C", root, "add", "README.md")
        git("-C", root, "commit", "-q", "-m", "начало")

        agent.onStart = { run, spec, _ ->
            when {
                spec.command.take(3) == listOf("claude", "auth", "status") -> {
                    agent.output(run, """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty"}""")
                    agent.exit(run, 0)
                }
                spec.command.first() == "claude" -> {
                    // «Claude» правит файл в рабочем дереве задачи.
                    File(spec.workDir!!, "feature.txt").writeText("новая возможность\n")
                    val c = spec.command
                    MockRunner.script(MockScenario.SUCCESS, c[c.indexOfFirst { it == "--session-id" || it == "--resume" } + 1]).forEach { agent.output(run, it) }
                    agent.exit(run, 0, ExitReason.EXITED)
                }
                else -> {
                    agent.output(run, "проверка в ${spec.workDir}: ok")
                    agent.exit(run, 0)
                }
            }
        }
        val target = TargetForm.validate(ids.next(IdKinds.TARGET), "сервер", "build.lan", "22", "dev", false, "/opt/dpai/bin/dpai", "/home/dev/.claude-dpai").valueOrNull()!!
        val worktrees = dir.resolve("trees").toString().replace('\\', '/')
        val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, root, worktrees, "main", listOf(com.dpsmpx.dpai.verification.VerifyStep("тесты", listOf("make", "test"))))
        val s = LiveSystem(clock, NioVfs(dir.resolve("ctl").also { it.toFile().mkdirs() }), listOf(target), { _, _ -> targetHost }, "/known_hosts", RandomSource.seeded(92), project = project)
        val task = s.addTask("фича", "добавить возможность").valueOrNull()!!
        repeat(6) {
            s.step()
            assertEquals(emptyList(), s.orchestrator.invariants())
            clock.advance(10.seconds)
        }
        val record = s.orchestrator.tasks.tasks.getValue(task)
        assertEquals(TaskState.DONE, record.state, s.problems.toString() + s.orchestrator.problems)
        assertEquals("PASSED", record.verification!!.verdict)
        val tree = s.orchestrator.worktrees.worktrees.values.single()
        assertTrue(tree.path.startsWith(worktrees), "дерево задачи — в каталоге деревьев проекта")
        // Слияние в main — только по одобрению.
        assertTrue(!File(root, "feature.txt").exists())
        val request = s.orchestrator.pendingRequests().single { it.purpose == "task.merge" }
        assertEquals(com.dpsmpx.dpai.core.operation.OperationStatus.SUCCEEDED, s.orchestrator.answer(request.id, "merge", null, request.hash, "автор").status)
        assertTrue(File(root, "feature.txt").exists(), "после одобрения — слито в main")
        assertEquals(listOf(task), s.orchestrator.merged)
        assertTrue(s.orchestrator.posted.none { it is NotificationDecision.Held })
    }

    /**
     * A-52: работа проверена, а коммит не удался (индекс дерева занят) —
     * задача не DONE и ветка без работы не предлагается к слиянию: задача
     * BLOCKED, человек спрошен, работа — в дереве задачи.
     */
    @Test
    fun failedCommitBlocksInsteadOfDoneWithoutWork() {
        val root = dir.resolve("repo").toFile().apply { mkdirs() }.path.replace('\\', '/')
        git("init", "-q", "-b", "main", root)
        git("-C", root, "config", "user.email", "dpai-test@example.org")
        git("-C", root, "config", "user.name", "dpai test")
        File(root, "README.md").writeText("проект\n")
        git("-C", root, "add", "README.md")
        git("-C", root, "commit", "-q", "-m", "начало")
        agent.onStart = { run, spec, _ ->
            when {
                spec.command.take(3) == listOf("claude", "auth", "status") -> {
                    agent.output(run, """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty"}""")
                    agent.exit(run, 0)
                }
                spec.command.first() == "claude" -> {
                    File(spec.workDir!!, "feature.txt").writeText("новая возможность\n")
                    // Индекс дерева занят чужим процессом: `git add` откажет.
                    val gitdir = File(spec.workDir!!, ".git").readText().substringAfter("gitdir:").trim()
                    File(gitdir, "index.lock").writeText("")
                    val c = spec.command
                    MockRunner.script(MockScenario.SUCCESS, c[c.indexOfFirst { it == "--session-id" || it == "--resume" } + 1]).forEach { agent.output(run, it) }
                    agent.exit(run, 0, ExitReason.EXITED)
                }
                else -> {
                    agent.output(run, "ok")
                    agent.exit(run, 0)
                }
            }
        }
        val target = TargetForm.validate(ids.next(IdKinds.TARGET), "сервер", "build.lan", "22", "dev", false, "/opt/dpai/bin/dpai", "/home/dev/.claude-dpai").valueOrNull()!!
        val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, root, dir.resolve("trees").toString().replace('\\', '/'), "main", listOf(com.dpsmpx.dpai.verification.VerifyStep("тесты", listOf("make", "test"))))
        val s = LiveSystem(clock, NioVfs(dir.resolve("ctl").also { it.toFile().mkdirs() }), listOf(target), { _, _ -> targetHost }, "/known_hosts", RandomSource.seeded(95), project = project)
        val task = s.addTask("фича", "добавить возможность").valueOrNull()!!
        repeat(6) {
            s.step()
            assertEquals(emptyList(), s.orchestrator.invariants())
            clock.advance(10.seconds)
        }
        val record = s.orchestrator.tasks.tasks.getValue(task)
        assertEquals(TaskState.BLOCKED, record.state)
        assertEquals(com.dpsmpx.dpai.core.diagnostics.DiagCode.GIT_COMMAND_FAILED, record.lastReason)
        assertTrue(s.orchestrator.pendingRequests().none { it.purpose == "task.merge" }, "ветка без работы к слиянию не предложена")
        assertTrue(s.orchestrator.pendingRequests().any { it.purpose == "task.blocked" })
        val tree = s.orchestrator.worktrees.worktrees.values.single()
        assertTrue(File(tree.path, "feature.txt").exists(), "работа — в дереве задачи")
    }

    /**
     * Ревью A-68, A-53: `git merge` сливает в выгруженную ветку, а одобрено
     * слияние в main. У автора выгружена своя ветка — отказ с причиной и
     * «Повторить слияние», ветка автора не переключается; выгрузил main —
     * слито.
     */
    @Test
    fun mergeGoesOnlyIntoTheApprovedBranch() {
        val (s, root) = doneAwaitingMerge(96)
        git("-C", root, "checkout", "-q", "-b", "author-work")
        val refused = answerMerge(s)
        assertEquals(OperationStatus.FAILED, refused.status)
        assertEquals(DiagCode.MERGE_TARGET_NOT_READY, refused.diagnostics.first().code, refused.diagnostics.toString())
        assertTrue(!File(root, "feature.txt").exists(), "в ветку автора не слито")
        assertEquals("author-work", gitOut("-C", root, "rev-parse", "--abbrev-ref", "HEAD"))
        assertEquals(emptyList(), s.orchestrator.merged)
        git("-C", root, "checkout", "-q", "main")
        assertEquals(OperationStatus.SUCCEEDED, answerMerge(s).status)
        assertTrue(File(root, "feature.txt").exists(), "после «Повторить слияние» — слито в main")
    }

    /**
     * Ревью A-54: конфликт не оставляет рабочую копию автора посреди
     * слияния — оно отменено, копия чистая, запрос «Повторить слияние» ждёт.
     */
    @Test
    fun conflictingMergeIsAbortedAndAskedAgain() {
        val (s, root) = doneAwaitingMerge(97)
        File(root, "feature.txt").writeText("своё\n")
        git("-C", root, "add", "feature.txt")
        git("-C", root, "commit", "-q", "-m", "своё")
        assertEquals(OperationStatus.FAILED, answerMerge(s).status)
        assertTrue(!File(root, ".git/MERGE_HEAD").exists(), "слияние отменено")
        assertEquals("", gitOut("-C", root, "status", "--porcelain"))
        assertEquals("своё\n", File(root, "feature.txt").readText())
        assertTrue(s.orchestrator.pendingRequests().any { it.purpose == "task.merge" }, "«Повторить слияние» ждёт")
        assertEquals(emptyList(), s.orchestrator.merged)
    }

    /** Ревью A-54: в копии незакоммиченная правка автора — не сливать поверх неё; правка цела. */
    @Test
    fun mergeRefusesDirtyWorkingCopy() {
        val (s, root) = doneAwaitingMerge(98)
        File(root, "README.md").appendText("правка автора\n")
        val refused = answerMerge(s)
        assertEquals(DiagCode.MERGE_TARGET_NOT_READY, refused.diagnostics.first().code, refused.diagnostics.toString())
        assertEquals("проект\nправка автора\n", File(root, "README.md").readText())
        assertTrue(!File(root, "feature.txt").exists())
    }

    private fun gitOut(vararg argv: String): String {
        val r = local.run(ProcessSpec(listOf("git", *argv)), 1.minutes).valueOrNull()!!
        assertEquals(0, r.exitCode, (r.stderr + r.stdout).joinToString("\n"))
        return r.stdout.joinToString("\n").trim()
    }

    private fun answerMerge(s: LiveSystem) =
        s.orchestrator.pendingRequests().single { it.purpose == "task.merge" }.let { s.orchestrator.answer(it.id, "merge", null, it.hash, "автор") }

    /**
     * Ревью A-56: приложение умерло посреди проверки задачи (ход Claude уже
     * завершён). После перезапуска проверка идёт снова — Claude заново не
     * запускается, — задача DONE, слияние спрошено.
     */
    @Test
    fun crashDuringVerificationResumesVerificationNotClaude() {
        val (project, target) = repoProject()
        crashOn = { "start" in it.command && it.stdin?.contains("\"make\"") == true }
        val first = live(project, target, 101)
        val task = first.addTask("фича", "добавить возможность").valueOrNull()!!
        assertTrue(!stepsUntilKilled(first, 6), "приложение «умерло» на проверке")
        val second = live(project, target, 102)
        repeat(3) {
            second.step()
            clock.advance(10.seconds)
        }
        assertEquals(TaskState.DONE, second.orchestrator.tasks.tasks.getValue(task).state, second.problems.toString() + second.orchestrator.problems)
        assertEquals(1, claudeRuns, "Claude заново не запускался")
        assertTrue(second.orchestrator.pendingRequests().any { it.purpose == "task.merge" })
    }

    /** Ревью A-56: приложение умерло после «проверка прошла», до коммита — после перезапуска коммит, DONE, запрос слияния. */
    @Test
    fun crashAfterVerifiedCommitsAndAsksMergeOnRestart() {
        val (project, target) = repoProject()
        crashOn = { it.command.first() == "git" && "add" in it.command && "worktree" !in it.command }
        val first = live(project, target, 103)
        val task = first.addTask("фича", "добавить возможность").valueOrNull()!!
        assertTrue(!stepsUntilKilled(first, 6), "приложение «умерло» перед коммитом")
        assertEquals(TaskState.VERIFIED, first.orchestrator.tasks.tasks.getValue(task).state)
        val second = live(project, target, 104)
        assertEquals(TaskState.DONE, second.orchestrator.tasks.tasks.getValue(task).state, second.problems.toString() + second.orchestrator.problems)
        assertEquals(1, claudeRuns)
        val request = second.orchestrator.pendingRequests().single { it.purpose == "task.merge" }
        assertEquals(OperationStatus.SUCCEEDED, second.orchestrator.answer(request.id, "merge", null, request.hash, "автор").status)
        assertTrue(File(project.root, "feature.txt").exists(), "работа закоммичена и слита")
    }

    private fun stepsUntilKilled(s: LiveSystem, n: Int): Boolean {
        repeat(n) {
            try {
                s.step()
            } catch (_: Killed) {
                return false
            }
            clock.advance(10.seconds)
        }
        return true
    }

    private fun live(project: ProjectEntry, target: TargetEntry, seed: Long) =
        LiveSystem(clock, NioVfs(dir.resolve("ctl").also { it.toFile().mkdirs() }), listOf(target), { _, _ -> targetHost }, "/known_hosts", RandomSource.seeded(seed), project = project)

    /** Репозиторий с main и проект на нём; «Claude» пишет feature.txt, проверка — «ok». */
    private fun repoProject(): Pair<ProjectEntry, TargetEntry> {
        val root = dir.resolve("repo").toFile().apply { mkdirs() }.path.replace('\\', '/')
        git("init", "-q", "-b", "main", root)
        git("-C", root, "config", "user.email", "dpai-test@example.org")
        git("-C", root, "config", "user.name", "dpai test")
        File(root, "README.md").writeText("проект\n")
        git("-C", root, "add", "README.md")
        git("-C", root, "commit", "-q", "-m", "начало")
        agent.onStart = { run, spec, _ ->
            when {
                spec.command.take(3) == listOf("claude", "auth", "status") -> {
                    agent.output(run, """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty"}""")
                    agent.exit(run, 0)
                }
                spec.command.first() == "claude" -> {
                    claudeRuns++
                    File(spec.workDir!!, "feature.txt").writeText("новая возможность\n")
                    val c = spec.command
                    MockRunner.script(MockScenario.SUCCESS, c[c.indexOfFirst { it == "--session-id" || it == "--resume" } + 1]).forEach { agent.output(run, it) }
                    agent.exit(run, 0, ExitReason.EXITED)
                }
                else -> {
                    agent.output(run, "ok")
                    agent.exit(run, 0)
                }
            }
        }
        val target = TargetForm.validate(ids.next(IdKinds.TARGET), "сервер", "build.lan", "22", "dev", false, "/opt/dpai/bin/dpai", "/home/dev/.claude-dpai").valueOrNull()!!
        val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, root, dir.resolve("trees").toString().replace('\\', '/'), "main", listOf(com.dpsmpx.dpai.verification.VerifyStep("тесты", listOf("make", "test"))))
        return project to target
    }

    /** Репозиторий с main, задача прошла проверку и ждёт ответа на запрос слияния. */
    private fun doneAwaitingMerge(seed: Long): Pair<LiveSystem, String> {
        val root = dir.resolve("repo").toFile().apply { mkdirs() }.path.replace('\\', '/')
        git("init", "-q", "-b", "main", root)
        git("-C", root, "config", "user.email", "dpai-test@example.org")
        git("-C", root, "config", "user.name", "dpai test")
        File(root, "README.md").writeText("проект\n")
        git("-C", root, "add", "README.md")
        git("-C", root, "commit", "-q", "-m", "начало")
        agent.onStart = { run, spec, _ ->
            when {
                spec.command.take(3) == listOf("claude", "auth", "status") -> {
                    agent.output(run, """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty"}""")
                    agent.exit(run, 0)
                }
                spec.command.first() == "claude" -> {
                    File(spec.workDir!!, "feature.txt").writeText("новая возможность\n")
                    val c = spec.command
                    MockRunner.script(MockScenario.SUCCESS, c[c.indexOfFirst { it == "--session-id" || it == "--resume" } + 1]).forEach { agent.output(run, it) }
                    agent.exit(run, 0, ExitReason.EXITED)
                }
                else -> {
                    agent.output(run, "ok")
                    agent.exit(run, 0)
                }
            }
        }
        val target = TargetForm.validate(ids.next(IdKinds.TARGET), "сервер", "build.lan", "22", "dev", false, "/opt/dpai/bin/dpai", "/home/dev/.claude-dpai").valueOrNull()!!
        val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, root, dir.resolve("trees").toString().replace('\\', '/'), "main", listOf(com.dpsmpx.dpai.verification.VerifyStep("тесты", listOf("make", "test"))))
        val s = LiveSystem(clock, NioVfs(dir.resolve("ctl").also { it.toFile().mkdirs() }), listOf(target), { _, _ -> targetHost }, "/known_hosts", RandomSource.seeded(seed), project = project)
        val task = s.addTask("фича", "добавить возможность").valueOrNull()!!
        repeat(6) {
            s.step()
            clock.advance(10.seconds)
        }
        assertEquals(TaskState.DONE, s.orchestrator.tasks.tasks.getValue(task).state, s.problems.toString() + s.orchestrator.problems)
        return s to root
    }
}
