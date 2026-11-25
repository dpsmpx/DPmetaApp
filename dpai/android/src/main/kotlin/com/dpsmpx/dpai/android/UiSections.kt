package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

internal val LocalSectionVisible = staticCompositionLocalOf { true }

/**
 * Скрываем представление, сохраняя введённое в памяти, включая секретные поля:
 * их нельзя сериализовать в saved state ради раскрытия (DPmetaApp:D-194).
 * Скрытая часть не участвует в касаниях/TalkBack и не продолжает Poll.
 */
@Composable
internal fun DetailSection(title: String, stateKey: String, expandedInitially: Boolean = !LocalUiLook.current.simple,
    content: @Composable () -> Unit) {
    var expanded by rememberSaveable(stateKey, expandedInitially) { mutableStateOf(expandedInitially) }
    val focus = LocalFocusManager.current
    val parentVisible = LocalSectionVisible.current
    Column(Modifier.fillMaxWidth()) {
        val state = androidx.compose.ui.res.stringResource(if (expanded) R.string.ui_expanded else R.string.ui_collapsed)
        TextButton(onClick = { focus.clearFocus(); expanded = !expanded }, modifier = Modifier.semantics { heading(); stateDescription = state }) {
            Text("${if (expanded) "▾" else "▸"} $title", style = MaterialTheme.typography.titleSmall)
        }
        Box(if (expanded) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().height(0.dp).clipToBounds().clearAndSetSemantics { }) {
            CompositionLocalProvider(LocalSectionVisible provides (parentVisible && expanded)) { Column { content() } }
        }
    }
}
