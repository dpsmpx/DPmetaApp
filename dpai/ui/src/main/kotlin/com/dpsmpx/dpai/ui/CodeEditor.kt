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
    fun edit(text: String, selection: EditorSelection, typing: Boolean = false): Boolean {
        if (text.length > MAX_CHARS) return false
        val selected = EditorSelection(selection.start.coerceIn(0, text.length), selection.end.coerceIn(0, text.length))
        if (text == this.text) {
            if (selected != state.selection) select(selected.start, selected.end)
            return true
        }
        val now = clock.monotonicNanos()
        val delta = text.length - this.text.length
        val sign = delta.compareTo(0)
        val small = typing && kotlin.math.abs(delta) in 1..2 && state.selection.start == state.selection.end && selected.start == selected.end
        val merge = small && direction == sign && lastEdit?.let { now - it in 0..750_000_000L } == true
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
        if (query.isEmpty()) return false
        val s = state.selection
        val at = if (backwards) {
            text.lastIndexOf(query, s.from - 1, ignoreCase).takeIf { it >= 0 } ?: text.lastIndexOf(query, text.length, ignoreCase)
        } else {
            text.indexOf(query, s.until, ignoreCase).takeIf { it >= 0 } ?: text.indexOf(query, 0, ignoreCase)
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

    val line: Int get() = text.take(state.selection.end).count { it == '\n' } + 1
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
            if (query.isEmpty()) return EditorMatches(emptyList(), false)
            val ranges = mutableListOf<IntRange>()
            var at = 0
            while (at <= text.length - query.length) {
                val next = text.indexOf(query, at, ignoreCase)
                if (next < 0) break
                if (ranges.size == MAX_MATCHES) return EditorMatches(ranges, true)
                ranges += next until next + query.length
                at = next + query.length
            }
            return EditorMatches(ranges, false)
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
        var tail = 0
        while (tail < minOf(old.size, new.size) - first && old[old.lastIndex - tail] == new[new.lastIndex - tail]) tail++
        val removed = old.size - first - tail
        val added = new.size - first - tail
        return buildList {
            add(DiffLine(DiffKind.HUNK, "Строка ${first + 1}: −$removed +$added"))
            for (i in first until old.size - tail) {
                if (size > limit) break
                add(DiffLine(DiffKind.REMOVED, "− ${old[i]}"))
            }
            for (i in first until new.size - tail) {
                if (size > limit) break
                add(DiffLine(DiffKind.ADDED, "+ ${new[i]}"))
            }
            if (removed + added > limit) add(DiffLine(DiffKind.NOTE, "Показаны первые $limit строк изменённого диапазона"))
        }
    }
}
