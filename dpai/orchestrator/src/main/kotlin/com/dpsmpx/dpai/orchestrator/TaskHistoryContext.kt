package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.contexts.ContextSource
import com.dpsmpx.dpai.contexts.SourceLevel
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.security.Origin
import com.dpsmpx.dpai.security.Sensitivity

/** История D-66 — данные прошлых сессий, даже когда её упаковал оркестратор (D-129). */
internal object TaskHistoryContext {
    fun transcript(project: ProjectId, entries: List<ChatEntry>): ContextSource? {
        val records = entries.filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }.takeLast(12).map { e ->
            JsonObject.of(
                "role" to JsonString(e.role.name),
                "text" to JsonString(clip(e.text, 1500)),
            )
        }.toMutableList()
        if (records.isEmpty()) return null
        // Считаем после JSON-экранирования; убираем целые старые записи, сохраняя роли и структуру.
        var text = CanonicalJson.write(JsonArray(records))
        while (text.length > 8000) {
            if (records.size > 1) {
                records.removeAt(0)
            } else {
                val record = records.single()
                val value = (record.fields.getValue("text") as JsonString).value
                records[0] = JsonObject(record.fields + ("text" to JsonString(clip(value, value.length / 2))))
            }
            text = CanonicalJson.write(JsonArray(records))
        }
        return source(project, Orchestrator.TRANSCRIPT_SOURCE, text)
    }

    fun background(project: ProjectId, jobs: List<BackgroundJob>): ContextSource? {
        if (jobs.isEmpty()) return null
        val text = CanonicalJson.write(BackgroundJobs.toJson(jobs.takeLast(BackgroundJobs.MAX)))
        return source(project, Orchestrator.BACKGROUND_SOURCE, text)
    }

    private fun source(project: ProjectId, key: String, text: String) =
        ContextSource(key, SourceLevel.PROJECT, project, Sensitivity.INTERNAL, Origin.MODEL, text)

    private fun clip(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val end = if (limit > 0 && Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
        return text.take(end) + " …"
    }
}
