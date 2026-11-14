package com.dpsmpx.dpai.execution.tools

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/** Ответ CLI до передачи аккаунта: старый агент не подтверждает поддержку управляемой папки. */
data class SharedToolsStatus(val windows: Boolean, val defaultRepository: String, val configured: SharedTools?) {
    fun toJson(): JsonObject = JsonObject.of(
        "api" to JsonNumber("1"), "platform" to JsonString(if (windows) "windows" else "posix"),
        "defaultRepository" to JsonString(defaultRepository), "configured" to (configured?.toJson() ?: JsonNull),
    )

    companion object {
        fun parse(value: JsonValue): Outcome<SharedToolsStatus> {
            val o = value as? JsonObject ?: return Outcome.failure(DiagCode.UNSUPPORTED, "обновите агент dpai для подключения общей папки")
            val platform = (o["platform"] as? JsonString)?.value
            val root = (o["defaultRepository"] as? JsonString)?.value
            if ((o["api"] as? JsonNumber)?.literal != "1" || platform !in setOf("windows", "posix") || root == null) {
                return Outcome.failure(DiagCode.UNSUPPORTED, "обновите агент dpai для подключения общей папки")
            }
            val configured = when (val config = o["configured"]) {
                JsonNull -> null
                is JsonObject -> when (val parsed = SharedTools.parse(config)) {
                    is Outcome.Failure -> return parsed
                    is Outcome.Success -> parsed.value
                }
                else -> return Outcome.failure(DiagCode.VALUE_INVALID, "нет настройки общих инструментов в ответе агента")
            }
            val probe = runCatching { SharedTools(root, root + "/runtime.py") }.getOrNull()
            if (probe == null || probe.windows != (platform == "windows") || (configured != null && configured.windows != probe.windows)) {
                return Outcome.failure(DiagCode.VALUE_INVALID, "пути общих инструментов не соответствуют платформе агента")
            }
            return Outcome.success(SharedToolsStatus(probe.windows, root, configured))
        }
    }
}
