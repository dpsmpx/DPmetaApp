package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.lsp.LanguageServers
import com.dpsmpx.dpai.lsp.LanguageSession
import com.dpsmpx.dpai.lsp.WorkspaceEdit

/**
 * Сначала узнать файлы, затем получить rename для наблюдаемого снимка.
 * Неизвестная версия закрытого файла не принимается за подтверждённую:
 * такой документ временно открывается у службы, запрос повторяется.
 */
object WorkspaceRename {
    fun prepare(client: LanguageSession, root: String, load: (String) -> Outcome<String>,
        request: () -> Outcome<WorkspaceEdit>): Outcome<WorkspaceEditPlan> = prepare(root, load,
        client.main::document, { uri, language, text -> client.main.borrowDocument(uri, language, text) }, request)

    internal fun prepare(root: String, load: (String) -> Outcome<String>, document: (String) -> Pair<Int, String>?,
        borrow: (String, String, String) -> Outcome<AutoCloseable>, request: () -> Outcome<WorkspaceEdit>): Outcome<WorkspaceEditPlan> {
        val sources = linkedMapOf<String, Pair<String, String>>() // относительный путь → URI и снимок
        val borrowed = arrayListOf<AutoCloseable>()
        var size = 0L
        var previous: Set<String>? = null
        try {
            var edit = when (val result = request()) {
                is Outcome.Failure -> return result
                is Outcome.Success -> result.value
            }
            repeat(3) { round ->
                val paths = when (val result = WorkspaceEdits.paths(edit, root)) {
                    is Outcome.Failure -> return result
                    is Outcome.Success -> result.value
                }
                if (paths.isEmpty()) return Outcome.success(WorkspaceEditPlan(emptyList()))
                var changed = false
                for ((uri, path) in paths) {
                    val known = sources[path]
                    if (known != null && known.first != uri) return Outcome.failure(DiagCode.VALUE_INVALID, "адрес файла «$path» изменился между ответами службы")
                    if (known == null) {
                        if (sources.size >= WorkspaceEdits.MAX_FILES) return Outcome.failure(DiagCode.VALUE_INVALID, "служба меняет слишком много файлов; ничего не изменено")
                        val text = when (val result = load(path)) {
                            is Outcome.Failure -> return result
                            is Outcome.Success -> result.value
                        }
                        size += text.toByteArray(Charsets.UTF_8).size
                        if (size > WorkspaceEdits.MAX_BYTES) return Outcome.failure(DiagCode.VALUE_INVALID, "снимок файлов больше 8 МиБ; ничего не изменено")
                        sources[path] = uri to text
                        changed = true
                    }
                    val text = sources.getValue(path).second
                    val open = document(uri)
                    if (open != null && open.second != text) return stale(path)
                    if (edit.versions[uri] != null && open == null) {
                        val language = LanguageServers.languageId(path) ?: when (path.substringAfterLast('.').lowercase()) {
                            "java" -> "java"
                            "xml" -> "xml"
                            "gradle" -> "groovy"
                            "json" -> "json"
                            else -> "plaintext"
                        }
                        when (val result = borrow(uri, language, text)) {
                            is Outcome.Failure -> return result
                            is Outcome.Success -> borrowed += result.value
                        }
                        changed = true
                    }
                }
                if (!changed && previous == paths.keys) {
                    return WorkspaceEdits.prepare(edit, root) { path ->
                        val (uri, before) = sources.getValue(path)
                        when (val result = load(path)) {
                            is Outcome.Failure -> result
                            is Outcome.Success -> {
                                val open = document(uri)
                                if (result.value != before || open != null && open.second != before) stale(path)
                                else Outcome.success(WorkspaceSource(before, open?.first))
                            }
                        }
                    }
                }
                if (round < 2) {
                    previous = paths.keys.toSet()
                    edit = when (val result = request()) {
                        is Outcome.Failure -> return result
                        is Outcome.Success -> result.value
                    }
                }
            }
            return Outcome.failure(DiagCode.STATE_CONFLICT, "служба ещё меняет набор файлов; дождитесь завершения индексации и повторите")
        } finally {
            borrowed.asReversed().forEach { it.close() }
        }
    }

    private fun <T> stale(path: String): Outcome<T> = Outcome.failure(DiagCode.STATE_CONFLICT, "файл «$path» изменился; повторите переименование", path)
}
