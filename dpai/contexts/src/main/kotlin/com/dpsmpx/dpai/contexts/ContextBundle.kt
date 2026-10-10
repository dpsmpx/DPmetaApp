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

/**
 * Уровень источника (ТЗ, §25): порядок перечисления — порядок вывода в
 * prompt (устойчивое раньше, меняющееся позже). Порядок отбора под
 * давлением бюджета — [ContextPolicy.priority]. `TOOL` — вывод
 * инструментов оркестратора, прежде всего выжимка проваленной проверки
 * (D-40).
 */
enum class SourceLevel { GLOBAL, PROJECT, REPOSITORY, TASK, DOCUMENT, CODE, HANDOFF, TOOL }

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
 * Источник, не вошедший в версию (D-40): по бюджету ([sameAs] = `null`) или
 * как повтор текста источника [sameAs]. В prompt он назван с размером —
 * исполнитель знает, что не видел, и может прочитать сам.
 */
data class OmittedSource(val key: String, val level: SourceLevel, val estimatedTokens: Long, val sameAs: String? = null)

/**
 * Версия контекста задачи (`dpai.context` v3; ТЗ, §24; D-10): неизменна;
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
    val omitted: List<OmittedSource> = emptyList(),
) {
    val hash: ContentHash get() = Hashing.sha256(body(VERSION))

    /** Хеш в виде формата [version]: документ v2 сверяется со своим хешем (`ContextCodec`). */
    internal fun hashAt(version: Int): ContentHash = Hashing.sha256(body(version))

    /** Содержание без времени создания и номера версии — то, по чему решается «изменился ли контекст». */
    fun contentHash(): ContentHash = Hashing.sha256(
        JsonObject.of(
            "task" to JsonString(task.value),
            "project" to JsonString(project.value),
            "objective" to JsonString(objective),
            "nextAction" to (nextAction?.let(::JsonString) ?: JsonNull),
            "sources" to JsonArray(sources.map { JsonObject.of("key" to JsonString(it.key), "hash" to JsonString(it.hash.toString())) }),
            "omitted" to JsonArray(omitted.map(::omittedJson)),
        ),
    )

    private fun body(format: Int): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, format),
            "id" to JsonString(id.value),
            "contextVersion" to JsonNumber.of(version),
            "task" to JsonString(task.value),
            "project" to JsonString(project.value),
            "objective" to JsonString(objective),
            "nextAction" to (nextAction?.let(::JsonString) ?: JsonNull),
            "sources" to JsonArray(sources.map(::sourceJson)),
            "previous" to (previous?.let { JsonString(it.toString()) } ?: JsonNull),
        ).apply { if (format >= 3) put("omitted", JsonArray(omitted.map(::omittedJson))) },
    )

    fun toJson(): JsonObject = JsonObject(LinkedHashMap(body(VERSION).fields).apply {
        put("createdAt", JsonNumber.of(createdAt.epochMillis))
        put("hash", JsonString(hash.toString()))
    })

    companion object {
        const val FORMAT = "dpai.context"
        /**
         * v3: не вошедшие источники `omitted` (D-40). v2: номер версии
         * контекста — `contextVersion`; в v1 он затирал версию формата (ключ
         * `version`), и v1 не читается.
         */
        const val VERSION = 3

        private fun omittedJson(o: OmittedSource): JsonValue = JsonObject(
            linkedMapOf(
                "key" to JsonString(o.key), "level" to JsonString(o.level.name), "estimatedTokens" to JsonNumber.of(o.estimatedTokens),
            ).apply { o.sameAs?.let { put("sameAs", JsonString(it)) } },
        )

        private fun sourceJson(s: IncludedSource): JsonValue = JsonObject.of(
            "key" to JsonString(s.key), "level" to JsonString(s.level.name), "sensitivity" to JsonString(s.sensitivity.name),
            "origin" to JsonString(s.origin.name), "hash" to JsonString(s.hash.toString()), "content" to JsonString(s.content),
        )
    }
}

/**
 * Политика сборки (`docs/EFFICIENCY.md`, §3): разрешён ли `SECRET` (явно, с
 * записью), предел знаков и бюджет в оценочных токенах ([TokenEstimate]),
 * порядок отбора под давлением бюджета, уровни, которые не выпадают молча,
 * и повторы текста. По умолчанию — как до D-40: только предел знаков,
 * отбор в порядке уровней, без критичных уровней и без поиска повторов.
 */
data class ContextPolicy(
    val allowSecret: Boolean = false,
    val maxChars: Int = 200_000,
    val maxTokens: Long? = null,
    val priority: List<SourceLevel> = SourceLevel.entries,
    val critical: Set<SourceLevel> = emptySet(),
    val dedupe: Boolean = false,
) {
    init {
        require(maxChars in 1_000..10_000_000) { "maxChars — 1000…10000000" }
        require(maxTokens == null || maxTokens in 500..10_000_000) { "maxTokens — 500…10000000" }
        require(priority.size == SourceLevel.entries.size && priority.toSet() == SourceLevel.entries.toSet()) { "priority — каждый уровень ровно один раз" }
    }

    companion object {
        /**
         * Порядок отбора D-40: правила, задача, handoff и итог проверки —
         * раньше документов и кода; прежде handoff, последний в порядке
         * уровней, выпадал первым (аудит, W9).
         */
        val PRIORITY: List<SourceLevel> = listOf(
            SourceLevel.GLOBAL, SourceLevel.PROJECT, SourceLevel.TASK, SourceLevel.HANDOFF, SourceLevel.TOOL,
            SourceLevel.REPOSITORY, SourceLevel.DOCUMENT, SourceLevel.CODE,
        )

        /** Критичное: правила, требования задачи, состояние продолжения, итог проверки. */
        val CRITICAL: Set<SourceLevel> = setOf(SourceLevel.GLOBAL, SourceLevel.PROJECT, SourceLevel.TASK, SourceLevel.HANDOFF, SourceLevel.TOOL)

        /** Политика D-40 с бюджетом [maxTokens]. */
        fun budgeted(maxTokens: Long, allowSecret: Boolean = false): ContextPolicy =
            ContextPolicy(allowSecret = allowSecret, maxTokens = maxTokens, priority = PRIORITY, critical = CRITICAL, dedupe = true)
    }
}

/**
 * Сборка версии контекста (`docs/CONTEXT_MODEL.md`, §2, §3;
 * `docs/EFFICIENCY.md`, §3; ТЗ, §25, §65, §67). Отбор детерминирован:
 * источники по [ContextPolicy.priority], затем по ключу; в версии — в
 * порядке уровней (устойчивое раньше).
 *
 * - Источник другого проекта не включается никогда — `CONTEXT_PROJECT_BOUNDARY`
 *   (ТЗ, §67, §107): граница проекта не настраивается политикой.
 * - `SECRET` без разрешения политики — исключён, `CONTEXT_SECRET_EXCLUDED`.
 * - Повтор текста (политика `dedupe`) — один раз, `CONTEXT_DEDUPLICATED`.
 * - Сверх предела — исключён с `CONTEXT_TRUNCATED` и назван в
 *   [ContextBundle.omitted]: что не вошло, названо, а не потеряно молча;
 *   критичный уровень сверх предела — отказ `CONTEXT_BUDGET_EXCEEDED`.
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
        var chars = policy.maxChars
        var tokens = policy.maxTokens ?: Long.MAX_VALUE
        val rank = policy.priority.withIndex().associate { (i, l) -> l to i }
        val included = mutableListOf<IncludedSource>()
        val omitted = mutableListOf<OmittedSource>()
        val missing = mutableListOf<String>()
        val byHash = HashMap<ContentHash, String>()
        for (s in candidates.sortedWith(compareBy({ rank.getValue(it.level) }, { it.key }))) {
            val hash = s.hash
            val cost = TokenEstimate.of(s.content)
            when {
                s.project != null && s.project != project ->
                    notes += Diagnostic.warning(DiagCode.CONTEXT_PROJECT_BOUNDARY, "источник проекта ${s.project} не включён в задачу проекта $project", s.key)
                s.sensitivity == Sensitivity.SECRET && !policy.allowSecret ->
                    notes += Diagnostic.info(DiagCode.CONTEXT_SECRET_EXCLUDED, "секретный источник исключён по умолчанию", s.key)
                policy.dedupe && hash in byHash -> {
                    omitted += OmittedSource(s.key, s.level, cost, sameAs = byHash.getValue(hash))
                    notes += Diagnostic.info(DiagCode.CONTEXT_DEDUPLICATED, "тот же текст, что у ${byHash.getValue(hash)}", s.key)
                }
                s.content.length > chars || cost > tokens ->
                    if (s.level in policy.critical) {
                        missing += "${s.key} (≈$cost токенов)"
                    } else {
                        omitted += OmittedSource(s.key, s.level, cost)
                        notes += Diagnostic.warning(DiagCode.CONTEXT_TRUNCATED, "не вошёл по пределу (${s.content.length} знаков, ≈$cost токенов; осталось $chars знаков, ${if (tokens == Long.MAX_VALUE) "∞" else "≈$tokens"} токенов)", s.key)
                    }
                else -> {
                    chars -= s.content.length
                    tokens -= cost
                    byHash[hash] = s.key
                    included += IncludedSource(s.key, s.level, s.sensitivity, s.origin, hash, s.content)
                }
            }
        }
        if (missing.isNotEmpty()) {
            return Outcome.Failure(
                notes + Diagnostic.error(DiagCode.CONTEXT_BUDGET_EXCEEDED, "критичное не влезает в бюджет контекста: ${missing.joinToString()}", "context $id"),
            )
        }
        if (policy.allowSecret && included.any { it.sensitivity == Sensitivity.SECRET }) {
            notes += Diagnostic.warning(DiagCode.CONTEXT_SECRET_INCLUDED, "секретные источники включены по разрешению политики", "context $id")
        }
        val ordered = included.sortedWith(compareBy({ it.level.ordinal }, { it.key }))
        val omittedOrdered = omitted.sortedWith(compareBy({ it.level.ordinal }, { it.key }))
        val candidate = ContextBundle(id, (previous?.version ?: 0) + 1, task, project, objective, nextAction, ordered, previous?.hash, now, omittedOrdered)
        if (previous != null && previous.contentHash() == candidate.contentHash()) return Outcome.success(previous, notes)
        return Outcome.success(candidate, notes)
    }
}

/**
 * Оценка числа токенов текста (D-40, п. 1): UTF-8 байты / 3 с округлением
 * вверх — с запасом и для латиницы (~4 байта на токен), и для кириллицы
 * (~5–6 байт на токен). Это оценка (`ESTIMATED`), а не счёт провайдера.
 */
object TokenEstimate {
    fun of(text: String): Long {
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return (bytes + 2) / 3
    }
}
