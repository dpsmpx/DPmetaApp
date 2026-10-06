package com.dpsmpx.dpai.contexts

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.ContextId
import com.dpsmpx.dpai.core.ids.ProjectId
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

/** Уровень источника (ТЗ, §25): порядок перечисления — порядок отбора. */
enum class SourceLevel { GLOBAL, PROJECT, REPOSITORY, TASK, DOCUMENT, CODE, HANDOFF }

/**
 * Источник контекста: устойчивый ключ (`doc:docs/ARCHITECTURE.md`,
 * `handoff:hnd_…`), уровень, проект ([project] = `null` — общий для всех
 * проектов, только уровень `GLOBAL`), секретность и происхождение.
 */
data class ContextSource(
    val key: String,
    val level: SourceLevel,
    val project: ProjectId?,
    val sensitivity: Sensitivity,
    val origin: Origin,
    val content: String,
) {
    init {
        require(KEY.matches(key)) { "ключ источника — 1…300 знаков без управляющих" }
        require(project != null || level == SourceLevel.GLOBAL) { "у источника уровня $level должен быть проект" }
    }

    val hash: ContentHash get() = Hashing.sha256(content)

    companion object {
        private val KEY = Regex("[^\\p{Cntrl}]{1,300}")
    }
}

/** Включённый источник в манифесте версии: ключ, уровень, секретность, происхождение, хеш и содержимое. */
data class IncludedSource(
    val key: String,
    val level: SourceLevel,
    val sensitivity: Sensitivity,
    val origin: Origin,
    val hash: ContentHash,
    val content: String,
)

/**
 * Версия контекста задачи (`dpai.context` v1; ТЗ, §24; D-10): неизменна;
 * изменился любой источник, цель или следующее действие — новая версия с
 * тем же [id] и [version] + 1. [hash] — SHA-256 канонического вида без
 * времени создания: одинаковые входы — одинаковый хеш (D-22).
 */
data class ContextBundle(
    val id: ContextId,
    val version: Int,
    val task: TaskId,
    val project: ProjectId,
    val objective: String,
    val nextAction: String?,
    val sources: List<IncludedSource>,
    val previous: ContentHash?,
    val createdAt: Timestamp,
) {
    val hash: ContentHash get() = Hashing.sha256(body())

    /** Содержание без времени создания и номера версии — то, по чему решается «изменился ли контекст». */
    fun contentHash(): ContentHash = Hashing.sha256(
        JsonObject.of(
            "task" to JsonString(task.value),
            "project" to JsonString(project.value),
            "objective" to JsonString(objective),
            "nextAction" to (nextAction?.let(::JsonString) ?: JsonNull),
            "sources" to JsonArray(sources.map { JsonObject.of("key" to JsonString(it.key), "hash" to JsonString(it.hash.toString())) }),
        ),
    )

    private fun body(): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "id" to JsonString(id.value),
            "version" to JsonNumber.of(version),
            "task" to JsonString(task.value),
            "project" to JsonString(project.value),
            "objective" to JsonString(objective),
            "nextAction" to (nextAction?.let(::JsonString) ?: JsonNull),
            "sources" to JsonArray(sources.map(::sourceJson)),
            "previous" to (previous?.let { JsonString(it.toString()) } ?: JsonNull),
        ),
    )

    fun toJson(): JsonObject = JsonObject(LinkedHashMap(body().fields).apply {
        put("createdAt", JsonNumber.of(createdAt.epochMillis))
        put("hash", JsonString(hash.toString()))
    })

    companion object {
        const val FORMAT = "dpai.context"
        const val VERSION = 1

        private fun sourceJson(s: IncludedSource): JsonValue = JsonObject.of(
            "key" to JsonString(s.key), "level" to JsonString(s.level.name), "sensitivity" to JsonString(s.sensitivity.name),
            "origin" to JsonString(s.origin.name), "hash" to JsonString(s.hash.toString()), "content" to JsonString(s.content),
        )
    }
}

/** Политика сборки: разрешён ли `SECRET` (явно, с записью) и предел объёма содержимого. */
data class ContextPolicy(val allowSecret: Boolean = false, val maxChars: Int = 200_000) {
    init {
        require(maxChars in 1_000..10_000_000) { "maxChars — 1000…10000000" }
    }
}

/**
 * Сборка версии контекста (`docs/CONTEXT_MODEL.md`, §2, §3; ТЗ, §25, §65,
 * §67). Отбор детерминирован: источники по уровню, затем по ключу.
 *
 * - Источник другого проекта не включается никогда — `CONTEXT_PROJECT_BOUNDARY`
 *   (ТЗ, §67, §107): граница проекта не настраивается политикой.
 * - `SECRET` без разрешения политики — исключён, `CONTEXT_SECRET_EXCLUDED`.
 * - Сверх предела объёма — исключён с `CONTEXT_TRUNCATED`: что не вошло,
 *   названо, а не потеряно молча.
 * - Тот же контекст, что у [previous], — та же версия, а не новая.
 */
object ContextAssembler {
    fun assemble(
        id: ContextId,
        task: TaskId,
        project: ProjectId,
        objective: String,
        nextAction: String?,
        candidates: List<ContextSource>,
        now: Timestamp,
        previous: ContextBundle? = null,
        policy: ContextPolicy = ContextPolicy(),
    ): Outcome<ContextBundle> {
        if (objective.isBlank()) return Outcome.failure(DiagCode.VALUE_INVALID, "у контекста нет цели", "task $task")
        if (previous != null && (previous.id != id || previous.task != task || previous.project != project)) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "прежняя версия — другого контекста, задачи или проекта", "context $id")
        }
        val notes = mutableListOf<Diagnostic>()
        val dup = candidates.groupBy { it.key }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) return Outcome.failure(DiagCode.VALUE_INVALID, "источники с одинаковым ключом: ${dup.sorted().joinToString()}", "context $id")
        var budget = policy.maxChars
        val included = mutableListOf<IncludedSource>()
        for (s in candidates.sortedWith(compareBy({ it.level.ordinal }, { it.key }))) {
            when {
                s.project != null && s.project != project ->
                    notes += Diagnostic.warning(DiagCode.CONTEXT_PROJECT_BOUNDARY, "источник проекта ${s.project} не включён в задачу проекта $project", s.key)
                s.sensitivity == Sensitivity.SECRET && !policy.allowSecret ->
                    notes += Diagnostic.info(DiagCode.CONTEXT_SECRET_EXCLUDED, "секретный источник исключён по умолчанию", s.key)
                s.content.length > budget ->
                    notes += Diagnostic.warning(DiagCode.CONTEXT_TRUNCATED, "не вошёл по пределу объёма (${s.content.length} знаков, осталось $budget)", s.key)
                else -> {
                    budget -= s.content.length
                    included += IncludedSource(s.key, s.level, s.sensitivity, s.origin, s.hash, s.content)
                }
            }
        }
        if (policy.allowSecret && included.any { it.sensitivity == Sensitivity.SECRET }) {
            notes += Diagnostic.warning(DiagCode.CONTEXT_SECRET_INCLUDED, "секретные источники включены по разрешению политики", "context $id")
        }
        val candidate = ContextBundle(id, (previous?.version ?: 0) + 1, task, project, objective, nextAction, included, previous?.hash, now)
        if (previous != null && previous.contentHash() == candidate.contentHash()) return Outcome.success(previous, notes)
        return Outcome.success(candidate, notes)
    }
}
