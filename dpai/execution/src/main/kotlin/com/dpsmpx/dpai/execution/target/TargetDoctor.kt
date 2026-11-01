package com.dpsmpx.dpai.execution.target

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.execution.agent.DoctorReport
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Итог одной проверки `doctor`. `UNKNOWN` — не удалось узнать (D-13), а не «в порядке». */
enum class CheckStatus { OK, WARN, FAIL, UNKNOWN }

data class DoctorCheck(val name: String, val status: CheckStatus, val detail: String)

data class DoctorResult(val checks: List<DoctorCheck>, val diagnostics: List<Diagnostic> = emptyList()) {
    /** Худший итог: FAIL > UNKNOWN > WARN > OK. */
    val overall: CheckStatus
        get() = when {
            checks.any { it.status == CheckStatus.FAIL } -> CheckStatus.FAIL
            checks.any { it.status == CheckStatus.UNKNOWN } -> CheckStatus.UNKNOWN
            checks.any { it.status == CheckStatus.WARN } -> CheckStatus.WARN
            else -> CheckStatus.OK
        }
}

/** Пороги `doctor` (ТЗ, §34). */
data class DoctorPolicy(
    val diskWarnBytes: Long = 1L shl 30,
    val diskFailBytes: Long = 200L shl 20,
    val memoryWarnBytes: Long = 256L shl 20,
    val clockWarn: Duration = 30.seconds,
    val clockFail: Duration = 5.minutes,
)

/**
 * `doctor` для target (ТЗ, §34; D-26): связь, агент, Claude Code, Git, диск,
 * память, часы. Расхождение часов — время target минус середина интервала
 * запроса по часам контроллера; ошибка оценки — половина задержки.
 * Отсутствие Claude Code — FAIL: target без Claude не исполнитель (D-26).
 * Версия агента не та, что у контроллера, — WARN: протокол
 * `dpai.agent-reply` строгий, и новое поле одной стороны — отказ другой.
 */
object TargetDoctor {
    fun evaluate(
        report: Outcome<DoctorReport>,
        sentAt: Timestamp,
        receivedAt: Timestamp,
        policy: DoctorPolicy = DoctorPolicy(),
        controllerVersion: String? = null,
    ): DoctorResult {
        val r = when (report) {
            is Outcome.Failure -> return DoctorResult(
                // Словами и одним разом: не `render()` с «ERROR … at …» и классом исключения (D-89; аудит 1 ноября 2026, A-5).
                listOf(DoctorCheck("связь", CheckStatus.FAIL, report.errors.joinToString("; ") { "${Words.reason(it.code.name)}: ${it.message}" })),
                report.diagnostics,
            )
            is Outcome.Success -> report.value
        }
        val checks = mutableListOf<DoctorCheck>()
        val rtt = receivedAt - sentAt
        checks += DoctorCheck("связь", CheckStatus.OK, "ответ за ${rtt.inWholeMilliseconds} мс")
        val agentLine = "dpai ${r.agentVersion}; ${r.os} ${r.arch}; Java ${r.javaVersion}" + if (r.termux) "; Termux" else ""
        checks += if (controllerVersion != null && controllerVersion != r.agentVersion) {
            DoctorCheck("агент", CheckStatus.WARN, "$agentLine; контроллер $controllerVersion — обновить агента")
        } else {
            DoctorCheck("агент", CheckStatus.OK, agentLine)
        }
        checks += tool("Claude Code", r.claude.found, r.claude.version, r.claude.detail, CheckStatus.FAIL)
        checks += tool("Git", r.git.found, r.git.version, r.git.detail, CheckStatus.WARN)
        checks += when (val free = r.diskFreeBytes) {
            null -> DoctorCheck("диск", CheckStatus.UNKNOWN, "свободное место неизвестно")
            else -> DoctorCheck(
                "диск",
                when {
                    free < policy.diskFailBytes -> CheckStatus.FAIL
                    free < policy.diskWarnBytes -> CheckStatus.WARN
                    else -> CheckStatus.OK
                },
                "свободно ${free shr 20} МиБ",
            )
        }
        checks += when (val free = r.memoryFreeBytes) {
            null -> DoctorCheck("память", CheckStatus.UNKNOWN, "свободная память неизвестна")
            else -> DoctorCheck(
                "память",
                if (free < policy.memoryWarnBytes) CheckStatus.WARN else CheckStatus.OK,
                "свободно ${free shr 20} МиБ" + (r.memoryTotalBytes?.let { " из ${it shr 20} МиБ" } ?: ""),
            )
        }
        val mid = sentAt.epochMillis + (receivedAt.epochMillis - sentAt.epochMillis) / 2
        val skew = (r.timeMillis - mid).milliseconds
        val err = rtt / 2
        val magnitude = (abs(skew.inWholeMilliseconds).milliseconds - err).coerceAtLeast(Duration.ZERO)
        checks += DoctorCheck(
            "часы",
            when {
                magnitude > policy.clockFail -> CheckStatus.FAIL
                magnitude > policy.clockWarn -> CheckStatus.WARN
                else -> CheckStatus.OK
            },
            "расхождение ${skew.inWholeMilliseconds} мс ± ${err.inWholeMilliseconds} мс",
        )
        return DoctorResult(checks, report.diagnostics)
    }

    private fun tool(name: String, found: Boolean, version: String?, detail: String?, missing: CheckStatus): DoctorCheck =
        if (found) {
            DoctorCheck(name, CheckStatus.OK, version ?: "версия неизвестна")
        } else {
            DoctorCheck(name, missing, detail ?: "не найден")
        }
}
