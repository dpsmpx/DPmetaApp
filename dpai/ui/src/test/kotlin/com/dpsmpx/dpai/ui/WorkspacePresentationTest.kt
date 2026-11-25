package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspacePresentationTest {
    @Test fun everyPreviouslySavedPageStillOpensItsContent() {
        // Контракт сохранённого состояния dev.46, а не номера новых вкладок.
        for (old in listOf("FILES", "AI", "CHANGES", "CHECKS", "BUILD", "RUN", "DEPENDENCIES", "AUTOMATIONS")) {
            val place = WorkspacePresentation.restore(old)
            assertEquals(old, place.operation ?: place.primary)
        }
        assertEquals("FILES", WorkspacePresentation.restore("removed-or-unknown").primary)
        assertNull(WorkspacePresentation.restore("removed-or-unknown").operation)
    }
}
