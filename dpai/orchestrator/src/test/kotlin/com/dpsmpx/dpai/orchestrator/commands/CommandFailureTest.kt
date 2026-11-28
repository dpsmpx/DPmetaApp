package com.dpsmpx.dpai.orchestrator.commands

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandFailureTest {
    @Test fun thrownExecutorSettlesAcceptedCommandAndCannotRemainPendingForReplay() {
        val id = IdGenerator(ManualClock(), RandomSource.seeded(222)).next(IdKinds.OPERATION)
        val inbox = CommandInbox()
        val future = inbox.submit(Command.AddTask("Тест", "Цель", key = "test-command-failure"), id)
        val settled = mutableListOf<Pair<String, String>>()
        assertEquals(1, inbox.drain({ error("частичный эффект неизвестен") }, { accepted, status -> settled += accepted.value to status }))
        assertTrue(future.isCompletedExceptionally)
        assertEquals(listOf(id.value to "FAILED"), settled)
        assertEquals(0, inbox.drain({ error("не повторять") }))
    }
}
