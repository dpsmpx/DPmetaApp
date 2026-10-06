package com.dpsmpx.dpai.contexts

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.ContextId
import com.dpsmpx.dpai.core.ids.HandoffId
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.security.Origin
import com.dpsmpx.dpai.security.Sensitivity

/** Почему создан handoff (ТЗ, §27). */
enum class HandoffReason { NEAR_LIMIT, MANUAL, TARGET_FAILED, ACCOUNT_EXHAUSTED, APP_STOPPED, PAUSED, TURN_FINISHED }

/**
 * Handoff (`dpai.handoff` v1; ТЗ, §27; `docs/CONTEXT_MODEL.md`, §5):
 * неизменяемый, хешируемый; минимум полей ТЗ. Всё, что в нём «сделано»
 * или «проверено», — заявление исполнителя, пока его не подтвердила
 * проверка (D-23).
 */
data class Handoff(
    val id: HandoffId,
    val key: String,
    val reason: HandoffReason,
    val session: SessionId,
    val task: TaskId,
    val project: ProjectId,
    val context: ContextId,
    val contextHash: ContentHash,
    val state: String,
    val completed: List<String>,
    val verified: List<String>,
    val failed: List<String>,
    val warnings: List<String>,
    val openProblems: List<String>,
    val currentAction: String?,
    val nextAction: String,
    val modifiedFiles: List<String>,
    val commit: String?,
    val branch: String?,
    val workspace: String?,
    val createdAt: Timestamp,
) {
    val hash: ContentHash get() = Hashing.sha256(toJson().fields.filterKeys { it != "hash" }.let(::JsonObject))

    fun toJson(): JsonObject {
        fun list(v: List<String>) = JsonArray(v.map(::JsonString))
        fun opt(v: String?): JsonValue = v?.let(::JsonString) ?: JsonNull
        val body = JsonObject(
            linkedMapOf(
                *FormatHeader.write(FORMAT, VERSION),
                "id" to JsonString(id.value), "key" to JsonString(key), "reason" to JsonString(reason.name),
                "session" to JsonString(session.value), "task" to JsonString(task.value), "project" to JsonString(project.value),
                "context" to JsonString(context.value), "contextHash" to JsonString(contextHash.toString()), "state" to JsonString(state),
                "completed" to list(completed), "verified" to list(verified), "failed" to list(failed), "warnings" to list(warnings),
                "openProblems" to list(openProblems), "currentAction" to opt(currentAction), "nextAction" to JsonString(nextAction),
                "modifiedFiles" to list(modifiedFiles), "commit" to opt(commit), "branch" to opt(branch), "workspace" to opt(workspace),
                "createdAt" to JsonNumber.of(createdAt.epochMillis),
            ),
        )
        return JsonObject(LinkedHashMap(body.fields).apply { put("hash", JsonString(Hashing.sha256(body).toString())) })
    }

    /**
     * Handoff как источник контекста следующей сессии (уровень `HANDOFF`):
     * текст для Claude — со всеми полями ТЗ, §27. Происхождение — `MODEL`:
     * это заявления прошлой сессии, а не указания оркестратора (T3).
     */
    fun asSource(): ContextSource = ContextSource(
        key = "handoff:${id.value}",
        level = SourceLevel.HANDOFF,
        project = project,
        sensitivity = Sensitivity.INTERNAL,
        origin = Origin.MODEL,
        content = buildString {
            appendLine("Состояние: $state")
            section("Сделано (заявлено)", completed)
            section("Проверено", verified)
            section("Не удалось", failed)
            section("Предупреждения", warnings)
            section("Открытые проблемы", openProblems)
            currentAction?.let { appendLine("Текущее действие: $it") }
            appendLine("Следующее действие: $nextAction")
            section("Изменённые файлы", modifiedFiles)
            listOfNotNull(branch?.let { "ветка $it" }, commit?.let { "коммит $it" }, workspace?.let { "рабочий каталог $it" })
                .takeIf { it.isNotEmpty() }?.let { appendLine("Git: ${it.joinToString(", ")}") }
            append("Сессия: ${session.value}; контекст: ${context.value} ($contextHash)")
        },
    )

    private fun StringBuilder.section(title: String, items: List<String>) {
        if (items.isEmpty()) return
        appendLine("$title:")
        items.forEach { appendLine("- $it") }
    }

    companion object {
        const val FORMAT = "dpai.handoff"
        const val VERSION = 1
    }
}

/**
 * Реестр handoff: повтор создания с тем же ключом — тот же handoff, без
 * второго (ТЗ, §5.4); handoff не меняется — новое состояние — новый
 * handoff.
 */
class Handoffs {
    private val byKey = LinkedHashMap<String, Handoff>()

    @Synchronized
    fun create(h: Handoff): Outcome<Handoff> {
        if (h.nextAction.isBlank()) return Outcome.failure(DiagCode.VALUE_INVALID, "у handoff нет следующего действия (ТЗ, §27)", "handoff ${h.id}")
        byKey[h.key]?.let { return Outcome.success(it, listOf(Diagnostic.info(DiagCode.HANDOFF_ALREADY_EXISTS, "handoff с этим ключом уже создан", "handoff ${it.id}"))) }
        byKey[h.key] = h
        return Outcome.success(h)
    }

    @Synchronized
    fun latest(task: TaskId): Handoff? = byKey.values.filter { it.task == task }.maxByOrNull { it.createdAt }

    @Synchronized
    fun all(): List<Handoff> = byKey.values.toList()
}
