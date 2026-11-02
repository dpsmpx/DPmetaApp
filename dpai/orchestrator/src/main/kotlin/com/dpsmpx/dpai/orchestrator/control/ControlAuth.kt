package com.dpsmpx.dpai.orchestrator.control

import com.dpsmpx.dpai.core.clock.RandomSource
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Подпись канала `dpai` (D-92): после сопряжения токен в сеть не уходит.
 * Запрос несёт время, одноразовое значение и HMAC-SHA256 токеном над
 * методом, путём, временем, значением и SHA-256 тела; ответ приложения —
 * HMAC над значением запроса, кодом и SHA-256 тела ответа. Чужая программа
 * на порту 47777 токена не видит и выдать себя за приложение не может.
 *
 * Тело — строка, подписываются её байты UTF-8: сервер получает тело
 * байтами и читает как UTF-8, неверный UTF-8 даёт другую сумму — отказ.
 * Имена заголовков — в нижнем регистре: так их отдаёт разбор сервера.
 */
object ControlAuth {
    const val TIME = "x-dpai-time"
    const val NONCE = "x-dpai-nonce"
    const val SIGNATURE = "x-dpai-signature"

    /** Насколько время запроса может расходиться с часами телефона (D-92). */
    val WINDOW: Duration = 2.minutes

    private val TOKEN = Regex("[0-9a-f]{64}")
    private val NONCE_FORMAT = Regex("[0-9a-f]{32}")
    private val SIGNATURE_FORMAT = Regex("[0-9a-f]{64}")

    /** Токен — 32 байта в hex (`Pairing.token`); другое — не токен, подписывать им нечего. */
    fun validToken(token: String): Boolean = TOKEN.matches(token)

    fun validNonce(nonce: String): Boolean = NONCE_FORMAT.matches(nonce)

    fun validSignature(signature: String): Boolean = SIGNATURE_FORMAT.matches(signature)

    /** Одноразовое значение запроса: 16 случайных байт. */
    fun newNonce(random: RandomSource): String = hex(random.nextBytes(16))

    /** Что подписывает `dpai`: версия схемы — первой строкой, чтобы подпись запроса не годилась ответом. */
    fun requestText(method: String, path: String, time: Long, nonce: String, body: String?): String =
        listOf("dpai-request-1", method, path, time.toString(), nonce, sha256(body.orEmpty())).joinToString("\n")

    fun responseText(nonce: String, status: Int, body: String): String =
        listOf("dpai-response-1", nonce, status.toString(), sha256(body)).joinToString("\n")

    /** HMAC-SHA256 ключом — байтами токена; hex. */
    fun sign(token: String, text: String): String {
        require(validToken(token)) { "токен — 64 знака hex" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(unhex(token), "HmacSHA256"))
        return hex(mac.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    /** Заголовки подписанного запроса — для `dpai`. */
    fun requestHeaders(token: String, method: String, path: String, body: String?, time: Long, nonce: String): Map<String, String> =
        mapOf(TIME to time.toString(), NONCE to nonce, SIGNATURE to sign(token, requestText(method, path, time, nonce, body)))

    /** Ответ подписан токеном для этого значения запроса — иначе ему не верить. */
    fun responseValid(token: String, nonce: String, status: Int, body: String, signature: String?): Boolean =
        signature != null && validSignature(signature) && same(signature, sign(token, responseText(nonce, status, body)))

    /** Сравнение за постоянное время: подбор подписи по времени ответа не проходит. */
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun sha256(s: String): String = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
