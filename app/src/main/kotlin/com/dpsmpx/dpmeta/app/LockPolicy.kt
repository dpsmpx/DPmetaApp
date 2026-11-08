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

    /**
     * Нужен вход: процесс только запущен или фон длился час и больше —
     * считая и фон, который длится сейчас: окно запроса поверх приложений
     * спрашивает, пока окон приложения нет (D-61).
     */
    val locked: Boolean get() = !open || backgroundSince?.let { elapsedMs() - it >= backgroundLimitMs } == true

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
        /**
         * Почему окно PIN закрылось без входа — словами, без кода (UX-19).
         * Коды — `BiometricPrompt.BIOMETRIC_ERROR_*` Android: отмена — 5, 10,
         * 13; время вышло — 3; попыток много — 7 (подождать), 9 (разблокировать
         * телефон); нет PIN — 14.
         */
        fun reason(code: Int): String = when (code) {
            5, 10, 13 -> "Отменено"
            3 -> "Время вышло"
            7 -> "Слишком много попыток — подождите"
            9 -> "Слишком много попыток — разблокируйте телефон и попробуйте снова"
            14 -> "У телефона нет PIN"
            else -> "Не вышло"
        }

        /** Час в фоне — снова вход (слово автора, D-59). */
        const val BACKGROUND_LIMIT_MS: Long = 60 * 60 * 1000L
    }
}
