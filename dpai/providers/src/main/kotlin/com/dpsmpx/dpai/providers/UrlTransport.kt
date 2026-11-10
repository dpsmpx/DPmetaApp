package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * Транспорт на `HttpURLConnection` — есть и в JDK, и в Android (у телефона
 * `java.net.http` нет). Правила те же, что у сети DPmetaApp
 * (`DPmetaApp:core/net`): TLS системными корнями, без переадресаций,
 * заголовок — только печатные ASCII, текст исключения в диагностику не
 * переносится (в нём бывает адрес с подробностями). Тело ответа читается
 * построчно — поток SSE идёт, пока модель пишет.
 *
 * [readTimeoutMillis] — пауза между байтами ответа: долгий ход модели
 * без вывода дольше неё обрывается как сбой сети.
 */
class UrlTransport(
    private val connectTimeoutMillis: Int = 20_000,
    private val readTimeoutMillis: Int = 180_000,
    private val maxLineBytes: Int = MAX_LINE_BYTES,
) : Transport {
    override fun open(call: HttpCall, cancel: Cancel): Outcome<HttpReply> {
        val bad = call.headers.entries.firstOrNull { !printable(it.key) || !printable(it.value) }?.key
            ?: call.secret?.let { if (printable(it.name) && printable(it.prefix) && printable(it.secret.reveal())) null else it.name }
        if (bad != null) return Outcome.failure(DiagCode.VALUE_INVALID, "заголовок «$bad» — только печатные знаки ASCII", call.url.host)
        if (cancel.cancelled) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "запрос отменён", call.url.host)
        val c = try {
            call.url.toURL().openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return Outcome.failure(DiagCode.NETWORK_UNREACHABLE, "соединение не открылось: ${e.javaClass.simpleName}", call.url.host)
        } catch (e: IllegalArgumentException) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "адрес не собран: ${e.javaClass.simpleName}", call.url.host)
        }
        cancel.onCancel { c.disconnect() }
        return try {
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.connectTimeout = connectTimeoutMillis
            c.readTimeout = readTimeoutMillis
            c.requestMethod = call.method
            for ((k, v) in call.headers) c.setRequestProperty(k, v)
            call.secret?.let { c.setRequestProperty(it.name, it.prefix + it.secret.reveal()) }
            call.body?.let { body ->
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            Outcome.success(Reply(c, status, stream, maxLineBytes))
        } catch (e: IOException) {
            c.disconnect()
            if (cancel.cancelled) {
                Outcome.failure(DiagCode.CANCELLED_BY_USER, "запрос отменён", call.url.host)
            } else {
                Outcome.failure(DiagCode.NETWORK_UNREACHABLE, "нет ответа: ${e.javaClass.simpleName}", call.url.host)
            }
        } catch (e: IllegalArgumentException) {
            c.disconnect()
            Outcome.failure(DiagCode.VALUE_INVALID, "запрос не собран: ${e.javaClass.simpleName}", call.url.host)
        }
    }

    private class Reply(
        private val c: HttpURLConnection,
        override val status: Int,
        stream: InputStream?,
        private val maxLine: Int,
    ) : HttpReply {
        private val input = stream?.buffered()
        private val line = ByteArrayOutputStream()

        override fun header(name: String): String? = c.getHeaderField(name)

        override fun readLine(): String? {
            val inp = input ?: return null
            line.reset()
            while (true) {
                val b = inp.read()
                when {
                    b < 0 -> return if (line.size() == 0) null else text()
                    b == '\n'.code -> return text()
                    line.size() >= maxLine -> throw ReplyTooLarge(maxLine)
                    else -> line.write(b)
                }
            }
        }

        private fun text(): String {
            val s = line.toString(Charsets.UTF_8.name())
            return if (s.endsWith('\r')) s.dropLast(1) else s
        }

        override fun read(buf: ByteArray): Int = input?.read(buf) ?: -1

        override fun close() {
            runCatching { input?.close() }
            c.disconnect()
        }
    }

    companion object {
        /** Предел строки ответа: событие SSE с куском текста — килобайты; список моделей одной строкой — сотни килобайт. */
        const val MAX_LINE_BYTES = 4 * 1024 * 1024

        private fun printable(s: String) = s.all { it in ' '..'~' }
    }
}
