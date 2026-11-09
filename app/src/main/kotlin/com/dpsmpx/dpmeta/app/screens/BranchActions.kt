package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpmeta.actions.ActionCommand
import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpmeta.actions.ActionTarget
import com.dpsmpx.dpmeta.github.BranchDetail
import com.dpsmpx.dpmeta.github.Comparison
import com.dpsmpx.dpmeta.model.RepoRef

/**
 * Ветки в пульте (F-14; D-36, D-37): влита ли ветка в основную, можно ли её удалить и
 * какой ценой. Влитая — уровень 2 (окно и PIN), невлитая — уровень 3 (красный экран
 * «что будет потеряно» и ввод имени ветки, без биометрии). Чистый Kotlin.
 */
object BranchActions {
    /** Ветка на экране: репозиторий, основная ветка, голова и сравнение с основной (у основной — `null`). */
    data class View(val repo: RepoRef, val base: String, val branch: BranchDetail, val comparison: Comparison?) {
        val merged: Boolean get() = comparison?.merged == true
    }

    fun lines(v: View): List<String> = buildList {
        add("Основная ветка: ${v.base}")
        val c = v.comparison
        when {
            c == null -> add("Это основная ветка")
            c.merged -> add("Влита в ${v.base}" + if (c.behindBy > 0) "; ${v.base} ушла вперёд на ${c.behindBy}" else "")
            else -> add("Не влита: впереди ${v.base} на ${c.aheadBy}, позади на ${c.behindBy}")
        }
        add("Последний коммит: ${v.branch.sha.take(7)}")
        if (v.branch.protected) add("Защищена правилами ветки")
    }

    /** Почему удалить нельзя; `null` — можно (ценой своего уровня). */
    fun deleteBlocked(v: View): String? = when {
        v.comparison == null || v.branch.name == v.base -> "основную ветку пульт не удаляет"
        v.branch.protected -> "ветка защищена правилами — GitHub её не удалит"
        else -> null
    }

    /**
     * Команда удаления: влитая — [ActionKind.BRANCH_DELETE_MERGED] (уровень 2), иначе —
     * [ActionKind.BRANCH_DELETE_UNMERGED] (уровень 3). Голова — в параметрах: исполнитель
     * сверит, что удаляется та ветка, что была на экране, а журнал сохранит её SHA.
     */
    fun delete(v: View): ActionCommand = ActionCommand(
        if (v.merged) ActionKind.BRANCH_DELETE_MERGED else ActionKind.BRANCH_DELETE_UNMERGED,
        ActionTarget(v.repo, v.branch.name),
        mapOf("head" to v.branch.sha, "base" to v.base, "ahead" to "${v.comparison?.aheadBy ?: 0}"),
    )

    /** Окно «что изменится» (уровень 2). [openPulls] — номера открытых PR из этой ветки. */
    fun preview(v: View, openPulls: List<Long>): List<String> = listOf(
        "Удалить ветку ${v.branch.name} в ${v.repo}.",
        "Все её коммиты уже в ${v.base} — работа не теряется.",
        "Последний коммит ${v.branch.sha.take(7)} — SHA останется в журнале действий.",
    ) + pulls(openPulls)

    /** Красный экран уровня 3 (D-37, F-70): что будет потеряно безвозвратно. */
    fun losses(v: View, openPulls: List<Long>): List<String> = listOf(
        "Коммитов, которых нет в ${v.base}: ${v.comparison?.aheadBy ?: "?"} — из ветки они пропадут.",
        "Вернуть ветку можно только по SHA последнего коммита: ${v.branch.sha} (он будет в журнале действий), пока GitHub хранит эти коммиты.",
    ) + pulls(openPulls)

    private fun pulls(open: List<Long>): List<String> =
        if (open.isEmpty()) emptyList() else listOf("Открытые PR из этой ветки GitHub закроет: " + open.joinToString(", ") { "#$it" } + ".")
}
