package com.dpsmpx.dpmeta.app.screens

/**
 * Построчный дифф для окна «что изменится» правки файла (F-69): общее начало и конец
 * отрезаются, середина — наибольшей общей подпоследовательностью строк. Если середина
 * слишком велика для таблицы ([MAX_CELLS]), она показывается блоком «убрано / добавлено»:
 * дифф грубее, но верен.
 */
object LineDiff {
    enum class Kind { SAME, ADD, DEL }

    data class Line(val kind: Kind, val text: String)

    /** Предел таблицы сравнения середины: ~4 МБ памяти. */
    const val MAX_CELLS: Long = 1_000_000

    fun diff(old: String, new: String): List<Line> {
        val a = old.split('\n')
        val b = new.split('\n')
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val am = a.subList(pre, a.size - suf)
        val bm = b.subList(pre, b.size - suf)
        val middle = if (am.size.toLong() * bm.size <= MAX_CELLS) lcs(am, bm) else am.map { Line(Kind.DEL, it) } + bm.map { Line(Kind.ADD, it) }
        return a.take(pre).map { Line(Kind.SAME, it) } + middle + a.takeLast(suf).map { Line(Kind.SAME, it) }
    }

    private fun lcs(a: List<String>, b: List<String>): List<Line> {
        val n = a.size
        val m = b.size
        // t[i][j] — длина общей подпоследовательности хвостов a[i..] и b[j..].
        val t = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            t[i][j] = if (a[i] == b[j]) t[i + 1][j + 1] + 1 else maxOf(t[i + 1][j], t[i][j + 1])
        }
        val out = ArrayList<Line>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += Line(Kind.SAME, a[i]); i++; j++ }
                t[i + 1][j] >= t[i][j + 1] -> { out += Line(Kind.DEL, a[i]); i++ }
                else -> { out += Line(Kind.ADD, b[j]); j++ }
            }
        }
        while (i < n) out += Line(Kind.DEL, a[i++])
        while (j < m) out += Line(Kind.ADD, b[j++])
        return out
    }

    /** Сколько строк добавлено и убрано. */
    fun stats(lines: List<Line>): Pair<Int, Int> = lines.count { it.kind == Kind.ADD } to lines.count { it.kind == Kind.DEL }

    /**
     * Для экрана: изменённые строки с [context] строками вокруг; пропуск — «…». Строка —
     * «+ », «- » или «  » и текст.
     */
    fun hunks(lines: List<Line>, context: Int = 2): List<String> {
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { i, l ->
            if (l.kind != Kind.SAME) for (k in maxOf(0, i - context)..minOf(lines.size - 1, i + context)) keep[k] = true
        }
        val out = ArrayList<String>()
        var gap = false
        lines.forEachIndexed { i, l ->
            if (keep[i]) {
                if (gap && out.isNotEmpty()) out += "…"
                gap = false
                out += when (l.kind) {
                    Kind.ADD -> "+ "
                    Kind.DEL -> "- "
                    Kind.SAME -> "  "
                } + l.text
            } else {
                gap = true
            }
        }
        return out
    }
}
