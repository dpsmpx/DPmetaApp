package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.result.Outcome

/**
 * Поиск по проекту в IDE (DPmetaApp:D-116, D-149): по именам файлов и по
 * тексту — в дереве файлов, без внешних программ, поэтому работает и в
 * папке телефона, и в Linux приложения.
 *
 * Обход ограничен: служебные каталоги ([IGNORED]) не проходятся, файл
 * больше [MAX_FILE_BYTES] и нетекстовый — пропускается, файлов — не больше
 * [MAX_FILES], находок — не больше заданного. Обрезанный итог помечен
 * ([Result.limited]): человек видит, что показано не всё.
 */
object TreeSearch {
    /** Каталоги, в которых исходников автора нет: история Git, сборка, зависимости, кэши. */
    val IGNORED: Set<String> = setOf(".git", ".gradle", ".idea", ".cache", "build", "node_modules", "__pycache__", ".venv", "venv")

    const val MAX_FILES: Int = 20_000
    const val MAX_FILE_BYTES: Long = 1024L * 1024
    const val MAX_PREVIEW: Int = 160

    /** Находка в тексте: строка и столбец с единицы, [preview] — строка без краевых пробелов, обрезанная вокруг находки. */
    data class TextHit(val path: String, val line: Int, val column: Int, val preview: String)

    /** [scanned] — просмотрено файлов; [skipped] — пропущено (велики, нетекстовые, не прочитаны). */
    data class Result<T>(val hits: List<T>, val limited: Boolean, val scanned: Int, val skipped: Int)

    /**
     * Файлы, в пути которых есть [query] (без учёта регистра; пробелы делят
     * запрос на части — должны найтись все). Сначала — совпадения в начале
     * имени, затем в имени, затем в каталогах; короткие пути — выше.
     */
    fun names(tree: FileTree, query: String, dir: String = "", limit: Int = 200, cancelled: () -> Boolean = { false }): Outcome<Result<String>> {
        val parts = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Outcome.success(Result(emptyList(), false, 0, 0))
        val found = ArrayList<Pair<Int, String>>()
        var scanned = 0
        val walked = walk(tree, dir, cancelled) { path, _ ->
            scanned++
            val low = path.lowercase()
            if (parts.all { it in low }) {
                val name = low.substringAfterLast('/')
                found += when {
                    name.startsWith(parts.first()) -> 0
                    parts.all { it in name } -> 1
                    else -> 2
                } to path
            }
            true
        }
        val limitedWalk = when (walked) {
            is Outcome.Failure -> return walked
            is Outcome.Success -> walked.value
        }
        val sorted = found.sortedWith(compareBy({ it.first }, { it.second.length }, { it.second })).map { it.second }
        return Outcome.success(Result(sorted.take(limit), limitedWalk || sorted.size > limit, scanned, 0))
    }

    /** Строки файлов с буквальным [query]; одна находка на строку — первая. */
    fun text(tree: FileTree, query: String, ignoreCase: Boolean = true, dir: String = "", limit: Int = 300, cancelled: () -> Boolean = { false }): Outcome<Result<TextHit>> {
        if (query.isEmpty()) return Outcome.success(Result(emptyList(), false, 0, 0))
        val hits = ArrayList<TextHit>()
        var scanned = 0
        var skipped = 0
        var full = false
        val walked = walk(tree, dir, cancelled) { path, meta ->
            if (meta.size > MAX_FILE_BYTES) {
                skipped++
                return@walk true
            }
            val text = when (val r = tree.read(path, MAX_FILE_BYTES)) {
                is Outcome.Failure -> null
                is Outcome.Success -> (TextFile.decode(r.value, MAX_FILE_BYTES) as? Outcome.Success)?.value
            }
            if (text == null) {
                skipped++
                return@walk true
            }
            scanned++
            var from = 0
            var line = 1
            var lineStart = 0
            while (true) {
                val at = text.indexOf(query, from, ignoreCase)
                if (at < 0) break
                while (true) {
                    val next = text.indexOf('\n', lineStart)
                    if (next < 0 || next >= at) break
                    lineStart = next + 1
                    line++
                }
                val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
                if (hits.size >= limit) {
                    full = true
                    return@walk false
                }
                hits += TextHit(path, line, at - lineStart + 1, preview(text.substring(lineStart, end).trimEnd('\r'), at - lineStart, query.length))
                if (end >= text.length) break
                from = end + 1
            }
            true
        }
        val limitedWalk = when (walked) {
            is Outcome.Failure -> return walked
            is Outcome.Success -> walked.value
        }
        return Outcome.success(Result(hits, full || limitedWalk, scanned, skipped))
    }

    /** Строка для списка: без отступа, не длиннее [MAX_PREVIEW], находка — в видимой части. */
    internal fun preview(line: String, at: Int, length: Int): String {
        val indent = line.length - line.trimStart().length
        val start = if (at + length - indent <= MAX_PREVIEW) indent else maxOf(indent, at - MAX_PREVIEW / 3)
        val cut = line.substring(minOf(start, line.length), minOf(line.length, start + MAX_PREVIEW))
        return (if (start > indent) "…" else "") + cut + (if (start + MAX_PREVIEW < line.length) "…" else "")
    }

    /**
     * Файлы под [dir] в порядке имён; [visit] возвращает `false` — обход
     * кончен. Итог — `true`, если обход оборван (отмена, предел файлов,
     * решение [visit]) или часть каталогов не прочитана. Корень не
     * прочитан — отказ.
     */
    private fun walk(tree: FileTree, dir: String, cancelled: () -> Boolean, visit: (String, FileMeta) -> Boolean): Outcome<Boolean> {
        val queue = ArrayDeque(listOf(dir))
        var files = 0
        var partial = false
        var first = true
        while (queue.isNotEmpty()) {
            if (cancelled()) return Outcome.success(true)
            val current = queue.removeFirst()
            val entries = when (val r = tree.list(current)) {
                is Outcome.Failure -> {
                    if (first) return r
                    partial = true
                    continue
                }
                is Outcome.Success -> r.value
            }
            first = false
            for (e in entries.sortedBy { it.name }) {
                if (!FileNames.segmentValid(e.name) || e.name.startsWith(FileNames.TMP_PREFIX)) continue
                val path = FileNames.join(current, e.name)
                if (e.dir) {
                    if (e.name !in IGNORED) queue.addLast(path)
                    continue
                }
                if (++files > MAX_FILES) return Outcome.success(true)
                if (cancelled()) return Outcome.success(true)
                if (!visit(path, e.meta)) return Outcome.success(true)
            }
        }
        return Outcome.success(partial)
    }
}
