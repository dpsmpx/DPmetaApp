package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.json
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
 * Сторона JSON-RPC 2.0 поверх кадров LSP (D-137): запросы с номерами и
 * ответы на них, уведомления, запросы службы к редактору.
 *
 * Читает один поток ([start]); [Handler] вызывается из него же и не
 * должен ждать ответа службы — иначе чтение встанет. Сообщение не по
 * протоколу или обрыв потока останавливают сторону целиком: все ждущие
 * запросы получают отказ, [Handler.closed] — причину. Половинчатого
 * состояния «часть ответов потеряна» нет.
 */
class JsonRpcPeer(input: InputStream, output: OutputStream, private val handler: Handler, maxBody: Int = LspReader.DEFAULT_MAX_BODY) {
    interface Handler {
        fun notification(method: String, params: JsonValue?)

        /** Запрос службы к редактору: результат или отказ — он уйдёт службе ошибкой `-32601`. */
        fun request(method: String, params: JsonValue?): Outcome<JsonValue>

        /** Сторона остановлена: [reason] — почему. Вызывается один раз. */
        fun closed(reason: Diagnostic)
    }

    /** Отправленный запрос: дождаться ответа или отменить. */
    inner class Call internal constructor(private val id: Long, private val method: String, private val future: CompletableFuture<Outcome<JsonValue>>) {
        fun await(timeout: Duration): Outcome<JsonValue> = try {
            future.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            cancel()
            Outcome.failure(DiagCode.LSP_TIMEOUT, "служба не ответила за ${timeout.inWholeMilliseconds} мс", method)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            cancel()
            Outcome.failure(DiagCode.CANCELLED_BY_USER, "ожидание ответа службы прервано", method)
        } catch (e: ExecutionException) {
            Outcome.failure(DiagCode.INTERNAL_ERROR, "ответ службы не разобран: ${e.cause?.message ?: e.message}", method)
        }

        /** Ответ больше не нужен: служба получает `$/cancelRequest`, поздний ответ отбрасывается. */
        fun cancel() {
            if (pending.remove(id) != null) {
                future.complete(Outcome.failure(DiagCode.CANCELLED_BY_USER, "запрос отменён", method))
                writer.write(JsonObject.of("jsonrpc" to json("2.0"), "method" to json("$/cancelRequest"), "params" to JsonObject.of("id" to json(id))))
            }
        }
    }

    private val reader = LspReader(input, maxBody)
    private val writer = LspWriter(output)
    private val source = input
    private val pending = ConcurrentHashMap<Long, CompletableFuture<Outcome<JsonValue>>>()
    private val ids = AtomicLong(0)
    private val closedOnce = AtomicBoolean(false)

    @Volatile
    private var stopped: Diagnostic? = null

    private val thread = Thread({ loop() }, "dpai-lsp-reader").apply { isDaemon = true }

    fun start() {
        thread.start()
    }

    val isOpen: Boolean get() = stopped == null

    fun call(method: String, params: JsonValue?): Call {
        val id = ids.incrementAndGet()
        val future = CompletableFuture<Outcome<JsonValue>>()
        val call = Call(id, method, future)
        stopped?.let {
            future.complete(Outcome.failure(it))
            return call
        }
        pending[id] = future
        val message = linkedMapOf<String, JsonValue>("jsonrpc" to json("2.0"), "id" to json(id), "method" to json(method))
        if (params != null) message["params"] = params
        val sent = writer.write(JsonObject(message))
        if (sent is Outcome.Failure) {
            pending.remove(id)
            future.complete(sent)
            stop(sent.diagnostics.first())
        }
        // Остановка могла пройти между проверкой и записью в pending: ждущий не должен остаться без ответа.
        stopped?.let { d -> pending.remove(id)?.complete(Outcome.failure(d)) }
        return call
    }

    fun request(method: String, params: JsonValue?, timeout: Duration): Outcome<JsonValue> = call(method, params).await(timeout)

    fun notify(method: String, params: JsonValue?): Outcome<Unit> {
        stopped?.let { return Outcome.failure(it) }
        val message = linkedMapOf<String, JsonValue>("jsonrpc" to json("2.0"), "method" to json(method))
        if (params != null) message["params"] = params
        val sent = writer.write(JsonObject(message))
        if (sent is Outcome.Failure) stop(sent.diagnostics.first())
        return sent
    }

    /** Закрыть потоки и отказать ждущим; повторный вызов ничего не делает. */
    fun close() {
        stop(Diagnostic.of(DiagCode.LSP_SERVER_STOPPED, "связь со службой закрыта"))
    }

    private fun loop() {
        while (stopped == null) {
            val message = when (val r = reader.read()) {
                is Outcome.Failure -> return stop(r.diagnostics.first())
                is Outcome.Success -> r.value ?: return stop(Diagnostic.of(DiagCode.LSP_SERVER_STOPPED, "служба закрыла поток"))
            }
            val obj = message as? JsonObject ?: return stop(Diagnostic.of(DiagCode.LSP_REPLY_INVALID, "сообщение службы — не объект"))
            val method = (obj["method"] as? JsonString)?.value
            val id = obj["id"]
            when {
                method != null && id != null && id != JsonNull -> answer(id, method, obj["params"])
                method != null -> handler.notification(method, obj["params"])
                id is JsonNumber -> complete(id, obj)
                // Ответ с номером-строкой или без номера: наших таких запросов нет — протокол нарушен.
                else -> return stop(Diagnostic.of(DiagCode.LSP_REPLY_INVALID, "сообщение службы без method и числового id"))
            }
        }
    }

    private fun answer(id: JsonValue, method: String, params: JsonValue?) {
        val reply = when (val r = handler.request(method, params)) {
            is Outcome.Success -> JsonObject.of("jsonrpc" to json("2.0"), "id" to id, "result" to r.value)
            is Outcome.Failure -> JsonObject.of(
                "jsonrpc" to json("2.0"),
                "id" to id,
                "error" to JsonObject.of("code" to json(METHOD_NOT_FOUND), "message" to json(r.diagnostics.first().message)),
            )
        }
        val sent = writer.write(reply)
        if (sent is Outcome.Failure) stop(sent.diagnostics.first())
    }

    private fun complete(id: JsonNumber, obj: JsonObject) {
        // Поздний ответ на отменённый или просроченный запрос — не ошибка.
        val future = id.toLongExact()?.let { pending.remove(it) } ?: return
        val error = obj["error"]
        future.complete(
            if (error != null && error != JsonNull) {
                val e = error as? JsonObject
                val text = (e?.get("message") as? JsonString)?.value?.take(MAX_ERROR_TEXT) ?: "ошибка без текста"
                val code = (e?.get("code") as? JsonNumber)?.toLongExact()
                Outcome.failure(DiagCode.LSP_REQUEST_FAILED, text, code?.toString())
            } else {
                Outcome.success(obj["result"] ?: JsonNull)
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
            // Поток службы уже закрыт.
        }
        val waiting = pending.keys.toList()
        for (id in waiting) pending.remove(id)?.complete(Outcome.failure(reason))
        handler.closed(reason)
    }

    companion object {
        const val METHOD_NOT_FOUND: Int = -32601
        const val MAX_ERROR_TEXT: Int = 500
    }
}
