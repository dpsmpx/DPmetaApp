package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.EnvironmentPlan
import com.dpsmpx.dpai.ui.AutomationPlan
import com.dpsmpx.dpai.ui.DependencyAutomations
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.ui.ProjectEntry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class EnvironmentApprovalsTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(216))
    private val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), ids.next(IdKinds.TARGET), "/root/project", "/root/trees", "main", emptyList())

    @Test fun dangerousCommandNeedsActualNameAndBoundOneTimeGrant() = waiting(command()) { broker, request, answer, _ ->
        assertFalse(answer.isDone)
        assertFalse(broker.answer("test", ids.next(IdKinds.APPROVAL), true, "project"))
        assertFalse(broker.answer("test", request.id, true, "wrong"))
        assertTrue(broker.answer("test", request.id, true, "project"))
        val grant = answer.get(5, TimeUnit.SECONDS).valueOrNull()!!
        assertEquals(DiagCode.APPROVAL_INVALID, grant.consume(request.proposal.copy(arguments = listOf("подмена"))).diagnostics.single().code)
        assertIs<Outcome.Success<Unit>>(grant.consume(request.proposal))
        assertEquals(DiagCode.APPROVAL_INVALID, grant.consume(request.proposal).diagnostics.single().code)
    }

    @Test fun sdkLicenseCannotBeAcceptedByModelParameters() {
        val plan = EnvironmentPlan.create("environment.android_sdk", emptyMap()).valueOrNull()!!
        waiting(plan) { broker, request, answer, _ ->
            assertFalse(broker.answer("test", request.id, true))
            assertFalse(answer.isDone)
            assertTrue(broker.answer("test", request.id, true, license = true))
            assertIs<Outcome.Success<Unit>>(answer.get(5, TimeUnit.SECONDS).valueOrNull()!!.consume(request.proposal))
        }
    }

    @Test fun cancellationDoesNotTurnIntoApprovalAndRejectsLateClick() = waiting(command()) { broker, request, answer, stop ->
        stop.set(true)
        assertEquals(DiagCode.CANCELLED_BY_USER, answer.get(5, TimeUnit.SECONDS).diagnostics.single().code)
        assertFalse(broker.answer("test", request.id, true, "project"))
    }

    @Test fun expiredGrantIsRejectedBeforeExecution() = waiting(command()) { broker, request, answer, _ ->
        assertTrue(broker.answer("test", request.id, true, "project"))
        val grant = answer.get(5, TimeUnit.SECONDS).valueOrNull()!!
        clock.advance(6.minutes)
        assertEquals(DiagCode.APPROVAL_INVALID, grant.consume(request.proposal).diagnostics.single().code)
    }

    @Test fun callWithoutHumanSurfaceIsRejected() {
        val broker = EnvironmentApprovalBroker(clock, ids)
        assertEquals(DiagCode.APPROVAL_REQUIRED, broker.await(null, project, command(), "revision") { false }.diagnostics.single().code)
        assertTrue(broker.pending.value.isEmpty())
    }

    @Test fun projectDependencyApprovalBindsScopeModeAndTarget() {
        val plan = DependencyAutomations.plan(mapOf("manager" to "python", "mode" to "pin"), TargetPlatform.POSIX, true).valueOrNull()!!
        waiting(plan) { broker, request, answer, _ ->
            assertEquals(project.target.value, request.targetLabel)
            assertEquals(DiagCode.APPROVAL_REQUIRED, broker.await(null, project, plan, "revision") { false }.diagnostics.single().code)
            assertTrue(broker.answer("test", request.id, true))
            val grant = answer.get(5, TimeUnit.SECONDS).valueOrNull()!!
            assertIs<Outcome.Failure>(grant.consume(plan.copy(scope = AutomationPlan.Scope.GUEST).proposal(project, "revision")))
            assertIs<Outcome.Failure>(grant.consume(plan.copy(effect = AutomationPlan.Effect.DEPENDENCY_REPORT).proposal(project, "revision")))
            assertIs<Outcome.Failure>(grant.consume(plan.proposal(project.copy(target = ids.next(IdKinds.TARGET)), "revision")))
            assertIs<Outcome.Success<*>>(grant.consume(request.proposal))
        }
    }

    @Test fun declinedPlanDoesNotIssueAGrant() = waiting(command()) { broker, request, answer, _ ->
        assertTrue(broker.answer("test", request.id, false))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, answer.get(5, TimeUnit.SECONDS).diagnostics.single().code)
        assertFalse(broker.answer("test", request.id, true, "project"))
    }

    @Test fun indexUpdateRequiresHumanButListingAvailableUpdatesDoesNot() {
        val broker = EnvironmentApprovalBroker(clock, ids)
        val update = EnvironmentPlan.create("environment.apk.update", emptyMap(), listOf("broken-package")).valueOrNull()!!
        assertTrue(update.snapshot)
        assertEquals(1, update.profile.steps.size, "обновление индекса не переустанавливает повреждённые пакеты")
        assertEquals(DiagCode.APPROVAL_REQUIRED, broker.await(null, project, update, "revision") { false }.diagnostics.single().code)
        val listing = EnvironmentPlan.create("environment.apk.list_upgrades", emptyMap()).valueOrNull()!!
        assertIs<Outcome.Success<EnvironmentApprovalBroker.Grant?>>(broker.await(null, project, listing, "revision") { false })
        assertTrue(broker.pending.value.isEmpty())
    }

    private fun command() = EnvironmentPlan.create("environment.command", mapOf("argv" to "[\"java\",\"-version\"]")).valueOrNull()!!
    private fun waiting(plan: AutomationPlan, block: (EnvironmentApprovalBroker, EnvironmentApprovalBroker.Request,
        CompletableFuture<Outcome<EnvironmentApprovalBroker.Grant?>>, AtomicBoolean) -> Unit) {
        val broker = EnvironmentApprovalBroker(clock, ids)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seen = CountDownLatch(1)
        scope.launch { broker.pending.collect { if ("test" in it) seen.countDown() } }
        val executor = Executors.newSingleThreadExecutor()
        val stop = AtomicBoolean(false)
        val answer = CompletableFuture.supplyAsync({ broker.await("test", project, plan, "revision") { stop.get() } }, executor)
        try {
            assertTrue(seen.await(5, TimeUnit.SECONDS), "план должен стать видимым человеку")
            block(broker, broker.pending.value.getValue("test"), answer, stop)
        } finally {
            stop.set(true)
            try { answer.get(5, TimeUnit.SECONDS) } finally { scope.cancel(); executor.shutdownNow() }
        }
    }
}
