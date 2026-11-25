package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import org.junit.jupiter.api.io.TempDir
import java.io.Closeable
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WorkflowEngineTest {
    @TempDir lateinit var root: Path
    private val clock = ManualClock()
    private val context = Hashing.sha256("project and target").hex
    private fun tool(id: String = "manual.check") = AutomationDefinition(id, "Проверка", "Настоящий исполнитель",
        AutomationSource.MANUAL, AutomationCategory.CHECK, Hashing.sha256(id).hex, ActionLevel.REVERSIBLE,
        listOf("/project"), AutomationInvocation.DIRECT, true, verification = "код процесса", cancellable = true)
    private fun catalog(vararg entries: AutomationDefinition) = AutomationCatalog.of(entries.toList()).valueOrNull()!!
    private fun workflow(vararg entries: AutomationDefinition) = Workflow("check-project", "Проверить проект", context,
        entries.map { WorkflowStep(it.id, it.revision) }, enabled = true)
    private fun event(id: String = "user:1") = AutomationEvent(id, AutomationTrigger.MANUAL, context, Hashing.sha256("tree").hex)
    private fun success() = Outcome.success(JsonObject.of("status" to JsonString("SUCCEEDED")))
    private fun status(o: Outcome<JsonObject>) = (assertIs<Outcome.Success<JsonObject>>(o).value["status"] as JsonString).value
    private fun store() = WorkflowStore(NioVfs(root))

    @Test fun sequentialFailureStopsBeforeTheNextEffectAndKeepsEvidence() {
        val a = tool(); val b = tool("manual.build"); val c = tool("environment.inspect")
        val w = workflow(a, b, c); val s = store()
        assertTrue(s.save(w, null).isSuccess)
        val calls = arrayListOf<String>()
        val result = WorkflowEngine(s, clock).run(w.id, w.revision, event(), { false },
            { Outcome.success(catalog(a, b, c)) }, { _, _ -> Outcome.success(Unit) }, { step, _, _ ->
                calls += step.id
                Outcome.success(JsonObject.of("status" to JsonString(if (step.id == b.id) "FAILED" else "SUCCEEDED")))
            })
        assertEquals("FAILED", status(result))
        assertEquals(listOf(a.id, b.id), calls)
        assertEquals(2L, (result.valueOrNull()!!["completed"] as com.dpsmpx.dpai.core.serialization.JsonNumber).toLongExact())
        assertTrue(result.valueOrNull()!!.fields.containsKey("results"))
    }

    @Test fun processDeathAfterAnEffectNeverReplaysTheStartedStep() {
        val d = tool(); val w = workflow(d); val s = store(); s.save(w, null)
        var effects = 0
        try {
            WorkflowEngine(s, clock).run(w.id, w.revision, event(), { false }, { Outcome.success(catalog(d)) },
                { _, _ -> Outcome.success(Unit) }, { _, _, _ -> effects++; throw AssertionError("simulated process death") })
        } catch (_: AssertionError) { }
        val recovered = WorkflowEngine(store(), clock).run(w.id, w.revision, event(), { false }, { Outcome.success(catalog(d)) },
            { _, _ -> Outcome.success(Unit) }, { _, _, _ -> effects++; success() })
        assertEquals("INCOMPLETE", status(recovered))
        assertEquals(1, effects)
    }

    @Test fun duplicateEventReturnsTheOriginalRunAcrossNewStoreInstances() {
        val d = tool(); val w = workflow(d); store().save(w, null)
        var calls = 0
        fun run() = WorkflowEngine(store(), clock).run(w.id, w.revision, event(), { false },
            { Outcome.success(catalog(d)) }, { _, _ -> Outcome.success(Unit) }, { _, _, _ -> calls++; success() })
        val first = run().valueOrNull()!!
        clock.advance(100.seconds)
        assertEquals(first, run().valueOrNull())
        assertEquals(1, calls)
    }

    @Test fun failedDurableClaimPreventsTheExecutor() {
        val d = tool(); val w = workflow(d); store().save(w, null)
        val real = NioVfs(root)
        val failing = object : Vfs by real {
            override fun fsync(path: String) { if (path.startsWith("runs/")) throw IOException("full"); real.fsync(path) }
        }
        val s = WorkflowStore(failing) { Closeable { } }
        var called = false
        val r = WorkflowEngine(s, clock).run(w.id, w.revision, event(), { false }, { Outcome.success(catalog(d)) },
            { _, _ -> Outcome.success(Unit) }, { _, _, _ -> called = true; success() })
        assertIs<Outcome.Failure>(r); assertFalse(called)
    }

    @Test fun leaseIsSharedAcrossProcessesAndStoreObjects() {
        val d = tool(); val w = workflow(d); store().save(w, null)
        NioVfs(root).tryLease("operation.lock")!!.use {
            val r = WorkflowEngine(store(), clock).run(w.id, w.revision, event(), { false }, { error("must not inspect") },
                { _, _ -> error("must not authorize") }, { _, _, _ -> error("must not execute") })
            assertEquals(DiagCode.WORKTREE_OWNED, assertIs<Outcome.Failure>(r).errors.first().code)
        }
    }

    @Test fun changedToolIsRejectedBeforeAuthorizationOfItsExecution() {
        val d = tool(); val w = workflow(d); val s = store(); s.save(w, null)
        var calls = 0
        val changed = d.copy(sourceHash = Hashing.sha256("changed command").hex)
        val r = WorkflowEngine(s, clock).run(w.id, w.revision, event(), { false }, { Outcome.success(catalog(changed)) },
            { _, _ -> Outcome.success(Unit) }, { _, _, _ -> calls++; success() })
        assertEquals("NEEDS_REVIEW", status(r)); assertEquals(0, calls)
    }

    @Test fun availabilityIsRecheckedBetweenSteps() {
        val a = tool(); val b = tool("manual.build"); val w = workflow(a, b); val s = store(); s.save(w, null)
        var calls = 0
        val r = WorkflowEngine(s, clock).run(w.id, w.revision, event(), { false },
            { Outcome.success(catalog(a, b.copy(available = calls == 0))) }, { _, _ -> Outcome.success(Unit) },
            { _, _, _ -> calls++; success() })
        assertEquals("BLOCKED", status(r)); assertEquals(1, calls)
    }

    @Test fun revokedAuthorityStopsTheNextStep() {
        val a = tool(); val w = workflow(a, a); val s = store(); s.save(w, null); var calls = 0
        val r = WorkflowEngine(s, clock).run(w.id, w.revision, event(), { false }, { Outcome.success(catalog(a)) },
            { _, _ -> if (calls == 0) Outcome.success(Unit) else Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "revoked") },
            { _, _, _ -> calls++; success() })
        assertEquals("BLOCKED", status(r)); assertEquals(1, calls)
    }

    @Test fun timeoutIsVisibleToTheRunningOldExecutor() {
        val d = tool(); val w = workflow(d).copy(timeoutSeconds = 1); val s = store(); s.save(w, null)
        var observed = false
        val r = WorkflowEngine(s, clock).run(w.id, w.revision, event(), { false }, { Outcome.success(catalog(d)) },
            { _, _ -> Outcome.success(Unit) }, { _, stop, _ ->
                clock.advance(2.seconds); observed = stop()
                Outcome.failure(DiagCode.PROCESS_TIMEOUT, "stop requested")
            })
        assertTrue(observed); assertEquals("INCOMPLETE", status(r))
    }

    @Test fun aNestedWorkflowWithNoProgressIsBlockedButANewFingerprintIsAllowed() {
        val d = tool(); val w = workflow(d); val s = store(); s.save(w, null)
        var lineage = emptySet<String>()
        val e = WorkflowEngine(s, clock)
        assertEquals("PASSED", status(e.run(w.id, w.revision, event(), { false }, { Outcome.success(catalog(d)) },
            { _, _ -> Outcome.success(Unit) }, { _, _, l -> lineage = l; success() })))
        val refused = e.run(w.id, w.revision, event("child").copy(lineage = lineage), { false },
            { error("cycle checked first") }, { _, _ -> error("cycle checked first") }, { _, _, _ -> error("cycle") })
        assertIs<Outcome.Failure>(refused)
        val changed = event("changed").copy(lineage = lineage, fingerprint = Hashing.sha256("new tree").hex)
        assertEquals("PASSED", status(e.run(w.id, w.revision, changed, { false }, { Outcome.success(catalog(d)) },
            { _, _ -> Outcome.success(Unit) }, { _, _, _ -> success() })))
    }

    @Test fun outputIsBoundedAndCannotTurnFailureIntoSuccess() {
        val d = tool(); val w = workflow(d); val s = store(); s.save(w, null)
        val r = WorkflowEngine(s, clock).run(w.id, w.revision, event(), { false }, { Outcome.success(catalog(d)) },
            { _, _ -> Outcome.success(Unit) }, { _, _, _ -> Outcome.success(JsonObject.of(
                "status" to JsonString("FAILED"), "output" to JsonString("trace\n".repeat(100_000)))) })
        assertEquals("FAILED", status(r))
        assertTrue(com.dpsmpx.dpai.core.serialization.CanonicalJson.write(r.valueOrNull()!!).length < 20_000)
    }

    @Test fun importAndUnknownFieldsCannotEnableBackgroundExecution() {
        val w = workflow(tool()).copy(enabled = false)
        assertEquals(w, Workflow.parse(w.json()).valueOrNull())
        assertIs<Outcome.Failure>(Workflow.parse(JsonObject(w.json().fields + ("approve" to JsonBool(true)))))
        val s = store(); s.save(w, null)
        val r = WorkflowEngine(s, clock).run(w.id, w.revision, event().copy(type = AutomationTrigger.AI), { false },
            { error("disabled") }, { _, _ -> error("disabled") }, { _, _, _ -> error("disabled") })
        assertIs<Outcome.Failure>(r)
    }

    @Test fun scheduleUsesStableBucketsAndSkipsMissedIntervals() {
        val w = workflow(tool()).copy(triggers = listOf(WorkflowTrigger(AutomationTrigger.SCHEDULE, everySeconds = 60)))
        val first = WorkflowSchedule.events(w, event().fingerprint, clock.now()).single()
        clock.advance(1.seconds)
        assertEquals(first.id, WorkflowSchedule.events(w, event().fingerprint, clock.now()).single().id)
        clock.advance(600.seconds)
        val later = WorkflowSchedule.events(w, event().fingerprint, clock.now())
        assertEquals(1, later.size); assertTrue(first.id != later.single().id)
        assertTrue(WorkflowSchedule.events(w.copy(enabled = false), event().fingerprint, clock.now()).isEmpty())
    }
    @Test fun longDottedIdsAndSummarySurviveRestartAndStaleEditsAreRefused() {
        val d = tool(); val w = workflow(d).copy(id = "project." + "x".repeat(112)); val s = store()
        assertTrue(s.save(w, null).isSuccess)
        assertEquals(w, store().list().valueOrNull()!!.single())
        assertIs<Outcome.Failure>(s.save(w.copy(title = "чужая правка"), null))
        val result = WorkflowEngine(s, clock).run(w.id, w.revision, event(), { false }, { Outcome.success(catalog(d)) },
            { _, _ -> Outcome.success(Unit) }, { _, _, _ -> success() }).valueOrNull()!!
        assertEquals(result, store().latest(w.id).valueOrNull())
        assertEquals(JsonObject(result.fields - "results"), store().latestSummary(w.id).valueOrNull())
    }

    @Test fun userCanDisableRunningWorkflowBeforeTheNextStep() {
        val d = tool(); val b = tool("manual.build"); val w = workflow(d, b); val s = store(); s.save(w, null)
        var calls = 0
        val result = WorkflowEngine(s, clock).run(w.id, w.revision, event(), { false }, { Outcome.success(catalog(d, b)) },
            { _, _ -> Outcome.success(Unit) }, { _, _, _ ->
                calls++; assertTrue(store().save(w.copy(enabled = false), w.revision).isSuccess); success()
            })
        assertEquals("NEEDS_REVIEW", status(result)); assertEquals(1, calls)
        assertFalse(store().workflow(w.id).valueOrNull()!!.enabled)
    }

    @Test fun deliveryRefusalAfterProcessDeathClosesTheUncertainRunWithoutReplay() {
        val d = tool(); val w = workflow(d); val s = store(); s.save(w, null)
        try {
            WorkflowEngine(s, clock).run(w.id, w.revision, event(), { false }, { Outcome.success(catalog(d)) },
                { _, _ -> Outcome.success(Unit) }, { _, _, _ -> throw AssertionError("process died") })
        } catch (_: AssertionError) { }
        assertTrue(store().refuse(w, event(), "target изменился", clock.now().epochMillis).isSuccess)
        assertEquals(JsonString("INCOMPLETE"), store().result(w.id, event().id).valueOrNull()!!["status"])
    }

    @Test fun warningSuccessPreservesWarningsAndContinuesThePipeline() {
        val d = tool(); val w = workflow(d); val s = store(); s.save(w, null)
        val result = WorkflowEngine(s, clock).run(w.id, w.revision, event(), { false }, { Outcome.success(catalog(d)) },
            { _, _ -> Outcome.success(Unit) }, { _, _, _ -> Outcome.success(JsonObject.of(
                "status" to JsonString("SUCCEEDED_WITH_WARNINGS"), "note" to JsonString("optional checker unavailable"))) })
        assertEquals("PASSED", status(result))
        assertTrue(result.valueOrNull().toString().contains("SUCCEEDED_WITH_WARNINGS"))
    }

}
