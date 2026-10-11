package com.dpsmpx.dpai.orchestrator.git

/**
 * Изменения задачи (D-42, п. 3): что принесёт слияние ветки задачи в ветку
 * интеграции и что в её дереве ещё не закоммичено. Смотреть — не
 * действовать: это чтение Git на target, без команды и события в журнале
 * (команда с событием — у действий, D-42).
 */
data class TaskChanges(
    /** Ветка задачи `task/<id>`. */
    val branch: String,
    /** Ветка интеграции проекта; `null` — у проекта её нет, сравнивать не с чем. */
    val base: String?,
    /** `git diff --stat --patch <база>...<ветка>`; `null` — ветки ещё нет или нет базы. */
    val committed: List<String>?,
    /** `git diff` в дереве задачи; `null` — дерева нет (не создано или убрано после слияния, A-65). */
    val uncommitted: List<String>?,
    /** Новые файлы дерева: в `git diff` их нет, пока они не добавлены. */
    val untracked: List<String>,
    /** Часть вывода не показана: больше [MAX_LINES] строк в части. */
    val truncated: Boolean,
) {
    /** Изменений нет — ни в ветке, ни в дереве. */
    val empty: Boolean get() = committed.isNullOrEmpty() && uncommitted.isNullOrEmpty() && untracked.isEmpty()

    companion object {
        /** Предел строк на часть: вывод Git — в памяти экрана телефона. */
        const val MAX_LINES = 4000

        /** Первые [MAX_LINES] строк и признак, что были ещё. */
        fun cap(lines: List<String>): Pair<List<String>, Boolean> =
            if (lines.size <= MAX_LINES) lines to false else lines.take(MAX_LINES) to true
    }
}
