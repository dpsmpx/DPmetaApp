package com.dpsmpx.dpmeta.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Вход в приложение (D-59): раз за запуск и после часа в фоне. */
class LockPolicyTest {
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
    fun foregroundWithoutBackgroundChangesNothing() {
        policy.unlocked()
        minutes(120)
        policy.foreground()
        assertFalse(policy.locked, "на экране всё время — вход не нужен")
    }
}
