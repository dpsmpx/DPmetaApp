package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.orchestrator.git.TaskDiff

/**
 * Описание PR при «Отправить ветку» — по шаблону «Сделано / Осталось / Проверки» из фактов
 * задачи, без ИИ (D-189; `DPmetaApp:D-168`, «меньше ИИ», шаг 3). Человек правит текст перед
 * отправкой. Чего приложение не знает — так и сказано, а не придумано (D-13).
 */
object PrTemplate {
    const val MAX_FILES = 20
    const val MAX_OBJECTIVE = 1500
    const val MAX_BODY = 20_000

    fun body(task: String, objective: String, branch: String, diff: TaskDiff?, reviews: List<String> = emptyList()): String = buildString {
        objective.trim().takeIf { it.isNotEmpty() }?.let { appendLine(it.take(MAX_OBJECTIVE)); appendLine() }
        appendLine("## Сделано")
        appendLine()
        if (diff == null) {
            appendLine("- изменения задачи не прочитаны — впишите сами")
        } else {
            val added = diff.files.sumOf { it.added ?: 0 }
            val removed = diff.files.sumOf { it.removed ?: 0 }
            val binary = diff.files.count { it.added == null || it.removed == null }
            appendLine("- Изменено файлов: ${diff.files.size} (+$added −$removed" + (if (binary > 0) ", двоичных $binary" else "") + ")" +
                (if (diff.untracked.isNotEmpty()) "; новых вне индекса: ${diff.untracked.size}" else ""))
            for (f in diff.files.take(MAX_FILES)) {
                appendLine("  - `${f.path}`" + if (f.added != null && f.removed != null) " (+${f.added} −${f.removed})" else " (двоичный)")
            }
            if (diff.files.size > MAX_FILES) appendLine("  - …и ещё ${diff.files.size - MAX_FILES}")
        }
        appendLine()
        appendLine("## Осталось")
        appendLine()
        appendLine("- впишите или удалите раздел")
        appendLine()
        appendLine("## Проверки")
        appendLine()
        if (reviews.isEmpty()) appendLine("- итог проверок не известен приложению — впишите («Проверки» проекта или `full --strict`)")
        else for (r in reviews) appendLine("- $r")
        appendLine()
        append("Задача $task контура «AI», ветка $branch.")
    }.take(MAX_BODY)
}
