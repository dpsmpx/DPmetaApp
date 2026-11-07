package com.dpsmpx.dpai.ui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Палитра всего приложения — пульта и «AI» (`DPmetaApp:D-74`; аудит UI/UX
 * 5 ноября 2026, UX-5, AI:A-12): цвета интерфейса — из обоев телефона
 * (Android 12+), а здесь — запасная схема на Android 11 и **смысловые
 * цвета**, одинаковые во всём приложении и на всех графиках. Цвета — ARGB
 * без Compose: модуль чистый Kotlin, тему из них строят оболочки.
 */
object AppPalette {
    /** Смысл цвета: состояние или ряд графика. */
    enum class Role { DONE, WARNING, ERROR, AI, HUMAN, DECISIONS, NEUTRAL }

    /** Пара цветов роли: на светлой и на тёмной теме. */
    data class Pair(val light: Long, val dark: Long) {
        fun of(dark: Boolean): Long = if (dark) this.dark else light
    }

    /**
     * Смысловые цвета. Контраст к фону карточки — не ниже 4,5 : 1 на обеих
     * темах и при любых обоях (ТЗ, N-07; `AppPaletteTest`).
     */
    val SEMANTIC: Map<Role, Pair> = mapOf(
        Role.DONE to Pair(0xFF1B6B23, 0xFF8FD694),
        Role.WARNING to Pair(0xFF8A4F00, 0xFFFFB74D),
        Role.ERROR to Pair(0xFFB3261E, 0xFFF2B8B5),
        Role.AI to Pair(0xFF7A4F00, 0xFFF0C27A),
        Role.HUMAN to Pair(0xFF00676C, 0xFF7FD8DC),
        Role.DECISIONS to Pair(0xFF6A3FA0, 0xFFD0B8F5),
        Role.NEUTRAL to Pair(0xFF2F5D8A, 0xFF9CC3EA),
    )

    fun semantic(role: Role, dark: Boolean): Long = SEMANTIC.getValue(role).of(dark)

    /** Роли схемы Material 3 — запасная схема Android 11, где цветов обоев нет. */
    data class Scheme(
        val primary: Long, val onPrimary: Long, val primaryContainer: Long, val onPrimaryContainer: Long,
        val secondary: Long, val onSecondary: Long, val secondaryContainer: Long, val onSecondaryContainer: Long,
        val tertiary: Long, val onTertiary: Long, val tertiaryContainer: Long, val onTertiaryContainer: Long,
        val background: Long, val onBackground: Long, val surface: Long, val onSurface: Long,
        val surfaceVariant: Long, val onSurfaceVariant: Long, val outline: Long, val outlineVariant: Long,
        val error: Long, val onError: Long, val errorContainer: Long, val onErrorContainer: Long,
        val surfaceContainerLowest: Long, val surfaceContainerLow: Long, val surfaceContainer: Long,
        val surfaceContainerHigh: Long, val surfaceContainerHighest: Long,
        val inverseSurface: Long, val inverseOnSurface: Long, val inversePrimary: Long,
    )

    /** Синий от прежних токенов пульта (`#2F5D8A`), тона — по правилам Material 3. */
    val LIGHT = Scheme(
        0xFF2F5D8A, 0xFFFFFFFF, 0xFFD1E4FF, 0xFF001D36,
        0xFF535F70, 0xFFFFFFFF, 0xFFD7E3F7, 0xFF101C2B,
        0xFF6B5778, 0xFFFFFFFF, 0xFFF2DAFF, 0xFF251431,
        0xFFF8F9FF, 0xFF191C20, 0xFFF8F9FF, 0xFF191C20,
        0xFFDFE2EB, 0xFF43474E, 0xFF73777F, 0xFFC3C7CF,
        0xFFBA1A1A, 0xFFFFFFFF, 0xFFFFDAD6, 0xFF410002,
        0xFFFFFFFF, 0xFFF2F3FA, 0xFFECEEF4, 0xFFE6E8EE, 0xFFE1E2E8,
        0xFF2E3135, 0xFFEFF0F7, 0xFF9FCAFF,
    )

    val DARK = Scheme(
        0xFF9FCAFF, 0xFF003258, 0xFF0F4A73, 0xFFD1E4FF,
        0xFFBBC7DB, 0xFF253140, 0xFF3B4858, 0xFFD7E3F7,
        0xFFD6BEE4, 0xFF3B2948, 0xFF523F5F, 0xFFF2DAFF,
        0xFF111418, 0xFFE1E2E8, 0xFF111418, 0xFFE1E2E8,
        0xFF43474E, 0xFFC3C7CF, 0xFF8D9199, 0xFF43474E,
        0xFFFFB4AB, 0xFF690005, 0xFF93000A, 0xFFFFDAD6,
        0xFF0B0E13, 0xFF191C20, 0xFF1D2024, 0xFF272A2F, 0xFF32353A,
        0xFFE1E2E8, 0xFF2E3135, 0xFF2F5D8A,
    )

    fun scheme(dark: Boolean): Scheme = if (dark) DARK else LIGHT

    /** Контраст двух цветов по WCAG 2.x (1…21); прозрачность не учитывается. */
    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val v = ((argb shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
