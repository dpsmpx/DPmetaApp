package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpmeta.app.data.BackgroundSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Фоновое обновление пульта (D-149): интервалы, выбор клонов, строки итога. */
class BackgroundSyncTest {
    @Test
    fun intervalsDefaultAndUnknownValues() {
        assertEquals(30, BackgroundSync.interval(null), "по умолчанию — 30 минут (D-18)")
        assertEquals(0, BackgroundSync.interval(0), "выключено сохраняется")
        assertEquals(15, BackgroundSync.interval(15))
        assertEquals(30, BackgroundSync.interval(5), "чаще 15 — нельзя: по умолчанию")
        assertEquals(30, BackgroundSync.interval(45))
        assertTrue(BackgroundSync.INTERVALS.filter { it > 0 }.all { it >= 15 }, "система не даёт чаще 15 минут")
        assertEquals("Выключено", BackgroundSync.title(0))
        assertEquals("60 мин", BackgroundSync.title(60))
    }

    @Test
    fun newClonesAreNotCreatedInBackground() {
        assertEquals(setOf("a/b"), BackgroundSync.clones(setOf("a/b"), missing = false))
        assertEquals(emptySet(), BackgroundSync.clones(setOf("a/b", "c/d"), missing = true), "нового клона нет — клоны в этот раз не трогаются (D-78)")
    }

    @Test
    fun resultLines() {
        assertEquals("Последний фоновый запуск: 15.11 10:42 — пропуск: токен закрыт — телефон давно не разблокировали",
            BackgroundSync.line("15.11 10:42", BackgroundSync.Result.TokenLocked.text))
        assertEquals("готово: 12 проектов", BackgroundSync.Result.Done("12 проектов").text)
        assertEquals("не вышло: нет сети", BackgroundSync.Result.Failed("нет сети").text)
    }
}
