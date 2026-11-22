package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.lsp.DocumentUri
import com.dpsmpx.dpai.lsp.TextEdit
import com.dpsmpx.dpai.lsp.TextEdits
import com.dpsmpx.dpai.lsp.WorkspaceEdit

/** Наблюдаемый текст и версия открытого в службе документа; у закрытого версия неизвестна. */
data class WorkspaceSource(val text: String, val version: Int? = null)
data class WorkspaceTextChange(val path: String, val uri: String, val before: String, val after: String, val edits: List<TextEdit>)
data class WorkspaceEditPlan(val files: List<WorkspaceTextChange>, val index: com.dpsmpx.dpai.lsp.ProjectIndex.Lease? = null) {
    val editCount: Int get() = files.sumOf { it.edits.size }
}

/**
 * Подготовка всего набора LSP для предпросмотра. Ничего не пишет;
 * сначала проверяет все адреса, затем версии и непересекающиеся диапазоны.
 * Сохранение отдельно сверяет исходные байты под блокировкой проекта.
 */
object WorkspaceEdits {
    const val MAX_FILES = 128
    const val MAX_BYTES = 8L * 1024 * 1024
    private const val MAX_EDITS = WorkspaceEdit.MAX_EDITS

    fun paths(edit: WorkspaceEdit, root: String): Outcome<Map<String, String>> {
        val base = root.trimEnd('/').ifEmpty { "/" }
        if (!base.startsWith('/') || base != "/" && !FileNames.pathValid(base.drop(1))) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "корень языковой службы недопустим")
        }
        val changed = edit.changes.filterValues { it.isNotEmpty() }
        if (changed.size > MAX_FILES || changed.values.sumOf { it.size.toLong() } > MAX_EDITS) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "слишком большой набор правок; ничего не изменено")
        }
        val prefix = if (base == "/") "/" else "$base/"
        val paths = linkedMapOf<String, String>()
        val seen = hashSetOf<String>()
        for (uri in changed.keys) {
            val absolute = if ('?' in uri || '#' in uri) null else DocumentUri.toPath(uri)
            val relative = absolute?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
            if (relative == null || !FileNames.pathValid(relative) || relative.split('/').any { it.equals(".git", ignoreCase = true) }) {
                return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "правка выходит за разрешённые файлы проекта; ничего не изменено")
            }
            if (!seen.add(relative)) return Outcome.failure(DiagCode.VALUE_INVALID, "разные адреса обозначают один файл; ничего не изменено", relative)
            paths[uri] = relative
        }
        return Outcome.success(paths)
    }

    fun prepare(edit: WorkspaceEdit, root: String, load: (String) -> Outcome<WorkspaceSource>): Outcome<WorkspaceEditPlan> {
        val paths = when (val result = paths(edit, root)) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        val changes = arrayListOf<WorkspaceTextChange>()
        var size = 0L
        for ((uri, path) in paths) {
            val source = when (val result = load(path)) {
                is Outcome.Failure -> return result
                is Outcome.Success -> result.value
            }
            val expected = edit.versions[uri]
            if (expected != null && source.version != expected) {
                return Outcome.failure(DiagCode.STATE_CONFLICT, "версия файла «$path» не совпала с ответом службы; повторите переименование", path)
            }
            val edits = edit.changes.getValue(uri)
            val after = when (val result = TextEdits.apply(source.text, edits)) {
                is Outcome.Failure -> return result
                is Outcome.Success -> result.value
            }
            if (!Charsets.UTF_8.newEncoder().canEncode(after)) return Outcome.failure(DiagCode.VALUE_INVALID, "правка разбивает Unicode; ничего не изменено", path)
            size += source.text.toByteArray(Charsets.UTF_8).size.toLong() + after.toByteArray(Charsets.UTF_8).size
            if (size > MAX_BYTES) return Outcome.failure(DiagCode.VALUE_INVALID, "набор правок больше 8 МиБ; ничего не изменено")
            if (after != source.text) changes += WorkspaceTextChange(path, uri, source.text, after, edits.toList())
        }
        return Outcome.success(WorkspaceEditPlan(changes.toList()))
    }
}
