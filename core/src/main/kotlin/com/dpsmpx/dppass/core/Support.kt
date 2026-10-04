package com.dpsmpx.dppass.core

import java.security.MessageDigest

/**
 * Время — только через [Clock]: логика backoff, истории активаций и
 * бюджета проб проверяется тестами с [ManualClock], без ожиданий.
 */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}

class ManualClock(private var now: Long = 0) : Clock {
    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        require(millis >= 0) { "время не идёт назад" }
        now += millis
    }
}

object Checksums {
    /** SHA-256 в нижнем шестнадцатеричном виде. */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
}
