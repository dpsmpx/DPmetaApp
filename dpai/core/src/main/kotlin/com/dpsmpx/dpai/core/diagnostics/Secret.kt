package com.dpsmpx.dpai.core.diagnostics

import java.security.MessageDigest

/**
 * Секретное значение: ключ API, пароль, токен, закрытый ключ (D-14).
 *
 * `toString()` никогда не показывает значение — секрет не утечёт через
 * журнал, диагностику, сообщение исключения или `toString()` объемлющего
 * data-класса. Достать значение можно только явным [reveal]; его вызовы
 * разрешены в немногих местах (`tools/verify/check_forbidden_apis.py`,
 * правило `secret-reveal`). Сравнение — за постоянное время.
 */
class Secret(private val value: String) {
    fun reveal(): String = value

    val isEmpty: Boolean get() = value.isEmpty()

    override fun toString(): String = REDACTED

    override fun equals(other: Any?): Boolean =
        other is Secret && MessageDigest.isEqual(value.toByteArray(Charsets.UTF_8), other.value.toByteArray(Charsets.UTF_8))

    override fun hashCode(): Int = 0 // не раскрывает значение через распределение по корзинам

    companion object {
        const val REDACTED: String = "<redacted>"
    }
}
