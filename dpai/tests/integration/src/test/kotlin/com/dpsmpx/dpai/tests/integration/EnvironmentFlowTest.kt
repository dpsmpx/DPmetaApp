package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.linux.NativeChild
import com.dpsmpx.dpai.execution.linux.NativeProcesses
import com.dpsmpx.dpai.execution.linux.WindowSize
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.orchestrator.agent.InAppAgentHost
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.testkit.RealTime
import com.dpsmpx.dpai.ui.EnvironmentPlan
import com.dpsmpx.dpai.ui.ManualProfile
import com.dpsmpx.dpai.ui.ManualProfiles
import com.dpsmpx.dpai.ui.ManualRunView
import com.dpsmpx.dpai.ui.ProjectEntry
import com.dpsmpx.dpai.ui.ProjectWorkspaceView
import com.dpsmpx.dpai.ui.TargetForm
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Настоящий агент/процессы: bootstrap без .git и ошибка JVM после успешного менеджера пакетов. */
class EnvironmentFlowTest {
    @TempDir lateinit var dir: Path
    private val ids = IdGenerator(Clock.SYSTEM, RandomSource.seeded(216))
    private val native = object : NativeProcesses {
        override fun spawn(argv: List<String>, environment: Map<String, String>, workDir: String?, pty: WindowSize?): Outcome<NativeChild> =
            Outcome.failure(DiagCode.UNSUPPORTED, "тест использует LocalProcessHost")
        override fun kill(pid: Int, signal: Int): Boolean = ProcessHandle.of(kotlin.math.abs(pid).toLong()).map {
            if (signal == 9) it.destroyForcibly() else it.destroy()
        }.orElse(false)
    }

    @Test fun literalShellVariablesSurvivePersistenceAndBootstrapNeedsNoGit() {
        val plan = EnvironmentPlan.create("environment.command", mapOf("argv" to "[\"/bin/sh\",\"-c\",\"value='a b'; printf '%s' \\\"${'$'}{value}\\\" > ready.txt\"]")).valueOrNull()!!
        val restored = ManualProfile.read(plan.profile.toJson())!!
        val view = execute(restored, LocalProcessHost())
        assertTrue(view.complete, view.note)
        assertEquals("a b", dir.resolve("ready.txt").toFile().readText())
        assertFalse(dir.resolve(".git").toFile().exists())
    }

    @Test fun successfulPackageInstallDoesNotHideBrokenJvmOrRunFollowingSteps() {
        val plan = EnvironmentPlan.create("environment.apk.add", mapOf("packages" to "openjdk21-jdk")).valueOrNull()!!
        val local = LocalProcessHost()
        // Пакетная база/Android отсутствуют на хосте: заменены только внешние команды, не исполнитель профиля.
        val fixtures = object : ProcessHost {
            override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
                val script = if (spec.command.first() == "apk") "print('fixture: package installed')" else
                    "import sys; print('Failed to mark memory page as executable - check if grsecurity/PaX is enabled'); sys.exit(1)"
                return local.start(spec.copy(command = listOf("python3", "-c", script)), onOutput)
            }
        }
        val view = execute(plan.profile, fixtures)
        assertFalse(view.complete)
        assertEquals(listOf(0, 1), view.results.map { it.exitCode })
        assertTrue(view.output.contains("Failed to mark memory page as executable"))
        assertTrue(view.note.contains("не запускался"), "следующая JDK-проба не запускается после отказа JVM")
    }

    private fun execute(profile: ManualProfile, host: ProcessHost): ManualRunView {
        val target = TargetForm.inApp(ids.next(IdKinds.TARGET), "Тест", "/root/.claude-slot-1").valueOrNull()!!
        val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, dir.toString(), dir.resolve("trees").toString(), "main", emptyList())
        val store = ManualProfiles(DocumentStore(NioVfs(dir.resolve("state")), "profiles"), ids, Clock.SYSTEM)
        val client = AgentClient(InAppAgentHost.create(dir.resolve("agent").toString(), Clock.SYSTEM, "environment-test", native, host), listOf("dpai"), null, "test")
        try {
            val run = store.prepare(project, target, ProjectWorkspaceView.of(project, listOf(target), true).main, profile).valueOrNull()!!
            assertTrue(store.blocked)
            store.launch(run, client)
            val result = RealTime.await(30.seconds, "завершение плана окружения") { if (store.poll(client)) null else store.snapshot() }
            assertEquals(run.id, result.finished)
            assertFalse(store.blocked)
            return result
        } finally {
            if (store.blocked) RealTime.await(30.seconds, "остановка своего плана") { if (store.stop(client)) null else Unit }
        }
    }
}
