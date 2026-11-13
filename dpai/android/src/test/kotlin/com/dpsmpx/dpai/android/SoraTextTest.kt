package com.dpsmpx.dpai.android

import io.github.rosemoe.sora.text.Content
import kotlin.test.Test
import kotlin.test.assertEquals

/** Контракт нативного буфера: без нормализации байтовых окончаний и позиций UTF-16. */
class SoraTextTest {
    @Test fun contentRoundTripsMixedSeparatorsBomAndUnicode() {
        val source = "\uFEFFa🙂\r\nб\nc\r\nd"
        val content = Content(source)
        assertEquals(source, content.toString())
        for (index in listOf(0, 1, 4, 6, 7, 8, source.length)) {
            val p = content.indexer.getCharPosition(index)
            assertEquals(index, content.getCharIndex(p.line, p.column))
        }
        content.insert(1, 1, "X")
        assertEquals(source.replace("б", "бX"), content.toString())
    }
    @Test fun contentIndexesManyLinesWithoutFlatteningBoundaries() {
        val source = (1..20_000).joinToString("\r\n") { "val x$it = $it" }
        val content = Content(source)
        assertEquals(20_000, content.lineCount)
        val index = content.getCharIndex(19_999, 0)
        assertEquals("val x20000 = 20000", source.substring(index))
        assertEquals(19_999, content.indexer.getCharPosition(index).line)
        assertEquals(source, content.toString())
    }
}
