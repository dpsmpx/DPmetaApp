package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.orchestrator.control.ControlApi
import com.dpsmpx.dpai.orchestrator.control.ControlRequest
import com.dpsmpx.dpai.orchestrator.control.ControlResponse
import com.dpsmpx.dpai.orchestrator.control.Pairing
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore

/**
 * Управляющий канал для `dpai` (D-47): HTTP/1.1 на `127.0.0.1:[PORT]`,
 * одно соединение — один запрос, тело JSON до `ControlApi.MAX_BODY`. Смысл
 * — `ControlApi` (подписи D-92, решающее D-91); здесь только сокет и разбор
 * строки запроса. Команды — через очередь контура ([DemoModel.command]),
 * как с экрана.
 *
 * Медленный или чужой клиент канал не держит (аудит безопасности 1 ноября
 * 2026, A-7): соединений одновременно — не больше [MAX_CONNECTIONS], лишние
 * закрываются сразу; заголовки и тело — за [REQUEST_DEADLINE_MS] в сумме.
 */
object ControlServer {
    const val PORT = 47777
    private const val MAX_HEADER = 16 * 1024
    private const val READ_TIMEOUT_MS = 5_000
    private const val REQUEST_DEADLINE_MS = 10_000L
    private const val MAX_CONNECTIONS = 8

    @Volatile
    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "dpai-control").apply { isDaemon = true } }
    private val connections = Semaphore(MAX_CONNECTIONS)

    @Volatile
    private var pairing: Pairing? = null

    fun pairing(context: Context): Pairing = pairing ?: synchronized(this) {
        pairing ?: Pairing(
            NioVfs(context.applicationContext.filesDir.toPath()), RandomSource.secure(), Clock.SYSTEM,
            onRefusal = { AppLogs.get(context.applicationContext).warn("dpai", "отклонена попытка сопряжения командной строки") },
        ).also { pairing = it }
    }

    val running: Boolean get() = socket?.isClosed == false

    /** Почему канал не открылся — для карточки «Командная строка»; открыт — `null`. */
    @Volatile
    var problem: String? = null
        private set

    @Volatile
    private var starting = false

    /**
     * Запустить (повторный вызов — ничего). Сокет открывается в своём
     * потоке: в главном Android запрещает сеть (`NetworkOnMainThreadException`),
     * а вызывают из `onStartCommand`. Отказ — на карточке и в журнале.
     */
    @Synchronized
    fun start(context: Context) {
        if (running || starting) return
        starting = true
        val app = context.applicationContext
        Thread({ open(app) }, "dpai-control-open").apply { isDaemon = true }.start()
    }

    private fun open(app: Context) {
        try {
            openNow(app)
        } finally {
            starting = false
        }
    }

    private fun openNow(app: Context) {
        val log = AppLogs.get(app)
        val api = ControlApi(
            pairing(app), { DemoModel.get(app).liveOrchestrator() }, { q -> DemoModel.get(app).search(q)?.let(com.dpsmpx.dpai.ui.GlobalSearch::toJson) },
            confirmOnPhone = { c -> RequestPresenter.confirmFromCli(app, DemoModel.get(app), c) },
            automation = { request -> AutomationControl.handle(app, request) },
        ) { c ->
            when (val r = DemoModel.get(app).commandResult(c)) {
                is Outcome.Success -> ControlApi.OperationOutcome.Done(r.value)
                is Outcome.Failure -> ControlApi.OperationOutcome.Refused(r.diagnostics)
            }
        }
        val s = try {
            // Явно IPv4 127.0.0.1: на Android getLoopbackAddress() — это ::1, а dpai стучится на 127.0.0.1
            // (проверка на Redmi Note 13: канал открыт на [::1]:47777, dpai — ConnectException).
            ServerSocket(PORT, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        } catch (e: Exception) {
            problem = com.dpsmpx.dpai.core.diagnostics.Redactor.STANDARD.redact(com.dpsmpx.dpai.core.text.Words.failure(e))
            log.warn("dpai", "канал не открыт на 127.0.0.1:$PORT: ${AppLogs.describe(e)}")
            return
        }
        problem = null
        socket = s
        log.info("dpai", "канал dpai — 127.0.0.1:$PORT")
        Thread({
            while (!s.isClosed) {
                val c = try {
                    s.accept()
                } catch (_: Exception) {
                    break
                }
                if (!connections.tryAcquire()) {
                    runCatching { c.close() }
                    continue
                }
                try {
                    pool.execute {
                        try {
                            serve(c, api)
                        } finally {
                            connections.release()
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    connections.release()
                    runCatching { c.close() }
                }
            }
        }, "dpai-control-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun serve(c: Socket, api: ControlApi) {
        c.use { sock ->
            sock.soTimeout = READ_TIMEOUT_MS
            val deadline = Clock.SYSTEM.monotonicNanos() + REQUEST_DEADLINE_MS * 1_000_000
            val response = try {
                val input = BufferedInputStream(sock.getInputStream())
                val head = readHead(input, deadline) ?: return
                val lines = head.split("\r\n")
                val parts = lines.first().split(' ')
                if (parts.size < 2) return respond(sock, error(400, "неверная строка запроса"))
                val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
                val length = headers["content-length"]?.let { it.toIntOrNull() ?: -1 } ?: 0
                if (length < 0) return respond(sock, error(400, "неверный Content-Length"))
                if (length > ControlApi.MAX_BODY) return respond(sock, error(413, "тело больше ${ControlApi.MAX_BODY} байт"))
                val body = if (length > 0) String(readBody(input, length, deadline) ?: return, Charsets.UTF_8) else null
                // Только `dpai`: запрос страницы браузера (Origin) или через DNS rebinding (чужой Host) — отказ (A-21).
                if (!ControlApi.fromCli(headers["host"], headers["origin"])) return respond(sock, error(403, "запрос не от dpai: канал принимает только loopback без Origin"))
                api.handle(ControlRequest(parts[0], parts[1].substringBefore('?'), headers, body))
            } catch (e: Exception) {
                error(500, AppLogs.describe(e))
            }
            respond(sock, response)
        }
    }

    /** Строки запроса и заголовков до пустой строки; длиннее [MAX_HEADER] или позже [deadline] — отказ. */
    private fun readHead(input: InputStream, deadline: Long): String? {
        val out = ByteArrayOutputStream()
        var last4 = 0
        while (out.size() < MAX_HEADER) {
            if (Clock.SYSTEM.monotonicNanos() > deadline) return null
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return String(out.toByteArray(), Charsets.UTF_8).removeSuffix("\r\n\r\n")
        }
        return null
    }

    /** Тело ровно в [length] байт до [deadline]; оборвалось или опоздало — `null`. */
    private fun readBody(input: InputStream, length: Int, deadline: Long): ByteArray? {
        val body = ByteArray(length)
        var off = 0
        while (off < length) {
            if (Clock.SYSTEM.monotonicNanos() > deadline) return null
            val n = input.read(body, off, length - off)
            if (n < 0) return null
            off += n
        }
        return body
    }

    private fun respond(sock: Socket, r: ControlResponse) {
        val body = CanonicalJson.write(r.body).toByteArray(Charsets.UTF_8)
        val reason = when (r.status) {
            200 -> "OK"
            202 -> "Accepted"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        // Подпись ответа (D-92) — над тем же `CanonicalJson`, что уходит телом.
        val signature = r.signature?.let { "X-Dpai-Signature: $it\r\n" }.orEmpty()
        val head = "HTTP/1.1 ${r.status} $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\n${signature}Connection: close\r\n\r\n"
        runCatching {
            sock.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(body)
                flush()
            }
        }
    }

    private fun error(status: Int, message: String) = ControlResponse(
        status,
        com.dpsmpx.dpai.core.serialization.JsonObject.of(
            "status" to com.dpsmpx.dpai.core.serialization.JsonString("FAILED"),
            "diagnostics" to com.dpsmpx.dpai.orchestrator.control.CommandCodec.diagnostics(listOf(Diagnostic.error(DiagCode.VALUE_INVALID, message))),
        ),
    )
}
