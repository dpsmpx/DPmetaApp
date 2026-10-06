package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.state.CircuitState
import com.dpsmpx.dpai.core.state.TargetLifecycle
import com.dpsmpx.dpai.core.state.TargetState
import com.dpsmpx.dpai.execution.target.ProbeResult
import com.dpsmpx.dpai.execution.target.TargetHealth
import com.dpsmpx.dpai.execution.target.TargetHealthPolicy
import com.dpsmpx.dpai.execution.target.TargetHealthTracker
import com.dpsmpx.dpai.testkit.Gen
import com.dpsmpx.dpai.testkit.Property
import com.dpsmpx.dpai.testkit.map
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Здоровье target (критерий этапа 3): недоступный target уходит в
 * `OFFLINE` по нескольким порогам, а не по одному пропуску; предохранитель
 * не пускает пробы до паузы (ТЗ, §14, §81).
 */
class TargetHealthTest {
    private val policy = TargetHealthPolicy(offlineAfterFailures = 3, offlineAfter = 60.seconds, recoverAfterSuccesses = 2, openPause = 30.seconds)

    private fun ok(at: Timestamp, latency: Duration = 100.milliseconds) = ProbeResult(at, true, Measure.observed(latency))

    private fun fail(at: Timestamp) = ProbeResult(at, false, Measure.unknown("нет связи"))

    private fun feed(probes: List<ProbeResult>, start: TargetHealth = TargetHealth()): TargetHealth =
        probes.fold(start) { h, p -> TargetHealthTracker.observe(h, p, policy).health }

    @Test
    fun singleMissIsDegradedNotOffline() {
        val clock = ManualClock()
        val online = feed(listOf(ok(clock.now())))
        assertEquals(TargetState.ONLINE, online.state)
        clock.advance(10.seconds)
        val after = TargetHealthTracker.observe(online, fail(clock.now()), policy)
        assertEquals(TargetState.DEGRADED, after.health.state)
        assertEquals(TargetState.ONLINE, after.changedFrom)
    }

    @Test
    fun offlineNeedsBothCountAndDuration() {
        val clock = ManualClock()
        var h = feed(listOf(ok(clock.now())))
        // Пять пропусков за пять секунд: счёт набран, времени — нет.
        repeat(5) {
            clock.advance(1.seconds)
            h = TargetHealthTracker.observe(h, fail(clock.now()), policy).health
        }
        assertEquals(TargetState.DEGRADED, h.state)
        // Два пропуска с паузой больше минуты: время есть, счёт — заново после успеха.
        h = TargetHealthTracker.observe(h, ok(clock.now()), policy).health
        h = TargetHealthTracker.observe(h, fail(clock.now()), policy).health
        clock.advance(90.seconds)
        h = TargetHealthTracker.observe(h, fail(clock.now()), policy).health
        assertEquals(TargetState.DEGRADED, h.state, "два пропуска — меньше порога")
        clock.advance(1.seconds)
        val step = TargetHealthTracker.observe(h, fail(clock.now()), policy)
        assertEquals(TargetState.OFFLINE, step.health.state)
        assertEquals(TargetState.DEGRADED, step.changedFrom)
        assertEquals(CircuitState.OPEN, step.health.circuit)
    }

    @Test
    fun openCircuitHoldsProbesUntilPauseAndRecoversAfterSuccesses() {
        val clock = ManualClock()
        var h = TargetHealth()
        repeat(3) {
            h = TargetHealthTracker.observe(h, fail(clock.now()), policy).health
            clock.advance(31.seconds)
        }
        assertEquals(TargetState.OFFLINE, h.state)
        val openedAt = h.lastProbeAt!!
        assertFalse(h.probeAllowed(openedAt + 10.seconds, policy))
        assertTrue(h.probeAllowed(openedAt + 30.seconds, policy))
        h = TargetHealthTracker.observe(h, ok(clock.now()), policy).health
        assertEquals(TargetState.OFFLINE, h.state, "одной удачной пробы мало")
        assertEquals(CircuitState.HALF_OPEN, h.circuit)
        h = TargetHealthTracker.observe(h, ok(clock.now()), policy).health
        assertEquals(TargetState.ONLINE, h.state)
        assertEquals(CircuitState.RECOVERED, h.circuit)
        assertEquals(Measure.observed(clock.now()), h.lastSeen)
    }

    @Test
    fun slowProbeDegrades() {
        val h = feed(listOf(ok(ManualClock().now(), 10.seconds)))
        assertEquals(TargetState.DEGRADED, h.state)
    }

    @Test
    fun disabledTargetIgnoresProbes() {
        val h = TargetHealth(state = TargetState.DISABLED)
        assertEquals(h, TargetHealthTracker.observe(h, ok(ManualClock().now()), policy).health)
        assertFalse(h.probeAllowed(ManualClock().now(), policy))
    }

    /**
     * Свойство: любые серии проб двигают target только по таблице, `ONLINE`
     * не переходит в `OFFLINE` за одну пробу, а `OFFLINE` наступает только
     * после выполнения обоих порогов.
     */
    @Test
    fun randomProbeSeriesRespectThresholds() {
        val step = Gen.oneOf(Gen.int(0..90).map { it to true }, Gen.int(0..90).map { it to false })
        Property.forAll(Gen.list(step, 1..80)) { series ->
            val clock = ManualClock()
            var h = TargetHealth()
            var failures = 0
            var since: Timestamp? = null
            for ((gap, reachable) in series) {
                clock.advance(gap.seconds)
                val before = h
                val s = TargetHealthTracker.observe(h, if (reachable) ok(clock.now()) else fail(clock.now()), policy)
                h = s.health
                if (reachable) {
                    failures = 0
                    since = null
                } else {
                    failures++
                    if (since == null) since = clock.now()
                }
                assertTrue(TargetLifecycle.table.isAllowed(before.state, h.state), "${before.state} → ${h.state}")
                assertFalse(before.state == TargetState.ONLINE && h.state == TargetState.OFFLINE)
                if (h.state == TargetState.OFFLINE && before.state != TargetState.OFFLINE) {
                    assertTrue(failures >= policy.offlineAfterFailures && clock.now() - since!! >= policy.offlineAfter)
                }
            }
        }
    }
}
