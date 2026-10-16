package com.dpsmpx.dppass.app

import com.dpsmpx.dppass.core.ProfileId
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Состояние туннеля для интерфейса (D-34). */
class TunnelStatusTest {
    @Test
    fun busyMeansTunnelExistsOrIsComing() {
        assertTrue(TunnelStatus.Connecting(null).busy)
        assertTrue(TunnelStatus.Connected(ProfileId("a"), "A", 0).busy)
        assertFalse(TunnelStatus.Idle.busy)
        assertFalse(TunnelStatus.Stopping.busy)
        assertFalse(TunnelStatus.Failed(Diagnostic.error(DiagCode.ATTEMPTS_EXHAUSTED, "нет")).busy)
    }

    @Test
    fun stateIsShared() {
        TunnelState.set(TunnelStatus.Connecting("A"))
        assertEquals(TunnelStatus.Connecting("A"), TunnelState.status.value)
        TunnelState.set(TunnelStatus.Idle)
        assertEquals(TunnelStatus.Idle, TunnelState.status.value)
    }
}

/** Скорость по счётчикам ядра (D-40). */
class TrafficMeterTest {
    @Test
    fun speedIsDeltaOverTimeAndNewSessionRestarts() {
        val m = TrafficMeter()
        assertEquals(Traffic(100, 1000, 0, 0), m.sample(com.dpsmpx.dppass.engine.TrafficStats(100, 1000), 0))
        assertEquals(Traffic(600, 3000, 250, 1000), m.sample(com.dpsmpx.dppass.engine.TrafficStats(600, 3000), 2000))
        // Счётчики уменьшились — новая сессия ядра: скорость с нуля, а не отрицательная.
        assertEquals(Traffic(10, 20, 0, 0), m.sample(com.dpsmpx.dppass.engine.TrafficStats(10, 20), 3000))
        // Время не прошло — без деления на ноль.
        assertEquals(Traffic(10, 20, 0, 0), m.sample(com.dpsmpx.dppass.engine.TrafficStats(10, 20), 3000))
    }

    @Test
    fun trafficResetsWhenTunnelGoesAway() {
        TunnelState.set(TunnelStatus.Connected(ProfileId("a"), "A", 0))
        TunnelState.traffic(Traffic(1, 2, 3, 4))
        TunnelState.set(TunnelStatus.Idle)
        assertEquals(Traffic.ZERO, TunnelState.traffic.value)
    }
}
