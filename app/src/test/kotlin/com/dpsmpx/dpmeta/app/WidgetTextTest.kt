package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpmeta.app.screens.WidgetText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetTextTest {
    @Test
    fun unavailableStateDoesNotBecomeZeroOrConfigured() {
        val text = WidgetText.of(null, null)
        assertTrue(text.projects.contains("неизвестно"))
        assertEquals("Нет среза проектов", text.projectDate)
        assertEquals("ИИ: состояние неизвестно", text.ai)
        assertFalse(text.health.contains("0"))
    }

    @Test
    fun stoppedControllerCountsRemainUnknownButObservedZeroIsVisible() {
        val stopped = Screens.AiCounts(true, Measure.unknown("остановлен"), Measure.unknown("остановлен"), Measure.unknown("остановлен"), 2)
        val text = WidgetText.of(null, stopped, problems = 2)
        assertEquals("ИИ: в работе неизвестно · ждут неизвестно", text.ai)
        assertEquals("Проверка: 2 проблем", text.health)
        val ready = stopped.copy(waiting = Measure.observed(0), running = Measure.observed(3))
        assertEquals("ИИ: в работе 3 · ждут 0", WidgetText.of(null, ready).ai)
        assertEquals("ИИ не настроен", WidgetText.of(null, ready.copy(configured = false)).ai)
    }
}
