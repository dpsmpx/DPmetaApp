package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
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

    /** Хост target: Git — настоящий, остальное — агент. */
    private val targetHost = object : ProcessHost {
        override fun start(spec: ProcessSpec, onOutput: (com.dpsmpx.dpai.execution.process.OutputLine) -> Unit) =
            if (spec.command.first() == "git") local.start(spec, onOutput) else agent.start(spec, onOutput)
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
}
