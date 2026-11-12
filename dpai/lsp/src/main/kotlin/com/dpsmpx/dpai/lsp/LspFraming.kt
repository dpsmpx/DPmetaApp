package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonValue
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Кадры базового протокола LSP (D-137): заголовки ASCII, пустая строка,
 * тело JSON в UTF-8 длиной `Content-Length` байт. Пределы — чтобы сбойный
 * или чужой сервер не занял память телефона: тело длиннее [maxBody] и
 * заголовки длиннее [MAX_HEADER] — отказ, а не попытка прочитать.
 */
class LspReader(private val input: InputStream, private val maxBody: Int = DEFAULT_MAX_BODY) {
    private val parser = JsonParser(JsonParser.Limits(maxChars = maxBody))

    /** Следующее сообщение; `null` — поток кончился на границе кадров. */
    fun read(): Outcome<JsonValue?> {
        var length = -1
        var first = true
        while (true) {
            val line = when (val l = headerLine(first)) {
                is Outcome.Failure -> return l
                is Outcome.Success -> l.value ?: return Outcome.success(null)
            }
            first = false
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) return Outcome.failure(DiagCode.LSP_REPLY_INVALID, "заголовок без двоеточия")
            if (line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                length = line.substring(colon + 1).trim().toIntOrNull()?.takeIf { it >= 0 }
                    ?: return Outcome.failure(DiagCode.LSP_REPLY_INVALID, "Content-Length — не число")
            }
        }
        if (length < 0) return Outcome.failure(DiagCode.LSP_REPLY_INVALID, "нет Content-Length")
        if (length > maxBody) return Outcome.failure(DiagCode.LSP_REPLY_INVALID, "сообщение службы длиннее предела: $length байт")
        val body = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = try {
                input.read(body, got, length - got)
            } catch (e: IOException) {
                return Outcome.failure(DiagCode.LSP_SERVER_STOPPED, "чтение оборвано: ${e.message ?: e.javaClass.simpleName}")
            }
            if (n < 0) return Outcome.failure(DiagCode.LSP_SERVER_STOPPED, "поток кончился посреди сообщения")
            got += n
        }
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(body)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            return Outcome.failure(DiagCode.LSP_REPLY_INVALID, "тело — не UTF-8")
        }
        return when (val v = parser.parse(text)) {
            is Outcome.Success -> Outcome.success(v.value)
            is Outcome.Failure -> Outcome.failure(DiagCode.LSP_REPLY_INVALID, "тело — не JSON: ${v.diagnostics.first().message}")
        }
    }

    /** Строка заголовка без `\r\n`; `null` — конец потока до первого байта первой строки. */
    private fun headerLine(first: Boolean): Outcome<String?> {
        val sb = StringBuilder()
        var any = false
        while (true) {
            val b = try {
                input.read()
            } catch (e: IOException) {
                return Outcome.failure(DiagCode.LSP_SERVER_STOPPED, "чтение оборвано: ${e.message ?: e.javaClass.simpleName}")
            }
            if (b < 0) {
                return if (first && !any) Outcome.success(null) else Outcome.failure(DiagCode.LSP_SERVER_STOPPED, "поток кончился посреди заголовка")
            }
            any = true
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return Outcome.success(sb.toString())
            }
            if (b > 0x7e || (b < 0x20 && b != '\r'.code && b != '\t'.code)) return Outcome.failure(DiagCode.LSP_REPLY_INVALID, "заголовок — не ASCII")
            if (sb.length >= MAX_HEADER) return Outcome.failure(DiagCode.LSP_REPLY_INVALID, "заголовок длиннее предела")
            sb.append(b.toChar())
        }
    }

    companion object {
        /** 8 МиБ: ответ на дополнение или ссылки в большом проекте; больше — отказ. */
        const val DEFAULT_MAX_BODY: Int = 8 * 1024 * 1024
        const val MAX_HEADER: Int = 1024
    }
}

/** Запись кадров; один кадр — одна запись под замком: сообщения разных потоков не перемешиваются. */
class LspWriter(private val output: OutputStream) {
    private val lock = Any()

    fun write(message: JsonValue): Outcome<Unit> {
        val body = CanonicalJson.bytes(message)
        val head = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        return try {
            synchronized(lock) {
                output.write(head)
                output.write(body)
                output.flush()
            }
            Outcome.success(Unit)
        } catch (e: IOException) {
            Outcome.failure(DiagCode.LSP_SERVER_STOPPED, "запись оборвана: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun close() {
        try {
            output.close()
        } catch (_: IOException) {
            // Закрытие уже закрытого потока службы — не отказ.
        }
    }
}
