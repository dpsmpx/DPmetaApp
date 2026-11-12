package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Ответы службы в действия редактора (D-137) — без платформы и без самой
 * службы: что станет с текстом и курсором, что подчеркнуть, что написать
 * в строке состояния. Файл не меняется: результат идёт в буфер редактора.
 */
object Assist {
    /** Текст после правки и место курсора в нём. */
    data class Applied(val text: String, val cursor: Int)

    /** Подчёркнутый отрезок текста `[from, until)` в единицах UTF-16. */
    data class Mark(val from: Int, val until: Int, val severity: LspSeverity)

    /**
     * Правки службы и курсор: правка до курсора сдвигает его, правка,
     * накрывшая курсор, ставит его в конец вставленного. Пересечение или
     * строка за концом текста — отказ целиком ([TextEdits.apply]).
     */
    fun applyEdits(text: String, cursor: Int, edits: List<TextEdit>): Outcome<Applied> {
        val result = when (val r = TextEdits.apply(text, edits)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val index = LineIndex(text)
        var at = cursor.coerceIn(0, text.length)
        var shift = 0
        for (e in edits.sortedBy { it.range.start }) {
            val from = index.offset(e.range.start) ?: continue
            val to = index.offset(e.range.end) ?: continue
            when {
                to <= cursor && !(from == to && from == cursor) -> shift += e.newText.length - (to - from)
                from <= cursor -> {
                    at = from
                    shift += e.newText.length
                }
            }
        }
        return Outcome.success(Applied(result, (at + shift).coerceIn(0, result.length)))
    }

    /** Часть идентификатора слева от курсора — то, что человек уже набрал. */
    fun typedPrefix(text: String, cursor: Int): String {
        var from = cursor.coerceIn(0, text.length)
        while (from > 0 && (text[from - 1].isLetterOrDigit() || text[from - 1] == '_')) from--
        return text.substring(from, cursor.coerceIn(0, text.length))
    }

    /**
     * Вставить вариант дополнения: его правка (или набранное слово — на
     * [CompletionItem.insertText]) и правки в других местах файла. Курсор —
     * сразу после вставленного.
     */
    fun complete(text: String, cursor: Int, item: CompletionItem): Outcome<Applied> {
        val at = cursor.coerceIn(0, text.length)
        val index = LineIndex(text)
        val main = item.edit ?: TextEdit(Range(index.position(at - typedPrefix(text, at).length), index.position(at)), item.insertText)
        val start = index.offset(main.range.start) ?: return Outcome.failure(DiagCode.LSP_REPLY_INVALID, "дополнение — за концом текста")
        // Служба считала правку для своего места курсора: если текст с тех пор ушёл вперёд, заменяем до нынешнего курсора.
        val end = maxOf(index.offset(main.range.end) ?: return Outcome.failure(DiagCode.LSP_REPLY_INVALID, "дополнение — за концом текста"), if (start <= at) at else start)
        val whole = TextEdit(Range(main.range.start, index.position(end)), main.newText)
        return when (val r = applyEdits(text, start, listOf(whole) + item.additional)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(r.value)
        }
    }

    /** Варианты, подходящие набранному [prefix], в порядке службы (`sortText`), не больше [limit]. */
    fun filter(items: List<CompletionItem>, prefix: String, limit: Int = 100): List<CompletionItem> = items
        .filter { (it.filterText ?: it.insertText).startsWith(prefix, ignoreCase = true) }
        .sortedWith(compareBy({ it.sortText ?: it.label }, { it.label }))
        .take(limit)

    /** Отрезки для подчёркивания; пустой диапазон службы растягивается на знак, чтобы его было видно. */
    fun marks(text: String, diagnostics: List<LspDiagnostic>): List<Mark> {
        val index = LineIndex(text)
        return diagnostics.mapNotNull { d ->
            val from = index.offset(d.range.start) ?: return@mapNotNull null
            val to = index.offset(d.range.end) ?: text.length
            val until = if (to > from) to else minOf(from + 1, text.length)
            if (until > from) Mark(from, until, d.severity) else if (from > 0) Mark(from - 1, from, d.severity) else null
        }
    }

    /** Строка состояния: «ошибок: 2 · предупреждений: 1» или «замечаний нет». */
    fun summary(diagnostics: List<LspDiagnostic>): String {
        val errors = diagnostics.count { it.severity == LspSeverity.ERROR }
        val warnings = diagnostics.count { it.severity == LspSeverity.WARNING }
        val notes = diagnostics.size - errors - warnings
        return listOfNotNull(
            "ошибок: $errors".takeIf { errors > 0 },
            "предупреждений: $warnings".takeIf { warnings > 0 },
            "подсказок: $notes".takeIf { notes > 0 },
        ).joinToString(" · ").ifEmpty { "замечаний нет" }
    }
}
