package com.dpsmpx.dpai.android

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource

/** Чтение истории не прерывается новым выводом (DPmetaApp:D-194). */
@Composable
internal fun OutputFollow(list: LazyListState, revision: Any?, last: Int) {
    var following by remember(list) { mutableStateOf(true) }
    var automatic by remember(list) { mutableStateOf(false) }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) following = false }
    LaunchedEffect(list) {
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.isScrollInProgress) }.collect { (_, _, scrolling) ->
            if (scrolling && !automatic) following = false
        }
    }
    LaunchedEffect(revision, following, last) {
        if (following && last >= 0) {
            automatic = true
            try { list.scrollToItem(last) } finally { automatic = false }
        }
    }
    if (!following) TextButton(onClick = { following = true }) { Text(stringResource(R.string.ui_next_messages)) }
}
