package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import org.junit.jupiter.api.io.TempDir
import java.io.Closeable
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkflowEventsTest {
    @TempDir lateinit var root: Path
    private val clock = ManualClock()
    private val hash = Hashing.sha256("project").hex
    private val w = Workflow("project.flow", "Проверка", hash, listOf(WorkflowStep("manual.check", hash)),
        listOf(WorkflowTrigger(AutomationTrigger.FILES_CHANGED)), enabled = true)
    private val event = AutomationEvent("editor:1", AutomationTrigger.FILES_CHANGED, hash, hash)
    private fun queue() = WorkflowEvents(NioVfs(root), clock)

    @Test fun pendingDeliverySurvivesRestartAndDuplicateDoesNotReplaceItsRevision() {
        assertTrue(queue().enqueue(w, event).isSuccess)
        assertTrue(queue().enqueue(w, event).isSuccess)
        assertIs<Outcome.Failure>(queue().enqueue(w.copy(title = "новая редакция"), event))
        assertIs<Outcome.Failure>(queue().enqueue(w, event.copy(fingerprint = Hashing.sha256("changed").hex)))
        val pending = queue().pending().valueOrNull()!!.single()
        assertEquals(w.revision, pending.revision)
        assertEquals(event, pending.event)
        assertTrue(queue().acknowledge(pending.key).isSuccess)
        assertEquals(emptyList(), queue().pending().valueOrNull())
    }

    @Test fun unknownDurableVersionIsRefusedRatherThanDropped() {
        queue().enqueue(w, event)
        val pending = queue().pending().valueOrNull()!!.single()
        assertTrue(DocumentStore(NioVfs(root), "pending-events").write(pending.key,
            JsonObject(pending.json().fields + ("version" to JsonNumber.of(99)))).isSuccess)
        assertIs<Outcome.Failure>(queue().pending())
        assertIs<Outcome.Failure>(queue().enqueue(w, event))
    }

    @Test fun leaseAndFailedAcknowledgementKeepTheDeliveryAvailable() {
        queue().enqueue(w, event)
        val key = queue().pending().valueOrNull()!!.single().key
        NioVfs(root).tryLease("events.lock")!!.use { assertIs<Outcome.Failure>(queue().pending()) }
        val real = NioVfs(root)
        val failing = object : Vfs by real {
            override fun delete(path: String) { throw IOException("disk unavailable") }
        }
        val failed = WorkflowEvents(failing, clock) { Closeable { } }.acknowledge(key)
        assertIs<Outcome.Failure>(failed)
        assertEquals(key, queue().pending().valueOrNull()!!.single().key)
    }

    @Test fun disabledOrWrongContextEventsNeverBecomePending() {
        assertIs<Outcome.Failure>(queue().enqueue(w.copy(enabled = false), event))
        assertIs<Outcome.Failure>(queue().enqueue(w, event.copy(context = Hashing.sha256("other").hex)))
        assertEquals(emptyList(), queue().pending().valueOrNull())
    }
}
