package com.dpsmpx.dpai.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CancelParentTest {
    @Test fun aSharedDeadlineCancelsRegisteredHooksExactlyOnceWhenPolled() {
        var expired = false
        val cancel = Cancel { expired }
        var hooks = 0
        cancel.onCancel { hooks++ }
        assertFalse(cancel.cancelled)
        expired = true
        assertTrue(cancel.cancelled); assertTrue(cancel.cancelled)
        cancel.cancel()
        assertEquals(1, hooks)
        cancel.onCancel { hooks++ }
        assertEquals(2, hooks)
    }

    @Test fun anAlreadyCancelledParentCannotLeaveANewHookWaiting() {
        val cancel = Cancel { true }
        var called = false
        cancel.onCancel { called = true }
        assertTrue(called); assertTrue(cancel.cancelled)
    }
}
