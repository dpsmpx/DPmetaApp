package com.dpsmpx.dpmeta.net

import com.dpsmpx.dpai.core.diagnostics.Secret

/** Право на аккаунт только в памяти; отзыв делает запоздалый результат входа недействительным (D-95). */
class AccountLease {
    private var generation = 0L
    private var value: Secret? = null

    @Synchronized
    fun request(): Long = generation

    @Synchronized
    fun grant(request: Long, secret: Secret): Boolean {
        if (request != generation) return false
        value = secret
        return true
    }

    @Synchronized
    fun read(): Secret? = value

    @Synchronized
    fun close() {
        value = null
        generation++
    }
}
