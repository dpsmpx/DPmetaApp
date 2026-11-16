package com.dpsmpx.dpai.orchestrator.linux

/**
 * Сравнение версий пакетов Alpine — как `apk version -t` apk-tools 3 (D-191). Запись:
 * числа через `.`, одна буква `a`…`z`, суффиксы `_alpha` `_beta` `_pre` `_rc` (раньше
 * выпуска) и `_cvs` `_svn` `_git` `_hg` `_p` (после) с номером или без, хеш `~<0-9a-f>`,
 * ревизия `-rN`. Правила сверены с apk-tools 3.0.8 на корпусе пар (`AlpineVersionTest`):
 * первое число и номера — числами; следующая часть с нулём в начале — строкой (`1.05` <
 * `1.5`, `2.0` < `2.00`); при равном начале дальше решает, что идёт следом: число > буква
 * > суффикс > номер суффикса > хеш > ревизия > конец, но суффикс раньше выпуска — меньше
 * конца. Неверная запись — `null`: смысл не угадывается (D-05).
 */
object AlpineVersion {
    private const val DIGIT = 0
    private const val LETTER = 1
    private const val SUFFIX = 2
    private const val SUFFIX_NO = 3
    private const val HASH = 4
    private const val REVISION = 5
    private const val END = 6

    private val PRE = listOf("alpha", "beta", "pre", "rc")
    private val POST = listOf("cvs", "svn", "git", "hg", "p")

    /** Длиннее — не версия пакета. */
    private const val MAX_LENGTH = 128

    private class Token(val type: Int, val text: String, val first: Boolean = false, val rank: Int = 0)

    /** Части записи по грамматике apk; неверная — `null`. */
    private fun tokens(v: String): List<Token>? {
        if (v.isEmpty() || v.length > MAX_LENGTH) return null
        val out = mutableListOf<Token>()
        var i = 0
        fun digits(): String {
            val start = i
            while (i < v.length && v[i] in '0'..'9') i++
            return v.substring(start, i)
        }
        val head = digits().ifEmpty { return null }
        out += Token(DIGIT, head, first = true)
        while (i < v.length && v[i] == '.') {
            i++
            out += Token(DIGIT, digits().ifEmpty { return null })
        }
        if (i < v.length && v[i] in 'a'..'z') out += Token(LETTER, v[i++].toString())
        while (i < v.length && v[i] == '_') {
            i++
            val pre = PRE.indexOfFirst { v.startsWith(it, i) }
            val post = if (pre >= 0) -1 else POST.indexOfFirst { v.startsWith(it, i) }
            val (name, rank) = when {
                pre >= 0 -> PRE[pre] to pre - PRE.size
                post >= 0 -> POST[post] to post
                else -> return null
            }
            i += name.length
            out += Token(SUFFIX, name, rank = rank)
            digits().takeIf { it.isNotEmpty() }?.let { out += Token(SUFFIX_NO, it) }
        }
        if (i < v.length && v[i] == '~') {
            i++
            val start = i
            while (i < v.length && (v[i] in '0'..'9' || v[i] in 'a'..'f')) i++
            if (i == start) return null
            out += Token(HASH, v.substring(start, i))
        }
        if (v.startsWith("-r", i)) {
            i += 2
            out += Token(REVISION, digits().ifEmpty { return null })
        }
        return if (i == v.length) out else null
    }

    private fun numeric(a: String, b: String): Int {
        val x = a.trimStart('0')
        val y = b.trimStart('0')
        return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
    }

    private fun value(a: Token, b: Token): Int = when (a.type) {
        DIGIT -> if (a.first || !a.text.startsWith('0') && !b.text.startsWith('0')) numeric(a.text, b.text) else a.text.compareTo(b.text)
        SUFFIX -> a.rank.compareTo(b.rank)
        SUFFIX_NO, REVISION -> numeric(a.text, b.text)
        else -> a.text.compareTo(b.text)
    }.coerceIn(-1, 1)

    /** Запись — версия Alpine. */
    fun valid(v: String): Boolean = tokens(v) != null

    /** −1, 0, 1 — как `apk version -t`; неверная запись — `null`. [fuzzy] — `~`: версия [b] — начало [a]. */
    fun compare(a: String, b: String, fuzzy: Boolean = false): Int? {
        val x = tokens(a) ?: return null
        val y = tokens(b) ?: return null
        var i = 0
        while (true) {
            val ta = x.getOrNull(i)?.type ?: END
            val tb = y.getOrNull(i)?.type ?: END
            if (ta != tb) {
                if (fuzzy && tb == END) return 0
                if (ta == SUFFIX && x[i].rank < 0) return -1
                if (tb == SUFFIX && y[i].rank < 0) return 1
                return if (ta > tb) -1 else 1
            }
            if (ta == END) return 0
            value(x[i], y[i]).let { if (it != 0) return it }
            i++
        }
    }

    /** Версия [version] подходит под ограничение `op` и [wanted]; неизвестный знак или запись — `null`. */
    fun satisfies(version: String, op: String, wanted: String): Boolean? {
        val fuzzy = op == "~" || op == "=~" || op == "~="
        val r = compare(version, wanted, fuzzy) ?: return null
        return when (op) {
            "=", "~", "=~", "~=" -> r == 0
            "<" -> r < 0
            "<=", "=<" -> r <= 0
            ">" -> r > 0
            ">=", "=>" -> r >= 0
            else -> null
        }
    }
}
