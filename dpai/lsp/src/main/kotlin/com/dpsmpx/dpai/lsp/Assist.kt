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

    /**
     * Куда ведёт место, найденное службой (определение, использование): этот
     * файл, файл того же корня — его откроет вкладка, или файл вне корня —
     * только путь и строка. Строки — с единицы, как в редакторе.
     */
    sealed interface Target {
        val line: Int

        data class Here(override val line: Int, val position: Position) : Target

        data class Tab(val path: String, override val line: Int) : Target

        data class Outside(val path: String, override val line: Int) : Target
    }

    /**
     * Место [location] относительно файла [uri] и корня [guestRoot] (путь
     * гостя, от которого открыт файл). Адреса сравниваются путями — у одной
     * службы кодирование одно, а путь вне корня или с `..` вкладкой не
     * открывается. Адрес не `file://` — `null`.
     */
    fun target(location: Location, uri: String, guestRoot: String): Target? {
        val path = DocumentUri.toPath(location.uri) ?: return null
        val line = location.range.start.line + 1
        if (path == DocumentUri.toPath(uri)) return Target.Here(line, location.range.start)
        val root = guestRoot.trimEnd('/')
        val rel = if (path.startsWith("$root/")) path.substring(root.length + 1) else null
        val parts = rel?.split('/')
        return if (parts != null && parts.none { it.isEmpty() || it == "." || it == ".." }) Target.Tab(rel, line) else Target.Outside(path, line)
    }

    /**
     * Правки этого файла из правки службы (переименование, исправление).
     * Правка задевает другие файлы — отказ целиком: часть переименования
     * ломает код, а другие файлы редактор пока не правит (D-105).
     */
    fun editsForFile(edit: WorkspaceEdit, uri: String): Outcome<List<TextEdit>> {
        val here = DocumentUri.toPath(uri)
        val mine = mutableListOf<TextEdit>()
        val others = sortedSetOf<String>()
        for ((u, edits) in edit.changes) {
            if (edits.isEmpty()) continue
            val path = DocumentUri.toPath(u)
            if (path != null && path == here) mine += edits else others += (path ?: u)
        }
        if (others.isNotEmpty()) {
            val names = others.take(3).joinToString { it.substringAfterLast('/') } + if (others.size > 3) " и ещё ${others.size - 3}" else ""
            return Outcome.failure(DiagCode.UNSUPPORTED, "правка задевает другие файлы (${others.size}: $names) — пока применяется только в одном файле; ничего не изменено")
        }
        return Outcome.success(mine)
    }

    /**
     * Переименование применяется только в этом файле, поэтому безопасно,
     * только если символ — этого файла: [places] (объявление и определение
     * по ответу службы) все в нём, и сам файл не заголовок C/C++. Службы
     * видят проект не целиком: clangd без фонового индекса (D-105) и pyright
     * в режиме открытых файлов (D-142) для символа из другого файла вернули
     * бы правки одного файла — и переименование молча стало бы частичным.
     */
    fun renameScope(uri: String, places: List<Location>): Outcome<Unit> {
        val here = DocumentUri.toPath(uri) ?: return Outcome.failure(DiagCode.UNSUPPORTED, "файл не на диске службы")
        if (here.substringAfterLast('/').substringAfterLast('.', "").lowercase() in HEADERS) {
            return Outcome.failure(DiagCode.UNSUPPORTED, "это заголовок: его символы видят другие файлы, а служба без индекса проекта их не знает — переименование пока только в исходном файле")
        }
        if (places.isEmpty()) return Outcome.failure(DiagCode.UNSUPPORTED, "служба не нашла объявления символа под курсором")
        val outside = places.mapNotNull { DocumentUri.toPath(it.uri) ?: it.uri }.filter { it != here }.distinct()
        if (outside.isNotEmpty()) {
            return Outcome.failure(DiagCode.UNSUPPORTED, "символ объявлен в ${outside.first().substringAfterLast('/')}: переименование через файлы пока не применяется — отменено, чтобы не стать частичным; ничего не изменено")
        }
        return Outcome.success(Unit)
    }

    private val HEADERS = setOf("h", "hh", "hpp", "hxx", "h++", "inl", "ipp", "tcc", "ixx")

    /** Имя для переименования — идентификатор C/C++: латиница, цифры, `_`, не с цифры. */
    fun validName(name: String): Boolean = name.length in 1..MAX_NAME && IDENTIFIER.matches(name)

    /**
     * Подпись вызова для показа: текст активной перегрузки, отрезок текущего
     * параметра в нём (если служба его назвала) и сколько ещё перегрузок.
     */
    data class SignatureLine(val label: String, val parameter: IntRange?, val more: Int)

    fun signature(help: SignatureHelp?): SignatureLine? {
        val list = help?.signatures?.takeIf { it.isNotEmpty() } ?: return null
        val active = list[help.active.coerceIn(0, list.lastIndex)]
        val parameter = (active.activeParameter ?: help.activeParameter)?.let { active.parameters.getOrNull(it) }?.takeIf { it.first >= 0 && it.last < active.label.length }
        return SignatureLine(active.label, parameter, list.size - 1)
    }

    /** Подсказка подписи нужна: курсор сразу после `(` или `,` (пробелы не в счёт). */
    fun wantsSignature(text: String, cursor: Int): Boolean {
        var i = cursor.coerceIn(0, text.length) - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
        return i >= 0 && (text[i] == '(' || text[i] == ',')
    }

    /** Подсказку подписи снять: вызов закрыт или строка кончилась. */
    fun endsSignature(text: String, cursor: Int): Boolean {
        val i = cursor.coerceIn(0, text.length) - 1
        return i < 0 || text[i] == ')' || text[i] == ';' || text[i] == '\n'
    }

    /** Исправления с правкой — сначала предпочтительные; [commands] — сколько предложено командой, без правки (не поддержано). */
    data class Fixes(val applicable: List<CodeAction>, val commands: Int)

    fun fixes(actions: List<CodeAction>): Fixes {
        val (withEdit, without) = actions.partition { it.edit != null }
        return Fixes(withEdit.sortedWith(compareBy({ !it.preferred }, { it.title })), without.size)
    }

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private const val MAX_NAME = 255

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
