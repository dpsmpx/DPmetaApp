package com.dpsmpx.dpai.core.ids

/**
 * Crockford base32 для тела идентификатора: 26 знаков = 10 знаков времени
 * (48 бит) + 16 знаков случайности (80 бит). Алфавит без I, L, O, U.
 */
internal object Crockford {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val BODY_LENGTH = 26
    private const val TIME_LENGTH = 10

    fun isValidBody(body: String): Boolean =
        body.length == BODY_LENGTH && body.all { it in ALPHABET } && body[0] <= '7' // 130 бит знаков, 128 значимых

    fun encode(timeMillis: Long, random: ByteArray): String {
        require(timeMillis in 0..MAX_TIME) { "время вне 48 бит: $timeMillis" }
        require(random.size == RANDOM_BYTES) { "нужно $RANDOM_BYTES байт случайности" }
        val sb = StringBuilder(BODY_LENGTH)
        for (i in TIME_LENGTH - 1 downTo 0) sb.append(ALPHABET[((timeMillis shr (5 * i)) and 31).toInt()])
        // 80 бит случайности → 16 знаков по 5 бит, старшие биты первыми.
        var acc = 0L
        var bits = 0
        for (b in random) {
            acc = (acc shl 8) or (b.toLong() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[((acc shr bits) and 31).toInt()])
            }
        }
        return sb.toString()
    }

    fun decodeTime(body: String): Long {
        var t = 0L
        for (i in 0 until TIME_LENGTH) t = (t shl 5) or ALPHABET.indexOf(body[i]).toLong()
        return t
    }

    const val RANDOM_BYTES = 10
    const val MAX_TIME = (1L shl 48) - 1
}
