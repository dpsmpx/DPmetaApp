package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.then

/** Подпапка существующего дерева: его запреты ссылок/скрытых путей сохраняются (D-132). */
class SubTree(private val tree: FileTree, private val prefix: String) : FileTree {
    init { require(prefix.isEmpty() || FileNames.pathValid(prefix)) }

    private fun resolve(path: String, empty: Boolean = true): Outcome<String> =
        if (path.isEmpty() && !empty || path.isNotEmpty() && !FileNames.pathValid(path)) {
            Outcome.failure(DiagCode.VALUE_INVALID, "недопустимый путь внутри проекта")
        } else Outcome.success(if (path.isEmpty()) prefix else FileNames.join(prefix, path))

    override fun list(dir: String) = resolve(dir).then(tree::list)
    override fun read(path: String, maxBytes: Long) = resolve(path, false).then { tree.read(it, maxBytes) }
    override fun write(path: String, bytes: ByteArray) = resolve(path, false).then { tree.write(it, bytes) }
    override fun delete(path: String) = resolve(path, false).then(tree::delete)
}
