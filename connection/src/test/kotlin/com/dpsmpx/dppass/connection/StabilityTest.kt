package com.dpsmpx.dppass.connection

import com.dpsmpx.dppass.core.ProfileId
import com.dpsmpx.dppass.storage.InMemoryBlobStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StabilityTest {
    @Test
    fun learnerPreservesJitterAndPersistence() {
        val store = InMemoryBlobStore()
        val id = ProfileId("edge-1")
        val book = StabilityBook(store)

        book.observe(id, StabilitySample(true, 100, timestampMillis = 1_000))
        book.observe(id, StabilitySample(true, 180, timestampMillis = 2_000))
        book.observe(id, StabilitySample(true, 120, timestampMillis = 3_000))

        val before = book.stats(id)
        assertEquals(3, before.attempts)
        assertTrue(before.ewmaJitterMillis > 0.0)
        assertTrue(before.ewmaRttMillis in 100.0..180.0)
        assertTrue(book.persist() is com.dpsmpx.dppass.diagnostics.Outcome.Success)

        val restored = StabilityBook(store)
        assertEquals(before, restored.stats(id))
    }

    @Test
    fun repeatedHardFailuresTriggerRecoveryHysteresis() {
        val book = StabilityBook()
        val id = ProfileId("edge-2")
        repeat(4) { n ->
            book.observe(
                id,
                StabilitySample(
                    success = false,
                    rttMillis = 5_000,
                    timestampMillis = (n + 1L) * 1_000,
                ),
            )
        }
        val state = book.stats(id)
        assertTrue(state.badStreak >= 4)
        assertTrue(book.shouldRecover(id))
    }
}
