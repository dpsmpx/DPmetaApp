package com.dpsmpx.dpai.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/** Декодер UTF-8 и ширина символов эмулятора (D-71). */
class TextDecodingTest {
    private fun decode(vararg chunks: ByteArray): List<Int> {
        val d = Utf8Decoder()
        val out = ArrayList<Int>()
        for (c in chunks) d.decode(c, 0, c.size) { out += it }
        return out
    }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun validTextAcrossChunkBoundaries() {
        val text = "aж中😀"
        val all = text.toByteArray(Charsets.UTF_8)
        val expected = text.codePoints().toArray().toList()
        for (cut in 0..all.size) assertEquals(expected, decode(all.copyOfRange(0, cut), all.copyOfRange(cut, all.size)))
    }

    @Test
    fun invalidSequencesBecomeReplacementWithoutLosingTheNextByte() {
        val r = Utf8Decoder.REPLACEMENT
        // Обрыв: следующий байт — заново.
        assertEquals(listOf(r, 'A'.code), decode(bytes(0xd0, 0x41)))
        // Избыточная форма '/' и суррогат.
        assertEquals(listOf(r, r), decode(bytes(0xc0, 0xaf)))
        assertEquals(listOf(r), decode(bytes(0xed, 0xa0, 0x80)))
        // Одинокое продолжение и запрещённые байты.
        assertEquals(listOf(r, r), decode(bytes(0x80, 0xff)))
    }

    @Test
    fun characterWidths() {
        assertEquals(1, CharWidth.of('a'.code))
        assertEquals(1, CharWidth.of('ж'.code))
        assertEquals(0, CharWidth.of(0x0301))
        assertEquals(0, CharWidth.of(0x200D))
        assertEquals(0, CharWidth.of(0xFE0F))
        assertEquals(2, CharWidth.of('中'.code))
        assertEquals(2, CharWidth.of(0xAC00))
        assertEquals(2, CharWidth.of(0x1F680))
        assertEquals(1, CharWidth.of(0x2500))
        assertEquals(0, CharWidth.of(0x07))
    }
}
