package com.dpsmpx.dpmeta.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.dpsmpx.dpai.ui.MarkStyle
import com.dpsmpx.dpai.ui.Markdown
import com.dpsmpx.dpai.ui.Rendered
import com.dpsmpx.dpmeta.app.screens.Links

/**
 * Документ Markdown на экране (F-09): разбор — `Markdown` подсистемы «AI»
 * (одна функция на всё приложение), здесь — стили и переходы: ссылки,
 * якоря, `Проект:путь` в коде. Внешние адреса подчёркнуты, но из пульта
 * не открываются.
 */
object DocText {
    /** Разобранный документ: текст со стилями и заголовки — смещение и текст (для якорей). */
    class Doc(val rendered: Rendered, val headings: List<Pair<Int, String>>)

    private val HEADINGS = setOf(MarkStyle.HEADING_1, MarkStyle.HEADING_2, MarkStyle.HEADING_3)

    fun build(markdown: String): Doc {
        val r = Markdown.render(markdown)
        val headings = r.marks.filter { it.style in HEADINGS }.map { it.start to r.text.substring(it.start, it.end) }
        return Doc(r, headings)
    }

    /** [project] и [path] — где лежит документ: от него разрешаются относительные ссылки. */
    fun annotated(doc: Doc, names: Map<String, String>, project: String, path: String, onTarget: (Links.Target) -> Unit): AnnotatedString {
        val text = doc.rendered.text
        val b = AnnotatedString.Builder(text)
        for (m in doc.rendered.marks) {
            when (m.style) {
                MarkStyle.HEADING_1 -> b.addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp), m.start, m.end)
                MarkStyle.HEADING_2 -> b.addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 19.sp), m.start, m.end)
                MarkStyle.HEADING_3 -> b.addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp), m.start, m.end)
                MarkStyle.BOLD -> b.addStyle(SpanStyle(fontWeight = FontWeight.Bold), m.start, m.end)
                MarkStyle.ITALIC -> b.addStyle(SpanStyle(fontStyle = FontStyle.Italic), m.start, m.end)
                MarkStyle.STRIKE -> b.addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), m.start, m.end)
                MarkStyle.CODE, MarkStyle.CODE_BLOCK, MarkStyle.TABLE -> {
                    b.addStyle(SpanStyle(fontFamily = FontFamily.Monospace), m.start, m.end)
                    if (m.style == MarkStyle.CODE) {
                        Links.crossRef(text.substring(m.start, m.end), names)?.let { target -> link(b, m.start, m.end, target, onTarget) }
                    }
                }
                MarkStyle.QUOTE -> b.addStyle(SpanStyle(fontStyle = FontStyle.Italic), m.start, m.end)
                MarkStyle.LINK -> {
                    val target = m.url?.let { Links.resolve(project, path, it) }
                    when (target) {
                        null -> Unit
                        is Links.Target.External -> b.addStyle(SpanStyle(textDecoration = TextDecoration.Underline), m.start, m.end)
                        else -> link(b, m.start, m.end, target, onTarget)
                    }
                }
            }
        }
        return b.toAnnotatedString()
    }

    private fun link(b: AnnotatedString.Builder, start: Int, end: Int, target: Links.Target, onTarget: (Links.Target) -> Unit) {
        b.addStyle(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium), start, end)
        b.addLink(LinkAnnotation.Clickable(target.toString()) { onTarget(target) }, start, end)
    }
}
