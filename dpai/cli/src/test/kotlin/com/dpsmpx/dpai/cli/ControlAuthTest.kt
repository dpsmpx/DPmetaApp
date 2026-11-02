package com.dpsmpx.dpai.cli

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.orchestrator.control.ControlApi
import com.dpsmpx.dpai.orchestrator.control.ControlAuth
import com.dpsmpx.dpai.orchestrator.control.ControlRequest
import com.dpsmpx.dpai.orchestrator.control.Pairing
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.io.TempDir

/**
 * Подпись канала `dpai` (D-92; аудит безопасности 1 ноября 2026, A-2):
 * токен в сеть не уходит; чужая, просроченная и повторная подпись —
 * `401`; ответ без верной подписи приложения `dpai` не принимает — на
 * порту могла оказаться чужая программа.
 */
class ControlAuthTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val nonces = RandomSource.seeded(4)
    private val pairing by lazy { Pairing(NioVfs(dir.resolve("app").also { it.toFile().mkdirs() }), RandomSource.seeded(5), clock) }

    /** Канал без контура: всё, что прошло подпись, — `503` «контроллер не запущен», подписанный. */
    private val api by lazy { ControlApi(pairing, { null }) { error("контура нет — до команд не дойдёт") } }

    /** Что ушло в провод: заголовки и тело каждого запроса. */
    private val sent = mutableListOf<Pair<Map<String, String>, String?>>()

    /** Провод прямо в канал — как `ControlServer`: тело ответа — `CanonicalJson`, подпись — заголовком. */
    private fun wire(change: (WireReply) -> WireReply = { it }) = Wire { method, path, headers, body ->
        sent += headers to body
        val r = api.handle(ControlRequest(method, path, headers.mapKeys { it.key.lowercase() }, body))
        change(WireReply(r.status, r.signature?.let { mapOf(ControlAuth.SIGNATURE to it) }.orEmpty(), CanonicalJson.write(r.body)))
    }

    private fun pair(): String {
        val (code, _) = pairing.newCode()
        return (api.handle(ControlRequest("POST", "/v1/pair", body = """{"code":"$code"}""")).body["token"] as JsonString).value
    }

    private fun signed(token: String, method: String, path: String, body: String?, at: Long = clock.now().epochMillis) =
        ControlAuth.requestHeaders(token, method, path, body, at, ControlAuth.newNonce(nonces))

    @Test
    fun signedRequestIsAcceptedForgedStaleAndReplayedAreNot() {
        val token = pair()
        val ok = SignedTransport(wire(), clock, RandomSource.seeded(6)).call("GET", "/v1/state", token, null)
        assertEquals(503, ok.status, "подпись верна — запрос дошёл до контура (его здесь нет): ${ok.error}")
        assertNotNull(ok.body)
        assertTrue(sent.none { (h, b) -> h.values.any { token in it } || b?.contains(token) == true }, "токен в провод не уходит")

        assertEquals(401, SignedTransport(wire(), clock, RandomSource.seeded(7)).call("GET", "/v1/state", "1".repeat(64), null).status, "чужой токен")
        assertEquals(401, api.handle(ControlRequest("GET", "/v1/state", mapOf("authorization" to "Bearer $token"))).status, "токен открытым текстом больше не принимается")
        assertEquals(401, api.handle(ControlRequest("GET", "/v1/state")).status, "без подписи")

        val old = signed(token, "GET", "/v1/state", null, at = clock.now().epochMillis - 3.minutes.inWholeMilliseconds)
        assertEquals(401, api.handle(ControlRequest("GET", "/v1/state", old)).status, "старше двух минут")
        val ahead = signed(token, "GET", "/v1/state", null, at = clock.now().epochMillis + 3.minutes.inWholeMilliseconds)
        assertEquals(401, api.handle(ControlRequest("GET", "/v1/state", ahead)).status, "новее двух минут")

        val forState = signed(token, "GET", "/v1/state", null)
        assertEquals(401, api.handle(ControlRequest("POST", "/v1/commands", forState, "{}")).status, "подпись — к другому методу, пути и телу")
        val body = """{"command":"task.hold"}"""
        val forBody = signed(token, "POST", "/v1/commands", body)
        assertEquals(401, api.handle(ControlRequest("POST", "/v1/commands", forBody, """{"command":"task.cancel"}""")).status, "тело подменено")

        val once = signed(token, "GET", "/v1/state", null)
        assertEquals(503, api.handle(ControlRequest("GET", "/v1/state", once)).status)
        assertEquals(401, api.handle(ControlRequest("GET", "/v1/state", once)).status, "повтор того же запроса")
        clock.advance(1.minutes)
        assertEquals(401, api.handle(ControlRequest("GET", "/v1/state", once)).status, "и через минуту — повтор")

        pairing.revoke()
        assertEquals(401, SignedTransport(wire(), clock, RandomSource.seeded(8)).call("GET", "/v1/state", token, null).status, "токен отозван")
    }

    @Test
    fun responseWithoutValidSignatureIsNotTrusted() {
        val token = pair()
        val tampered = SignedTransport(wire { it.copy(body = it.body!!.replace("контроллер", "всё хорошо, контроллер")) }, clock, RandomSource.seeded(9))
            .call("GET", "/v1/state", token, null)
        assertNull(tampered.body, "тело ответа подменено — не верим")
        assertEquals(SignedTransport.UNSIGNED, tampered.error)
        assertNull(SignedTransport(wire { it.copy(status = 200) }, clock, RandomSource.seeded(10)).call("GET", "/v1/state", token, null).body, "код подменён")

        // Чужая программа на порту 47777: отвечает «как приложение», но без подписи.
        val impostor = Wire { _, _, _, _ -> WireReply(200, emptyMap(), """{"tasks":[],"sessions":[],"requests":[]}""") }
        val r = SignedTransport(impostor, clock, RandomSource.seeded(11)).call("GET", "/v1/state", token, null)
        assertEquals(0, r.status)
        assertNull(r.body)
        // Подпись ответа к другому значению запроса — не подходит: старый ответ не повторить.
        val replayed = Wire { _, _, _, _ ->
            val other = ControlAuth.newNonce(RandomSource.seeded(12))
            val text = """{"tasks":[]}"""
            WireReply(200, mapOf(ControlAuth.SIGNATURE to ControlAuth.sign(token, ControlAuth.responseText(other, 200, text))), text)
        }
        assertNull(SignedTransport(replayed, clock, RandomSource.seeded(13)).call("GET", "/v1/state", token, null).body)
        assertFalse(ControlAuth.responseValid(token, "0".repeat(32), 200, "{}", "не подпись"))
    }

    @Test
    fun dpaiPairsAndCallsThroughSignedChannel() {
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        val config = NioVfs(dir.resolve("config").also { it.toFile().mkdirs() })
        val cli = ControlCommand(out::add, err::add, config) { SignedTransport(wire(), clock, RandomSource.seeded(14)) }
        val (code, _) = pairing.newCode()
        assertEquals(ControlCommand.OK, cli.run("control", listOf("pair", code)), err.toString())
        assertEquals(ControlCommand.FAILED, cli.run("status", emptyList()))
        assertTrue(err.any { "контроллер не запущен" in it }, "подписанный ответ приложения принят и показан: $err")

        // Порт занят чужой программой: её «всё готово» dpai не показывает.
        err.clear()
        val fake = ControlCommand(out::add, err::add, config) { SignedTransport(Wire { _, _, _, _ -> WireReply(200, emptyMap(), """{"status":"SUCCEEDED"}""") }, clock, RandomSource.seeded(15)) }
        assertEquals(ControlCommand.FAILED, fake.run("task", listOf("archive-done")))
        assertTrue(err.any { "подписи" in it }, err.toString())
    }
}
