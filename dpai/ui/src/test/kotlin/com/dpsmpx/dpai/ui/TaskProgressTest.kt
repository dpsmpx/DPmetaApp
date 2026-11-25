package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskProgressTest {
    @Test fun realDownloadFractionsAndCacheHitsHaveDifferentMeaning() {
        val down = TaskProgress.latest("DPAI_PROGRESS download SDK\n### 12.5%\r###### 50.0%\r")
        assertEquals(TaskProgress.Phase.DOWNLOAD, down?.phase)
        assertEquals(0.5f, down?.fraction)
        val cached = TaskProgress.latest("DPAI_PROGRESS download SDK\n100%\nDPAI_PROGRESS cache SDK")
        assertEquals(TaskProgress.Phase.CACHE, cached?.phase)
        assertNull(cached?.fraction, "готовый архив ещё не означает завершённую установку")
    }

    @Test fun nativeGradleWrapperAndNormalTaskStatusesRemainProgress() {
        val wrapper = TaskProgress.latest("Fetching distribution.\nDownloading https://example.invalid/gradle.zip\n...10%...20%...100%")
        assertEquals(1f, wrapper?.fraction)
        for (status in listOf("NO-SOURCE", "UP-TO-DATE", "FROM-CACHE", "SKIPPED")) {
            val task = TaskProgress.latest("> Task :module:compileJava $status")
            assertEquals(TaskProgress.Phase.TASK, task?.phase)
            assertEquals(":module:compileJava", task?.detail)
            assertNull(task?.fraction)
        }
    }

    @Test fun knownPackageCountsAreCheckedAndUnknownTotalsStayUnknown() {
        val packages = TaskProgress.latest("(2/8) Installing package")
        assertEquals(0.25f, packages?.fraction)
        assertEquals(2, packages?.current)
        assertNull(TaskProgress.latest("(2/0) invalid"))
        assertNull(TaskProgress.latest("(999999999999/1) invalid"))
        assertNull(TaskProgress.latest("unrelated log"))
    }
}
