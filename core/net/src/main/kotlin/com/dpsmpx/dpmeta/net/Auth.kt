package com.dpsmpx.dpmeta.net

import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.hashing.Hashing
import java.net.URLEncoder

/**
 * Единственное место `:core:net`, где секрет раскрывается (CLAUDE.md, «Правила
 * кода»; T-02): заголовок авторизации для транспорта и отпечаток для ключа
 * кэша. В журнал и диагностику секрет не попадает.
 */
internal object Auth {
    fun bearer(secret: Secret): String = "Bearer " + secret.reveal()

    /** Секрет годится в заголовок: печатные ASCII, без пробелов и переводов строки. */
    fun printable(secret: Secret): Boolean = secret.reveal().let { v -> v.isNotEmpty() && v.all { it in '!'..'~' } }

    /** Короткий отпечаток: различает токены в ключе кэша, сам токен не восстанавливается. */
    fun fingerprint(secret: Secret): String = Hashing.sha256(secret.reveal()).toString().takeLast(16)
}

/**
 * Тело `application/x-www-form-urlencoded` (device flow GitHub, D-17):
 * значение — строка или [Secret]; секрет раскрывается здесь, в теле запроса.
 */
object FormBody {
    fun of(vararg fields: Pair<String, Any>): ByteArray = fields.joinToString("&") { (k, v) ->
        val value = when (v) {
            is Secret -> v.reveal()
            else -> v.toString()
        }
        URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(value, Charsets.UTF_8)
    }.toByteArray(Charsets.UTF_8)

    const val CONTENT_TYPE = "application/x-www-form-urlencoded"
}

/** Байты секрета для шифрования на устройстве (`:app`, Keystore): раскрытие — здесь, в `:core:net`. */
object Secrets {
    fun bytes(secret: Secret): ByteArray = secret.reveal().toByteArray(Charsets.UTF_8)

    /**
     * Пароль для git по HTTPS (`:core:git`, D-65): токен — паролем при имени
     * `x-access-token`, не в адресе; раскрытие — здесь, в `:core:net`.
     */
    fun gitPassword(secret: Secret): CharArray = secret.reveal().toCharArray()
}
