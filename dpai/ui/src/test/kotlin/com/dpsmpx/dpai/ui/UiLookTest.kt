package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Оформление (DPmetaApp:D-115; D-145): разбор настроек и контраст каждой пары акцента и темы. */
class UiLookTest {
    @Test fun selectedThemeWinsOverPhoneAndAdaptiveAccentKeepsNeutralBase() {
        for (system in listOf(false, true)) {
            assertEquals(false, UiLook(theme = UiLook.Theme.LIGHT).dark(system))
            assertEquals(true, UiLook(theme = UiLook.Theme.DARK).dark(system))
            assertEquals(true, UiLook(theme = UiLook.Theme.BLACK).dark(system))
            assertEquals(system, UiLook().dark(system))
            for (theme in UiLook.Theme.entries) for (source in listOf(0xffeeeeeeL, 0xff111111L, 0xffffcc00L)) {
                val look = UiLook(theme = theme, accent = UiLook.Accent.WALLPAPER)
                val scheme = look.palette(system, source)
                if (theme == UiLook.Theme.BLACK) assertEquals(0xff000000L, scheme.background)
                if (theme == UiLook.Theme.LIGHT) assertEquals(0xffffffffL, scheme.background)
                assertTrue(AppPalette.contrast(scheme.primary, scheme.background) >= 4.5)
                assertTrue(AppPalette.contrast(scheme.primary, scheme.surfaceContainerHigh) >= 4.5)
                assertTrue(AppPalette.contrast(scheme.onPrimary, scheme.primary) >= 4.5)
                assertTrue(AppPalette.contrast(AppPalette.semantic(AppPalette.Role.WARNING, look.dark(system)), scheme.surfaceContainerHigh) >= 4.5)
            }
        }
    }

    @Test fun legacyKeysAndFullModeSurviveRoundTrip() {
        val legacy = UiLook.fromMap(mapOf(UiLook.THEME to "light", UiLook.ACCENT to "blue"))
        assertEquals(UiLook.Theme.LIGHT, legacy.theme)
        val full = legacy.copy(presentation = UiLook.Presentation.FULL, theme = UiLook.Theme.BLACK)
        assertEquals(full, UiLook.fromMap(full.toMap()))
        assertEquals(false, full.simple)
    }

    @Test
    fun parsesSavedValuesAndFallsBackOnUnknown() {
        val look = UiLook(UiLook.Theme.DARK, UiLook.Accent.VIOLET, UiLook.TextSize.XL, UiLook.Density.COMPACT, 18)
        assertEquals(look, UiLook.fromMap(look.toMap()))
        assertEquals(UiLook(), UiLook.fromMap(mapOf(UiLook.THEME to "neon", UiLook.ACCENT to 7, UiLook.EDITOR_FONT to "x")))
        assertEquals(UiLook.EDITOR_RANGE.last, UiLook.fromMap(mapOf(UiLook.EDITOR_FONT to "99")).editorFont)
    }

    @Test
    fun everyAccentIsReadableOnBothThemes() {
        for (accent in UiLook.Accent.entries) for (dark in listOf(false, true)) {
            val s = UiLook.scheme(accent, dark)
            val where = "$accent ${if (dark) "тёмная" else "светлая"}"
            assertTrue(AppPalette.contrast(s.primary, s.surfaceContainerHighest) >= 4.5, "акцент на карточке: $where")
            assertTrue(AppPalette.contrast(s.primary, s.background) >= 4.5, "акцент на фоне: $where")
            assertTrue(AppPalette.contrast(s.onPrimary, s.primary) >= 4.5, "текст на акценте: $where")
            assertTrue(AppPalette.contrast(s.onSurface, s.background) >= 7.0, "текст на фоне: $where")
            assertTrue(AppPalette.contrast(s.onSurfaceVariant, s.surfaceContainerHighest) >= 4.5, "вторичный текст: $where")
            assertTrue(AppPalette.contrast(s.error, s.surfaceContainerHighest) >= 4.5, "ошибка: $where")
            assertTrue(AppPalette.contrast(s.onPrimaryContainer, s.primaryContainer) >= 4.5, "текст на мягком акценте: $where")
        }
    }
}
