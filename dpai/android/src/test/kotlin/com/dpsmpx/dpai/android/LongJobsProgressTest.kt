package com.dpsmpx.dpai.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LongJobsProgressTest {
    @Test fun lateOutputCannotCrossIntoNextRun() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val jobs = LongJobs(scope)
            val first = CompletableDeferred<Unit>()
            val second = CompletableDeferred<Unit>()
            var old: (String) -> Unit = {}
            var current: (String) -> Unit = {}
            assertTrue(jobs.start(LongJobs.LINUX_APK, "first") {
                old = jobs.guestReporter()
                first.await()
                LongJobs.Done("first done")
            })
            old("DPAI_PROGRESS download SDK")
            assertEquals(listOf("DPAI_PROGRESS download SDK"), jobs.state.value.getValue(LongJobs.LINUX_APK).output)
            first.complete(Unit)
            assertTrue(jobs.start(LongJobs.LINUX_APK, "second") {
                current = jobs.guestReporter()
                second.await()
                LongJobs.Done("second done")
            })
            old("late old error")
            assertTrue(jobs.state.value.getValue(LongJobs.LINUX_APK).output.isEmpty())
            current("new progress")
            assertEquals(listOf("new progress"), jobs.state.value.getValue(LongJobs.LINUX_APK).output)
            second.complete(Unit)
        } finally {
            scope.cancel()
        }
    }
}
