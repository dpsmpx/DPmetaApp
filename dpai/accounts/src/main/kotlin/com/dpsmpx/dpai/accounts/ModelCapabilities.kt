package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.measure.MeasureSource
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/** Логические роли ТЗ автоматизации, §6.1: роль не выдаёт права на инструменты. */
enum class ModelRole { ARCHITECT, IMPLEMENTER, RESEARCHER, REVIEWER, AUDITOR, DEBUGGER, COORDINATOR }

enum class ModelAvailability { AVAILABLE, DISABLED, RATE_LIMITED, UNAVAILABLE }

/** Подтверждённая проверка работы модели; ответ самой модели доказательством не является. */
data class ModelVerification(val evidence: String, val complexity: Int, val passed: Boolean) {
    init { require(evidence.matches(Regex("[0-9a-f]{64}")) && complexity in 1..5) }
}

/**
 * Возможности конкретного API-профиля. binding связывает сведения с провайдером,
 * адресом и моделью; переименование профиля и замена ключа его не меняют.
 * Числа неизвестны с причиной, пользовательские оценки — ESTIMATED.
 * Наличие инструмента в описании не заменяет проверку полномочий вызова.
 */
data class ModelCapabilities(
    val binding: String,
    val roles: Set<ModelRole> = emptySet(),
    val tools: Set<String>? = null,
    val contextTokens: Measure<Long> = Measure.unknown("предел контекста не указан"),
    val code: Measure<Boolean> = Measure.unknown("возможности работы с кодом не указаны"),
    val reasoning: Measure<Boolean> = Measure.unknown("поддержка рассуждений не указана"),
    val relativeCost: Measure<Long> = Measure.unknown("относительная стоимость не указана"),
    val remainingCalls: Measure<Long> = Measure.unknown("доступный остаток не сообщён"),
    val maximumComplexity: Measure<Long> = Measure.unknown("оценка сложности не указана"),
    val availability: Measure<ModelAvailability> = Measure.unknown("доступность не проверена"),
    val specializations: Set<String> = emptySet(),
    val verified: List<ModelVerification> = emptyList(),
) {
    init {
        require(binding.matches(Regex("[0-9a-f]{64}")))
        require(listOf(contextTokens, code, reasoning, relativeCost, remainingCalls, maximumComplexity, availability).all {
            it !is Measure.Unknown || it.reason.isNotBlank() && it.reason.length <= 1000 && it.reason.none(Char::isISOControl)
        })
        require(tools == null || tools.size <= 128 && tools.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,99}")) })
        require(contextTokens.orNull()?.let { it in 1..10_000_000 } != false)
        require(relativeCost.orNull()?.let { it in 1..1_000_000 } != false)
        require(remainingCalls.orNull()?.let { it >= 0 } != false)
        require(maximumComplexity.orNull()?.let { it in 1..5 } != false)
        require(specializations.size <= 32 && specializations.all { it.length in 1..100 && it.none(Char::isISOControl) })
        require(verified.size <= 64 && verified.map { it.evidence }.distinct().size == verified.size)
    }

    /** Результат добавляет только вызывающий, проверивший соответствующее доказательство. */
    fun record(result: ModelVerification): ModelCapabilities = copy(
        verified = (verified.filterNot { it.evidence == result.evidence } + result).takeLast(64),
    )

    fun json(): JsonObject = JsonObject.of(
        "format" to JsonString("dpai.model-capabilities"), "version" to JsonNumber.of(1),
        "binding" to JsonString(binding), "roles" to strings(roles.map { it.name }.sorted()),
        "tools" to (tools?.let { strings(it.sorted()) } ?: JsonNull),
        "contextTokens" to measured(contextTokens) { JsonNumber.of(it) },
        "code" to measured(code, ::JsonBool), "reasoning" to measured(reasoning, ::JsonBool),
        "relativeCost" to measured(relativeCost) { JsonNumber.of(it) },
        "remainingCalls" to measured(remainingCalls) { JsonNumber.of(it) },
        "maximumComplexity" to measured(maximumComplexity) { JsonNumber.of(it) },
        "availability" to measured(availability) { JsonString(it.name) },
        "specializations" to strings(specializations.sorted()),
        "verified" to JsonArray(verified.map {
            JsonObject.of("evidence" to JsonString(it.evidence), "complexity" to JsonNumber.of(it.complexity), "passed" to JsonBool(it.passed))
        }),
    )

    companion object {
        /** Только публичная идентичность; ключ API в отпечаток не входит. */
        fun binding(profile: ApiProfile): String = Hashing.sha256(
            JsonObject.of("profile" to JsonString(profile.id.value), "provider" to JsonString(profile.kind.key),
                "address" to JsonString(profile.baseUrl), "model" to JsonString(profile.model)),
        ).hex

        fun read(value: JsonValue): Outcome<ModelCapabilities> = try {
            val o = value as JsonObject
            require(o.fields.keys == setOf("format", "version", "binding", "roles", "tools", "contextTokens", "code", "reasoning",
                "relativeCost", "remainingCalls", "maximumComplexity", "availability", "specializations", "verified"))
            require(o["format"] == JsonString("dpai.model-capabilities") && o["version"] == JsonNumber.of(1))
            fun text(k: String) = (o[k] as JsonString).value
            fun names(k: String): List<String> = (o[k] as JsonArray).items.also { require(it.size <= 128) }.map { (it as JsonString).value }
            fun long(k: String): Measure<Long> = readMeasured(o[k]) { (it as JsonNumber).toLongExact()!! }
            fun bool(k: String): Measure<Boolean> = readMeasured(o[k]) { (it as JsonBool).value }
            val roleNames = names("roles")
            val toolNames = if (o["tools"] == JsonNull) null else names("tools")
            val specialties = names("specializations")
            require(roleNames.distinct().size == roleNames.size && toolNames?.let { it.distinct().size == it.size } != false && specialties.distinct().size == specialties.size)
            val records = (o["verified"] as JsonArray).items.also { require(it.size <= 64) }.map {
                val record = it as JsonObject
                require(record.fields.keys == setOf("evidence", "complexity", "passed"))
                val rank = (record["complexity"] as JsonNumber).toLongExact()!!.also { n -> require(n in 1..5) }
                ModelVerification((record["evidence"] as JsonString).value, rank.toInt(), (record["passed"] as JsonBool).value)
            }
            Outcome.success(ModelCapabilities(text("binding"), roleNames.map(ModelRole::valueOf).toSet(), toolNames?.toSet(),
                long("contextTokens"), bool("code"), bool("reasoning"), long("relativeCost"), long("remainingCalls"), long("maximumComplexity"),
                readMeasured(o["availability"]) { ModelAvailability.valueOf((it as JsonString).value) }, specialties.toSet(), records))
        } catch (_: IllegalArgumentException) { invalid() } catch (_: ClassCastException) { invalid() } catch (_: NullPointerException) { invalid() }

        private fun invalid(): Outcome<ModelCapabilities> = Outcome.failure(DiagCode.VALUE_INVALID, "неверный профиль возможностей модели")
        private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonString))
        private fun <T> measured(value: Measure<T>, encode: (T) -> JsonValue): JsonObject = when (value) {
            is Measure.Unknown -> JsonObject.of("unknown" to JsonString(value.reason))
            is Measure.Known -> JsonObject.of("value" to encode(value.value), "source" to JsonString(value.source.name))
        }
        private fun <T> readMeasured(raw: JsonValue?, decode: (JsonValue) -> T): Measure<T> {
            val value = raw as JsonObject
            return if (value.fields.keys == setOf("unknown")) {
                val reason = (value["unknown"] as JsonString).value
                require(reason.isNotBlank() && reason.length <= 1000 && reason.none(Char::isISOControl))
                Measure.unknown(reason)
            } else {
                require(value.fields.keys == setOf("value", "source"))
                Measure.Known(decode(value["value"]!!), MeasureSource.valueOf((value["source"] as JsonString).value))
            }
        }
    }
}
