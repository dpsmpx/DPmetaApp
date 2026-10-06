package com.dpsmpx.dpai.core.hashing

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonValue
import java.security.MessageDigest

/**
 * Хеш содержимого `sha256:<64 hex>` (D-10): идентичность версии контекста,
 * привязка одобрения к предложению, целостность снимков и копий.
 */
class ContentHash private constructor(val algorithm: String, val hex: String) {
    override fun equals(other: Any?): Boolean = other is ContentHash && algorithm == other.algorithm && hex == other.hex

    override fun hashCode(): Int = hex.hashCode()

    override fun toString(): String = "$algorithm:$hex"

    companion object {
        private val FORM = Regex("sha256:[0-9a-f]{64}")

        fun parse(text: String): Outcome<ContentHash> =
            if (FORM.matches(text)) {
                Outcome.success(ContentHash("sha256", text.substring(7)))
            } else {
                Outcome.failure(DiagCode.HASH_INVALID, "не хеш вида sha256:<64 строчных hex>")
            }

        internal fun sha256Hex(hex: String): ContentHash = ContentHash("sha256", hex)
    }
}

object Hashing {
    fun sha256(bytes: ByteArray): ContentHash {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(64)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v shr 4]).append(HEX[v and 15])
        }
        return ContentHash.sha256Hex(sb.toString())
    }

    fun sha256(text: String): ContentHash = sha256(text.toByteArray(Charsets.UTF_8))

    /** Хеш значения — хеш его канонического вида: порядок ключей не влияет. */
    fun sha256(value: JsonValue): ContentHash = sha256(CanonicalJson.bytes(value))

    /** Сверка содержимого с ожидаемым хешем: несовпадение — `HASH_MISMATCH`. */
    fun verify(bytes: ByteArray, expected: ContentHash, subject: String? = null): Outcome<ContentHash> {
        val actual = sha256(bytes)
        return if (actual == expected) {
            Outcome.success(actual)
        } else {
            Outcome.failure(DiagCode.HASH_MISMATCH, "содержимое не совпадает с ожидаемым хешем", subject)
        }
    }

    private const val HEX = "0123456789abcdef"
}
