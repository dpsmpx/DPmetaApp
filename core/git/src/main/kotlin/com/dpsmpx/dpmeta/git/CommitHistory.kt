package com.dpsmpx.dpmeta.git

import com.dpsmpx.dpai.core.clock.Timestamp

/**
 * История проекта из клона (D-66): дни с коммитами, записи журнала решений
 * по дням перемен и ветки впереди основной. Без имён авторов, сообщений и
 * путей. [complete] — клон полный (не shallow): история с первого коммита.
 */
data class CommitHistory(
    val first: String?,
    val days: List<Day>,
    val decisions: List<Pair<String, Int>>,
    val ahead: List<Ahead>,
    val complete: Boolean,
) {
    /**
     * День `ГГГГ-ММ-ДД`: [commits] — своих коммитов работы; из них [ai] и
     * [human]; [mainline] — сейчас в основной ветке, [branchOnly] — только в
     * ветках; [merges] — слияний PR в основную ветку.
     */
    data class Day(val date: String, val commits: Int, val ai: Int, val human: Int, val mainline: Int, val branchOnly: Int, val merges: Int)

    /** Ветка впереди основной: своих коммитов сверх неё и время последнего. */
    data class Ahead(val name: String, val commits: Int, val last: Timestamp)

    companion object {
        /** Основной ветки в клоне нет — истории нет (не «ноль коммитов», а пусто). */
        val EMPTY = CommitHistory(null, emptyList(), emptyList(), emptyList(), complete = false)
    }
}
