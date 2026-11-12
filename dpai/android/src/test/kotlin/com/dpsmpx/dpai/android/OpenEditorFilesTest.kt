package com.dpsmpx.dpai.android

import androidx.compose.runtime.mutableStateOf
import com.dpsmpx.dpai.ui.EditorTabs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenEditorFilesTest {
    @Test fun tappingActiveTabPreviewsWithoutClosingIt() {
        val files = OpenEditorFiles("active-tab-test", mutableStateOf(EditorTabs().open("code.cpp")))
        var previewed = 0
        files.ready("code.cpp", EditorCallbacks(preview = { previewed++ }))
        files.preview("code.cpp")
        assertEquals(1, previewed); assertEquals(listOf("code.cpp"), files.tabs.paths)
    }
    @Test fun closingInactiveTabWaitsForItsOwnCallbacksAndIgnoresStaleFile() {
        val files = OpenEditorFiles("close-tab-test", mutableStateOf(EditorTabs().open("one.cpp").open("two.py")))
        var wrong = false; var closed = false
        files.ready("two.py", EditorCallbacks(close = { wrong = true }))
        files.requestClose("one.cpp")
        assertEquals("one.cpp", files.tabs.active)
        files.ready("two.py", EditorCallbacks(close = { wrong = true }))
        files.ready("one.cpp", EditorCallbacks(close = { closed = true }))
        assertTrue(closed); assertEquals(false, wrong)
    }
    @Test fun busyDocumentBlocksExitRunAndTabChanges() {
        val files = OpenEditorFiles("busy-tab-test", mutableStateOf(EditorTabs().open("code.cpp")))
        var invoked = false
        files.ready("code.cpp", EditorCallbacks(busy = true, close = { invoked = true }, exit = { invoked = true }, run = { invoked = true }))
        files.preview("other.py"); files.requestClose("code.cpp"); files.exit(); files.run(); files.browse()
        assertEquals("code.cpp", files.tabs.active); assertEquals(false, invoked)
    }
    @Test fun browseKeepsPersistedTabsAndCloseRemovesOnlyChosenPath() {
        val files = OpenEditorFiles("persist-tabs-test", mutableStateOf(EditorTabs().open("one.cpp").open("two.py")))
        files.browse(); assertEquals(listOf("one.cpp", "two.py"), IdeState.state.value.roots["persist-tabs-test"]?.tabs?.paths)
        files.close("one.cpp")
        assertEquals(listOf("two.py"), IdeState.state.value.roots["persist-tabs-test"]?.tabs?.paths)
    }
    @Test fun acknowledgedExitWorksBeforeTheNextUiFrameAndIgnoresStaleFiles() {
        val files = OpenEditorFiles("finish-tab-test", mutableStateOf(EditorTabs().open("code.cpp")))
        files.ready("code.cpp", EditorCallbacks(busy = true))
        files.finishedBrowse("stale.cpp"); assertEquals("code.cpp", files.tabs.active)
        files.finishedBrowse("code.cpp")
        assertEquals(null, files.tabs.active); assertEquals(listOf("code.cpp"), files.tabs.paths)
    }
    @Test fun closingLastTabReleasesEmptyFolderMetadata() {
        val files = OpenEditorFiles("empty-root-test", mutableStateOf(EditorTabs().open("last.cpp")))
        files.browse(); assertTrue("empty-root-test" in IdeState.state.value.roots)
        files.close("last.cpp")
        assertEquals(emptyList(), files.tabs.paths)
        assertEquals(false, "empty-root-test" in IdeState.state.value.roots)
    }
}
