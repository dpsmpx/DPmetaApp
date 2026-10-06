package com.dpsmpx.dpai.verification

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.process.Completed
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds

/** Уровень проверки (ТЗ, §33): `FAST` — static, `NORMAL` — affected, `FULL` — full. */
enum class VerifyLevel { FAST, NORMAL, FULL }

/** Шаг профиля: команда в рабочем дереве; [required] — без него проверка не достаточна. */
data class VerifyStep(val name: String, val command: List<String>, val required: Boolean = true, val timeout: Duration = 30.minutes) {
    init {
        require(name.isNotBlank() && command.isNotEmpty()) { "у шага нет имени или команды" }
    }
}

/**
 * Профили проверки проекта (ТЗ, §33; D-16). Задаёт их пользователь в
 * настройках оркестратора, а не репозиторий: файл профиля в чужом
 * репозитории исполнил бы что угодно (T4). Внешний DP-Verify — просто шаг
 * с его командой.
 */
data class VerificationProfile(val project: ProjectId, val levels: Map<VerifyLevel, List<VerifyStep>>) {
    init {
        require(levels.values.all { steps -> steps.map { it.name }.toSet().size == steps.size }) { "имена шагов уровня повторяются" }
    }
}

/** Исполнитель шага: команда в рабочем дереве с тайм-аутом. Реализация — в оркестраторе (процессы — `execution`). */
fun interface StepRunner {
    fun run(command: List<String>, worktree: String, timeout: Duration): Outcome<Completed>
}

enum class StepResult { PASSED, FAILED, PRE_EXISTING, UNAVAILABLE }

/** Доказательство шага (ТЗ, §32, §33): команда, код, хеш вывода, длительность, время. */
data class StepEvidence(
    val name: String,
    val command: List<String>,
    val required: Boolean,
    val result: StepResult,
    val exitCode: Int?,
    val outputHash: ContentHash?,
    val duration: Duration,
    val startedAt: Timestamp,
    val tail: List<String>,
)

/**
 * Итог проверки: `PASSED` — все обязательные шаги прошли (падения из
 * базовой линии — не регрессия, но названы); `FAILED` — новая ошибка;
 * `INCOMPLETE` — обязательный шаг не удалось выполнить (тайм-аут, нет
 * программы) или нет профиля: это не «прошло» (D-13, D-23).
 */
enum class Verdict { PASSED, FAILED, INCOMPLETE }

/** Отчёт `dpai.verification-report` v1 (`docs/DATA_MODEL.md`, §4). */
data class VerificationReport(
    val project: ProjectId?,
    val level: VerifyLevel,
    val commit: String?,
    val steps: List<StepEvidence>,
    val verdict: Verdict,
    val diagnostics: List<Diagnostic>,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "project" to (project?.let { JsonString(it.value) } ?: JsonNull),
            "level" to JsonString(level.name),
            "commit" to (commit?.let(::JsonString) ?: JsonNull),
            "verdict" to JsonString(verdict.name),
            "steps" to JsonArray(steps.map { s ->
                JsonObject.of(
                    "name" to JsonString(s.name), "command" to JsonArray(s.command.map(::JsonString)), "required" to JsonBool(s.required),
                    "result" to JsonString(s.result.name), "exitCode" to (s.exitCode?.let { JsonNumber.of(it) } ?: JsonNull),
                    "outputHash" to (s.outputHash?.let { JsonString(it.toString()) } ?: JsonNull),
                    "durationMs" to JsonNumber.of(s.duration.inWholeMilliseconds), "startedAt" to JsonNumber.of(s.startedAt.epochMillis),
                )
            }),
        ),
    )

    val hash: ContentHash get() = Hashing.sha256(toJson())

    /** Отчёт для человека и описания PR (уровень `report`). */
    fun render(): String = buildString {
        appendLine("verify ($level): **$verdict**" + (commit?.let { " на коммите `${it.take(12)}`" } ?: ""))
        appendLine()
        for (s in steps) {
            val mark = when (s.result) {
                StepResult.PASSED -> "✓"
                StepResult.FAILED -> "✗"
                StepResult.PRE_EXISTING -> "○"
                StepResult.UNAVAILABLE -> "?"
            }
            appendLine("- $mark ${s.name}" + (s.exitCode?.let { " (код $it)" } ?: "") + if (!s.required) " — необязательный" else "")
        }
        if (diagnostics.isNotEmpty()) {
            appendLine()
            diagnostics.forEach { appendLine("- ${it.render()}") }
        }
    }.trimEnd()

    companion object {
        const val FORMAT = "dpai.verification-report"
        const val VERSION = 1
    }
}

/** Базовая линия (`baseline`): какие шаги падали на исходном коммите — до правки. */
data class Baseline(val commit: String?, val failing: Set<String>)

/**
 * Проверка рабочего дерева по профилю (ТЗ, §32, §33; уровни
 * `plan`, `baseline`, `report`). Шаги идут по порядку профиля; упавший
 * обязательный шаг не останавливает остальные: отчёт полон.
 */
class Verifier(private val runner: StepRunner, private val clock: Clock) {
    /** Что будет запущено (`plan`), без запуска. */
    fun plan(profile: VerificationProfile?, level: VerifyLevel): Outcome<List<VerifyStep>> =
        profile?.levels?.get(level)?.takeIf { it.isNotEmpty() }?.let { Outcome.success(it) }
            ?: Outcome.failure(DiagCode.VERIFICATION_PROFILE_MISSING, "у проекта нет профиля уровня $level", profile?.project?.let { "project $it" })

    fun verify(profile: VerificationProfile?, level: VerifyLevel, worktree: String, commit: String?, baseline: Baseline? = null): VerificationReport {
        val project = profile?.project
        val steps = when (val p = plan(profile, level)) {
            is Outcome.Failure -> return VerificationReport(project, level, commit, emptyList(), Verdict.INCOMPLETE, p.diagnostics)
            is Outcome.Success -> p.value
        }
        val notes = mutableListOf<Diagnostic>()
        val evidence = steps.map { step ->
            val startedAt = clock.now()
            val t0 = clock.monotonicNanos()
            val r = runner.run(step.command, worktree, step.timeout)
            val duration = (clock.monotonicNanos() - t0).nanoseconds
            when (r) {
                is Outcome.Failure -> {
                    notes += Diagnostic.of(DiagCode.VERIFICATION_STEP_UNAVAILABLE, "шаг ${step.name} не выполнен", step.name, causes = r.errors)
                    StepEvidence(step.name, step.command, step.required, StepResult.UNAVAILABLE, null, null, duration, startedAt, emptyList())
                }
                is Outcome.Success -> {
                    val done = r.value
                    val output = (done.stdout + done.stderr).joinToString("\n")
                    val result = when {
                        done.ok -> StepResult.PASSED
                        baseline != null && step.name in baseline.failing -> StepResult.PRE_EXISTING
                        else -> StepResult.FAILED
                    }
                    if (result == StepResult.PRE_EXISTING) {
                        notes += Diagnostic.warning(DiagCode.VERIFICATION_BASELINE_FAILURE, "шаг ${step.name} падал и до правки (${baseline?.commit ?: "базовая линия"})", step.name)
                    }
                    StepEvidence(step.name, step.command, step.required, result, done.exitCode, Hashing.sha256(output), duration, startedAt, (done.stdout + done.stderr).takeLast(20))
                }
            }
        }
        val required = evidence.filter { it.required }
        val verdict = when {
            required.any { it.result == StepResult.FAILED } -> Verdict.FAILED
            required.any { it.result == StepResult.UNAVAILABLE } -> Verdict.INCOMPLETE
            else -> Verdict.PASSED
        }
        if (verdict == Verdict.FAILED) {
            notes += Diagnostic.error(DiagCode.VERIFICATION_FAILED, "не прошли: ${required.filter { it.result == StepResult.FAILED }.joinToString { it.name }}", worktree)
        }
        return VerificationReport(project, level, commit, evidence, verdict, notes)
    }

    /** Базовая линия: проверка исходного коммита, запоминаются упавшие шаги. */
    fun baseline(profile: VerificationProfile?, level: VerifyLevel, worktree: String, commit: String?): Baseline {
        val r = verify(profile, level, worktree, commit)
        return Baseline(commit, r.steps.filter { it.result == StepResult.FAILED }.map { it.name }.toSet())
    }

}
