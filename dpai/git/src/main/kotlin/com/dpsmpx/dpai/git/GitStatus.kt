package com.dpsmpx.dpai.git

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Состояние дерева по `git status --porcelain=v2 --branch` (ТЗ, §30):
 * ветка, коммит, отставание и опережение (неизвестны без upstream — D-13),
 * изменённые, неотслеживаемые и конфликтные файлы.
 */
data class GitStatus(
    val branch: String?,
    val head: String?,
    val ahead: Measure<Int>,
    val behind: Measure<Int>,
    val changed: List<String>,
    val untracked: List<String>,
    val conflicted: List<String>,
) {
    val clean: Boolean get() = changed.isEmpty() && untracked.isEmpty() && conflicted.isEmpty()

    companion object {
        /** Строгий разбор: незнакомая строка — `GIT_OUTPUT_INVALID`, а не пропуск (D-05). */
        fun parse(lines: List<String>): Outcome<GitStatus> {
            var branch: String? = null
            var head: String? = null
            var ahead: Measure<Int> = Measure.unknown("нет upstream")
            var behind: Measure<Int> = Measure.unknown("нет upstream")
            val changed = mutableListOf<String>()
            val untracked = mutableListOf<String>()
            val conflicted = mutableListOf<String>()
            for ((i, l) in lines.withIndex()) {
                if (l.isEmpty()) continue
                when {
                    l.startsWith("# branch.oid ") -> head = l.removePrefix("# branch.oid ").takeIf { it != "(initial)" }
                    l.startsWith("# branch.head ") -> branch = l.removePrefix("# branch.head ").takeIf { it != "(detached)" }
                    l.startsWith("# branch.upstream ") -> Unit
                    l.startsWith("# branch.ab ") -> {
                        val m = Regex("""\+(\d+) -(\d+)""").find(l) ?: return bad(i, l)
                        ahead = Measure.observed(m.groupValues[1].toInt())
                        behind = Measure.observed(m.groupValues[2].toInt())
                    }
                    l.startsWith("# ") -> Unit
                    l.startsWith("1 ") -> changed += l.split(' ', limit = 9).getOrNull(8) ?: return bad(i, l)
                    l.startsWith("2 ") -> changed += l.split(' ', limit = 10).getOrNull(9)?.substringBefore('\t') ?: return bad(i, l)
                    l.startsWith("u ") -> conflicted += l.split(' ', limit = 11).getOrNull(10) ?: return bad(i, l)
                    l.startsWith("? ") -> untracked += l.removePrefix("? ")
                    l.startsWith("! ") -> Unit
                    else -> return bad(i, l)
                }
            }
            return Outcome.success(GitStatus(branch, head, ahead, behind, changed, untracked, conflicted))
        }

        private fun bad(i: Int, l: String) = Outcome.failure(DiagCode.GIT_OUTPUT_INVALID, "строка ${i + 1} не похожа на porcelain v2: ${l.take(80)}", "git status")
    }
}
