package com.dpsmpx.dpai.verification

/**
 * Выжимка вывода инструмента для модели (D-40, п. 3; `docs/EFFICIENCY.md`,
 * §5): вместо тысяч строк — ошибки, упавшие тесты, следы стека, итоговые
 * счётчики, предупреждения и края вывода, в исходном порядке, с пометкой,
 * сколько пропущено. Без модели и детерминированно (D-22): одинаковый
 * вывод — одинаковая выжимка. Ничего не выдумывается: каждая строка
 * выжимки — строка вывода (обрезанная по длине — с пометкой).
 */
object OutputDigest {
    /** Пределы выжимки: строк всего, длина строки, края, кадры стека после ошибки, ошибок. */
    data class Policy(
        val maxLines: Int = 80,
        val maxLineChars: Int = 400,
        val headLines: Int = 3,
        val tailLines: Int = 8,
        val framesPerTrace: Int = 6,
        val maxErrors: Int = 40,
        val maxWarnings: Int = 8,
    ) {
        init {
            require(maxLines in 10..10_000 && maxLineChars in 40..100_000) { "maxLines — 10…10000, maxLineChars — 40…100000" }
            require(headLines >= 0 && tailLines >= 0 && framesPerTrace >= 0 && maxErrors >= 1 && maxWarnings >= 0) { "пределы — не отрицательные" }
        }
    }

    /** Выжимка: строки, сколько было, сколько ошибок и предупреждений нашлось. */
    data class Digest(val lines: List<String>, val totalLines: Int, val errors: Int, val warnings: Int) {
        fun render(): String = lines.joinToString("\n")
    }

    private enum class Kind { ERROR, SUMMARY, FRAME, WARNING, PLAIN }

    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    private val ERROR = Regex(
        "(?i)(\\berror\\b|\\bexception\\b|\\bfailed\\b|\\bfailure\\b|\\bfatal\\b|\\bpanic\\b|assertion|expected:?\\s.*but was|^e: |^\\s*FAIL\\b|caused by:|✗)",
    )
    private val SUMMARY = Regex(
        "(?i)(\\d+\\s+tests?\\s+(completed|run|ran|passed|failed)|tests? run:|tests?:\\s+\\d+|BUILD (SUCCESSFUL|FAILED)|\\d+\\s+(passed|failed|errors?)\\b)",
    )
    private val FRAME = Regex("^(\\s+at [\\w.$<>/]+\\(.*\\)|\\s+\\.\\.\\. \\d+ more|\\s+File \".*\", line \\d+)")
    private val WARNING = Regex("(?i)(\\bwarn(ing)?\\b|^w: )")

    fun digest(raw: List<String>, policy: Policy = Policy()): Digest {
        val lines = raw.map { clean(it, policy.maxLineChars) }
        val kinds = lines.map(::kind)
        val keep = BooleanArray(lines.size)
        // Порядок важности: ошибки (первые и последние), итоговые счётчики, стек после ошибки, предупреждения, края.
        val errorIdx = kinds.indices.filter { kinds[it] == Kind.ERROR }
        val errorsKept = if (errorIdx.size <= policy.maxErrors) errorIdx else errorIdx.take(policy.maxErrors / 2) + errorIdx.takeLast(policy.maxErrors - policy.maxErrors / 2)
        val seen = HashSet<String>()
        var budget = policy.maxLines
        fun take(i: Int): Boolean {
            if (keep[i] || budget <= 0) return false
            // Повтор строки — один раз (счёт повторов — в пометке пропуска).
            if (kinds[i] != Kind.FRAME && !seen.add(lines[i].trim())) return false
            keep[i] = true
            budget--
            return true
        }
        for (i in errorsKept) take(i)
        kinds.indices.filter { kinds[it] == Kind.SUMMARY }.forEach(::take)
        for (i in errorsKept) {
            var j = i + 1
            var frames = 0
            while (j < lines.size && kinds[j] == Kind.FRAME && frames < policy.framesPerTrace) {
                take(j)
                frames++
                j++
            }
        }
        kinds.indices.filter { kinds[it] == Kind.WARNING }.take(policy.maxWarnings).forEach(::take)
        // Края — для обстановки; ошибки сверх предела через них не проходят (счёт «показано» точен).
        (lines.indices.reversed().take(policy.tailLines) + lines.indices.take(policy.headLines)).filter { kinds[it] != Kind.ERROR }.forEach(::take)
        val out = mutableListOf<String>()
        var skipped = 0
        for (i in lines.indices) {
            if (keep[i]) {
                if (skipped > 0) out += "… пропущено строк: $skipped"
                skipped = 0
                out += lines[i]
            } else {
                skipped++
            }
        }
        if (skipped > 0) out += "… пропущено строк: $skipped"
        if (errorIdx.size > errorsKept.size) out += "… ошибок всего: ${errorIdx.size}, показано: ${errorsKept.size}"
        return Digest(out, lines.size, errorIdx.size, kinds.count { it == Kind.WARNING })
    }

    private fun clean(line: String, max: Int): String {
        val s = ANSI.replace(line, "").trimEnd()
        return if (s.length <= max) s else s.take(max) + "…[+${s.length - max} знаков]"
    }

    private fun kind(line: String): Kind = when {
        FRAME.containsMatchIn(line) -> Kind.FRAME
        SUMMARY.containsMatchIn(line) -> Kind.SUMMARY
        ERROR.containsMatchIn(line) -> Kind.ERROR
        WARNING.containsMatchIn(line) -> Kind.WARNING
        else -> Kind.PLAIN
    }
}
