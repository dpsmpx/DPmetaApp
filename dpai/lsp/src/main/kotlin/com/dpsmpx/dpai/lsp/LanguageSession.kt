package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.result.Outcome
import kotlin.time.Duration

/**
 * Службы проекта, которые отвечают за файл (D-142): основная [main] и,
 * если описана, вспомогательная [helper] (ruff для Python). Запросы о смысле
 * кода — дополнение, сведения, подпись, переходы, использования,
 * переименование — идут основной; исправления — обеим; формат —
 * вспомогательной, если она его даёт, иначе основной. Диагностику каждая
 * служба шлёт своим списком, редактор показывает объединение
 * ([DiagnosticsBoard]); службам из [pull] её нужно спрашивать самим
 * ([pullDiagnostics]).
 *
 * Сеанс работает, пока работают все его службы: полсемантики не выдаётся
 * за всю.
 */
class LanguageSession(val main: LspClient, val helper: LspClient? = null, private val pull: Set<LspClient> = emptySet()) {
    val clients: List<LspClient> = listOfNotNull(main, helper)

    val isRunning: Boolean get() = clients.all { it.isRunning }

    /** Индекс службы для [DiagnosticsBoard]: основная — 0, вспомогательная — 1. */
    fun indexOf(client: LspClient): Int = clients.indexOf(client)

    fun open(uri: String, languageId: String, text: String): Outcome<Unit> = each { it.open(uri, languageId, text) }

    fun saved(uri: String): Outcome<Unit> = each { it.saved(uri) }

    /** Записанный проектный набор: открытые документы обновляются, закрытые — событием файлов. */
    fun filesChanged(files: Map<String, String>): Outcome<Unit> = each { client ->
        for ((uri, text) in files) if (client.document(uri) != null) {
            val changed = client.update(uri, text)
            if (changed is Outcome.Failure) return@each changed
            val saved = client.saved(uri)
            if (saved is Outcome.Failure) return@each saved
        }
        client.filesChanged(files.keys)
    }

    fun close(uri: String): Outcome<Unit> = each { it.close(uri) }

    fun completion(uri: String, at: Position, trigger: Char? = null): Outcome<CompletionList> = main.completion(uri, at, trigger)

    fun hover(uri: String, at: Position): Outcome<Hover?> = main.hover(uri, at)

    fun signatureHelp(uri: String, at: Position): Outcome<SignatureHelp?> = main.signatureHelp(uri, at)

    fun definition(uri: String, at: Position): Outcome<List<Location>> = main.definition(uri, at)

    /** Объявление; служба без `declarationProvider` (pylsp, kotlin-lsp) — пустой список, а не ошибка. */
    fun declaration(uri: String, at: Position): Outcome<List<Location>> =
        if (main.info?.capabilities?.declaration == true) main.declaration(uri, at) else Outcome.success(emptyList())

    fun references(uri: String, at: Position): Outcome<List<Location>> = main.references(uri, at)

    /** Символы документа; служба их не даёт — пустой список, а не отказ. */
    fun symbols(uri: String): Outcome<List<CodeSymbol>> =
        if (main.info?.capabilities?.documentSymbols == true) main.symbols(uri) else Outcome.success(emptyList())

    fun rename(uri: String, at: Position, newName: String): Outcome<WorkspaceEdit> = main.rename(uri, at, newName)

    /** Служба формата: вспомогательная, если умеет, иначе основная. */
    val formatter: LspClient get() = helper?.takeIf { it.info?.capabilities?.formatting == true } ?: main

    fun format(uri: String, tabSize: Int, spaces: Boolean): Outcome<List<TextEdit>> = formatter.format(uri, tabSize, spaces)

    /** Исправления обеих служб; отказ одной не прячет ответ другой, отказ всех — первый отказ. */
    fun codeActions(uri: String, range: Range, diagnostics: List<LspDiagnostic>): Outcome<List<CodeAction>> {
        val asked = clients.filter { it.info?.capabilities?.codeAction == true }
        if (asked.isEmpty()) return Outcome.success(emptyList())
        val results = asked.map { it.codeActions(uri, range, diagnostics) }
        val found = results.mapNotNull { (it as? Outcome.Success)?.value }
        return if (found.isNotEmpty()) Outcome.success(found.flatten()) else results.first()
    }

    /** Спросить диагностику [uri] у служб, которые её не присылают: индекс службы → список (`null` — не изменилась). */
    fun pullDiagnostics(uri: String, timeout: Duration = LspClient.SLOW_TIMEOUT): Map<Int, Outcome<List<LspDiagnostic>?>> =
        clients.filter { it in pull }.associate { indexOf(it) to it.pullDiagnostics(uri, timeout) }

    val pulls: Boolean get() = pull.any { it in clients }

    fun stop() = clients.forEach { it.stop() }

    private fun each(step: (LspClient) -> Outcome<Unit>): Outcome<Unit> {
        for (c in clients) step(c).let { if (it is Outcome.Failure) return it }
        return Outcome.success(Unit)
    }
}

/**
 * Диагностика файлов от нескольких служб: у каждой свой последний список,
 * показывается объединение по порядку служб. Потокобезопасна: службы
 * пишут из своих потоков чтения.
 */
class DiagnosticsBoard {
    private val bySource = HashMap<Int, HashMap<String, List<LspDiagnostic>>>()

    /** Новый список службы [source] для [uri]; возвращает объединение для [uri]. */
    @Synchronized
    fun put(source: Int, uri: String, items: List<LspDiagnostic>): List<LspDiagnostic> {
        bySource.getOrPut(source) { HashMap() }[uri] = items
        return merged(uri)
    }

    @Synchronized
    fun merged(uri: String): List<LspDiagnostic> = bySource.keys.sorted().flatMap { bySource.getValue(it)[uri].orEmpty() }

    @Synchronized
    fun clear(uri: String) = bySource.values.forEach { it.remove(uri) }
}
