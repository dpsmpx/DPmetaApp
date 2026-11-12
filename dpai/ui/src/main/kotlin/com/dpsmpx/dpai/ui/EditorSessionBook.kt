package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore

/** Только метаданные интерфейса; текст/исходные байты остаются в зашифрованных черновиках (D-106). */
data class EditorOptions(val zen: Boolean = false, val previewSyntax: Boolean = true, val editorSyntax: Boolean = true)
data class EditorRootSession(val tabs: EditorTabs = EditorTabs(), val runCommand: String = "")
data class EditorSessions(val options: EditorOptions = EditorOptions(), val roots: Map<String, EditorRootSession> = emptyMap(), val lastPlace: String? = null)

class EditorSessionBook(private val docs: DocumentStore) {
    fun load(): Outcome<EditorSessions> = when (val result = docs.read("sessions")) {
        is Outcome.Failure -> result
        is Outcome.Success -> if (result.value == null) Outcome.success(EditorSessions()) else decode(result.value!!.content)?.let { Outcome.success(it) }
            ?: Outcome.failure(DiagCode.STORAGE_CORRUPTED, "Вкладки и настройки IDE не прочитаны; сохранённая копия оставлена")
    }
    fun save(state: EditorSessions): Outcome<Unit> {
        val encoded = encode(state)
        if (decode(encoded) == null) return Outcome.failure(DiagCode.VALUE_INVALID, "Слишком много вкладок или недопустимые настройки IDE")
        return when (val result = docs.write("sessions", encoded)) { is Outcome.Failure -> result; is Outcome.Success -> Outcome.success(Unit) }
    }
    companion object {
        const val MAX_ROOTS = 32
        private fun encode(s: EditorSessions) = JsonObject.of("format" to JsonString("dpai.editor-sessions"), "version" to JsonNumber.of(1),
            "zen" to JsonBool(s.options.zen), "previewSyntax" to JsonBool(s.options.previewSyntax), "editorSyntax" to JsonBool(s.options.editorSyntax),
            "lastPlace" to JsonString(s.lastPlace.orEmpty()), "roots" to JsonArray(s.roots.map { (key, root) -> JsonObject.of(
                "key" to JsonString(key), "active" to JsonString(root.tabs.active.orEmpty()), "paths" to JsonArray(root.tabs.paths.map(::JsonString)), "run" to JsonString(root.runCommand)) }))
        private fun decode(o: JsonObject): EditorSessions? {
            fun text(name: String) = (o[name] as? JsonString)?.value
            if (text("format") != "dpai.editor-sessions" || (o["version"] as? JsonNumber)?.toLongExact() != 1L) return null
            val roots = (o["roots"] as? JsonArray)?.items ?: return null
            if (roots.size > MAX_ROOTS) return null
            val values = linkedMapOf<String, EditorRootSession>()
            for (item in roots) {
                val r = item as? JsonObject ?: return null
                val key = (r["key"] as? JsonString)?.value ?: return null
                val paths = (r["paths"] as? JsonArray)?.items?.map { (it as? JsonString)?.value ?: return null } ?: return null
                val active = (r["active"] as? JsonString)?.value?.ifEmpty { null }
                val command = (r["run"] as? JsonString)?.value ?: return null
                if (key.isEmpty() || key.length > 4096 || key in values || paths.size > EditorTabs.MAX_OPEN || paths.distinct().size != paths.size || paths.any { it.isEmpty() || it.length > 2048 || '\u0000' in it } || active != null && active !in paths || command.length > 4096 || '\u0000' in command) return null
                values[key] = EditorRootSession(EditorTabs(paths, active), command)
            }
            val place = text("lastPlace")?.ifEmpty { null }
            if (place != null && place.length > 4096) return null
            return EditorSessions(EditorOptions((o["zen"] as? JsonBool)?.value ?: return null, (o["previewSyntax"] as? JsonBool)?.value ?: return null,
                (o["editorSyntax"] as? JsonBool)?.value ?: return null), values, place)
        }
    }
}
