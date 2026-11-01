package com.dpsmpx.dpmeta.net

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

/**
 * Транспорт на `java.net.http` для `dpmeta-cli` и тестов на JVM; у телефона
 * транспорт свой, в `:app`. TLS — системными корнями, своего `TrustManager`
 * нет (ТЗ, §6.8); переадресации выключены.
 */
class JdkTransport(private val timeout: Duration = Duration.ofSeconds(20)) : Transport {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(timeout)
        .build()

    override fun send(request: HttpRequest): Outcome<HttpResponse> {
        val b = java.net.http.HttpRequest.newBuilder(request.url).timeout(timeout)
        for ((k, v) in request.headers) b.header(k, v)
        request.auth?.let { b.header("Authorization", Auth.bearer(it)) }
        b.method(request.method, request.body?.let { BodyPublishers.ofByteArray(it) } ?: BodyPublishers.noBody())
        return try {
            val r = client.send(b.build(), BodyHandlers.ofByteArray())
            Outcome.success(HttpResponse(r.statusCode(), r.headers().map(), r.body()))
        } catch (e: IOException) {
            Outcome.failure(DiagCode.NETWORK_UNREACHABLE, "нет ответа: ${e.javaClass.simpleName}", request.url.host)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Outcome.failure(DiagCode.NETWORK_UNREACHABLE, "запрос прерван", request.url.host)
        }
    }
}
