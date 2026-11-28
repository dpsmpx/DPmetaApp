package com.dpsmpx.dpai.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpsmpx.dpai.ui.UiLook

/**
 * «Оформление» (`DPmetaApp:D-115`): тема, акцент, размер текста, плотность, шрифт
 * редактора — сразу во всём приложении, пульте и «AI». До документа настроек
 * (D-112) — карточкой в настройках «AI».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LookCard(showTitle: Boolean = true) {
    val context = LocalContext.current.applicationContext
    val look = Looks.remember()
    fun set(l: UiLook) = Looks.write(context, l)
    val simpleTitle = stringResource(R.string.ui_simple)
    val fullTitle = stringResource(R.string.ui_full)
    val themeNames = mapOf(UiLook.Theme.SYSTEM to stringResource(R.string.ui_theme_system), UiLook.Theme.LIGHT to stringResource(R.string.ui_theme_white),
        UiLook.Theme.DARK to stringResource(R.string.ui_theme_dark), UiLook.Theme.BLACK to stringResource(R.string.ui_theme_black))
    val dark = MaterialTheme.colorScheme.background.luminanceBelowHalf()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showTitle) Text(stringResource(R.string.ui_appearance), style = MaterialTheme.typography.titleSmall)
            Choice(stringResource(R.string.ui_mode), UiLook.Presentation.entries, look.presentation,
                { if (it == UiLook.Presentation.SIMPLE) simpleTitle else fullTitle }) { set(look.copy(presentation = it)) }
            Text(stringResource(R.string.ui_mode_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Choice(stringResource(R.string.ui_theme), UiLook.Theme.entries, look.theme, { themeNames.getValue(it) }) { set(look.copy(theme = it)) }
            Text(stringResource(R.string.ui_accent), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (a in UiLook.Accent.entries) {
                    FilterChip(
                        selected = look.accent == a,
                        onClick = { set(look.copy(accent = a)) },
                        label = { Text(a.title) },
                        leadingIcon = {
                            Box(Modifier.size(14.dp).background(Color(if (dark) a.dark else a.light), CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                        },
                    )
                }
            }
            Choice("Размер текста", UiLook.TextSize.entries, look.text, { it.title }) { set(look.copy(text = it)) }
            Choice("Плотность", UiLook.Density.entries, look.density, { it.title }) { set(look.copy(density = it)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Шрифт редактора", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                OutlinedButton(enabled = look.editorFont > UiLook.EDITOR_RANGE.first, onClick = { set(look.copy(editorFont = look.editorFont - 1)) }) { Text("A−") }
                Text("${look.editorFont}", fontFamily = FontFamily.Monospace, fontSize = look.editorFont.sp)
                OutlinedButton(enabled = look.editorFont < UiLook.EDITOR_RANGE.last, onClick = { set(look.copy(editorFont = look.editorFont + 1)) }) { Text("A+") }
            }
            TextButton(onClick = { set(UiLook()) }) { Text(stringResource(R.string.ui_reset_display)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choice(title: String, options: List<T>, current: T, label: (T) -> String, onPick: (T) -> Unit) {
    Text(title, style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (o in options) FilterChip(selected = o == current, onClick = { onPick(o) }, label = { Text(label(o)) })
    }
}

private fun Color.luminanceBelowHalf(): Boolean = (red * 0.2126f + green * 0.7152f + blue * 0.0722f) < 0.5f
