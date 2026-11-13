package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.diagnostics.DiagCode
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

    /**
     * Что искать: буквальный текст или регулярное выражение Java. Выражение
     * длиннее [MAX_PATTERN], неразборное или совпадающее с пустой строкой —
     * отказ: пустое совпадение «нашлось» бы в каждой строке.
     */
    class Query private constructor(private val literal: String, private val pattern: java.util.regex.Pattern?, private val ignoreCase: Boolean) {
        val isRegex: Boolean get() = pattern != null

        /** Первая находка не раньше [from]: начало и конец (не включён); `null` — нет. */
        internal fun find(text: CharSequence, from: Int): Pair<Int, Int>? {
            if (pattern == null) {
                val at = text.indexOf(literal, from, ignoreCase)
                return if (at < 0) null else at to at + literal.length
            }
            val m = pattern.matcher(text)
            var start = from
            while (start <= text.length && m.find(start)) {
                if (m.end() > m.start()) return m.start() to m.end()
                start = m.end() + 1
            }
            return null
        }

        /** Все находки заменены на [replacement]; у выражения `$1`…`$9` — группы, `\$` и `\\` — сами знаки. Итог — текст и число замен. */
        internal fun replaceAll(text: CharSequence, replacement: String): Pair<String, Int> {
            val out = StringBuilder(text.length)
            var at = 0
            var count = 0
            if (pattern == null) {
                while (true) {
                    val hit = text.indexOf(literal, at, ignoreCase)
                    if (hit < 0) break
                    out.append(text, at, hit).append(replacement)
                    at = hit + literal.length
                    count++
                }
                out.append(text, at, text.length)
                return out.toString() to count
            }
            val m = pattern.matcher(text)
            // StringBuffer, не StringBuilder: перегрузки appendReplacement/appendTail
            // со StringBuilder есть только с Android 14 (API 34), minSdk — 30.
            val sb = StringBuffer()
            while (m.find()) {
                if (m.end() == m.start()) continue
                m.appendReplacement(sb, replacement)
                count++
            }
            m.appendTail(sb)
            return sb.toString() to count
        }

        /** Годна ли строка замены: у выражения `$` — только с номером существующей группы, `\` — не последним. */
        internal fun replacementProblem(replacement: String): String? {
            val p = pattern ?: return null
            val groups = p.matcher("").groupCount()
            var i = 0
            while (i < replacement.length) {
                when (replacement[i]) {
                    '\\' -> {
                        if (i + 1 >= replacement.length) return "замена кончается на «\\»: напишите «\\\\» для самой черты"
                        i++
                    }
                    '$' -> {
                        val d = replacement.getOrNull(i + 1)?.digitToIntOrNull() ?: return "после «$» — номер группы: «$1»; сам знак — «\\$»"
                        if (d > groups) return "группы $$d в выражении нет: групп — $groups"
                        i++
                    }
                }
                i++
            }
            return null
        }

        companion object {
            const val MAX_PATTERN: Int = 500

            fun of(text: String, ignoreCase: Boolean = true, regex: Boolean = false): Outcome<Query> {
                if (text.isEmpty()) return Outcome.failure(DiagCode.FIELD_MISSING, "введите, что искать")
                if (!regex) return Outcome.success(Query(text, null, ignoreCase))
                if (text.length > MAX_PATTERN) return Outcome.failure(DiagCode.VALUE_INVALID, "выражение длиннее $MAX_PATTERN знаков")
                val flags = java.util.regex.Pattern.MULTILINE or if (ignoreCase) java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE else 0
                val pattern = try {
                    java.util.regex.Pattern.compile(text, flags)
                } catch (e: java.util.regex.PatternSyntaxException) {
                    return Outcome.failure(DiagCode.VALUE_INVALID, "выражение не разобрано: ${e.description} (знак ${e.index + 1})")
                }
                if (pattern.matcher("").find()) return Outcome.failure(DiagCode.VALUE_INVALID, "выражение совпадает с пустой строкой — уточните его")
                return Outcome.success(Query("", pattern, ignoreCase))
            }
        }
    }

    /** Отмена и предел работы выражения: сигнал из [Guarded], наружу не выходит. */
    private class Stop(val budget: Boolean) : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * Текст для выражения с пределом работы: неудачное выражение может
     * перебирать варианты часами. Каждое обращение к знаку считается; сверх
     * [MAX_REGEX_STEPS] на файл — файл пропущен, отмена — обход кончен.
     */
    private class Guarded(private val text: String, private val cancelled: () -> Boolean) : CharSequence {
        private var steps = 0L
        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            if (++steps and 0xFFFF == 0L) {
                if (cancelled()) throw Stop(false)
                if (steps > regexSteps) throw Stop(true)
            }
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)

        override fun toString(): String = text
    }

    const val MAX_REGEX_STEPS: Long = 200_000_000

    /** Действующий предел шагов выражения на файл; меняют только тесты. */
    @Volatile
    internal var regexSteps: Long = MAX_REGEX_STEPS

    /** Строки файлов с буквальным [query]; одна находка на строку — первая. */
    fun text(tree: FileTree, query: String, ignoreCase: Boolean = true, dir: String = "", limit: Int = 300, regex: Boolean = false, cancelled: () -> Boolean = { false }): Outcome<Result<TextHit>> {
        if (query.isEmpty()) return Outcome.success(Result(emptyList(), false, 0, 0))
        val q = when (val r = Query.of(query, ignoreCase, regex)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
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
            val subject: CharSequence = if (q.isRegex) Guarded(text, cancelled) else text
            while (true) {
                val found = try {
                    q.find(subject, from)
                } catch (stop: Stop) {
                    // Выражение не уложилось в предел на этом файле — файл пропущен; отмена — обход кончен.
                    if (!stop.budget) return@walk false
                    scanned--
                    skipped++
                    null
                } ?: break
                val at = found.first
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
                hits += TextHit(path, line, at - lineStart + 1, preview(text.substring(lineStart, end).trimEnd('\r'), at - lineStart, minOf(found.second, end) - at))
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

    /** [files] — изменено файлов, [replacements] — замен; [failed] — путь и причина там, где запись не прошла. */
    data class Replaced(val files: Int, val replacements: Int, val failed: List<Pair<String, String>>, val limited: Boolean, val skipped: Int)

    /**
     * Замена по проекту (DPmetaApp:D-116, D-150): во всех текстовых файлах под
     * [dir] все находки [query] — на [replacement]. Файл пишется, только если
     * он не изменился с момента чтения ([TextFileEdit.save]); отказ одного
     * файла не останавливает остальные и назван в итоге. Отмена оставляет уже
     * записанные файлы записанными — итог говорит, сколько их.
     */
    fun replace(tree: FileTree, query: String, replacement: String, ignoreCase: Boolean = true, regex: Boolean = false, dir: String = "", cancelled: () -> Boolean = { false }): Outcome<Replaced> {
        val q = when (val r = Query.of(query, ignoreCase, regex)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        q.replacementProblem(replacement)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it) }
        var files = 0
        var count = 0
        var skipped = 0
        val failed = ArrayList<Pair<String, String>>()
        val walked = walk(tree, dir, cancelled) { path, meta ->
            if (meta.size > MAX_FILE_BYTES) {
                skipped++
                return@walk true
            }
            val original = (tree.read(path, MAX_FILE_BYTES) as? Outcome.Success)?.value
            val text = original?.let { (TextFile.decode(it, MAX_FILE_BYTES) as? Outcome.Success)?.value }
            if (original == null || text == null) {
                skipped++
                return@walk true
            }
            val (changed, n) = try {
                q.replaceAll(if (q.isRegex) Guarded(text, cancelled) else text, replacement)
            } catch (stop: Stop) {
                if (!stop.budget) return@walk false
                skipped++
                return@walk true
            }
            if (n == 0 || changed == text) return@walk true
            when (val saved = TextFileEdit.save(tree, path, original, changed)) {
                is Outcome.Failure -> failed += path to saved.diagnostics.first().message
                is Outcome.Success -> {
                    files++
                    count += n
                }
            }
            true
        }
        return when (walked) {
            is Outcome.Failure -> walked
            is Outcome.Success -> Outcome.success(Replaced(files, count, failed, walked.value, skipped))
        }
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
