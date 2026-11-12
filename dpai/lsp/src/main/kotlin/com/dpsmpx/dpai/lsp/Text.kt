package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/** Позиция LSP: строка с нуля и смещение в ней в единицах UTF-16 — как индексы строк Kotlin и поля редактора. */
data class Position(val line: Int, val character: Int) : Comparable<Position> {
    init {
        require(line >= 0 && character >= 0) { "позиция неотрицательна" }
    }

    override fun compareTo(other: Position): Int = if (line != other.line) line.compareTo(other.line) else character.compareTo(other.character)
}

/** Диапазон: [start] включён, [end] — нет. */
data class Range(val start: Position, val end: Position) {
    init {
        require(start <= end) { "начало диапазона не позже конца" }
    }

    val isEmpty: Boolean get() = start == end
}

/** Замена [range] текстом [newText]. */
data class TextEdit(val range: Range, val newText: String)

/**
 * Начала строк текста: перевод между смещением в тексте и [Position].
 * Строки делит `\n` (у `\r\n` знак `\r` — последний в строке): так считает
 * clangd, и так же считает поле редактора.
 */
class LineIndex(private val text: String) {
    private val starts: IntArray = run {
        var n = 1
        for (c in text) if (c == '\n') n++
        val a = IntArray(n)
        var i = 1
        text.forEachIndexed { at, c -> if (c == '\n') a[i++] = at + 1 }
        a
    }

    val lines: Int get() = starts.size

    /** Длина строки без её перевода `\n`. */
    fun lineLength(line: Int): Int = (if (line + 1 < starts.size) starts[line + 1] - 1 else text.length) - starts[line]

    /** Смещение позиции; смещение за концом строки — её конец (LSP); строки нет — `null`. */
    fun offset(p: Position): Int? = if (p.line >= starts.size) null else starts[p.line] + minOf(p.character, lineLength(p.line))

    fun position(offset: Int): Position {
        require(offset in 0..text.length) { "смещение вне текста" }
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return Position(lo, offset - starts[lo])
    }
}

object TextEdits {
    /**
     * Применить правки службы к [text]: все диапазоны — в координатах
     * исходного текста. Пересечение правок или строка за концом текста —
     * отказ целиком: частично применённое переименование хуже, чем никакое.
     */
    fun apply(text: String, edits: List<TextEdit>): Outcome<String> {
        if (edits.isEmpty()) return Outcome.success(text)
        val index = LineIndex(text)
        val spans = edits.map { e ->
            val from = index.offset(e.range.start)
            val to = index.offset(e.range.end)
            if (from == null || to == null) return Outcome.failure(DiagCode.LSP_REPLY_INVALID, "правка службы — за концом текста: строка ${e.range.end.line + 1}")
            Triple(from, to, e.newText)
        }.withIndex().sortedWith(compareBy({ it.value.first }, { it.value.second }, { it.index })).map { it.value }
        val out = StringBuilder(text.length + edits.sumOf { it.newText.length })
        var at = 0
        for ((from, to, new) in spans) {
            if (from < at) return Outcome.failure(DiagCode.LSP_REPLY_INVALID, "правки службы пересекаются")
            out.append(text, at, from).append(new)
            at = to
        }
        out.append(text, at, text.length)
        return Outcome.success(out.toString())
    }

    /**
     * Одна правка, превращающая [old] в [new]: общие начало и конец
     * остаются, середина заменяется. Службе уходит изменённый кусок, а не
     * файл целиком на каждое нажатие. `null` — тексты равны. Суррогатная
     * пара не режется пополам.
     */
    fun delta(old: String, new: String): TextEdit? {
        if (old == new) return null
        val max = minOf(old.length, new.length)
        var head = 0
        while (head < max && old[head] == new[head]) head++
        if (head > 0 && old[head - 1].isHighSurrogate()) head--
        var tail = 0
        while (tail < max - head && old[old.length - 1 - tail] == new[new.length - 1 - tail]) tail++
        if (tail > 0 && old[old.length - tail].isLowSurrogate()) tail--
        val index = LineIndex(old)
        return TextEdit(Range(index.position(head), index.position(old.length - tail)), new.substring(head, new.length - tail))
    }
}
