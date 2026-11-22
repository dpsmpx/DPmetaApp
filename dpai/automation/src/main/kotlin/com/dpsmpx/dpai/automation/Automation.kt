package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.operation.OperationResult
import com.dpsmpx.dpai.core.operation.Provenance
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.verification.VerifyStep
import com.dpsmpx.dpai.core.diagnostics.Redactor

/** Четыре существующих источника D-172; это не новые хранилища или исполнители. */
enum class AutomationSource { PULT, MANUAL, VERIFICATION, ENVIRONMENT }
enum class AutomationCategory { REPOSITORY, BUILD, CHECK, RUN, ENVIRONMENT }

/** OPEN_UI сохраняет подтверждение/терминал прежнего инструмента, не имитирует прямой вызов. */
enum class AutomationInvocation { DIRECT, OPEN_UI }

/** В первом пакете параметры — только варианты, уже заданные пользователем в профиле. */
data class AutomationParameter(val name: String, val title: String, val choices: List<String>, val defaultValue: String? = null)

/**
 * Машиночитаемый вид инструмента (D-215). [sourceHash] связывает описание
 * с полным исходным профилем без выдачи его argv/окружения модели.
 * Неизвестные характеристики — null, а не обещание нулевого расхода.
 */
data class AutomationDefinition(
    val id: String,
    val title: String,
    val purpose: String,
    val source: AutomationSource,
    val category: AutomationCategory,
    val sourceHash: String,
    val level: ActionLevel,
    val scopes: List<String>,
    val invocation: AutomationInvocation,
    val available: Boolean,
    val reason: String? = null,
    val parameters: List<AutomationParameter> = emptyList(),
    val platforms: Set<TargetPlatform> = TargetPlatform.entries.toSet(),
    val dependencies: List<String> = emptyList(),
    val timeoutSeconds: Long? = null,
    val remote: Boolean = true,
    val deterministic: Boolean? = null,
    val idempotent: Boolean? = null,
    val cancellable: Boolean = false,
    val verification: String,
    val triggers: Set<String> = setOf("MANUAL"),
    val steps: List<VerifyStep> = emptyList(),
) {
    /** Ревизия не меняется лишь из-за занятости; меняется при смене профиля, места или полномочий. */
    val revision: String get() = Hashing.sha256(JsonObject(details().fields + ("sourceHash" to JsonString(sourceHash)))).hex

    fun inputSchema(): JsonObject = JsonObject.of(
        "type" to JsonString("object"),
        "properties" to JsonObject(parameters.associate { p -> p.name to JsonObject.of(
            "type" to JsonString("string"), "description" to JsonString(p.title), "enum" to JsonArray(p.choices.map(::JsonString)),
            "default" to (p.defaultValue?.let(::JsonString) ?: JsonNull)) }),
        "additionalProperties" to JsonBool(false),
    )

    fun toJson(): JsonObject = JsonObject(details().fields + mapOf(
        "revision" to JsonString(revision), "available" to JsonBool(available), "reason" to (reason?.let(::JsonString) ?: JsonNull),
    ))

    private fun details(): JsonObject = JsonObject.of(
        "id" to JsonString(id), "title" to JsonString(title), "purpose" to JsonString(purpose), "version" to JsonNumber.of(1),
        "source" to JsonString(source.name), "category" to JsonString(category.name), "level" to JsonNumber.of(level.code),
        "confirmation" to JsonString(level.confirmation), "scopes" to JsonArray(scopes.map(::JsonString)),
        "invocation" to JsonString(invocation.name), "inputSchema" to inputSchema(),
        "outputSchema" to JsonObject.of("type" to JsonString("object")),
        "platforms" to JsonArray(TargetPlatform.entries.filter { it in platforms }.map { JsonString(it.name) }),
        "dependencies" to JsonArray(dependencies.map(::JsonString)), "timeoutSeconds" to (timeoutSeconds?.let { JsonNumber.of(it) } ?: JsonNull),
        "remote" to JsonBool(remote), "deterministic" to known(deterministic), "idempotent" to known(idempotent),
        "memoryBytes" to JsonNull, "diskBytes" to JsonNull, "cancellable" to JsonBool(cancellable),
        "verification" to JsonString(verification), "retry" to JsonString("EXPLICIT_ONLY"),
        "triggers" to JsonArray(triggers.sorted().map(::JsonString)),
        "steps" to JsonArray(steps.map { step -> JsonObject.of("name" to JsonString(Redactor.STANDARD.redact(step.name)),
            "required" to JsonBool(step.required), "timeoutSeconds" to JsonNumber.of(step.timeout.inWholeSeconds)) }),
    )

    private fun known(value: Boolean?): JsonValue = value?.let(::JsonBool) ?: JsonNull
}

/** Каталог — снимок нынешних реестров. Коллизия никогда не выбирает первую команду молча. */
class AutomationCatalog private constructor(val entries: List<AutomationDefinition>) {
    fun byId(id: String): AutomationDefinition? = entries.firstOrNull { it.id == id }
    fun toJson(): JsonObject = JsonObject.of("entries" to JsonArray(entries.map { it.toJson() }))

    /**
     * Общая проверка до передачи прежнему исполнителю. [authorize] обязателен:
     * знание id/ревизии не является разрешением. Никаких процессов и повторов здесь нет.
     */
    fun invoke(id: String, revision: String, input: JsonObject, provenance: Provenance, ids: IdGenerator, clock: Clock,
        cancelled: () -> Boolean, authorize: (AutomationDefinition) -> Outcome<Unit>,
        execute: (AutomationDefinition, Map<String, String>) -> Outcome<JsonObject>): OperationResult<JsonObject> {
        fun run(): Outcome<JsonObject> {
            if (cancelled()) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "вызов автоматизации отменён")
            val entry = byId(id) ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "автоматизации нет в каталоге", id)
            if (revision != entry.revision) return Outcome.failure(DiagCode.STATE_CONFLICT, "описание инструмента изменилось; запросите его заново", id)
            if (!entry.available) return Outcome.failure(DiagCode.STATE_CONFLICT, entry.reason ?: "инструмент недоступен", id)
            if (entry.invocation != AutomationInvocation.DIRECT) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "откройте прежний экран инструмента для подтверждения или терминала", id)
            if (input.fields.keys.any { key -> entry.parameters.none { it.name == key } }) return Outcome.failure(DiagCode.FIELD_UNKNOWN, "неизвестный параметр автоматизации", id)
            val values = linkedMapOf<String, String>()
            for ((key, value) in input.fields) {
                val text = (value as? JsonString)?.value ?: return Outcome.failure(DiagCode.FIELD_TYPE, "параметр должен быть строкой", key)
                if (text !in entry.parameters.first { it.name == key }.choices) return Outcome.failure(DiagCode.VALUE_INVALID, "значения нет среди вариантов профиля", key)
                values[key] = text
            }
            when (val allowed = authorize(entry)) { is Outcome.Failure -> return allowed; is Outcome.Success -> Unit }
            if (cancelled()) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "вызов автоматизации отменён")
            return execute(entry, values)
        }
        val outcome = try { run() } catch (_: java.util.concurrent.CancellationException) {
            Outcome.failure(DiagCode.CANCELLED_BY_USER, "вызов автоматизации отменён")
        } catch (e: Exception) {
            Outcome.failure(DiagCode.INTERNAL_ERROR, "сбой исполнителя автоматизации: ${e.javaClass.simpleName}", id)
        }
        return OperationResult.record("automation.$id", provenance, ids, clock, outcome)
    }

    companion object {
        private val ID = Regex("[a-z][a-z0-9_.-]{0,119}")
        private val PARAM = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
        private val HASH = Regex("[a-f0-9]{64}")

        fun of(entries: List<AutomationDefinition>): Outcome<AutomationCatalog> {
            if (entries.size > 500 || entries.map { it.id }.toSet().size != entries.size) return Outcome.failure(DiagCode.VALUE_INVALID, "каталог слишком велик или содержит повтор id")
            for (e in entries) {
                if (!ID.matches(e.id) || !HASH.matches(e.sourceHash) || e.title.isBlank() || e.title.length > 200 || e.purpose.length > 2000 ||
                    e.scopes.isEmpty() || e.scopes.size > 16 || e.scopes.any { it.isBlank() || it.length > 4096 || '\u0000' in it } ||
                    e.platforms.isEmpty() || e.dependencies.size > 100 || e.parameters.size > 32 ||
                    e.parameters.map { it.name }.toSet().size != e.parameters.size ||
                    e.parameters.any { !PARAM.matches(it.name) || it.choices.isEmpty() || it.choices.size > 100 || it.defaultValue?.let { v -> v !in it.choices } == true || it.choices.any { v -> v.length > 4096 || '\u0000' in v } } ||
                    e.timeoutSeconds?.let { it < 1 } == true || e.verification.isBlank() || e.triggers.isEmpty()) {
                    return Outcome.failure(DiagCode.VALUE_INVALID, "неверное описание автоматизации", e.id)
                }
            }
            return Outcome.success(AutomationCatalog(entries.sortedBy { it.id }))
        }
    }
}
