package com.dpsmpx.dpai.core.ids

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource

/**
 * Генератор идентификаторов (D-08): время — из [Clock], случайность — из
 * [RandomSource] (D-07), поэтому в тестах последовательность воспроизводима.
 *
 * Монотонность: идентификаторы одного генератора строго возрастают, даже
 * если в одну миллисекунду создано несколько или часы устройства прыгнули
 * назад, — тогда время берётся прежнее, а случайная часть увеличивается на
 * единицу (как у монотонных ULID).
 */
class IdGenerator(private val clock: Clock, private val random: RandomSource) {
    private var lastMillis = -1L
    private val lastRandom = ByteArray(Crockford.RANDOM_BYTES)

    @Synchronized
    fun <T : TypedId> next(kind: IdKind<T>): T {
        val now = clock.now().epochMillis.coerceIn(0, Crockford.MAX_TIME)
        if (now > lastMillis) {
            lastMillis = now
            random.nextBytes(Crockford.RANDOM_BYTES).copyInto(lastRandom)
        } else if (!increment(lastRandom)) {
            // 80 бит исчерпаны в одну миллисекунду: логическое время — на шаг вперёд.
            lastMillis += 1
            random.nextBytes(Crockford.RANDOM_BYTES).copyInto(lastRandom)
        }
        return kind.fromBody(Crockford.encode(lastMillis, lastRandom))
    }

    private fun increment(bytes: ByteArray): Boolean {
        for (i in bytes.indices.reversed()) {
            val v = (bytes[i].toInt() and 0xff) + 1
            bytes[i] = v.toByte()
            if (v <= 0xff) return true
        }
        return false
    }
}
