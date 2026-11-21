package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.TextFile
import com.dpsmpx.dpai.files.TextFileEdit
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.core.hashing.Hashing

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
            val conflict = Outcome.failure(DiagCode.STATE_CONFLICT, "индекс проекта изменился; повторите переименование")
            return Outcome.Failure(conflict.diagnostics + cleared.diagnostics)
        }
        for (file in files) {
            when (val result = save(tree, file.path, file.before.takeIf { file.beforeExists }, file.after.takeIf { file.afterExists })) {
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
        val current = arrayListOf<ByteArray?>()
        // Проверить ВСЕ файлы до первой записи; произвольные новые правки не перезаписывать.
        for (file in copy.files) {
            val bytes = when (val result = current(tree, file)) {
                is Outcome.Failure -> return result
                is Outcome.Success -> result.value
            }
            if (!same(bytes, file.before.takeIf { file.beforeExists }) && !same(bytes, file.after.takeIf { file.afterExists })) return conflict(file.path)
            current += bytes
        }
        for ((file, bytes) in copy.files.zip(current)) {
            val wanted = if (after) file.after.takeIf { file.afterExists } else file.before.takeIf { file.beforeExists }
            if (same(bytes, wanted)) continue
            when (val result = save(tree, file.path, bytes, wanted)) {
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
            val result = current(tree, file)
            if (result is Outcome.Failure) return result
            if (!same((result as Outcome.Success).value, file.after.takeIf { file.afterExists })) return conflict(file.path)
        }
        return applyBytes(project, root, tree, copy.files.map { WorkspaceBytes(it.path, it.after, it.before, it.afterExists, it.beforeExists) })
    }

    /** API-правка одного файла: null ожидаемой суммы означает создание, не перезапись (D-214). */
    fun write(project: String, root: String, tree: FileTree, path: String, expected: String?, text: String): Outcome<Unit> {
        val before = when (val r = find(tree, path)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        if (before == null && expected != null || before != null && Hashing.sha256(before).hex != expected) return conflict(path)
        val after = when (val r = TextFileEdit.encode(before ?: byteArrayOf(), text, path)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        return applyBytes(project, root, tree, listOf(WorkspaceBytes(path, before ?: byteArrayOf(), after, before != null)))
    }

    /** Удаление API также имеет зашифрованную копию и отмену; сумма обязательна. */
    fun delete(project: String, root: String, tree: FileTree, path: String, expected: String): Outcome<Unit> {
        val before = when (val r = find(tree, path)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value ?: return conflict(path) }
        if (Hashing.sha256(before).hex != expected) return conflict(path)
        return applyBytes(project, root, tree, listOf(WorkspaceBytes(path, before, byteArrayOf(), afterExists = false)))
    }

    private fun same(a: ByteArray?, b: ByteArray?): Boolean = if (a == null) b == null else b != null && a.contentEquals(b)
    private fun current(tree: FileTree, f: WorkspaceBytes): Outcome<ByteArray?> =
        if (f.beforeExists && f.afterExists) tree.read(f.path, TextFile.MAX_EDIT_BYTES) else find(tree, f.path)

    /** Отсутствие подтверждается списком родителей; ошибка чтения не считается отсутствием. */
    private fun find(tree: FileTree, path: String): Outcome<ByteArray?> {
        if (!FileNames.pathValid(path) || path.split('/').any { it.equals(".git", true) }) return conflict(path)
        var parent = ""
        val parts = path.split('/')
        for ((i, part) in parts.withIndex()) {
            val entries = when (val r = tree.list(parent)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
            val entry = entries.firstOrNull { it.name == part } ?: return Outcome.success(null)
            if (i == parts.lastIndex) return if (entry.dir) conflict(path) else tree.read(path, TextFile.MAX_EDIT_BYTES)
            if (!entry.dir) return conflict(path)
            parent = FileNames.join(parent, part)
        }
        return conflict(path)
    }

    private fun save(tree: FileTree, path: String, before: ByteArray?, after: ByteArray?): Outcome<Unit> {
        if (before != null && after != null) return TextFileEdit.saveBytes(tree, path, before, after)
        val current = when (val r = find(tree, path)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        if (!same(current, before)) return conflict(path)
        if (after == null) return if (before == null) Outcome.success(Unit) else tree.delete(path)
        return when (val r = tree.write(path, after)) { is Outcome.Failure -> r; is Outcome.Success -> Outcome.success(Unit) }
    }

    private fun <T> conflict(path: String? = null): Outcome<T> = Outcome.failure(DiagCode.STATE_CONFLICT, "файлы или дерево изменились; правка отменена, копия сохранена", path)
}
