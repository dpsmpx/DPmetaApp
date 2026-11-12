package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditorTabsTest {
    @Test fun browseKeepsOpenFilesAndOpeningSamePathDoesNotDuplicate() {
        var tabs = EditorTabs().open("a/main.kt").open("b/main.kt")
        tabs = tabs.browse()
        assertNull(tabs.active); assertEquals(listOf("a/main.kt", "b/main.kt"), tabs.paths)
        tabs = tabs.open("a/main.kt"); assertEquals(2, tabs.paths.size); assertEquals("a/main.kt", tabs.active)
    }
    @Test fun closeSelectsNeighbourAndEmptySetHasNoActiveFile() {
        var tabs = EditorTabs().open("a").open("b").open("c").open("b")
        tabs = tabs.close("b"); assertEquals("c", tabs.active)
        tabs = tabs.close("c"); assertEquals("a", tabs.active)
        tabs = tabs.close("a"); assertNull(tabs.active); assertEquals(emptyList(), tabs.paths)
    }
    @Test fun keyboardCyclesAndCapDoesNotEvictOpenFiles() {
        var tabs = EditorTabs()
        repeat(EditorTabs.MAX_OPEN) { tabs = tabs.open("$it.kt") }
        assertEquals(tabs, tabs.open("overflow.kt"))
        assertEquals("0.kt", tabs.next().active)
        assertEquals("14.kt", tabs.next(true).active)
        assertEquals(tabs.paths, tabs.next().paths)
    }
}
