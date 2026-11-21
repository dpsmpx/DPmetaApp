package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectIndexTest {
    @Test fun onlyPairedEndCompletesTheQueueAndANewQueueRevokesOldLease() {
        val index = ProjectIndex()
        index.progress(ProjectIndex.PROGRESS_ID, "end")
        assertTrue(index.lease() is Outcome.Failure)
        index.requested("other")
        index.progress("other", "begin"); index.progress("other", "end")
        assertTrue(index.lease() is Outcome.Failure)
        index.requested(ProjectIndex.PROGRESS_ID)
        index.progress(ProjectIndex.PROGRESS_ID, "begin")
        index.progress(ProjectIndex.PROGRESS_ID, "report")
        assertTrue(index.lease() is Outcome.Failure)
        index.progress(ProjectIndex.PROGRESS_ID, "end")
        val lease = (index.lease() as Outcome.Success).value
        assertTrue(lease.valid())
        index.requested(ProjectIndex.PROGRESS_ID)
        assertFalse(lease.valid())
        index.progress(ProjectIndex.PROGRESS_ID, "end")
        val next = (index.lease() as Outcome.Success).value
        index.stopped()
        assertFalse(next.valid())
    }

    @Test fun clientTracksTheActualProgressTokenAndClosingRevokesTheLease() {
        fun parse(s: String) = (JsonParser().parse(s) as Outcome.Success).value
        val fake = FakeServer { method, _ -> if (method == "initialize") parse("""{"capabilities":{}}""") else JsonNull }
        val client = LspClient(fake.clientIn, fake.clientOut, "file:///p", object : LspClient.Listener {
            override fun diagnostics(uri: String, version: Int?, items: List<LspDiagnostic>) = Unit
            override fun stopped(reason: Diagnostic) = Unit
        })
        assertTrue(client.start() is Outcome.Success)
        assertTrue(client.projectIndexLease() is Outcome.Failure)
        fake.send(parse("""{"jsonrpc":"2.0","id":"index","method":"window/workDoneProgress/create","params":{"token":"backgroundIndexProgress"}}"""))
        fake.notify("$/progress", parse("""{"token":"backgroundIndexProgress","value":{"kind":"begin","title":"indexing"}}"""))
        fake.notify("$/progress", parse("""{"token":"backgroundIndexProgress","value":{"kind":"report","percentage":100}}"""))
        repeat(100) { if (fake.responses.isEmpty()) CountDownLatch(1).await(10, TimeUnit.MILLISECONDS) }
        assertTrue(client.hover("file:///p/main.cpp", Position(0, 0)) is Outcome.Success)
        assertTrue(client.projectIndexLease() is Outcome.Failure)
        fake.notify("$/progress", parse("""{"token":"backgroundIndexProgress","value":{"kind":"end"}}"""))
        var lease: ProjectIndex.Lease? = null
        repeat(100) { if (lease == null) { lease = (client.projectIndexLease() as? Outcome.Success)?.value; if (lease == null) CountDownLatch(1).await(10, TimeUnit.MILLISECONDS) } }
        assertTrue(lease?.valid() == true)
        client.stop()
        assertFalse(lease.valid())
    }
}
