package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.linux.NativeChild
import com.dpsmpx.dpai.execution.linux.NativeProcesses
import com.dpsmpx.dpai.execution.linux.WindowSize
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.files.LocalTree
import com.dpsmpx.dpai.orchestrator.agent.InAppAgentHost
import com.dpsmpx.dpai.security.Approval
import com.dpsmpx.dpai.security.ApprovalLedger
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.testkit.RealTime
import com.dpsmpx.dpai.ui.DependencyAutomations
import com.dpsmpx.dpai.ui.AutomationPlan
import com.dpsmpx.dpai.ui.ManualProfiles
import com.dpsmpx.dpai.ui.ProjectEntry
import com.dpsmpx.dpai.ui.ProjectWorkspaceView
import com.dpsmpx.dpai.ui.TargetForm
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Одобрение → прежний агент → настоящий pip/venv → lock/отчёт; без платного API и сети. */
class DependencyAutomationFlowTest {
    @TempDir lateinit var dir: Path
    private val ids = IdGenerator(Clock.SYSTEM, RandomSource.seeded(217))
    private val native = object : NativeProcesses {
        override fun spawn(argv: List<String>, environment: Map<String, String>, workDir: String?, pty: WindowSize?): Outcome<NativeChild> =
            Outcome.failure(DiagCode.UNSUPPORTED, "тест использует LocalProcessHost")
        override fun kill(pid: Int, signal: Int): Boolean = ProcessHandle.of(kotlin.math.abs(pid).toLong()).map {
            if (signal == 9) it.destroyForcibly() else it.destroy()
        }.orElse(false)
    }

    @Test fun approvedPythonReportAndPinUseTheNativeWorkerAndReadableReport() {
        val host = LocalProcessHost()
        val venv = host.run(ProcessSpec(listOf("python3", "-m", "venv", ".venv"), dir.toString()), 1.minutes).valueOrNull()!!
        assertTrue(venv.ok, (venv.stdout + venv.stderr).joinToString("\n"))
        // The profile runs from the project root. Keep the interpreter project-relative:
        // JUnit's random absolute @TempDir segment may resemble a secret to Redactor.
        val py = "./.venv/bin/python"
        val target = TargetForm.inApp(ids.next(IdKinds.TARGET), "Тест", "/root/.claude-slot-1").valueOrNull()!!
        val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, dir.toString(), dir.resolve("trees").toString(), "main", emptyList())
        val store = ManualProfiles(DocumentStore(NioVfs(dir.resolve("state")), "profiles"), ids, Clock.SYSTEM)
        val client = AgentClient(InAppAgentHost.create(dir.resolve("agent").toString(), Clock.SYSTEM, "dependency-automation-test", native, host), listOf("dpai"), null, "test")
        try {
            for (mode in listOf("report", "pin", "restore")) {
                val planned = DependencyAutomations.plan(mapOf("manager" to "python", "mode" to mode, "python" to py), TargetPlatform.POSIX, true)
                val initial = assertIs<Outcome.Success<AutomationPlan>>(planned, "план Python $mode: ${planned.diagnostics}").value
                val plan = initial.copy(profile = initial.profile.copy(env = listOf("PIP_NO_INDEX" to "1", "PIP_DISABLE_PIP_VERSION_CHECK" to "1")))
                val proposal = plan.proposal(project, "revision")
                val ledger = ApprovalLedger()
                assertEquals(DiagCode.APPROVAL_REQUIRED, ledger.consume(proposal, null, Clock.SYSTEM.now()).diagnostics.single().code)
                assertFalse(store.blocked, "отказ до одобрения не готовит запуск")
                val now = Clock.SYSTEM.now()
                val approval = Approval(ids.next(IdKinds.APPROVAL), proposal.hash, "user", now, now + 5.minutes)
                assertIs<Outcome.Success<*>>(ledger.consume(proposal, approval, now))
                val run = store.prepare(project, target, ProjectWorkspaceView.of(project, listOf(target), true).main, plan.profile).valueOrNull()!!
                assertTrue(store.blocked)
                store.launch(run, client)
                val view = RealTime.await(45.seconds, "pip $mode через профиль") { if (store.poll(client)) null else store.snapshot() }
                assertEquals(run.id, view.finished)
                assertTrue(view.complete, view.output + "\n" + view.note)
                assertEquals(0, view.results.single().exitCode)
                assertFalse(store.blocked)
                val report = DependencyAutomations.read(LocalTree(dir), mapOf("manager" to "python")).valueOrNull()!!
                assertTrue((report["content"] as JsonString).value.contains("dp-ide.python-dependencies"))
                assertEquals(JsonBool(false), report["freshnessVerified"], "сохранённый файл сам по себе не доказывает свежую приёмку")
            }
            assertTrue(dir.resolve("requirements.dp-ide.lock").toFile().readText().contains("pip=="))
        } finally {
            if (store.blocked) RealTime.await(30.seconds, "остановка тестового профиля") { if (store.stop(client)) null else Unit }
        }
    }
}
