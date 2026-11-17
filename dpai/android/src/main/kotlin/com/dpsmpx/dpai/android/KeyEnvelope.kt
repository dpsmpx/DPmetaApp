package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Формат обёртки ключа (D-184): v2 связывает шифр с alias и именем файла через
 * GCM AAD. Прежний формат — IV + шифр без заголовка; его читает миграция KeyWrap.
 * Здесь нет Android API, чтобы проверять совместимость и подмену на JVM.
 */
internal object KeyEnvelope {
    private const val FORMAT = "dpai.key-wrap"
    private const val VERSION = 2
    private const val IV = 12
    private const val TAG_BYTES = 16
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val MAGIC = "$FORMAT\u0000".toByteArray(Charsets.US_ASCII)
    internal val VERSION_OFFSET: Int get() = MAGIC.size

    fun isLegacy(sealed: ByteArray): Boolean =
        sealed.size < MAGIC.size || MAGIC.indices.any { sealed[it] != MAGIC[it] }

    private fun aad(alias: String, name: String): ByteArray = CanonicalJson.write(JsonObject.of(
        "format" to JsonString(FORMAT), "version" to JsonNumber.of(VERSION),
        "alias" to JsonString(alias), "name" to JsonString(name),
    )).toByteArray(Charsets.UTF_8)

    fun seal(key: SecretKey, alias: String, name: String, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(aad(alias, name))
        }
        check(cipher.iv.size == IV) { "неподдерживаемая длина IV обёртки" }
        return MAGIC + byteArrayOf(VERSION.toByte()) + cipher.iv + cipher.doFinal(plain)
    }

    fun open(key: SecretKey, alias: String, name: String, sealed: ByteArray): ByteArray {
        val legacy = isLegacy(sealed)
        val offset = if (legacy) 0 else {
            require(sealed.size > VERSION_OFFSET && sealed[VERSION_OFFSET].toInt() == VERSION) { "версия обёртки ключа не поддерживается" }
            VERSION_OFFSET + 1
        }
        require(sealed.size >= offset + IV + TAG_BYTES) { "обёртка ключа обрезана" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, offset, IV))
            if (!legacy) updateAAD(aad(alias, name))
        }
        return cipher.doFinal(sealed, offset + IV, sealed.size - offset - IV)
    }
}
