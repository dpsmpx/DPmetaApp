package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpmeta.model.RepoRef

/**
 * Документ проекта пульта в IDE (D-177): по клону и рабочему проекту — открыть в редакторе,
 * спросить один раз «Сделать рабочим проектом IDE?» или читать в пульте с кнопкой «В IDE…».
 */
object IdeOpen {
    /** Как проект может открыть документ в IDE. */
    sealed interface Access {
        /** IDE нет или проекта нет в списке пульта — только чтение в пульте. */
        data object Unavailable : Access

        /** Рабочий проект IDE [projectId] на клоне — документ открывается в редакторе. */
        data class Working(val projectId: String) : Access

        /** Клон [guestRoot] есть, рабочего проекта нет; [declined] — «Нет» уже было. */
        data class CloneOnly(val guestRoot: String, val declined: Boolean) : Access

        /** Клона нет — чтение с GitHub и «Клонировать и открыть в IDE…». */
        data class NoClone(val repo: RepoRef) : Access
    }

    /** Что делает касание документа в карточке. */
    enum class Tap { IDE, ASK, READ }

    /** Клон пульта в госте (D-111, D-113): `/root/projects/<владелец>/<имя>`. */
    fun guestRoot(repo: RepoRef): String = "${CommandActions.GUEST_ROOT}/$repo"

    fun access(ide: Boolean, repo: RepoRef?, cloned: Boolean, working: String?, declined: Boolean): Access = when {
        !ide || repo == null -> Access.Unavailable
        working != null -> Access.Working(working)
        cloned -> Access.CloneOnly(guestRoot(repo), declined)
        else -> Access.NoClone(repo)
    }

    /** Касание: рабочий проект — в редактор; клон без ответа — спросить; иначе — читать в пульте. */
    fun tap(a: Access): Tap = when (a) {
        is Access.Working -> Tap.IDE
        is Access.CloneOnly -> if (a.declined) Tap.READ else Tap.ASK
        Access.Unavailable, is Access.NoClone -> Tap.READ
    }

    /** Кнопка экрана чтения: `null` — без IDE. */
    fun readerButton(a: Access): String? = when (a) {
        Access.Unavailable -> null
        is Access.Working -> "Открыть в IDE"
        is Access.CloneOnly -> "В IDE…"
        is Access.NoClone -> "Клонировать и открыть в IDE…"
    }

    /** Ветка интеграции нового рабочего проекта — текущая ветка клона; отделённая голова — `main`. */
    fun branch(cloneBranch: String?): String = cloneBranch?.takeIf { it.isNotBlank() } ?: "main"

    /** Окно согласия: клон есть — рабочий проект; клона нет — клон и рабочий проект одним согласием. */
    fun askText(a: Access): String? = when (a) {
        is Access.CloneOnly -> "Клон ${a.guestRoot.removePrefix("/root/")} станет рабочим проектом IDE на телефоне: у него появятся сборка, проверки и задачи ИИ. Файл откроется в редакторе."
        is Access.NoClone -> "Репозиторий ${a.repo} будет клонирован в ~/projects/${a.repo} (трафик и место на телефоне) и станет рабочим проектом IDE. После клона файл откроется в редакторе."
        else -> null
    }
}
