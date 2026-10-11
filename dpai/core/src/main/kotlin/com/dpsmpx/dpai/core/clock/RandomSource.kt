package com.dpsmpx.dpai.core.clock

import java.security.SecureRandom
import java.util.SplittableRandom

/**
 * Источник случайности (D-07): в работе — криптографический, в тестах —
 * с зерном, чтобы упавший прогон повторялся. Другие источники в коде
 * запрещены (`tools/verify/check_forbidden_apis.py`).
 */
interface RandomSource {
    fun nextLong(): Long

    /** Случайное число в `[0, bound)`. */
    fun nextInt(bound: Int): Int

    fun nextBytes(size: Int): ByteArray

    companion object {
        fun seeded(seed: Long): RandomSource = SeededRandomSource(seed)

        fun secure(): RandomSource = SecureRandomSource()
    }
}

private class SeededRandomSource(seed: Long) : RandomSource {
    private val random = SplittableRandom(seed)

    override fun nextLong(): Long = random.nextLong()

    override fun nextInt(bound: Int): Int = random.nextInt(bound)

    /**
     * Как `SplittableRandom.nextBytes` из JDK 10+ — та же
     * последовательность при том же зерне, — но своими силами: в Android 11
     * (minSdk 30, D-28) у `SplittableRandom` этого метода нет, и вызов
     * падал с `NoSuchMethodError` на устройстве.
     */
    override fun nextBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var i = 0
        repeat(size ushr 3) {
            var rnd = random.nextLong()
            repeat(8) {
                bytes[i++] = rnd.toByte()
                rnd = rnd ushr 8
            }
        }
        if (i < size) {
            var rnd = random.nextLong()
            while (i < size) {
                bytes[i++] = rnd.toByte()
                rnd = rnd ushr 8
            }
        }
        return bytes
    }
}

private class SecureRandomSource : RandomSource {
    private val random = SecureRandom()

    override fun nextLong(): Long = random.nextLong()

    override fun nextInt(bound: Int): Int = random.nextInt(bound)

    override fun nextBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}
