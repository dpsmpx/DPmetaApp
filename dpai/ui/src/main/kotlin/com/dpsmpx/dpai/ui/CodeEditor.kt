package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.Clock

/** Выделение хранится с направлением; позиции — UTF-16, как в Compose (D-131). */
data class EditorSelection(val start: Int, val end: Int = start) {
    val from: Int get() = minOf(start, end)
    val until: Int get() = maxOf(start, end)
}

data class EditorSnapshot(val text: String, val selection: EditorSelection)
data class EditorMatches(val ranges: List<IntRange>, val limited: Boolean)

/** Ручная правка: история в памяти, буквальный поиск, одна замена — один undo (D-131). */
class CodeEditorBuffer(val original: String, private val clock: Clock = Clock.SYSTEM) {
    @Volatile
    var state = EditorSnapshot(original, EditorSelection(0))
        private set
    val text: String get() = state.text
    val dirty: Boolean get() = text != original
    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    private val undo = ArrayDeque<EditorSnapshot>()
    private val redo = ArrayDeque<EditorSnapshot>()
    private var historyChars = 0
    private var lastEdit: Long? = null
    private var direction = 0

    fun select(start: Int, end: Int = start) {
        state = state.copy(selection = EditorSelection(start.coerceIn(0, text.length), end.coerceIn(0, text.length)))
        lastEdit = null
    }

    /** Только соседние короткие вводы в пределах 750 мс объединяются в шаг. */
    fun edit(text: String, selection: EditorSelection, typing: Boolean = false, coalesce: Boolean = false): Boolean {
        if (text.length > MAX_CHARS) return false
        val selected = EditorSelection(selection.start.coerceIn(0, text.length), selection.end.coerceIn(0, text.length))
        if (text == this.text) {
            if (selected != state.selection) select(selected.start, selected.end)
            return true
        }
        val now = clock.monotonicNanos()
        val delta = text.length - this.text.length
        val sign = delta.compareTo(0)
        val adjacent = if (delta > 0) selected.start == state.selection.end + delta else selected.start in (state.selection.end + delta)..state.selection.end
        val small = typing && adjacent && kotlin.math.abs(delta) in 1..2 && state.selection.start == state.selection.end && selected.start == selected.end
        val merge = coalesce && undo.isNotEmpty() || small && direction == sign && lastEdit?.let { now - it in 0..750_000_000L } == true
        if (!merge) {
            undo.addLast(state)
            historyChars += state.text.length
            while (undo.size > 1 && (undo.size > MAX_HISTORY || historyChars > MAX_HISTORY_CHARS)) historyChars -= undo.removeFirst().text.length
        }
        redo.clear()
        state = EditorSnapshot(text, selected)
        lastEdit = if (small) now else null
        direction = sign
        return true
    }

    fun undo(): Boolean {
        if (undo.isEmpty()) return false
        redo.addLast(state)
        state = undo.removeLast()
        historyChars -= state.text.length
        lastEdit = null
        return true
    }

    fun redo(): Boolean {
        if (redo.isEmpty()) return false
        undo.addLast(state)
        historyChars += state.text.length
        state = redo.removeLast()
        lastEdit = null
        return true
    }

    fun replaceSelection(replacement: String): Boolean {
        val s = state.selection
        val length = text.length.toLong() - (s.until - s.from) + replacement.length
        if (length > MAX_CHARS) return false
        return edit(text.replaceRange(s.from, s.until, replacement), EditorSelection(s.from + replacement.length))
    }

    fun matches(query: String, ignoreCase: Boolean = true): EditorMatches = find(text, query, ignoreCase)

    /** Следующее/предыдущее совпадение с циклическим переходом и выделением. */
    fun findNext(query: String, backwards: Boolean = false, ignoreCase: Boolean = true): Boolean {
        if (query.isEmpty() || query.length > text.length) return false
        val s = state.selection
        val pattern = EditorPattern(query, ignoreCase)
        val at = if (backwards) {
            var previous = -1
            var last = -1
            pattern.scan(text, overlap = true) { at ->
                last = at
                if (at < s.from) previous = at
                true
            }
            if (previous >= 0) previous else last
        } else {
            pattern.first(text, s.until).takeIf { it >= 0 } ?: pattern.first(text, 0)
        }
        if (at < 0) return false
        select(at, at + query.length)
        return true
    }

    fun replaceNext(query: String, replacement: String, ignoreCase: Boolean = true): Boolean {
        if (query.isEmpty()) return false
        val s = state.selection
        if (!text.substring(s.from, s.until).equals(query, ignoreCase) && !findNext(query, ignoreCase = ignoreCase)) return false
        return replaceSelection(replacement)
    }

    /** Замена не обрабатывает свой результат повторно; превышение лимита не меняет документ. */
    fun replaceAll(query: String, replacement: String, ignoreCase: Boolean = true): Int {
        val found = matches(query, ignoreCase)
        if (found.limited || found.ranges.isEmpty()) return 0
        val length = text.length.toLong() + found.ranges.size.toLong() * (replacement.length.toLong() - query.length)
        if (length > MAX_CHARS) return 0
        val result = buildString(length.toInt()) {
            var at = 0
            for (range in found.ranges) {
                append(text, at, range.first)
                append(replacement)
                at = range.last + 1
            }
            append(text, at, text.length)
        }
        edit(result, EditorSelection(0))
        return found.ranges.size
    }

    val line: Int get() {
        var line = 1
        for (i in 0 until state.selection.end) if (text[i] == '\n') line++
        return line
    }
    val column: Int get() = state.selection.end - text.lastIndexOf('\n', state.selection.end - 1)

    fun goToLine(line: Int): Boolean {
        if (line < 1) return false
        var at = 0
        repeat(line - 1) {
            val next = text.indexOf('\n', at)
            if (next < 0) return false
            at = next + 1
        }
        select(at)
        return true
    }

    companion object {
        const val MAX_CHARS = 2 * 1024 * 1024
        const val MAX_HISTORY = 40
        const val MAX_HISTORY_CHARS = 4 * 1024 * 1024
        const val MAX_MATCHES = 10_000

        fun find(text: String, query: String, ignoreCase: Boolean = true): EditorMatches {
            if (query.isEmpty() || query.length > text.length) return EditorMatches(emptyList(), false)
            val ranges = mutableListOf<IntRange>()
            var limited = false
            EditorPattern(query, ignoreCase).scan(text) { at ->
                if (ranges.size == MAX_MATCHES) {
                    limited = true
                    false
                } else {
                    ranges += at until at + query.length
                    true
                }
            }
            return EditorMatches(ranges, limited)
        }
    }
}

/** KMP: повторяющийся текст и длинный запрос не дают произведения их длин. */
private class EditorPattern(query: String, private val ignoreCase: Boolean) {
    private fun fold(c: Char): Char = if (ignoreCase) c.uppercaseChar().lowercaseChar() else c
    private val pattern = CharArray(query.length) { fold(query[it]) }
    private val prefix = IntArray(pattern.size).also { table ->
        var j = 0
        for (i in 1 until pattern.size) {
            while (j > 0 && pattern[i] != pattern[j]) j = table[j - 1]
            if (pattern[i] == pattern[j]) j++
            table[i] = j
        }
    }

    fun first(text: String, from: Int): Int {
        var result = -1
        scan(text, from) { result = it; false }
        return result
    }

    fun scan(text: String, from: Int = 0, overlap: Boolean = false, accept: (Int) -> Boolean) {
        var j = 0
        for (i in from until text.length) {
            val c = fold(text[i])
            while (j > 0 && c != pattern[j]) j = prefix[j - 1]
            if (c == pattern[j]) j++
            if (j == pattern.size) {
                if (!accept(i - pattern.size + 1)) return
                j = if (overlap) prefix[j - 1] else 0
            }
        }
    }
}

/** Ограниченный просмотр заменённого диапазона строк, без квадратичного сравнения (D-131). */
object EditorChanges {
    fun preview(before: String, after: String, limit: Int = 400): List<DiffLine> {
        require(limit > 0)
        if (before == after) return listOf(DiffLine(DiffKind.NOTE, "Изменений нет"))
        val old = before.lines()
        val new = after.lines()
        var first = 0
        while (first < minOf(old.size, new.size) && old[first] == new[first]) first++
        if (first == old.size && first == new.size) return listOf(DiffLine(DiffKind.NOTE, "Изменились переводы строк"))
        var tail = 0
        while (tail < minOf(old.size, new.size) - first && old[old.lastIndex - tail] == new[new.lastIndex - tail]) tail++
        val removed = old.size - first - tail
        val added = new.size - first - tail
        var oldRows = minOf(removed, limit / 2)
        val newRows = minOf(added, limit - oldRows)
        oldRows = minOf(removed, limit - newRows)
        fun display(line: String): String {
            if (line.length <= 2000) return line
            val end = if (Character.isHighSurrogate(line[1999])) 1999 else 2000
            return line.take(end) + " … [строка сокращена]"
        }
        return buildList {
            add(DiffLine(DiffKind.HUNK, "Строка ${first + 1}: −$removed +$added"))
            for (i in first until first + oldRows) {
                add(DiffLine(DiffKind.REMOVED, "− ${display(old[i])}"))
            }
            for (i in first until first + newRows) {
                add(DiffLine(DiffKind.ADDED, "+ ${display(new[i])}"))
            }
            if (removed > oldRows || added > newRows) add(DiffLine(DiffKind.NOTE, "Показан сокращённый изменённый диапазон"))
        }
    }
}
