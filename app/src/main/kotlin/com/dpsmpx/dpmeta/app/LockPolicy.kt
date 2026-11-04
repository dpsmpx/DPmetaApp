package com.dpsmpx.dpmeta.app

/**
 * Когда приложение спрашивает вход (D-59; F-45, T-01) — чистый Kotlin, без
 * Android: один раз за запуск процесса и снова, если приложение пробыло в
 * фоне [backgroundLimitMs] и дольше. Фон считает [AppLock] по окнам; службы и
 * уведомления блокировка не трогает. [elapsedMs] — монотонные миллисекунды,
 * идущие и в глубоком сне (на Android — `SystemClock.elapsedRealtime`): перевод
 * часов устройства предел не снимает.
 */
class LockPolicy(private val elapsedMs: () -> Long, private val backgroundLimitMs: Long = BACKGROUND_LIMIT_MS) {
    private var open = false
    private var backgroundSince: Long? = null

    /** Нужен вход: процесс только запущен или фон длился час и больше. */
    val locked: Boolean get() = !open

    /** Вход удался. */
    fun unlocked() {
        open = true
        backgroundSince = null
    }

    /** Ни одного окна приложения на экране. */
    fun background() {
        if (backgroundSince == null) backgroundSince = elapsedMs()
    }

    /** Окно приложения снова на экране: фон длился час и больше — нужен вход. */
    fun foreground() {
        val since = backgroundSince ?: return
        backgroundSince = null
        if (elapsedMs() - since >= backgroundLimitMs) open = false
    }

    companion object {
        /** Час в фоне — снова вход (слово автора, D-59). */
        const val BACKGROUND_LIMIT_MS: Long = 60 * 60 * 1000L
    }
}
