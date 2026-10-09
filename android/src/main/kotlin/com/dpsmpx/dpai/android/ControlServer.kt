package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.orchestrator.control.ControlApi
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

/**
 * Управляющий канал для `dpai` (D-47): HTTP/1.1 на `127.0.0.1:[PORT]`,
 * одно соединение — один запрос, тело JSON до `ControlApi.MAX_BODY`. Смысл
 * — `ControlApi`; здесь только сокет и разбор строки запроса. Команды —
 * через очередь контура ([DemoModel.command]), как с экрана.
 */
object ControlServer {
    const val PORT = 47777
    private const val MAX_HEADER = 16 * 1024
    private const val TIMEOUT_MS = 30_000

    @Volatile
    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "dpai-control").apply { isDaemon = true } }

    @Volatile
    private var pairing: Pairing? = null

    fun pairing(context: Context): Pairing = pairing ?: synchronized(this) {
        pairing ?: Pairing(NioVfs(context.applicationContext.filesDir.toPath()), RandomSource.secure(), Clock.SYSTEM).also { pairing = it }
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
        val api = ControlApi(pairing(app), { DemoModel.get(app).liveOrchestrator() }) { c ->
            when (val r = DemoModel.get(app).commandResult(c)) {
                is Outcome.Success -> ControlApi.OperationOutcome.Done(r.value)
                is Outcome.Failure -> ControlApi.OperationOutcome.Refused(r.diagnostics)
            }
        }
        val s = try {
            ServerSocket(PORT, 8, InetAddress.getLoopbackAddress())
        } catch (e: Exception) {
            problem = AppLogs.describe(e)
            log.warn("dpai", "канал не открыт на 127.0.0.1:$PORT: $problem")
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
                pool.execute { serve(c, api) }
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
            sock.soTimeout = TIMEOUT_MS
            val response = try {
                val input = BufferedInputStream(sock.getInputStream())
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val parts = lines.first().split(' ')
                if (parts.size < 2) return respond(sock, error(400, "неверная строка запроса"))
                val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length > ControlApi.MAX_BODY) return respond(sock, error(413, "тело больше ${ControlApi.MAX_BODY} байт"))
                val body = if (length > 0) String(input.readNBytes(length), Charsets.UTF_8) else null
                api.handle(parts[0], parts[1].substringBefore('?'), headers["authorization"], body)
            } catch (e: Exception) {
                error(500, AppLogs.describe(e))
            }
            respond(sock, response)
        }
    }

    /** Строки запроса и заголовков до пустой строки; длиннее [MAX_HEADER] — отказ. */
    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var last4 = 0
        while (out.size() < MAX_HEADER) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return String(out.toByteArray(), Charsets.UTF_8).removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun respond(sock: Socket, r: ControlResponse) {
        val body = CanonicalJson.write(r.body).toByteArray(Charsets.UTF_8)
        val reason = when (r.status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val head = "HTTP/1.1 ${r.status} $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
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
