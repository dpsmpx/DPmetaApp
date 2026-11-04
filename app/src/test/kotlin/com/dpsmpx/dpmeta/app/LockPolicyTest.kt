package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpai.core.clock.ManualClock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Вход в приложение (D-59): раз за запуск и после часа в фоне. */
class LockPolicyTest {
    private val clock = ManualClock()
    private val policy = LockPolicy(clock)

    @Test
    fun firstLaunchAsksOnceThenStaysOpen() {
        assertTrue(policy.locked, "процесс только запущен — нужен вход")
        policy.unlocked()
        assertFalse(policy.locked)
        policy.background()
        clock.advance(10.minutes)
        policy.foreground()
        assertFalse(policy.locked, "меньше часа в фоне — без входа")
    }

    @Test
    fun hourInBackgroundAsksAgain() {
        policy.unlocked()
        policy.background()
        clock.advance(59.minutes)
        policy.background() // повторный уход в фон не сдвигает начало
        clock.advance(1.minutes)
        policy.foreground()
        assertTrue(policy.locked, "час в фоне — снова вход")
        policy.unlocked()
        assertFalse(policy.locked)
    }

    @Test
    fun foregroundWithoutBackgroundChangesNothing() {
        policy.unlocked()
        clock.advance(120.minutes)
        policy.foreground()
        assertFalse(policy.locked, "на экране всё время — вход не нужен")
    }
}
