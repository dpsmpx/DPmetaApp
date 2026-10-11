package com.dpsmpx.dpai.testkit

import com.dpsmpx.dpai.core.clock.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Ожидание условия в тестах с настоящими процессами (target, агент, SSH;
 * `docs/TESTING.md`, §1). Логику с временем проверяют на `ManualClock`;
 * здесь ждут внешний мир — процесс ОС, файл агента, — и ждут ограниченно:
 * не дождались — тест падает с описанием, а не висит.
 */
object RealTime {
    fun <T> await(timeout: Duration, what: String, step: Duration = 50.milliseconds, probe: () -> T?): T {
        val clock = Clock.SYSTEM
        val deadline = clock.monotonicNanos() + timeout.inWholeNanoseconds
        val pause = CountDownLatch(1)
        while (true) {
            probe()?.let { return it }
            if (clock.monotonicNanos() >= deadline) throw AssertionError("не дождались за $timeout: $what")
            pause.await(step.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
    }
}
