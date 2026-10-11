package com.dpsmpx.dpmeta.model

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/**
 * Репозиторий GitHub `владелец/имя` (ТЗ, §6.3). Строгая проверка: имя не
 * подставит в запрос путь или параметр.
 */
@ConsistentCopyVisibility
data class RepoRef private constructor(val owner: String, val name: String) {
    override fun toString(): String = "$owner/$name"

    companion object {
        private val OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
        private val NAME = Regex("[A-Za-z0-9._-]{1,100}")

        fun of(owner: String, name: String): Outcome<RepoRef> = when {
            !OWNER.matches(owner) -> Outcome.failure(DiagCode.VALUE_INVALID, "владелец репозитория недопустим", owner)
            !NAME.matches(name) || name == "." || name == ".." || name.endsWith(".git") ->
                Outcome.failure(DiagCode.VALUE_INVALID, "имя репозитория недопустимо", name)
            else -> Outcome.success(RepoRef(owner, name))
        }

        /** Из записи `владелец/имя`. */
        fun parse(text: String): Outcome<RepoRef> {
            val parts = text.trim().split('/')
            if (parts.size != 2) return Outcome.failure(DiagCode.VALUE_INVALID, "ожидалось «владелец/имя»", text)
            return of(parts[0], parts[1])
        }
    }
}

/**
 * Уровень действия (ТЗ, §6.5; D-07). Уровня 3 в коде нет: запрещённое не
 * описывается типом, который можно передать в исполнитель.
 */
enum class ActionLevel(val code: Int, val confirmation: String) {
    /** Чтение: без подтверждения. */
    READ(0, "без подтверждения"),

    /** Обратимое, видимое: одно нажатие с показом, что уйдёт. */
    REVERSIBLE(1, "одно нажатие с показом, что уйдёт"),

    /** Значимое: экран «что изменится» и биометрия или PIN устройства. */
    SIGNIFICANT(2, "экран «что изменится» и биометрия или PIN"),
}

/** Откуда получено наблюдение (ТЗ, §6.4). */
enum class ObservationSource(val code: String) {
    REGISTRY("registry"),
    GITHUB("github"),
    SITE("site"),
    CLAUDE("claude"),
    SOCIAL("social"),
    MANUAL("manual"),
    ;

    companion object {
        fun of(code: String): ObservationSource? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Наблюдение (ТЗ, §6.3): что получено, откуда и когда. [payload] —
 * нормализованный JSON, [rawSha256] — отпечаток исходного ответа. Только
 * добавляется; [id] — отпечаток содержимого без времени получения: то же
 * содержимое того же источника и предмета — то же наблюдение, повтор не
 * дублируется.
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

    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "source" to JsonString(source.code),
            "subject" to JsonString(subject),
            "kind" to JsonString(kind),
            "asOf" to JsonNumber.of(asOf.epochMillis),
            "schema" to JsonString(schema),
            "payload" to payload,
            "rawSha256" to JsonString(rawSha256.toString()),
        ),
    )

    companion object {
        const val FORMAT = "dpmeta.observation"
        const val VERSION = 1
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

        fun fromJson(value: JsonValue): Outcome<Observation> {
            val d = DiagnosticCollector()
            val f = JsonFields.root(value, d) ?: return Outcome.Failure(d.all)
            FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
            val source = f.string("source")
            val subject = f.string("subject")
            val kind = f.string("kind")
            val asOf = f.long("asOf")
            val schema = f.string("schema")
            val payload = f.raw("payload")
            val raw = f.string("rawSha256")
            f.finish()
            if (d.hasErrors || source == null || subject == null || kind == null || asOf == null || schema == null || payload == null || raw == null) {
                return Outcome.Failure(d.all)
            }
            val src = ObservationSource.of(source) ?: return Outcome.failure(DiagCode.VALUE_INVALID, "неизвестный источник наблюдения", source)
            val hash = when (val h = ContentHash.parse(raw)) {
                is Outcome.Failure -> return h
                is Outcome.Success -> h.value
            }
            return of(src, subject, kind, Timestamp(asOf), schema, payload, hash)
        }
    }
}
