package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Ссылки по D-75: первое касание — копия, второе — открыть, только https. */
class LinkTapsTest {
    @Test
    fun firstCopiesSecondOpensHttps() {
        val t = LinkTaps()
        assertIs<LinkTaps.Act.Copy>(t.tap("https://example.org/a", 0))
        assertEquals(LinkTaps.Act.Open("https://example.org/a"), t.tap("https://example.org/a", 500))
        // После открытия — снова сначала: третье касание копирует.
        assertIs<LinkTaps.Act.Copy>(t.tap("https://example.org/a", 600))
    }

    @Test
    fun otherSchemesAreRefusedWithReason() {
        for (url in listOf("http://example.org", "intent://x#Intent;end", "javascript:alert(1)", "file:///sdcard/x", "https:///nohost")) {
            val t = LinkTaps()
            t.tap(url, 0)
            val second = t.tap(url, 1)
            assertIs<LinkTaps.Act.Refuse>(second, url)
        }
    }

    @Test
    fun anotherLinkOrLateTapStartsOver() {
        val t = LinkTaps(againMs = 1_000)
        t.tap("https://example.org/a", 0)
        assertIs<LinkTaps.Act.Copy>(t.tap("https://example.org/b", 100))
        assertIs<LinkTaps.Act.Copy>(t.tap("https://example.org/b", 5_000))
    }
}
