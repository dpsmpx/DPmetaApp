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

/**
 * Подпись событий мгновенного канала (D-23, D-152; F-55): HMAC-SHA256 общим ключом —
 * шестнадцатеричной строкой. Ключ раскрывается здесь, в `:core:net`.
 */
object PushSignature {
    fun hex(key: Secret, data: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.reveal().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * Строки окружения для хука (`tools/notify/`): адрес темы и ключ. Ключ раскрывается
     * только здесь — для «Скопировать для хука» после входа по PIN (D-152).
     */
    fun hookEnv(url: String, key: Secret): String = "DPMETA_PUSH_URL=$url\nDPMETA_PUSH_KEY=${key.reveal()}"
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
