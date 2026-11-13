package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LspFramingTest {
    private fun reader(bytes: ByteArray, max: Int = 1024) = LspReader(ByteArrayInputStream(bytes), max)

    private fun frame(body: String, header: String = "Content-Length: ${body.toByteArray().size}") = "$header\r\n\r\n$body".toByteArray()

    private fun code(r: Outcome<*>): DiagCode = (r as Outcome.Failure).diagnostics.first().code

    @Test
    fun writtenFrameIsReadBack() {
        val out = ByteArrayOutputStream()
        val message = JsonObject.of("method" to json("привет"), "n" to json(1))
        LspWriter(out).write(message)
        LspWriter(out).write(message)
        val r = reader(out.toByteArray())
        assertEquals(message, (r.read() as Outcome.Success).value)
        assertEquals(message, (r.read() as Outcome.Success).value)
        assertNull((r.read() as Outcome.Success).value)
    }

    @Test
    fun lengthCountsBytesNotChars() {
        val out = ByteArrayOutputStream()
        LspWriter(out).write(json("жж"))
        assertEquals("Content-Length: 6\r\n\r\n\"жж\"", out.toString(Charsets.UTF_8))
    }

    @Test
    fun extraHeadersAndCaseAreAccepted() {
        val bytes = "content-length: 2\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n{}".toByteArray()
        assertEquals(JsonObject(emptyMap()), (reader(bytes).read() as Outcome.Success).value)
    }

    @Test
    fun brokenFramesAreRefused() {
        assertEquals(DiagCode.LSP_REPLY_INVALID, code(reader("X: 1\r\n\r\n{}".toByteArray()).read()))
        assertEquals(DiagCode.LSP_REPLY_INVALID, code(reader(frame("{}", "Content-Length: -1")).read()))
        assertEquals(DiagCode.LSP_REPLY_INVALID, code(reader(frame("{}", "Content-Length: много")).read()))
        assertEquals(DiagCode.LSP_REPLY_INVALID, code(reader(frame("{}", "без двоеточия")).read()))
        assertEquals(DiagCode.LSP_REPLY_INVALID, code(reader(frame("{не json")).read()))
        assertEquals(DiagCode.LSP_REPLY_INVALID, code(reader("Content-Length: 2\r\n\r\n".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)).read()))
    }

    @Test
    fun oversizedBodyIsRefusedBeforeReading() {
        assertEquals(DiagCode.LSP_REPLY_INVALID, code(reader("Content-Length: 999999999\r\n\r\n".toByteArray(), max = 100).read()))
        assertEquals(DiagCode.LSP_REPLY_INVALID, code(reader(("A".repeat(5000) + ": 1\r\n\r\n").toByteArray()).read()))
    }

    @Test
    fun streamCutMidMessageIsServerStopped() {
        assertEquals(DiagCode.LSP_SERVER_STOPPED, code(reader("Content-Length: 10\r\n\r\n{}".toByteArray()).read()))
        assertEquals(DiagCode.LSP_SERVER_STOPPED, code(reader("Content-Len".toByteArray()).read()))
    }
}
