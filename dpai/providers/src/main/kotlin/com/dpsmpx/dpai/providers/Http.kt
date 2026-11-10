package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import java.io.IOException
import java.net.URI

/**
 * Заголовок с ключом (D-98): имя, приставка (`Bearer `) и секрет. Значение
 * раскрывает только транспорт, когда ставит заголовок (правило
 * `secret-reveal`); в адрес запроса ключ не попадает.
 */
class SecretHeader(val name: String, val prefix: String, val secret: Secret) {
    override fun toString(): String = "$name: $prefix${Secret.REDACTED}"
}

/** Запрос к провайдеру: метод, адрес, обычные заголовки, тело и заголовок с ключом. */
class HttpCall(
    val method: String,
    val url: URI,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val secret: SecretHeader? = null,
)

/**
 * Ответ провайдера: код и тело — построчно, для потока SSE. Строка длиннее
 * предела транспорта — [ReplyTooLarge]: ответ без конца строки не
 * съедает память.
 */
interface HttpReply : AutoCloseable {
    val status: Int

    fun header(name: String): String?

    /** Следующая строка тела без перевода строки; конец тела — `null`. */
    @Throws(IOException::class)
    fun readLine(): String?

    /**
     * Тело байтами — для файлов (модели GGUF, D-119): до `buf.size` байт, конец тела —
     * `-1`. В одном ответе — либо строки, либо байты. Транспорт без потока байт —
     * отказ чтения.
     */
    @Throws(IOException::class)
    fun read(buf: ByteArray): Int = throw IOException("тело байтами этот ответ не отдаёт")

    /** Тело целиком, не больше [limit] знаков: остальное отбрасывается. */
    @Throws(IOException::class)
    fun readText(limit: Int): String {
        val sb = StringBuilder()
        while (sb.length < limit) {
            val line = readLine() ?: break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        return if (sb.length > limit) sb.substring(0, limit) else sb.toString()
    }
}

/** Строка ответа длиннее предела транспорта. */
class ReplyTooLarge(limit: Int) : IOException("строка ответа длиннее $limit байт")

/**
 * Граница с сетью (D-98). Сбой соединения — `NETWORK_UNREACHABLE`; любой
 * полученный ответ (и 4xx, и 5xx) — успех с кодом. Переадресации не
 * выполняются: адрес назначения прошёл бы мимо списка разрешённых.
 */
fun interface Transport {
    fun open(call: HttpCall, cancel: Cancel): Outcome<HttpReply>
}

/**
 * Отмена запроса человеком: блокирующее чтение потока прерывается
 * закрытием соединения ([onCancel] регистрирует это у транспорта).
 */
class Cancel {
    private val hooks = mutableListOf<() -> Unit>()

    @Volatile
    var cancelled: Boolean = false
        private set

    fun cancel() {
        val run = synchronized(hooks) {
            if (cancelled) return
            cancelled = true
            hooks.toList().also { hooks.clear() }
        }
        run.forEach { runCatching(it) }
    }

    /** [hook] — сейчас, если запрос уже отменён; иначе — при отмене. */
    fun onCancel(hook: () -> Unit) {
        val now = synchronized(hooks) {
            if (!cancelled) hooks += hook
            cancelled
        }
        if (now) runCatching(hook)
    }
}
