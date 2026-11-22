package com.dpsmpx.dpmeta.model

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/** Откуда получено наблюдение (ТЗ, §6.4). */
enum class ObservationSource(val code: String) {
    REGISTRY("registry"),
    GITHUB("github"),
    SITE("site"),
    CLAUDE("claude"),
    SOCIAL("social"),
    MANUAL("manual"),

    /** Каталог на диске: синтетический корпус или клоны репозиториев. */
    DIRECTORY("directory"),
    ;

    companion object {
        fun of(code: String): ObservationSource? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Наблюдение (ТЗ, §6.3): что получено, откуда и когда. [payload] —
 * нормализованный JSON, [rawSha256] — отпечаток исходного ответа.
 * Наблюдение — вход вычисления состояния (D-34): после вычисления оно не
 * хранится, хранятся состояние документом и история чисел. [id] —
 * отпечаток содержимого без времени получения: то же содержимое того же
 * источника и предмета — то же наблюдение, повтор не дублируется.
 */
@ConsistentCopyVisibility
data class Observation private constructor(
    val source: ObservationSource,
    val subject: String,
    val kind: String,
    val asOf: Timestamp,
    val schema: String,
    val payload: JsonValue,
    val rawSha256: ContentHash,
) {
    val id: ContentHash get() = Hashing.sha256(identity())

    private fun identity(): JsonValue = JsonObject(
        linkedMapOf(
            "source" to JsonString(source.code),
            "subject" to JsonString(subject),
            "kind" to JsonString(kind),
            "schema" to JsonString(schema),
            "raw" to JsonString(rawSha256.toString()),
        ),
    )

    companion object {
        private val TOKEN = Regex("[a-z][a-z0-9._-]{0,63}")

        fun of(
            source: ObservationSource,
            subject: String,
            kind: String,
            asOf: Timestamp,
            schema: String,
            payload: JsonValue,
            rawSha256: ContentHash,
        ): Outcome<Observation> = when {
            subject.isBlank() || subject.length > 300 || subject.any { it.isISOControl() } ->
                Outcome.failure(DiagCode.VALUE_INVALID, "предмет наблюдения — от 1 до 300 знаков без управляющих", "subject")
            !TOKEN.matches(kind) -> Outcome.failure(DiagCode.VALUE_INVALID, "вид наблюдения — латиница в нижнем регистре", "kind")
            !TOKEN.matches(schema) -> Outcome.failure(DiagCode.VALUE_INVALID, "схема наблюдения — латиница в нижнем регистре", "schema")
            else -> Outcome.success(Observation(source, subject, kind, asOf, schema, payload, rawSha256))
        }
    }
}

/**
 * Отказ источника при синхронизации (ТЗ, §6.4): проект, вид источника,
 * диагностика и когда случился. По времени отказа «файла нет» не
 * спрашивается снова раньше срока из правил.
 */
data class SourceFailure(val project: String, val kind: String, val diagnostic: com.dpsmpx.dpai.core.diagnostics.Diagnostic, val at: Timestamp)
