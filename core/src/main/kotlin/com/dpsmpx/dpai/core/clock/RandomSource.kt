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

    override fun nextBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}

private class SecureRandomSource : RandomSource {
    private val random = SecureRandom()

    override fun nextLong(): Long = random.nextLong()

    override fun nextInt(bound: Int): Int = random.nextInt(bound)

    override fun nextBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}
