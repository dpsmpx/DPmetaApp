package com.dpsmpx.dpai.orchestrator.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessEnvironmentTest {
    @Test fun windowsRemovesMixedCaseOverridesAndDoesNotDuplicateSetNames() {
        val result = ProcessEnvironment.build(mapOf("Anthropic_Api_Key" to "value", "Path" to "old"),
            setOf("ANTHROPIC_API_KEY"), mapOf("PATH" to "new"), windows = true)
        assertEquals(1, result.size); assertEquals("new", result["path"])
    }
    @Test fun linuxNamesKeepTheirCaseSemantics() {
        val result = ProcessEnvironment.build(mapOf("Path" to "old", "PATH" to "old2"), emptySet(), mapOf("PATH" to "new"), false)
        assertEquals(mapOf("Path" to "old", "PATH" to "new"), result)
    }
}
