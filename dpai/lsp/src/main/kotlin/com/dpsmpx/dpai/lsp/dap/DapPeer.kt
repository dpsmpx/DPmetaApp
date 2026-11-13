package com.dpsmpx.dpai.lsp.dap

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.lsp.LspReader
import com.dpsmpx.dpai.lsp.LspWriter
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * Сторона протокола отладки DAP (D-159) поверх тех же кадров `Content-Length`,
 * что у LSP: запросы с `seq`, ответы по `request_seq`, события адаптера.
 *
 * Читает один поток ([start]); [Handler] вызывается из него и не должен ждать
 * ответа адаптера — иначе чтение встанет. Обратные запросы адаптера
 * (`runInTerminal`, `startDebugging`) получают отказ: программа идёт в процессе
 * адаптера, её вывод приходит событиями `output`. Сообщение не по протоколу или
 * обрыв потока останавливают сторону целиком: ждущие запросы получают отказ,
 * [Handler.closed] — причину (как у LSP, D-137).
 */
class DapPeer(input: InputStream, output: OutputStream, private val handler: Handler, maxBody: Int = LspReader.DEFAULT_MAX_BODY) {
    interface Handler {
        /** Событие адаптера: [event] — имя, [body] — тело или `null`. */
        fun event(event: String, body: JsonObject?)

        /** Сторона остановлена: [reason] — почему. Вызывается один раз. */
        fun closed(reason: Diagnostic)
    }

    /** Отправленный запрос: дождаться ответа. */
    class Call internal constructor(private val command: String, private val future: CompletableFuture<Outcome<JsonValue>>) {
        fun await(timeout: Duration): Outcome<JsonValue> = try {
            future.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            Outcome.failure(DiagCode.DAP_TIMEOUT, "отладчик не ответил за ${timeout.inWholeMilliseconds} мс", command)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Outcome.failure(DiagCode.CANCELLED_BY_USER, "ожидание ответа отладчика прервано", command)
        } catch (e: ExecutionException) {
            Outcome.failure(DiagCode.INTERNAL_ERROR, "ответ отладчика не разобран: ${e.cause?.message ?: e.message}", command)
        }

        val isDone: Boolean get() = future.isDone
    }

    private val reader = LspReader(input, maxBody)
    private val writer = LspWriter(output)
    private val source = input
    private val pending = ConcurrentHashMap<Long, Pair<String, CompletableFuture<Outcome<JsonValue>>>>()
    private val seq = AtomicLong(0)
    private val closedOnce = AtomicBoolean(false)

    @Volatile
    private var stopped: Diagnostic? = null

    private val thread = Thread({ loop() }, "dpai-dap-reader").apply { isDaemon = true }

    fun start() {
        thread.start()
    }

    val isOpen: Boolean get() = stopped == null

    /** Отправить запрос [command]; ответ — через [Call.await]. */
    fun call(command: String, arguments: JsonObject? = null): Call {
        val id = seq.incrementAndGet()
        val future = CompletableFuture<Outcome<JsonValue>>()
        val call = Call(command, future)
        stopped?.let {
            future.complete(Outcome.failure(it))
            return call
        }
        pending[id] = command to future
        val message = linkedMapOf<String, JsonValue>("seq" to json(id), "type" to json("request"), "command" to json(command))
        if (arguments != null) message["arguments"] = arguments
        val sent = writer.write(JsonObject(message))
        if (sent is Outcome.Failure) {
            pending.remove(id)
            future.complete(sent)
            stop(sent.diagnostics.first())
        }
        // Остановка могла пройти между проверкой и записью в pending: ждущий не должен остаться без ответа.
        stopped?.let { d -> pending.remove(id)?.second?.complete(Outcome.failure(d)) }
        return call
    }

    fun request(command: String, arguments: JsonObject?, timeout: Duration): Outcome<JsonValue> = call(command, arguments).await(timeout)

    /** Закрыть потоки и отказать ждущим; повторный вызов ничего не делает. */
    fun close() {
        stop(Diagnostic.of(DiagCode.DAP_ADAPTER_STOPPED, "связь с отладчиком закрыта"))
    }

    private fun loop() {
        while (stopped == null) {
            val message = when (val r = reader.read()) {
                // Кадры читает LspReader: его обрыв — остановка адаптера, остальное — протокол нарушен.
                is Outcome.Failure -> return stop(r.diagnostics.first().let {
                    Diagnostic.of(if (it.code == DiagCode.LSP_SERVER_STOPPED) DiagCode.DAP_ADAPTER_STOPPED else DiagCode.DAP_REPLY_INVALID, it.message, it.subject)
                })
                is Outcome.Success -> r.value ?: return stop(Diagnostic.of(DiagCode.DAP_ADAPTER_STOPPED, "отладчик закрыл поток"))
            }
            val obj = message as? JsonObject ?: return stop(Diagnostic.of(DiagCode.DAP_REPLY_INVALID, "сообщение отладчика — не объект"))
            when ((obj["type"] as? JsonString)?.value) {
                "response" -> complete(obj)
                "event" -> {
                    val name = (obj["event"] as? JsonString)?.value ?: return stop(Diagnostic.of(DiagCode.DAP_REPLY_INVALID, "событие отладчика без имени"))
                    handler.event(name, obj["body"] as? JsonObject)
                }
                "request" -> refuse(obj)
                else -> return stop(Diagnostic.of(DiagCode.DAP_REPLY_INVALID, "сообщение отладчика без type"))
            }
        }
    }

    /** Обратный запрос адаптера — отказ словами: терминала и второго сеанса у редактора нет. */
    private fun refuse(obj: JsonObject) {
        val requestSeq = obj["seq"] as? JsonNumber ?: return stop(Diagnostic.of(DiagCode.DAP_REPLY_INVALID, "запрос отладчика без seq"))
        val command = (obj["command"] as? JsonString)?.value.orEmpty()
        val reply = JsonObject.of(
            "seq" to json(seq.incrementAndGet()), "type" to json("response"), "request_seq" to requestSeq,
            "success" to JsonBool(false), "command" to json(command), "message" to json("не поддерживается редактором"),
        )
        val sent = writer.write(reply)
        if (sent is Outcome.Failure) stop(sent.diagnostics.first())
    }

    private fun complete(obj: JsonObject) {
        val requestSeq = (obj["request_seq"] as? JsonNumber)?.toLongExact()
            ?: return stop(Diagnostic.of(DiagCode.DAP_REPLY_INVALID, "ответ отладчика без request_seq"))
        // Ответ на запрос, которого мы не ждём (уже отказали по сроку), — не ошибка.
        val (command, future) = pending.remove(requestSeq) ?: return
        val success = (obj["success"] as? JsonBool)?.value == true
        future.complete(
            if (success) {
                Outcome.success(obj["body"] ?: JsonNull)
            } else {
                val body = obj["body"] as? JsonObject
                val detail = ((body?.get("error") as? JsonObject)?.get("format") as? JsonString)?.value
                val text = (detail ?: (obj["message"] as? JsonString)?.value ?: "отказ без текста").take(MAX_ERROR_TEXT)
                Outcome.failure(DiagCode.DAP_REQUEST_FAILED, text, command)
            },
        )
    }

    private fun stop(reason: Diagnostic) {
        if (!closedOnce.compareAndSet(false, true)) return
        stopped = reason
        writer.close()
        try {
            source.close()
        } catch (_: java.io.IOException) {
            // Поток адаптера уже закрыт.
        }
        for (id in pending.keys.toList()) pending.remove(id)?.second?.complete(Outcome.failure(reason))
        handler.closed(reason)
    }

    companion object {
        const val MAX_ERROR_TEXT: Int = 500
    }
}
