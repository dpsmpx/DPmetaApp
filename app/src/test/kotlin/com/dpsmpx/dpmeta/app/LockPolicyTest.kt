package com.dpsmpx.dpmeta.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Вход в приложение (D-59, D-61): раз за запуск и после часа в фоне. */
class LockPolicyTest {
    /** Причина отказа — словами, без кода (UX-19). */
    @Test
    fun reasonIsWordsNotCode() {
        kotlin.test.assertEquals("Отменено", LockPolicy.reason(10))
        kotlin.test.assertEquals("Слишком много попыток — подождите", LockPolicy.reason(7))
        kotlin.test.assertEquals("Не вышло", LockPolicy.reason(99))
        assertFalse(LockPolicy.reason(42).any { it.isDigit() }, "без кода")
    }

    private var now = 1_000_000L
    private val policy = LockPolicy({ now })

    private fun minutes(m: Long) {
        now += m * 60_000
    }

    @Test
    fun firstLaunchAsksOnceThenStaysOpen() {
        assertTrue(policy.locked, "процесс только запущен — нужен вход")
        policy.unlocked()
        assertFalse(policy.locked)
        policy.background()
        minutes(10)
        policy.foreground()
        assertFalse(policy.locked, "меньше часа в фоне — без входа")
    }

    @Test
    fun hourInBackgroundAsksAgain() {
        policy.unlocked()
        policy.background()
        minutes(59)
        policy.background() // повторный уход в фон не сдвигает начало
        minutes(1)
        policy.foreground()
        assertTrue(policy.locked, "час в фоне — снова вход")
        policy.unlocked()
        assertFalse(policy.locked)
    }

    @Test
    fun ongoingBackgroundCountsWithoutForeground() {
        policy.unlocked()
        policy.background()
        minutes(59)
        assertFalse(policy.locked, "фон длится меньше часа — окно поверх приложений отвечает без входа (D-61)")
        minutes(1)
        assertTrue(policy.locked, "фон длится час — опасный ответ окна поверх приложений только после входа (D-61)")
        policy.foreground()
        assertTrue(policy.locked, "вернулись после часа — вход")
    }

    @Test
    fun foregroundWithoutBackgroundChangesNothing() {
        policy.unlocked()
        minutes(120)
        policy.foreground()
        assertFalse(policy.locked, "на экране всё время — вход не нужен")
    }
}
