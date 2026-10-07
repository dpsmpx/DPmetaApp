package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.claude.RoutingRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Сессия уходит раннеру своего target; неизвестное — отказ, а не «первый попавшийся» (D-05). */
class RoutingRunnerTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(17))
    private val a = MockRunner.create(clock, RandomSource.seeded(1)) { _, _ -> MockScenario.SUCCESS }
    private val b = MockRunner.create(clock, RandomSource.seeded(2)) { _, _ -> MockScenario.SUCCESS }
    private val ta = ids.next(IdKinds.TARGET)
    private val tb = ids.next(IdKinds.TARGET)
    private val placed = HashMap<SessionId, ExecutionTargetId>()
    private val router = RoutingRunner({ placed[it] }, { mapOf(ta to a, tb to b)[it] })

    @Test
    fun sessionsGoToTheirTargets() {
        val s1 = ids.next(IdKinds.SESSION).also { placed[it] = ta }
        val s2 = ids.next(IdKinds.SESSION).also { placed[it] = tb }
        assertTrue(router.start(s1, ClaudeRequest("один")).isSuccess)
        assertTrue(router.start(s2, ClaudeRequest("два")).isSuccess)
        assertEquals(setOf(s1), a.host.runIds())
        assertEquals(setOf(s2), b.host.runIds())
        assertTrue(router.inspect(s2).isSuccess)
    }

    @Test
    fun unknownSessionOrTargetIsRefused() {
        val lost = ids.next(IdKinds.SESSION)
        assertEquals(DiagCode.ENTITY_NOT_FOUND, (router.start(lost, ClaudeRequest("x")) as Outcome.Failure).diagnostics.first().code)
        val orphan = ids.next(IdKinds.SESSION).also { placed[it] = ids.next(IdKinds.TARGET) }
        assertEquals(DiagCode.ENTITY_NOT_FOUND, (router.inspect(orphan) as Outcome.Failure).diagnostics.first().code)
        assertEquals(null, router.position(lost))
    }
}
