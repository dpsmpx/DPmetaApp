package com.dpsmpx.dpai.cli

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.orchestrator.control.ControlApi
import com.dpsmpx.dpai.orchestrator.control.ControlAuth
import com.dpsmpx.dpai.orchestrator.control.ControlRequest
import com.dpsmpx.dpai.orchestrator.control.ControlResponse
import com.dpsmpx.dpai.orchestrator.control.Pairing
import com.dpsmpx.dpai.storage.vfs.NioVfs
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AutomationChannelTest {
    @TempDir lateinit var root: Path

    @Test fun signedAutomationWorksWithoutClaudeContourButNeverWithoutAuthentication() {
        val clock = ManualClock(); val random = RandomSource.seeded(222)
        val pairing = Pairing(NioVfs(root), random, clock)
        val code = pairing.newCode().first
        val token = pairing.pair(code).valueOrNull()!!
        var effects = 0
        val api = ControlApi(pairing, { null }, automation = { input ->
            effects++; assertEquals(JsonString("list"), input["action"])
            ControlResponse(202, JsonObject.of("status" to JsonString("RUNNING")))
        }) { error("automation cannot become an orchestrator command") }
        val body = """{"action":"list"}"""
        assertEquals(401, api.handle(ControlRequest("POST", "/v1/automation", body = body)).status)
        assertEquals(0, effects)
        val headers = ControlAuth.requestHeaders(token, "POST", "/v1/automation", body, clock.now().epochMillis,
            ControlAuth.newNonce(random))
        val response = api.handle(ControlRequest("POST", "/v1/automation", headers, body))
        assertEquals(202, response.status); assertNotNull(response.signature); assertEquals(1, effects)
        val nonce = headers.getValue(ControlAuth.NONCE)
        assertEquals(true, ControlAuth.responseValid(token, nonce, response.status, CanonicalJson.write(response.body), response.signature))
        assertEquals(401, api.handle(ControlRequest("POST", "/v1/automation", headers, body)).status)
        assertEquals(1, effects)
        pairing.revoke()
        assertEquals(401, api.handle(ControlRequest("POST", "/v1/automation", headers, body)).status)
        assertEquals(1, effects)
    }
}
