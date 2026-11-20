package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Сохранение ручной правки с проверкой наблюдаемого исходного снимка (D-131). */
object TextFileEdit {
    fun save(tree: FileTree, path: String, original: ByteArray, text: String): Outcome<ByteArray> {
        val bytes = when (val result = encode(original, text, path)) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        return saveBytes(tree, path, original, bytes)
    }

    /** Общее кодирование для одиночной и проектной правки, с сохранением BOM. */
    fun encode(original: ByteArray, text: String, path: String): Outcome<ByteArray> {
        if ('\u0000' in text) return Outcome.failure(DiagCode.VALUE_INVALID, "нулевой символ — не текстовый файл", path)
        val encoded = try {
            val data = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
            ByteArray(data.remaining()).also { data.get(it) }
        } catch (_: CharacterCodingException) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "некорректный Unicode — файл не сохранён", path)
        }
        val bom = original.size >= 3 && original[0] == 0xEF.toByte() && original[1] == 0xBB.toByte() && original[2] == 0xBF.toByte()
        val bytes = if (bom) original.copyOfRange(0, 3) + encoded else encoded
        if (bytes.size > TextFile.MAX_EDIT_BYTES) return Outcome.failure(DiagCode.VALUE_INVALID, "текст больше 2 МиБ — не сохранён", path)
        return Outcome.success(bytes)
    }

    fun saveBytes(tree: FileTree, path: String, original: ByteArray, bytes: ByteArray): Outcome<ByteArray> {
        if (bytes.size > TextFile.MAX_EDIT_BYTES) return Outcome.failure(DiagCode.VALUE_INVALID, "текст больше 2 МиБ — не сохранён", path)
        when (val current = tree.read(path, TextFile.MAX_EDIT_BYTES)) {
            is Outcome.Failure -> return current
            is Outcome.Success -> if (!current.value.contentEquals(original)) {
                return Outcome.failure(DiagCode.STATE_CONFLICT, "файл изменён другим процессом; запись отменена, черновик сохранён", path)
            }
        }
        // FileTree не обещает CAS с внешними процессами: это проверка снимка перед обычной записью.
        return when (val written = tree.write(path, bytes)) {
            is Outcome.Failure -> written
            is Outcome.Success -> Outcome.success(bytes)
        }
    }
}
