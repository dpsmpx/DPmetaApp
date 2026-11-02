package com.dpsmpx.dpmeta.net

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import java.io.IOException
import java.net.HttpURLConnection

/**
 * Транспорт на `HttpURLConnection` — есть и в JDK, и в Android (у телефона
 * `java.net.http` нет). Те же правила, что у [JdkTransport]: TLS системными
 * корнями, без переадресаций, заголовок — только печатные ASCII, текст
 * исключения в диагностику не переносится.
 */
class UrlTransport(private val timeoutMillis: Int = 20_000) : Transport {
    override fun send(request: HttpRequest): Outcome<HttpResponse> {
        val bad = request.headers.entries.firstOrNull { !printable(it.key) || !printable(it.value) }?.key
            ?: request.auth?.let { if (Auth.printable(it)) null else "Authorization" }
        if (bad != null) return Outcome.failure(DiagCode.VALUE_INVALID, "заголовок «$bad» — только печатные знаки ASCII", request.url.host)
        return try {
            val c = request.url.toURL().openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = timeoutMillis
                c.readTimeout = timeoutMillis
                c.requestMethod = request.method
                for ((k, v) in request.headers) c.setRequestProperty(k, v)
                request.auth?.let { c.setRequestProperty("Authorization", Auth.bearer(it)) }
                request.body?.let { body ->
                    c.doOutput = true
                    c.outputStream.use { it.write(body) }
                }
                val status = c.responseCode
                val stream = if (status >= 400) c.errorStream else c.inputStream
                val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                val headers = c.headerFields.filterKeys { it != null }.mapValues { it.value.toList() }
                Outcome.success(HttpResponse(status, headers, bytes))
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            Outcome.failure(DiagCode.NETWORK_UNREACHABLE, "нет ответа: ${e.javaClass.simpleName}", request.url.host)
        } catch (e: IllegalArgumentException) {
            Outcome.failure(DiagCode.VALUE_INVALID, "запрос не собран: ${e.javaClass.simpleName}", request.url.host)
        }
    }
}
