package com.dpsmpx.dpai.ui

/** Команды поля, независимо от IME и платформенного движка (D-136). Все изменения — один undo. */
object EditorActions {
    private data class Patch(val from: Int, val until: Int, val text: String)
    private fun patch(buffer: CodeEditorBuffer, patches: List<Patch>): Boolean {
        if (patches.isEmpty()) return false
        val source = buffer.text
        val sorted = patches.sortedBy { it.from }
        val size = source.length.toLong() + sorted.sumOf { it.text.length.toLong() - (it.until - it.from) }
        if (size > CodeEditorBuffer.MAX_CHARS) return false
        val next = buildString(size.toInt()) {
            var at = 0
            for (p in sorted) { append(source, at, p.from); append(p.text); at = p.until }
            append(source, at, source.length)
        }
        fun position(old: Int): Int {
            var delta = 0
            for (p in sorted) {
                if (old < p.from) break
                if (old < p.until) return p.from + delta + p.text.length
                delta += p.text.length - (p.until - p.from)
            }
            return old + delta
        }
        val s = buffer.state.selection
        return buffer.edit(next, EditorSelection(position(s.start), position(s.end)))
    }
    private fun rows(buffer: CodeEditorBuffer): List<Int> {
        val text = buffer.text; val s = buffer.state.selection
        val first = text.lastIndexOf('\n', (s.from - 1).coerceAtLeast(-1)) + 1
        val last = if (s.until > s.from && text.getOrNull(s.until - 1) == '\n') s.until - 1 else s.until
        return buildList {
            var at = first
            while (at <= last) {
                add(at)
                val end = text.indexOf('\n', at)
                if (end < 0 || end >= last) break
                at = end + 1
            }
        }
    }

    fun surround(buffer: CodeEditorBuffer, open: String, close: String): Boolean {
        val s = buffer.state.selection; val text = buffer.text
        val selected = text.substring(s.from, s.until)
        if (text.length.toLong() + open.length + close.length > CodeEditorBuffer.MAX_CHARS) return false
        val next = text.substring(0, s.from) + open + selected + close + text.substring(s.until)
        val selection = if (s.from == s.until) EditorSelection(s.from + open.length)
            else EditorSelection(s.start + open.length, s.end + open.length)
        return buffer.edit(next, selection)
    }

    fun indent(buffer: CodeEditorBuffer, width: Int = 4, tabs: Boolean = false, backwards: Boolean = false): Boolean {
        require(width in 1..8)
        val s = buffer.state.selection
        if (s.from == s.until && !backwards) {
            val start = buffer.text.lastIndexOf('\n', (s.from - 1).coerceAtLeast(-1)) + 1
            return buffer.replaceSelection(if (tabs) "\t" else " ".repeat(width - (s.from - start) % width))
        }
        return patch(buffer, rows(buffer).mapNotNull { at ->
            if (!backwards) Patch(at, at, if (tabs) "\t" else " ".repeat(width)) else {
                var end = at
                if (buffer.text.getOrNull(end) == '\t') end++ else while (end - at < width && buffer.text.getOrNull(end) == ' ') end++
                if (end == at) null else Patch(at, end, "")
            }
        })
    }

    fun comment(buffer: CodeEditorBuffer, path: String): Boolean {
        val marker = when (CodeSyntax.language(path)) { "C/C++", "Kotlin", "Java" -> "//"; "Python" -> "#"; else -> return false }
        val text = buffer.text
        val positions = rows(buffer).mapNotNull { start ->
            var at = start
            while (text.getOrNull(at) in listOf(' ', '\t')) at++
            at.takeIf { text.getOrNull(at) != null && text[at] != '\r' && text[at] != '\n' }
        }
        val remove = positions.isNotEmpty() && positions.all { text.startsWith(marker, it) }
        return patch(buffer, positions.map { at ->
            if (remove) {
                var end = at + marker.length
                if (text.getOrNull(end) == ' ') end++
                Patch(at, end, "")
            } else Patch(at, at, "$marker ")
        })
    }

    fun duplicate(buffer: CodeEditorBuffer): Boolean {
        val s = buffer.state.selection; val text = buffer.text
        if (s.from != s.until) {
            val part = text.substring(s.from, s.until)
            if (text.length.toLong() + part.length > CodeEditorBuffer.MAX_CHARS) return false
            return buffer.edit(text.substring(0, s.until) + part + text.substring(s.until),
                if (s.start < s.end) EditorSelection(s.until, s.until + part.length) else EditorSelection(s.until + part.length, s.until))
        }
        val start = rows(buffer).first()
        val end = text.indexOf('\n', start)
        val block = if (end >= 0) text.substring(start, end + 1) else separator(text) + text.substring(start)
        val at = if (end >= 0) end + 1 else text.length
        if (text.length.toLong() + block.length > CodeEditorBuffer.MAX_CHARS) return false
        val next = text.substring(0, at) + block + text.substring(at)
        return buffer.edit(next, EditorSelection(s.start + block.length))
    }

    fun deleteLines(buffer: CodeEditorBuffer): Boolean {
        val starts = rows(buffer); val text = buffer.text
        var from = starts.first(); var until = text.indexOf('\n', starts.last()).let { if (it < 0) text.length else it + 1 }
        if (until == text.length && from > 0 && !text.endsWith('\n')) {
            from--; if (from > 0 && text[from - 1] == '\r') from--
        }
        return buffer.edit(text.removeRange(from, until), EditorSelection(from))
    }

    fun moveLines(buffer: CodeEditorBuffer, up: Boolean): Boolean {
        val text = buffer.text
        val starts = rows(buffer)
        val all = text.split('\n')
        val first = text.take(starts.first()).count { it == '\n' }
        val last = first + starts.size - 1
        if (up && first == 0 || !up && last == all.lastIndex) return false
        val endings = all.mapIndexed { i, line -> if (i == all.lastIndex) "" else if (line.endsWith('\r')) "\r\n" else "\n" }
        val contents = all.mapIndexed { i, line -> if (i == all.lastIndex) line else line.removeSuffix("\r") }.toMutableList()
        val oldStarts = mutableListOf(0)
        for (i in 0 until all.lastIndex) oldStarts += oldStarts.last() + all[i].length + 1
        if (up) { val before = contents.removeAt(first - 1); contents.add(last, before) }
        else { val after = contents.removeAt(last + 1); contents.add(first, after) }
        val newStarts = mutableListOf(0)
        for (i in 0 until contents.lastIndex) newStarts += newStarts.last() + contents[i].length + endings[i].length
        val next = contents.mapIndexed { i, line -> line + endings[i] }.joinToString("")
        val s = buffer.state.selection
        fun mapped(position: Int): Int {
            val row = oldStarts.indexOfLast { it <= position }
            val boundary = s.from != s.until && position == s.until && oldStarts.getOrNull(last + 1) == position
            val target = if (boundary) first + starts.size + (if (up) -1 else 1) else row + if (up) -1 else 1
            return if (target >= contents.size) next.length else newStarts[target] + if (boundary) 0 else (position - oldStarts[row]).coerceAtMost(contents[target].length)
        }
        return buffer.edit(next, EditorSelection(mapped(s.start), mapped(s.end)))
    }

    fun separator(text: String): String = if (text.indexOf('\n').let { it > 0 && text[it - 1] == '\r' }) "\r\n" else "\n"
}

/** Вкладки содержат только пути; тексты и история принадлежат отдельным черновикам (D-136). */
data class EditorTabs(val paths: List<String> = emptyList(), val active: String? = null) {
    fun open(path: String): EditorTabs = if (path in paths) copy(active = path)
        else if (paths.size < MAX_OPEN) copy(paths = paths + path, active = path) else this
    fun browse(): EditorTabs = copy(active = null)
    fun close(path: String): EditorTabs {
        val at = paths.indexOf(path); val rest = paths - path
        return EditorTabs(rest, if (active == path) rest.getOrNull(at.coerceAtMost(rest.lastIndex)) else active)
    }
    fun next(backwards: Boolean = false): EditorTabs {
        if (paths.isEmpty()) return this
        val index = paths.indexOf(active)
        return copy(active = paths[(index + (if (backwards) paths.size - 1 else 1)).mod(paths.size)])
    }
    companion object { const val MAX_OPEN = 16 }
}
