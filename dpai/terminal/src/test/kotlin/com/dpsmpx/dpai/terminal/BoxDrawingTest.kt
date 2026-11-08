package com.dpsmpx.dpai.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Рамки и блоки по сетке (терминал:A-18): знак — в пиксели ячейки, без шрифта. */
class BoxDrawingTest {
    /** Холст в пикселях: прямоугольники закрашивают, дуги и отрезки — записываются. */
    private class Raster(val w: Int, val h: Int) : BoxDrawing.Sink {
        val px = Array(h) { BooleanArray(w) }
        val alphas = mutableListOf<Float>()
        val arcs = mutableListOf<Float>()
        val lines = mutableListOf<FloatArray>()

        override fun rect(l: Float, t: Float, r: Float, b: Float, alpha: Float) {
            alphas += alpha
            for (y in t.toInt() until b.toInt()) for (x in l.toInt() until r.toInt()) if (x in 0 until w && y in 0 until h) px[y][x] = true
        }

        override fun arc(l: Float, t: Float, r: Float, b: Float, startDeg: Float, stroke: Float) {
            arcs += startDeg
        }

        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, stroke: Float) {
            lines += floatArrayOf(x0, y0, x1, y1)
        }

        fun row(y: Int) = (0 until w).count { px[y][it] }

        fun col(x: Int) = (0 until h).count { px[it][x] }

        fun count() = px.sumOf { r -> r.count { it } }
    }

    private fun draw(cp: Int, w: Int = 10, h: Int = 20, light: Int = 1): Raster =
        Raster(w, h).also { assertTrue(BoxDrawing.draw(cp, 0f, 0f, w.toFloat(), h.toFloat(), light, it)) }

    @Test
    fun tableCoversEveryLineSignExceptDiagonals() {
        for (cp in 0x2500..0x257F) {
            if (cp in 0x2571..0x2573) assertNull(BoxDrawing.arms(cp), "U+%04X".format(cp)) else assertNotNull(BoxDrawing.arms(cp), "U+%04X".format(cp))
        }
        assertEquals(listOf(3, 3, 3, 0), BoxDrawing.arms(0x2560)!!.toList()) // ╠
        assertEquals(listOf(2, 1, 1, 2), BoxDrawing.arms(0x2543)!!.toList()) // ╃
        assertEquals(listOf(0, 1, 1, 0), BoxDrawing.arms(0x256D)!!.toList()) // ╭
        assertFalse(BoxDrawing.covers('A'.code))
        assertFalse(BoxDrawing.draw('A'.code, 0f, 0f, 10f, 20f, 1, Raster(10, 20)))
    }

    @Test
    fun straightLinesSpanTheCellAndMeetNeighbours() {
        val h = draw(0x2500) // ─
        assertEquals(10, h.row(10))
        assertEquals(10, h.count(), "только одна строка пикселей")
        val v = draw(0x2502) // │
        assertEquals(20, v.col(5))
        assertEquals(20, v.count())
        val heavy = draw(0x2501, light = 2) // ━ — вдвое толще тонкой
        assertEquals(40, heavy.count())
    }

    @Test
    fun cornerClosesWithoutGapAndStaysInItsQuarter() {
        val c = draw(0x250C) // ┌
        assertTrue(c.px[10][5], "стык в середине закрашен")
        assertEquals(0, (0 until 10).count { c.px[it][5] }, "выше середины пусто")
        assertEquals(0, (0 until 5).count { c.px[10][it] }, "левее середины пусто")
        assertEquals(5, c.row(10))
        assertEquals(10, c.col(5))
    }

    @Test
    fun doubleLinesRunInParallelAndTurnRoundEachOther() {
        val eq = draw(0x2550) // ═
        assertEquals(10, eq.row(9))
        assertEquals(10, eq.row(11))
        assertEquals(0, eq.row(10), "между линиями зазор")
        val corner = draw(0x2554) // ╔
        assertTrue(corner.px[9][4], "внешний угол")
        assertTrue(corner.px[11][6], "внутренний угол")
        assertFalse(corner.px[10][5], "середина пуста")
        assertFalse(corner.px[9][3], "внешняя линия не выходит за угол")
        val cross = draw(0x256C) // ╬
        assertFalse(cross.px[10][5])
        assertFalse(cross.px[10][0], "горизонтали не проходят насквозь")
        assertTrue(cross.px[9][0] && cross.px[11][9] && cross.px[0][4] && cross.px[19][6])
    }

    @Test
    fun singleMeetsDoubleAtTheRightLine() {
        val t = draw(0x255F) // ╟ — тонкое вправо от правой линии двойной
        assertEquals(20, t.col(4))
        assertEquals(20, t.col(6))
        assertTrue(t.px[10][7] && t.px[10][9])
        assertFalse(t.px[10][5], "тонкая не заходит между линиями")
        val top = draw(0x2552) // ╒ — тонкая вниз доходит до верхней линии двойной
        assertTrue(top.px[9][5] && top.px[19][5])
        assertFalse(top.px[8][5])
    }

    @Test
    fun dashesArcsAndDiagonals() {
        val dash = draw(0x2504) // ┄ — три штриха с зазорами
        assertEquals(3, dash.alphas.size)
        assertTrue(dash.row(10) in 5..9)
        val arc = draw(0x256D) // ╭ — дуга и прямая до нижнего края
        assertEquals(listOf(180f), arc.arcs)
        assertTrue(arc.px[19][5])
        val cross = draw(0x2573) // ╳
        assertEquals(2, cross.lines.size)
    }

    @Test
    fun blocksFillTheirShare() {
        assertEquals(100, draw(0x2580).count()) // ▀
        assertEquals(100, draw(0x2584).count()) // ▄
        assertEquals(200, draw(0x2588).count()) // █
        assertEquals(20, draw(0x258F).count()) // ▏ — восьмая слева
        assertTrue(draw(0x2590).px[0][9]) // ▐
        assertEquals(listOf(0.5f), draw(0x2592).alphas) // ▒
        val q = draw(0x259A) // ▚ — верх-лево и низ-право
        assertTrue(q.px[0][0] && q.px[19][9])
        assertFalse(q.px[0][9] || q.px[19][0])
    }

    @Test
    fun lineWidthFollowsTheCell() {
        assertEquals(1, BoxDrawing.lightWidth(6f))
        assertEquals(3, BoxDrawing.lightWidth(21.5f))
    }
}
