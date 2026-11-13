package com.dpsmpx.dpai.contexts

import com.dpsmpx.dpai.core.diagnostics.Redactor

/**
 * Карта кода области задачи (D-171; `DPmetaApp:ROADMAP`, этап 4 — «контекст ИИ по
 * структуре кода»): файлы, их объявления (классы, функции, типы) со строками и
 * зависимости (импорты) — из вывода `git grep -n` по словам [WORDS]. Без
 * компилятора и служб языка: строка отбирается регулярным выражением, вложенность —
 * до одного уровня отступа. Детерминирована (D-22): тот же вывод — тот же текст.
 */
object CodeMap {
    /** Слова, по которым `git grep -w -E` отбирает строки-кандидаты на target. */
    const val WORDS = "(class|interface|object|enum|struct|fun|def|func|fn|function|trait|impl|namespace|typealias|record|import|from|include|use)"

    /** Пределы карты: файлов, объявлений на файл, импортов на файл, знаков текста. */
    data class Limits(val maxFiles: Int = 80, val maxDeclarations: Int = 25, val maxImports: Int = 6, val maxChars: Int = 8_000) {
        init {
            require(maxFiles > 0 && maxDeclarations > 0 && maxImports >= 0 && maxChars >= 200) { "пределы карты кода — положительные" }
        }
    }

    private val MODIFIERS = "(?:(?:export|public|private|protected|internal|static|abstract|final|open|override|data|sealed|inline|suspend|async|const|default|expect|actual|annotation|enum|value|pub(?:\\([^)]*\\))?|unsafe|extern|virtual|partial|fileprivate)\\s+)*"
    private val DECLARATION = Regex("^(\\s{0,4}|\\t?)$MODIFIERS(class|interface|object|enum|struct|fun|def|func|fn|function|trait|impl|namespace|typealias|record)\\b[\\s(<]+\\S")
    private val IMPORT = Regex("^\\s*(?:import\\s+(?:static\\s+)?([\\w.*{}, $/@'\"-]+)|from\\s+([\\w.]+)\\s+import\\b|#\\s*include\\s*[<\"]([^>\"]+)[>\"]|use\\s+([\\w:]+))")
    private val SOURCE = setOf("kt", "kts", "java", "scala", "swift", "py", "pyi", "go", "rs", "c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx", "m", "mm", "ts", "tsx", "js", "jsx", "mjs", "cs", "dart", "rb", "php")

    /** Строка `git grep -n --full-name`: путь, номер, текст; путь с `:` не разбирается — мимо. */
    private val LINE = Regex("^([^:]+):(\\d+):(.*)$")

    private class FileMap(val declarations: MutableList<Pair<Int, String>> = mutableListOf(), val imports: MutableList<Pair<Int, String>> = mutableListOf()) {
        /** Импорты в порядке строк файла, без повторов. */
        fun importNames(): List<String> = imports.sortedBy { it.first }.map { it.second }.distinct()
    }

    /**
     * Текст карты по строкам [grep]; [truncated] — вывод на target обрезан пределом.
     * Ничего не отобрано — `null`.
     */
    fun render(grep: List<String>, truncated: Boolean, limits: Limits = Limits()): String? {
        val files = sortedMapOf<String, FileMap>()
        for (raw in grep) {
            val m = LINE.matchEntire(raw) ?: continue
            val (path, num, text) = m.destructured
            if (path.substringAfterLast('.', "").lowercase() !in SOURCE) continue
            val line = num.toIntOrNull() ?: continue
            val f = files.getOrPut(path) { FileMap() }
            val imp = IMPORT.find(text)
            if (imp != null && !DECLARATION.containsMatchIn(text)) {
                val what = imp.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()?.trimEnd(';')?.take(80)
                if (!what.isNullOrEmpty()) f.imports += line to what
                continue
            }
            if (DECLARATION.containsMatchIn(text)) f.declarations += line to declaration(text)
        }
        val shown = files.filterValues { it.declarations.isNotEmpty() || it.imports.isNotEmpty() }
        if (shown.isEmpty()) return null
        val out = StringBuilder()
        var left = 0
        for ((index, entry) in shown.entries.withIndex()) {
            if (index >= limits.maxFiles) { left = shown.size - index; break }
            val (path, f) = entry
            val imports = f.importNames()
            val lines = ArrayList<String>()
            lines += buildString {
                append(path)
                if (imports.isNotEmpty() && limits.maxImports > 0) {
                    append(" — импорты: ").append(imports.take(limits.maxImports).joinToString(", "))
                    if (imports.size > limits.maxImports) append(" (+").append(imports.size - limits.maxImports).append(')')
                }
            }
            val decls = f.declarations.sortedBy { it.first }
            for ((line, text) in decls.take(limits.maxDeclarations)) lines += "  $line: $text"
            if (decls.size > limits.maxDeclarations) lines += "  … ещё ${decls.size - limits.maxDeclarations}"
            // Файл целиком не влез: первый — сколько влезет, следующие — уже нет.
            val size = lines.sumOf { it.length + 1 }
            if (out.length + size > limits.maxChars) {
                var partial = false
                if (out.isEmpty()) {
                    for (l in lines) { if (out.length + l.length + 1 > limits.maxChars) break; out.append(l).append('\n') }
                    partial = out.isNotEmpty()
                }
                if (partial) out.append("  … файл не вошёл целиком\n")
                left = shown.size - index - (if (partial) 1 else 0)
                break
            }
            for (l in lines) out.append(l).append('\n')
        }
        if (out.isEmpty()) return null
        if (left > 0) out.append("… ещё файлов: ").append(left).append('\n')
        if (truncated) out.append("… вывод на target обрезан: карта неполная\n")
        // Значения констант и строки отрезаны выше; известные виды ключей — ещё и редактором.
        return Redactor.STANDARD.redact(out.toString().trimEnd())
    }

    /**
     * Объявление без тела и значения: до `{` или ` =` вне скобок (значение по умолчанию
     * параметра остаётся), без `:` в конце, не длиннее 100 знаков; вложенное — с отступом.
     */
    private fun declaration(text: String): String {
        val indent = if (text.firstOrNull()?.isWhitespace() == true) "  " else ""
        val t = text.trim()
        var depth = 0
        var end = t.length
        for (i in t.indices) {
            when (t[i]) {
                '(', '[', '<' -> depth++
                ')', ']', '>' -> if (depth > 0) depth--
                '{' -> if (depth == 0) { end = i; break }
                '=' -> if (depth == 0 && i > 0 && t[i - 1] == ' ' && t.getOrNull(i + 1) != '=' && t.getOrNull(i + 1) != '>') { end = i; break }
            }
        }
        var d = t.substring(0, end).trimEnd().removeSuffix(":").trimEnd()
        if (d.length > 100) d = d.take(99) + "…"
        return indent + d
    }
}
