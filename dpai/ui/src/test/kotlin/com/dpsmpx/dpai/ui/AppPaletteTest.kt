package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Смысловые цвета читаются на обеих темах (DPmetaApp:D-74; ТЗ, N-07, N-08):
 * не ниже 4,5 : 1 к фону карточки — запасной схемы и типичных фонов Material 3
 * из обоев (их светлота близка при любом оттенке).
 */
class AppPaletteTest {
    private val lightCards = listOf(0xFFFFFFFF, 0xFFECE6F0, 0xFFF3EDF7, 0xFFE6E0E9) +
        with(AppPalette.LIGHT) { listOf(surface, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest) }
    private val darkCards = listOf(0xFF1D1B20, 0xFF2B2930, 0xFF36343B) +
        with(AppPalette.DARK) { listOf(surface, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest) }

    @Test
    fun semanticColorsMeetAaOnCards() {
        for ((role, pair) in AppPalette.SEMANTIC) {
            for (bg in lightCards) assertTrue(AppPalette.contrast(pair.light, bg) >= 4.5, "$role светлая на ${bg.toString(16)}: ${AppPalette.contrast(pair.light, bg)}")
            for (bg in darkCards) assertTrue(AppPalette.contrast(pair.dark, bg) >= 4.5, "$role тёмная на ${bg.toString(16)}: ${AppPalette.contrast(pair.dark, bg)}")
        }
    }

    @Test
    fun fallbackSchemeTextIsReadable() {
        for (s in listOf(AppPalette.LIGHT, AppPalette.DARK)) {
            assertTrue(AppPalette.contrast(s.onSurface, s.surface) >= 4.5)
            assertTrue(AppPalette.contrast(s.onSurfaceVariant, s.surfaceContainerHighest) >= 4.5)
            assertTrue(AppPalette.contrast(s.onPrimary, s.primary) >= 4.5)
            assertTrue(AppPalette.contrast(s.error, s.surface) >= 4.5)
        }
    }
}
