package com.dpsmpx.dpai.execution.agent

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Что запустить под агентом (`dpai.run-spec` v1). Окружение — правка
 * окружения агента: [unsetEnv] удаляются, [setEnv] задаются (D-29:
 * окружение слота). [stdin] — вход процесса: так Claude получает prompt, а
 * не аргументом командной строки (D-14; длина строки команды ограничена).
 *
 * Контроллер передаёт спецификацию агенту на стандартный вход
 * (`dpai agent start --spec-stdin`), а не аргументами: JSON-схема и prompt
 * не проходят через кавычки удалённой оболочки (D-31).
 */
data class RunSpec(
    val command: List<String>,
    val workDir: String? = null,
    val setEnv: Map<String, String> = emptyMap(),
    val unsetEnv: List<String> = emptyList(),
    val timeout: Duration? = null,
    val stdin: String? = null,
) {
    init {
        require(command.isNotEmpty()) { "пустая команда" }
        require(setEnv.keys.all { ENV_NAME.matches(it) } && unsetEnv.all { ENV_NAME.matches(it) }) { "имя переменной окружения недопустимо" }
        require(timeout == null || timeout.isPositive()) { "тайм-аут — положительный" }
    }

    fun toJson(): JsonObject {
        val f = linkedMapOf<String, JsonValue>(*FormatHeader.write(FORMAT, VERSION))
        f["command"] = JsonArray(command.map(::JsonString))
        workDir?.let { f["workDir"] = JsonString(it) }
        f["setEnv"] = JsonObject(setEnv.toSortedMap().mapValues { JsonString(it.value) })
        f["unsetEnv"] = JsonArray(unsetEnv.map(::JsonString))
        timeout?.let { f["timeoutSeconds"] = JsonNumber.of(it.inWholeSeconds.coerceAtLeast(1)) }
        stdin?.let { f["stdin"] = JsonString(it) }
        return JsonObject(f)
    }

    companion object {
        const val FORMAT = "dpai.run-spec"
        const val VERSION = 1
        val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")

        fun parse(text: String): Outcome<RunSpec> = when (val v = JsonParser().parse(text)) {
            is Outcome.Failure -> v
            is Outcome.Success -> fromJson(v.value)
        }

        fun fromJson(value: JsonValue): Outcome<RunSpec> {
            val d = DiagnosticCollector()
            val f = JsonFields.root(value, d) ?: return Outcome.Failure(d.all)
            FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
            val command = f.array("command")?.items?.map { (it as? JsonString)?.value }
            val workDir = f.optString("workDir")
            val setEnv = (f.raw("setEnv") as? JsonObject)?.fields?.mapValues { (it.value as? JsonString)?.value }
            val unset = f.array("unsetEnv")?.items?.map { (it as? JsonString)?.value }
            val timeout = f.optLong("timeoutSeconds")?.seconds
            val stdin = f.optString("stdin")
            f.finish()
            if (d.hasErrors) return Outcome.Failure(d.all)
            if (command == null || command.isEmpty() || command.any { it == null } || setEnv == null || setEnv.values.any { it == null } ||
                unset == null || unset.any { it == null }
            ) {
                return Outcome.failure(DiagCode.FIELD_TYPE, "command, setEnv, unsetEnv — непустой список и строки", "$")
            }
            return runCatching {
                RunSpec(command.filterNotNull(), workDir, setEnv.mapValues { it.value!! }, unset.filterNotNull(), timeout, stdin)
            }.fold(
                { Outcome.success(it) },
                { Outcome.failure(Diagnostic.error(DiagCode.VALUE_INVALID, it.message ?: "спецификация недопустима", "$")) },
            )
        }
    }
}
