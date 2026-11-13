package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpmeta.formats.StatusBlocks
import kotlin.test.Test
import kotlin.test.assertEquals

class StatusCommentsTest {
    @Test
    fun repeatedUnclosedCommentsArePreservedAndClosedCommentsRemoved() {
        val unfinished = "<!--".repeat(100_000)
        val text = "<!-- status:begin -->\nвидно<!-- скрыто -->\n$unfinished\n<!-- status:end -->"
        val block = StatusBlocks.parse(text).valueOrNull()!!.blocks.single()
        assertEquals("видно\n$unfinished", block.text)
    }
}
