package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.tools.SharedTools
import com.dpsmpx.dpai.execution.tools.SharedToolsSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class SharedToolsSyncTest {
    @Test
    fun accountTravelsOnlyInStdinWithJsonEscaping() {
        val value = listOf("synthetic", "account", "\"\\").joinToString("-")
        for (first in listOf(true, false)) {
            val spec = SharedToolsSync.request(SharedTools("/a space/tools", "/a space/controller.py"), "/state", first, Secret(value))
            assertFalse(spec.command.any { value in it })
            assertNull(spec.environment)
            assertEquals(if (first) "init" else "sync", spec.command[4])
            assertEquals("--github-stdin", spec.command.last())
            val decoded = assertIs<Outcome.Success<*>>(JsonParser().parse(spec.stdin!!)).value
            assertEquals(value, (assertIs<JsonObject>(decoded)["token"] as JsonString).value)
        }
    }
}
