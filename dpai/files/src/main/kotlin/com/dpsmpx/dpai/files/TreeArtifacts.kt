package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Артефакты сборки в дереве (DPmetaApp:D-122, D-157): файлы по шаблонам путей
 * от корня дерева. `**` — любое число папок (и ни одной), `*` — любые знаки
 * внутри имени, `?` — один знак. В отличие от поиска (D-149), папки сборки
 * (`build`, `out`) проходятся — артефакты там; не проходятся история Git,
 * кэши и зависимости.
 */
object TreeArtifacts {
    /** Шаблоны по умолчанию: APK и AAB в любом месте дерева. */
    val DEFAULT: List<String> = listOf("**/*.apk", "**/*.aab")

    val IGNORED: Set<String> = setOf(".git", ".gradle", ".idea", ".cache", "node_modules", "__pycache__", ".venv", "venv", ".cxx", "intermediates", "tmp")

    const val MAX_FILES: Int = 50_000
    const val MAX_PATTERNS: Int = 20

    data class Artifact(val path: String, val size: Long, val modified: Long)

    /** [limited] — обход оборван пределом или отменой, или часть папок не прочитана. */
    data class Found(val items: List<Artifact>, val limited: Boolean, val scanned: Int)

    /** Шаблон в выражение; негодный (пустой, абсолютный, с `..`, слишком длинный) — `null`. */
    fun pattern(glob: String): Regex? {
        val g = glob.trim()
        if (g.isEmpty() || g.length > 300 || g.startsWith("/") || g.contains('\\') || g.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        val sb = StringBuilder("^")
        val parts = g.split('/')
        parts.forEachIndexed { i, part ->
            val last = i == parts.size - 1
            if (part == "**") {
                sb.append(if (last) ".*" else "(?:[^/]+/)*")
                return@forEachIndexed
            }
            for (c in part) when (c) {
                '*' -> sb.append("[^/]*")
                '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            if (!last) sb.append('/')
        }
        return Regex(sb.append('$').toString())
    }

    /** Строка шаблонов через пробел или перевод строки; негодный — отказ с ним самим. */
    fun patterns(text: String): Outcome<List<String>> {
        val list = text.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (list.size > MAX_PATTERNS) return Outcome.failure(DiagCode.VALUE_INVALID, "шаблонов — не больше $MAX_PATTERNS")
        list.firstOrNull { pattern(it) == null }?.let { return Outcome.failure(DiagCode.VALUE_INVALID, "«$it» — не шаблон пути от корня дерева: без «/» в начале, «..» и «\\»") }
        return Outcome.success(list)
    }

    /** Файлы дерева по [globs], новые — первыми, не больше [limit]. */
    fun find(tree: FileTree, globs: List<String>, limit: Int = 100, cancelled: () -> Boolean = { false }): Outcome<Found> {
        val regexes = globs.ifEmpty { DEFAULT }.mapNotNull(::pattern)
        if (regexes.isEmpty()) return Outcome.failure(DiagCode.VALUE_INVALID, "нет годных шаблонов артефактов")
        val found = ArrayList<Artifact>()
        val queue = ArrayDeque(listOf(""))
        var files = 0
        var partial = false
        var first = true
        while (queue.isNotEmpty()) {
            if (cancelled()) return Outcome.success(Found(sorted(found, limit), true, files))
            val dir = queue.removeFirst()
            val entries = when (val r = tree.list(dir)) {
                is Outcome.Failure -> {
                    if (first) return r
                    partial = true
                    continue
                }
                is Outcome.Success -> r.value
            }
            first = false
            for (e in entries) {
                if (!FileNames.segmentValid(e.name) || e.name.startsWith(FileNames.TMP_PREFIX)) continue
                val path = FileNames.join(dir, e.name)
                if (e.dir) {
                    if (e.name !in IGNORED) queue.addLast(path)
                    continue
                }
                if (++files > MAX_FILES) return Outcome.success(Found(sorted(found, limit), true, files - 1))
                if (regexes.any { it.matches(path) }) found += Artifact(path, e.size, e.modified)
            }
        }
        return Outcome.success(Found(sorted(found, limit), partial || found.size > limit, files))
    }

    private fun sorted(found: List<Artifact>, limit: Int): List<Artifact> = found.sortedWith(compareByDescending<Artifact> { it.modified }.thenBy { it.path }).take(limit)
}
