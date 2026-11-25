package com.dpsmpx.dpai.cli

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.vfs.NioVfs
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutomationCommandTest {
    @TempDir lateinit var root: Path
    private val project = IdGenerator(ManualClock(), RandomSource.seeded(222)).next(IdKinds.PROJECT).value
    private fun paired(): NioVfs = NioVfs(root).also {
        it.write(ControlCommand.FILE, CanonicalJson.write(JsonObject.of("port" to JsonNumber.of(47777),
            "token" to JsonString("a".repeat(64)))).toByteArray())
    }

    @Test fun noPairingOrUnknownFlagsCannotReachTheChannel() {
        var calls = 0
        val errors = arrayListOf<String>()
        val cli = AutomationCommand({}, errors::add, config = NioVfs(root), transport = {
            ControlTransport { _, _, _, _ -> calls++; HttpReply(200, JsonObject.of()) }
        })
        assertEquals(2, cli.run(listOf(project, "list")))
        assertEquals(2, cli.run(listOf(project, "run", "--yes")))
        assertEquals(2, cli.run(listOf(project, "enable")))
        assertEquals(0, calls)
        assertTrue(errors.isNotEmpty())
    }

    @Test fun structuredRunUsesTheExistingSignedTransportAndDoesNotInventApproval() {
        val body = CanonicalJson.write(JsonObject.of("id" to JsonString("manual.check"), "revision" to JsonString("0".repeat(64)),
            "parameters" to com.dpsmpx.dpai.core.serialization.JsonArray(emptyList())))
        var request: JsonObject? = null
        val cli = AutomationCommand({}, { error(it) }, body.byteInputStream(), paired()) {
            ControlTransport { method, path, token, payload ->
                assertEquals("POST", method); assertEquals("/v1/automation", path)
                assertNotNull(token); request = payload
                HttpReply(202, JsonObject.of("accepted" to com.dpsmpx.dpai.core.serialization.JsonBool(true), "run" to JsonString("operation")))
            }
        }
        assertEquals(3, cli.run(listOf(project, "run", "--stdin")))
        assertEquals(setOf("project", "action", "input"), request!!.fields.keys)
        val input = request!!["input"] as JsonObject
        assertEquals(setOf("id", "revision", "parameters"), input.fields.keys)
    }

    @Test fun invalidOrOversizedInputIsRejectedBeforeTheNetwork() {
        for (text in listOf("[]", "{", " ".repeat(262_145))) {
            val cli = AutomationCommand({}, {}, text.byteInputStream(), paired()) {
                error("bad input must not reach the transport")
            }
            assertEquals(2, cli.run(listOf(project, "run", "--stdin")))
        }
    }

    @Test fun aSuccessfulHttpStatusCannotHideAnOperationFailure() {
        for (status in listOf("FAILED", "INCOMPLETE", "BLOCKED", "NEEDS_REVIEW")) {
            val cli = AutomationCommand({}, {}, config = paired(), transport = {
                ControlTransport { _, _, _, _ -> HttpReply(200, JsonObject.of("status" to JsonString(status))) }
            })
            assertEquals(1, cli.run(listOf(project, "status")))
        }
        val waiting = AutomationCommand({}, {}, config = paired(), transport = {
            ControlTransport { _, _, _, _ -> HttpReply(200, JsonObject.of("status" to JsonString("RUNNING"))) }
        })
        assertEquals(3, waiting.run(listOf(project, "status")))
    }
}
