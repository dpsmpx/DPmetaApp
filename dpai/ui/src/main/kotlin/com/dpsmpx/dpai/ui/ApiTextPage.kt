package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.TextFile

/** Общие страницы исходника/отчёта: полный hash, целый JSON и пары UTF-16 (D-214, D-217). */
object ApiTextPage {
    fun read(tree: FileTree, path: String, offset: Long, maxBytes: Long = TextFile.MAX_EDIT_BYTES,
        expectedSha256: String? = null, redact: Boolean = false): Outcome<JsonObject> {
        val raw = when (val r = tree.read(path, maxBytes)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        val original = when (val r = TextFile.decode(raw, maxBytes)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        val hash = Hashing.sha256(raw).hex
        if (expectedSha256 != null && expectedSha256 != hash)
            return Outcome.failure(DiagCode.STATE_CONFLICT, "файл изменился между страницами; начните чтение заново")
        // Сначала весь текст: секрет не должен оказаться разрезанным границей страниц.
        val content = if (redact) Redactor.STANDARD.redact(original) else original
        if (offset !in 0..content.length.toLong()) return Outcome.failure(DiagCode.VALUE_INVALID, "смещение выходит за границы текста")
        var from = offset.toInt()
        if (from > 0 && from < content.length && content[from].isLowSurrogate() && content[from - 1].isHighSurrogate()) from--
        var end = minOf(from + 18_000, content.length)
        while (true) {
            if (end < content.length && end > from && content[end - 1].isHighSurrogate()) end--
            val result = JsonObject(mapOf("sha256" to JsonString(hash), "offset" to JsonNumber.of(from), "nextOffset" to JsonNumber.of(end),
                "complete" to JsonBool(end == content.length), "content" to JsonString(content.substring(from, end))) +
                if (redact) mapOf("redacted" to JsonBool(content != original)) else emptyMap())
            if (CanonicalJson.write(result).length <= 30_000) return Outcome.success(result)
            end = from + (end - from) / 2
        }
    }
}
