package com.dpsmpx.dpai.ui

/** Важность находки в выводе сборки. */
enum class ProblemSeverity(val title: String) { ERROR("ошибка"), WARNING("предупреждение"), NOTE("примечание") }

/**
 * Место в коде из вывода компилятора или проверки (DPmetaApp:D-118, D-153):
 * [path] — как его написал инструмент (абсолютный или от рабочей папки),
 * [line] и [column] — с единицы.
 */
data class BuildProblem(val path: String, val line: Int, val column: Int?, val severity: ProblemSeverity, val message: String)

/** Находки по порядку вывода; [limited] — их было больше предела. */
data class BuildProblemList(val items: List<BuildProblem>, val limited: Boolean) {
    val errors: Int get() = items.count { it.severity == ProblemSeverity.ERROR }
    val warnings: Int get() = items.count { it.severity == ProblemSeverity.WARNING }

    /** «ошибок: 2 · предупреждений: 1»; только примечания — «замечаний: N». */
    fun summary(): String = listOfNotNull(
        "ошибок: $errors".takeIf { errors > 0 },
        "предупреждений: $warnings".takeIf { warnings > 0 },
        "примечаний: ${items.size - errors - warnings}".takeIf { errors + warnings == 0 && items.isNotEmpty() },
    ).joinToString(" · ") + if (limited) " · показаны первые ${items.size}" else ""
}

/**
 * Разбор вывода сборки и проверок в места кода (DPmetaApp:D-118, D-153):
 * GCC/Clang, kotlinc и Gradle (`e:`/`w:`), javac, mypy/ruff/flake8, pytest,
 * трассировки Python, MSVC, CMake, rustc. Строка, в которой нет файла и
 * номера строки, находкой не становится: лучше пропустить, чем отправить
 * человека не туда. Цвета терминала и `\r` убираются до разбора.
 */
object BuildProblems {
    const val MAX_PROBLEMS: Int = 500
    private const val MAX_MESSAGE: Int = 400
    private const val MAX_FRAMES: Int = 10

    private const val EXT = """\.[A-Za-z0-9+_-]{1,10}"""
    private const val FILE = """(?:[A-Za-z]:[\\/])?[^\s:"'(][^:"'()]*?$EXT"""
    private val SEVERITY = "(fatal error|error|warning|note|remark|info|hint)"

    /** `path:12:5: error: текст`, `path:12: warning: текст`, `path:1:8: F401 текст`. */
    private val GNU = Regex("""^($FILE):(\d+)(?::(\d+))?:\s*(?:$SEVERITY\s*(?:\[[^\]]*])?\s*:\s*)?(.*)$""")

    /** Gradle/kotlinc: `e: file:///p/A.kt:12:5 текст`, `w: /p/A.kt: (12, 5): текст`. */
    private val KOTLIN_NEW = Regex("""^([ew]): (?:file://)?(/[^:]+?\.kts?|[A-Za-z]:[\\/][^:]+?\.kts?):(\d+):(\d+) (.*)$""")
    private val KOTLIN_OLD = Regex("""^([ew]): (.+?\.kts?): \((\d+), (\d+)\): (.*)$""")

    /** MSVC и C#: `path(12,5): error C2065: текст`. */
    private val MSVC = Regex("""^($FILE)\((\d+)(?:,(\d+))?\)\s*:\s*(fatal error|error|warning|note)\s*([A-Z]{1,4}\d+)?\s*:\s*(.*)$""")

    /** Трассировка Python: кадр и строка исключения. */
    private val PY_FRAME = Regex("""^\s*File "([^"]+)", line (\d+)(?:, in .*)?$""")
    private val PY_EXCEPTION = Regex("""^([A-Za-z_][\w.]*(?:Error|Exception|Warning|Exit|Interrupt|Iteration)|KeyboardInterrupt|StopIteration)(?::\s*(.*))?$""")

    /** rustc и похожие: заголовок `error[E0425]: текст`, затем `--> path:2:5`. */
    private val RUST_HEAD = Regex("""^(error|warning)(?:\[[A-Za-z0-9]+])?: (.+)$""")
    private val RUST_AT = Regex("""^\s*--> ($FILE):(\d+):(\d+)$""")

    /** CMake: `CMake Error at CMakeLists.txt:12 (add_executable):`, текст — следующая строка с отступом. */
    private val CMAKE = Regex("""^CMake (Error|Warning|Deprecation Warning)(?: \(dev\))? at ($FILE):(\d+) \((.+)\):$""")

    /** Строка pytest без важности: `tests/test_a.py:12: AssertionError`. */
    private val EXCEPTION_NAME = Regex("""^[A-Z]\w*(?:Error|Exception)\b""")

    /** Без важности строку `path:1:8: …` принимаем только с кодом правила (`F401`, `E501`): иначе это не находка. */
    private val RULE_CODE = Regex("""^[A-Z]{1,4}\d{2,5}\b""")

    fun parse(output: String, limit: Int = MAX_PROBLEMS): BuildProblemList {
        val lines = CppDependencies.plain(output).split('\n').map { it.trimEnd('\r') }
        val out = LinkedHashSet<BuildProblem>()
        var limited = false
        fun add(found: BuildProblem) {
            if (found.line <= 0) return
            val p = found.copy(message = found.message.trim().take(MAX_MESSAGE))
            if (p in out) return
            if (out.size >= limit) {
                limited = true
                return
            }
            out += p
        }
        val frames = ArrayList<Pair<String, Int>>()
        var rustHead: Pair<ProblemSeverity, String>? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            // Трассировка Python: кадры копятся до строки исключения.
            PY_FRAME.matchEntire(line)?.let { m ->
                val path = m.groupValues[1]
                if (!path.startsWith("<")) frames += path to (m.groupValues[2].toIntOrNull() ?: 0)
                i++
                continue
            }
            if (frames.isNotEmpty()) {
                val exc = PY_EXCEPTION.matchEntire(line)
                if (exc != null) {
                    val text = listOf(exc.groupValues[1], exc.groupValues[2]).filter { it.isNotEmpty() }.joinToString(": ")
                    val shown = frames.takeLast(MAX_FRAMES).asReversed()
                    shown.forEachIndexed { n, (path, ln) ->
                        add(BuildProblem(path, ln, null, if (n == 0) ProblemSeverity.ERROR else ProblemSeverity.NOTE, if (n == 0) text else "вызвано отсюда: $text"))
                    }
                    frames.clear()
                    i++
                    continue
                }
                // Строки кода и «^» между кадром и исключением — часть трассировки.
                if (line.startsWith(" ") || line.startsWith("\t") || line.isBlank()) {
                    i++
                    continue
                }
                frames.clear()
            }
            CMAKE.matchEntire(line)?.let { m ->
                val detail = lines.getOrNull(i + 1)?.takeIf { it.startsWith("  ") }?.trim().orEmpty()
                val severity = if (m.groupValues[1] == "Error") ProblemSeverity.ERROR else ProblemSeverity.WARNING
                add(BuildProblem(m.groupValues[2], m.groupValues[3].toInt(), null, severity, listOf("${m.groupValues[4]}:", detail).joinToString(" ").trim()))
                i++
                continue
            }
            RUST_HEAD.matchEntire(line)?.let { m ->
                rustHead = (if (m.groupValues[1] == "error") ProblemSeverity.ERROR else ProblemSeverity.WARNING) to m.groupValues[2]
                i++
                continue
            }
            RUST_AT.matchEntire(line)?.let { m ->
                val head = rustHead
                if (head != null) {
                    add(BuildProblem(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(), head.first, head.second))
                    rustHead = null
                }
                i++
                continue
            }
            (KOTLIN_NEW.matchEntire(line) ?: KOTLIN_OLD.matchEntire(line))?.let { m ->
                val path = m.groupValues[2].let { if (line.contains("file://")) percentDecode(it) else it }
                add(BuildProblem(path, m.groupValues[3].toInt(), m.groupValues[4].toInt(), if (m.groupValues[1] == "e") ProblemSeverity.ERROR else ProblemSeverity.WARNING, m.groupValues[5]))
                i++
                continue
            }
            MSVC.matchEntire(line)?.let { m ->
                val code = m.groupValues[5].takeIf { it.isNotEmpty() }?.let { "$it: " }.orEmpty()
                add(BuildProblem(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull(), severity(m.groupValues[4]), code + m.groupValues[6]))
                i++
                continue
            }
            GNU.matchEntire(line)?.let { m ->
                val sev = m.groupValues[4]
                val text = m.groupValues[5]
                val severity = when {
                    sev.isNotEmpty() -> severity(sev)
                    RULE_CODE.containsMatchIn(text) -> ProblemSeverity.WARNING
                    EXCEPTION_NAME.containsMatchIn(text) -> ProblemSeverity.ERROR
                    else -> null
                }
                if (severity != null) add(BuildProblem(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull(), severity, text))
            }
            i++
        }
        return BuildProblemList(out.toList(), limited)
    }

    private fun severity(word: String): ProblemSeverity = when (word) {
        "fatal error", "error" -> ProblemSeverity.ERROR
        "warning" -> ProblemSeverity.WARNING
        else -> ProblemSeverity.NOTE
    }

    /**
     * Путь находки относительно корня дерева [root] (путь корня на машине,
     * где шла сборка); относительный путь находки — от рабочей папки [cwd]
     * (по умолчанию — корень). Файл вне корня, `..` выше корня, пустые и
     * управляющие знаки в имени — `null`: такой файл в дереве не открыть.
     * Пути Windows сравниваются без учёта регистра.
     */
    fun relative(path: String, root: String, cwd: String = root): String? {
        val p = slashes(path.removePrefix("file://").let { if (path.startsWith("file://")) percentDecode(it) else it })
        val r = slashes(root).trimEnd('/')
        val windows = DRIVE.containsMatchIn(r)
        val absolute = p.startsWith("/") || DRIVE.containsMatchIn(p)
        val full = if (absolute) p else slashes(cwd).trimEnd('/') + "/" + p
        val inside = when {
            r.isEmpty() -> full.removePrefix("/")
            full.startsWith("$r/", ignoreCase = windows) -> full.substring(r.length + 1)
            else -> return null
        }
        val parts = ArrayList<String>()
        for (s in inside.split('/')) {
            when (s) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> {
                    if (s.any { it.isISOControl() } || s.contains(':') || s.contains('\\')) return null
                    parts += s
                }
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    private val DRIVE = Regex("^[A-Za-z]:/")

    private fun slashes(p: String): String = p.replace('\\', '/')

    /** `%20` и другие байты UTF-8 из адреса `file://`; битое кодирование — как есть. */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return s
                bytes.write(v)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
