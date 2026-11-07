package com.dpsmpx.dpai.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight

/**
 * Касание ссылки в тексте модели (AI:A-20, D-75): первое копирует адрес и
 * показывает его целиком, второе той же — открывает в браузере, только
 * `https`. Текст модели — данные, а не команда (D-44, п. 5): сразу не
 * открывается ничего. Копия — «чувствительная»: в адресе бывает ключ.
 */
@Composable
internal fun rememberLinkTap(): (String) -> Unit {
    val context = LocalContext.current
    val undo = LocalUndo.current
    val taps = remember { com.dpsmpx.dpai.ui.LinkTaps() }
    return remember(context, undo) {
        { url: String ->
            fun say(text: String) = undo?.say(text) ?: android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
            when (val act = taps.tap(url, com.dpsmpx.dpai.core.clock.Clock.SYSTEM.monotonicNanos() / 1_000_000)) {
                is com.dpsmpx.dpai.ui.LinkTaps.Act.Copy -> {
                    Clipboard.copy(context, "ссылка", url, sensitive = true)
                    say(act.text)
                }
                is com.dpsmpx.dpai.ui.LinkTaps.Act.Open -> try {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(act.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: android.content.ActivityNotFoundException) {
                    say("Нет приложения для ссылки — адрес скопирован")
                }
                is com.dpsmpx.dpai.ui.LinkTaps.Act.Refuse -> say(act.text)
            }
        }
    }
}

/**
 * Оформленный текст ([com.dpsmpx.dpai.ui.Markdown]) → стили Compose; символов разметки в нём уже нет.
 * [onLink] — ссылки `http(s)` откликаются на касание (AI:A-20); `null` — только вид ссылки.
 */
internal fun annotated(
    r: com.dpsmpx.dpai.ui.Rendered,
    code: androidx.compose.ui.graphics.Color,
    link: androidx.compose.ui.graphics.Color,
    onLink: ((String) -> Unit)? = null,
): androidx.compose.ui.text.AnnotatedString {
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
        val url = m.url
        if (m.style == com.dpsmpx.dpai.ui.MarkStyle.LINK && onLink != null && url != null && (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true))) {
            b.addLink(
                androidx.compose.ui.text.LinkAnnotation.Clickable(url, androidx.compose.ui.text.TextLinkStyles(style)) { onLink(url) },
                m.start,
                m.end,
            )
        } else {
            b.addStyle(style, m.start, m.end)
        }
    }
    return b.toAnnotatedString()
}
