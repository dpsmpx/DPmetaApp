package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.TextFile
import com.dpsmpx.dpai.files.TextFileEdit

/** Проектная запись; весь вызов выполняется под WorkspaceWriteGate, общим с запуском ИИ. */
class WorkspaceChanges(private val store: WorkspaceChangeStore) {
    fun apply(project: String, root: String, tree: FileTree, plan: WorkspaceEditPlan): Outcome<Unit> {
        if (plan.index?.valid() == false) return Outcome.failure(DiagCode.STATE_CONFLICT, "индекс проекта изменился; повторите переименование")
        if (plan.files.isEmpty()) return Outcome.success(Unit)
        val files = arrayListOf<WorkspaceBytes>()
        for (file in plan.files) {
            val before = when (val result = tree.read(file.path, TextFile.MAX_EDIT_BYTES)) {
                is Outcome.Failure -> return result
                is Outcome.Success -> result.value
            }
            if (TextFile.decode(before).valueOrNull() != file.before) return conflict(file.path)
            val after = when (val result = TextFileEdit.encode(before, file.after, file.path)) {
                is Outcome.Failure -> return result
                is Outcome.Success -> result.value
            }
            files += WorkspaceBytes(file.path, before, after)
        }
        if (plan.index?.valid() == false) return Outcome.failure(DiagCode.STATE_CONFLICT, "индекс проекта изменился; повторите переименование")
        return applyBytes(project, root, tree, files, plan.index)
    }

    private fun applyBytes(project: String, root: String, tree: FileTree, files: List<WorkspaceBytes>, index: com.dpsmpx.dpai.lsp.ProjectIndex.Lease? = null): Outcome<Unit> {
        // До durable pending нет ни одной записи исходника.
        when (val result = store.begin(project, root, files)) {
            is Outcome.Failure -> return result
            is Outcome.Success -> Unit
        }
        // Копия могла писаться долго; до первой записи исходника перепроверяем очередь.
        if (index?.valid() == false) {
            val cleared = store.finish(project, applied = false)
            val conflict = Outcome.failure<Unit>(DiagCode.STATE_CONFLICT, "индекс проекта изменился; повторите переименование")
            return Outcome.Failure(conflict.diagnostics + cleared.diagnostics)
        }
        for (file in files) {
            when (val result = TextFileEdit.saveBytes(tree, file.path, file.before, file.after)) {
                is Outcome.Failure -> {
                    // Внешние изменения не затираются даже при откате. Неизвестный исход
                    // оставляет durable pending для отдельного решения пользователя.
                    val restored = recover(project, root, tree, after = false)
                    return Outcome.Failure(result.diagnostics + restored.diagnostics)
                }
                is Outcome.Success -> Unit
            }
        }
        return store.finish(project, applied = true)
    }

    fun recover(project: String, root: String, tree: FileTree, after: Boolean): Outcome<Unit> {
        val copy = when (val result = store.load(project)) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value ?: return conflict()
        }
        if (!copy.pending || copy.root != root) return conflict()
        val current = arrayListOf<ByteArray>()
        // Проверить ВСЕ файлы до первой записи; произвольные новые правки не перезаписывать.
        for (file in copy.files) {
            val bytes = when (val result = tree.read(file.path, TextFile.MAX_EDIT_BYTES)) {
                is Outcome.Failure -> return result
                is Outcome.Success -> result.value
            }
            if (!bytes.contentEquals(file.before) && !bytes.contentEquals(file.after)) return conflict(file.path)
            current += bytes
        }
        for ((file, bytes) in copy.files.zip(current)) {
            val wanted = if (after) file.after else file.before
            if (bytes.contentEquals(wanted)) continue
            when (val result = TextFileEdit.saveBytes(tree, file.path, bytes, wanted)) {
                is Outcome.Failure -> return result
                is Outcome.Success -> Unit
            }
        }
        return store.finish(project, applied = after)
    }

    fun undo(project: String, root: String, tree: FileTree): Outcome<Unit> {
        val copy = when (val result = store.load(project)) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value ?: return conflict()
        }
        if (copy.pending || !copy.applied || copy.root != root) return conflict()
        for (file in copy.files) {
            val result = tree.read(file.path, TextFile.MAX_EDIT_BYTES)
            if (result is Outcome.Failure) return result
            if (!(result as Outcome.Success).value.contentEquals(file.after)) return conflict(file.path)
        }
        return applyBytes(project, root, tree, copy.files.map { WorkspaceBytes(it.path, it.after, it.before) })
    }

    private fun <T> conflict(path: String? = null): Outcome<T> = Outcome.failure(DiagCode.STATE_CONFLICT, "файлы или дерево изменились; правка отменена, копия сохранена", path)
}
