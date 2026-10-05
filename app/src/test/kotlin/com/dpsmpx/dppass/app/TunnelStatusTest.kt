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
