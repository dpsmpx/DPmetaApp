package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkflowCorpusTest {
    @org.junit.jupiter.api.io.TempDir lateinit var root: java.nio.file.Path
    private fun sample(name: String): JsonObject = JsonParser().parse(javaClass.getResource("/workflows/$name.json")!!.readText()).valueOrNull() as JsonObject

    @Test fun eventAndRunFormatsAreValidatedAfterReadingDurableDocuments() {
        val vfs = com.dpsmpx.dpai.storage.vfs.NioVfs(root)
        val run = sample("run-v1")
        val key = (run["run"] as com.dpsmpx.dpai.core.serialization.JsonString).value
        val docs = com.dpsmpx.dpai.storage.document.DocumentStore(vfs, "runs")
        docs.write(key, run)
        val store = WorkflowStore(vfs)
        assertEquals(run, store.result("project.flow", "sample:1").valueOrNull())
        docs.write(key, JsonObject(run.fields + ("version" to com.dpsmpx.dpai.core.serialization.JsonNumber.of(99))))
        assertIs<Outcome.Failure>(store.result("project.flow", "sample:1"))
        com.dpsmpx.dpai.storage.document.DocumentStore(vfs, "pending-events").write(key, sample("event-v1"))
        assertEquals("sample:1", WorkflowEvents(vfs, com.dpsmpx.dpai.core.clock.ManualClock()).pending().valueOrNull()!!.single().event.id)
    }

    @Test fun knownFormatRoundTripsAndFutureFormatCannotBeEnabled() {
        fun read(name: String): JsonObject = JsonParser().parse(javaClass.getResource("/workflows/$name.json")!!.readText()).valueOrNull() as JsonObject
        val current = read("v1")
        assertEquals(current, assertIs<Outcome.Success<Workflow>>(Workflow.parse(current)).value.json())
        assertIs<Outcome.Failure>(Workflow.parse(read("unknown-version")))
    }
}
