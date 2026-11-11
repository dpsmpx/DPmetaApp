package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/** Сохранение ручной правки с проверкой наблюдаемого исходного снимка (D-131). */
object TextFileEdit {
    fun save(tree: FileTree, path: String, original: ByteArray, text: String): Outcome<FileMeta> {
        val encoded = TextFile.encode(text)
        val bom = original.size >= 3 && original[0] == 0xEF.toByte() && original[1] == 0xBB.toByte() && original[2] == 0xBF.toByte()
        val bytes = if (bom) original.copyOfRange(0, 3) + encoded else encoded
        if (bytes.size > TextFile.MAX_EDIT_BYTES) return Outcome.failure(DiagCode.VALUE_INVALID, "текст больше 2 МиБ — не сохранён", path)
        when (val current = tree.read(path, TextFile.MAX_EDIT_BYTES)) {
            is Outcome.Failure -> return current
            is Outcome.Success -> if (!current.value.contentEquals(original)) {
                return Outcome.failure(DiagCode.STATE_CONFLICT, "файл изменён другим процессом; запись отменена, черновик сохранён", path)
            }
        }
        // FileTree не обещает CAS с внешними процессами: это проверка снимка перед обычной записью.
        return tree.write(path, bytes)
    }
}
