package com.dpsmpx.dpai.ui

import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Цвета профилей (`DPmetaApp:D-83`): ни одного смыслового — расстояние в Lab
 * (CIE76) до каждого смыслового и друг до друга не меньше 20 на обеих темах;
 * кружок виден — контраст к фону карточки не ниже 3 : 1 (не текст).
 */
class ProfileColorsTest {
    private val lightCards = listOf(0xFFFFFFFF, 0xFFECE6F0, 0xFFF3EDF7, 0xFFE6E0E9) +
        with(AppPalette.LIGHT) { listOf(surface, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest) }
    private val darkCards = listOf(0xFF1D1B20, 0xFF2B2930, 0xFF36343B) +
        with(AppPalette.DARK) { listOf(surface, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest) }

    private fun lab(argb: Long): Triple<Double, Double, Double> {
        fun lin(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val (r, g, b) = Triple(lin(16), lin(8), lin(0))
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3) else 7.787 * t + 16.0 / 116
        return Triple(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    private fun deltaE(a: Long, b: Long): Double {
        val (l1, a1, b1) = lab(a)
        val (l2, a2, b2) = lab(b)
        return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
    }

    @Test
    fun profileColorsAreNotSemanticAndAreVisible() {
        for (c in ProfileColors.ALL) {
            for ((role, s) in AppPalette.SEMANTIC) {
                assertTrue(deltaE(c.light, s.light) >= 20, "${c.key} ≈ $role (светлая): ${deltaE(c.light, s.light)}")
                assertTrue(deltaE(c.dark, s.dark) >= 20, "${c.key} ≈ $role (тёмная): ${deltaE(c.dark, s.dark)}")
            }
            for (bg in lightCards) assertTrue(AppPalette.contrast(c.light, bg) >= 3.0, "${c.key} светлая на ${bg.toString(16)}")
            for (bg in darkCards) assertTrue(AppPalette.contrast(c.dark, bg) >= 3.0, "${c.key} тёмная на ${bg.toString(16)}")
        }
        for (a in ProfileColors.ALL) for (b in ProfileColors.ALL) if (a !== b) {
            assertTrue(deltaE(a.light, b.light) >= 20 && deltaE(a.dark, b.dark) >= 20, "${a.key} ≈ ${b.key}")
        }
        assertEquals(ProfileColors.ALL.size, ProfileColors.ALL.map { it.key }.toSet().size)
        assertEquals("olive", ProfileColors.of("olive")?.key)
        assertNull(ProfileColors.of("red"))
        assertNull(ProfileColors.of(null))
    }
}
