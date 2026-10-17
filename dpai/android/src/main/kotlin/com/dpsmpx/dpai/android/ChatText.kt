package com.dpsmpx.dpai.android

import androidx.compose.ui.text.font.FontWeight

/** Оформленный текст ([com.dpsmpx.dpai.ui.Markdown]) → стили Compose; символов разметки в нём уже нет. */
internal fun annotated(r: com.dpsmpx.dpai.ui.Rendered, code: androidx.compose.ui.graphics.Color, link: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.AnnotatedString {
    val b = androidx.compose.ui.text.AnnotatedString.Builder(r.text)
    for (m in r.marks) {
        val style = when (m.style) {
            com.dpsmpx.dpai.ui.MarkStyle.BOLD -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)
            com.dpsmpx.dpai.ui.MarkStyle.ITALIC -> androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            com.dpsmpx.dpai.ui.MarkStyle.STRIKE -> androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
            com.dpsmpx.dpai.ui.MarkStyle.CODE, com.dpsmpx.dpai.ui.MarkStyle.CODE_BLOCK ->
                androidx.compose.ui.text.SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, background = code)
            com.dpsmpx.dpai.ui.MarkStyle.HEADING_1 -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, fontSize = androidx.compose.ui.unit.TextUnit(1.35f, androidx.compose.ui.unit.TextUnitType.Em))
            com.dpsmpx.dpai.ui.MarkStyle.HEADING_2 -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, fontSize = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Em))
            com.dpsmpx.dpai.ui.MarkStyle.HEADING_3 -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, fontSize = androidx.compose.ui.unit.TextUnit(1.08f, androidx.compose.ui.unit.TextUnitType.Em))
            com.dpsmpx.dpai.ui.MarkStyle.QUOTE -> androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = link.copy(alpha = 0.8f))
            com.dpsmpx.dpai.ui.MarkStyle.LINK -> androidx.compose.ui.text.SpanStyle(color = link, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
            com.dpsmpx.dpai.ui.MarkStyle.TABLE -> androidx.compose.ui.text.SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        b.addStyle(style, m.start, m.end)
    }
    return b.toAnnotatedString()
}
