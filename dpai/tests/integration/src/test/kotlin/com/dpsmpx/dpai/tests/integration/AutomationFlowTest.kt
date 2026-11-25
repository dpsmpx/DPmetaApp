package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.automation.AutomationApi
import com.dpsmpx.dpai.automation.AutomationCatalog
import com.dpsmpx.dpai.automation.AutomationJson
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.operation.Actor
import com.dpsmpx.dpai.core.operation.ActorKind
import com.dpsmpx.dpai.core.operation.OperationResult
import com.dpsmpx.dpai.core.operation.OperationStatus
import com.dpsmpx.dpai.core.operation.Provenance
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.linux.NativeChild
import com.dpsmpx.dpai.execution.linux.NativeProcesses
import com.dpsmpx.dpai.execution.linux.WindowSize
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.orchestrator.agent.InAppAgentHost
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.testkit.RealTime
import com.dpsmpx.dpai.ui.AutomationContext
import com.dpsmpx.dpai.ui.ManualAutomations
import com.dpsmpx.dpai.ui.ManualProfile
import com.dpsmpx.dpai.ui.ManualProfileKind
import com.dpsmpx.dpai.ui.ManualProfiles
import com.dpsmpx.dpai.ui.ManualStep
import com.dpsmpx.dpai.ui.ProjectEntry
import com.dpsmpx.dpai.ui.ProjectWorkspaceView
import com.dpsmpx.dpai.ui.TargetForm
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import kotlin.time.Duration.Companion.seconds

/** Общий контракт UI/AGENT → настоящий ManualProfiles/AgentClient → процесс и сохранённый итог. */
class AutomationFlowTest {
    @TempDir lateinit var dir: Path
    private val ids = IdGenerator(Clock.SYSTEM, RandomSource.seeded(215))
    private val native = object : NativeProcesses {
        override fun spawn(argv: List<String>, environment: Map<String, String>, workDir: String?, pty: WindowSize?): Outcome<NativeChild> =
            Outcome.failure(DiagCode.UNSUPPORTED, "процессы теста идут через LocalProcessHost")
        override fun kill(pid: Int, signal: Int): Boolean = ProcessHandle.of(kotlin.math.abs(pid).toLong()).map {
            if (signal == 9) it.destroyForcibly() else it.destroy()
        }.orElse(false)
    }

    @Test fun userAndApiUseTheSameNativeProfileAndEvidence() {
        for (actor in listOf(ActorKind.USER, ActorKind.AGENT)) {
            val result = scenario(actor.name, actor, 0)
            assertEquals(OperationStatus.SUCCEEDED, result.status)
            assertEquals(JsonString("SUCCEEDED"), AutomationJson.result(result)["status"])
        }
    }

    @Test fun commandFailureIsNotTurnedIntoACompletedCheck() {
        val result = scenario("failed", ActorKind.AGENT, 7)
        assertEquals(OperationStatus.FAILED, result.status)
        assertEquals(DiagCode.PROCESS_EXIT_NONZERO, result.diagnostics.single().code)
    }

    @Test fun staleDescriptionIsRejectedBeforeTheRealProfileCanStart() {
        val root = dir.resolve("stale").toFile().apply { mkdirs() }
        val target = TargetForm.inApp(ids.next(IdKinds.TARGET), "Тест", "/root/.claude-slot-1").valueOrNull()!!
        val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, root.path, dir.resolve("trees").toString(), "main", emptyList())
        val first = profile(0)
        fun catalog(p: ManualProfile): AutomationCatalog = AutomationContext.bind(
            ManualAutomations.describe(mapOf(ManualProfileKind.BUILD to p), null, project.root, target.platform), project, target).valueOrNull()!!
        val before = catalog(first).byId("manual.build")!!
        val fresh = catalog(first.copy(steps = listOf(ManualStep("Другой шаг", listOf("python3", "-c", "raise SystemExit(1)"), 30))))
        var executed = false
        val result = fresh.invoke(before.id, before.revision, JsonObject.of(), Provenance(Actor(ActorKind.AGENT, "API")), ids, Clock.SYSTEM,
            { false }, { Outcome.success(Unit) }, { _, _ -> executed = true; Outcome.success(JsonObject.of()) })
        assertEquals(DiagCode.STATE_CONFLICT, result.diagnostics.single().code)
        assertFalse(executed)
        assertFalse(root.resolve("built.txt").exists())
    }

    @Test fun workflowExecutesRealProfilesAndNeverRepeatsADeliveredEvent() {
        for (code in listOf(0, 7)) {
            val name = "pipeline-$code"
            scenario(name, ActorKind.USER, code) { catalog, tool, revision, invoke ->
                val context = Hashing.sha256(name).hex
                val store = com.dpsmpx.dpai.automation.WorkflowStore(NioVfs(dir.resolve("ledger-$code")))
                val workflow = com.dpsmpx.dpai.automation.Workflow("real-profiles", "Две настоящие команды", context,
                    List(2) { com.dpsmpx.dpai.automation.WorkflowStep(tool, revision) }, enabled = true)
                assertTrue(store.save(workflow, null).isSuccess)
                val event = com.dpsmpx.dpai.automation.AutomationEvent("integration:1",
                    com.dpsmpx.dpai.automation.AutomationTrigger.MANUAL, context, context)
                val engine = com.dpsmpx.dpai.automation.WorkflowEngine(store, Clock.SYSTEM)
                val calls = arrayListOf<OperationResult<JsonObject>>()
                val result = engine.run(workflow.id, workflow.revision, event, { false }, { Outcome.success(catalog) },
                    { _, _ -> Outcome.success(Unit) }, { step, _, _ ->
                        assertEquals(tool, step.id); assertEquals(revision, step.revision)
                        val actual = invoke().also(calls::add)
                        Outcome.success(AutomationJson.result(actual))
                    }).valueOrNull()!!
                assertEquals(JsonString(if (code == 0) "PASSED" else "FAILED"), result["status"])
                assertEquals(if (code == 0) 2 else 1, calls.size)
                assertEquals(calls.size, calls.map { it.operationId }.distinct().size)
                val restored = com.dpsmpx.dpai.automation.WorkflowEngine(
                    com.dpsmpx.dpai.automation.WorkflowStore(NioVfs(dir.resolve("ledger-$code"))), Clock.SYSTEM)
                assertEquals(result, restored.run(workflow.id, workflow.revision, event, { false }, { Outcome.success(catalog) },
                    { _, _ -> Outcome.success(Unit) }, { _, _, _ -> error("доставленное событие не повторяет настоящий процесс") }).valueOrNull())
                calls.first()
            }
        }
    }

    private fun profile(code: Int) = ManualProfile(ManualProfileKind.BUILD, listOf(ManualStep("Реальный процесс", listOf("python3", "-c",
        "from pathlib import Path; import sys; Path('built.txt').write_text('executed'); print('real profile output'); sys.exit($code)"), 30)))

    private fun scenario(name: String, actor: ActorKind, code: Int,
        around: (AutomationCatalog, String, String, () -> OperationResult<JsonObject>) -> OperationResult<JsonObject> = { _, _, _, invoke -> invoke() }): OperationResult<JsonObject> {
        val root = dir.resolve(name).toFile().apply { mkdirs() }
        val target = TargetForm.inApp(ids.next(IdKinds.TARGET), "Тест", "/root/.claude-slot-1").valueOrNull()!!
        val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, root.path, dir.resolve("trees").toString(), "main", emptyList())
        val store = ManualProfiles(DocumentStore(NioVfs(root.resolve("state").toPath()), "profiles"), ids, Clock.SYSTEM)
        val client = AgentClient(InAppAgentHost.create(root.resolve("agent").path, Clock.SYSTEM, "automation-test", native, LocalProcessHost()), listOf("dpai"), null, "test")
        val profile = profile(code)
        val location = ProjectWorkspaceView.of(project, listOf(target), true).main
        val catalog = AutomationContext.bind(ManualAutomations.describe(mapOf(ManualProfileKind.BUILD to profile), null, root.path, target.platform), project, target).valueOrNull()!!
        val description = AutomationApi.describe(catalog, JsonObject.of("id" to JsonString("manual.build"))).valueOrNull()!!
        val call = AutomationApi.call(JsonObject.of("id" to description["id"]!!, "revision" to description["revision"]!!)).valueOrNull()!!
        try {
            return around(catalog, call.id, call.revision) { catalog.invoke(call.id, call.revision, call.parameters, Provenance(Actor(actor, "тест"), projectId = project.project, executionTargetId = target.id),
                ids, Clock.SYSTEM, { false }, { Outcome.success(Unit) }, { _, _ ->
                    val run = store.prepare(project, target, location, profile).valueOrNull()!!
                    assertTrue(store.blocked, "прежняя долговечная пауза выставлена до start")
                    store.launch(run, client)
                    val view = RealTime.await(30.seconds, "завершение настоящего профиля") { if (store.poll(client)) null else store.snapshot() }
                    assertEquals(run.id, view.finished)
                    assertEquals(code, view.results.single().exitCode)
                    assertTrue(view.output.contains("real profile output"))
                    assertEquals("executed", root.resolve("built.txt").readText())
                    assertFalse(store.blocked)
                    val evidence = JsonObject.of("run" to JsonString(run.id.value), "outputHash" to JsonString(Hashing.sha256(view.output).hex))
                    if (view.complete) Outcome.success(evidence) else Outcome.failure(DiagCode.PROCESS_EXIT_NONZERO, view.note)
                }) }
        } finally {
            if (store.blocked) RealTime.await(30.seconds, "остановка тестового профиля") { if (store.stop(client)) null else Unit }
        }
    }
}
