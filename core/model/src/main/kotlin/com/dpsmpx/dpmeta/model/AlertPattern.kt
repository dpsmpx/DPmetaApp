package com.dpsmpx.dpmeta.model

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

/** Пользовательский regex с линейным поиском, без Java backtracking (D-141). */
class AlertPattern private constructor(val pattern: String, private val compiled: Pattern) {
    fun containsMatchIn(text: String): Boolean = compiled.matcher(text).find()

    override fun equals(other: Any?): Boolean = other is AlertPattern && pattern == other.pattern
    override fun hashCode(): Int = pattern.hashCode()

    companion object {
        const val MAX_LENGTH = 200
        private const val MAX_PROGRAM = 4096

        fun compile(value: String, subject: String? = null): Outcome<AlertPattern> {
            if (value.length > MAX_LENGTH) return Outcome.failure(DiagCode.RULE_INVALID, "regex — не более $MAX_LENGTH знаков", subject)
            return try {
                val compiled = Pattern.compile(value, Pattern.CASE_INSENSITIVE)
                if (compiled.programSize() > MAX_PROGRAM) Outcome.failure(DiagCode.RULE_INVALID, "слишком сложная программа regex", subject)
                else Outcome.success(AlertPattern(value, compiled))
            } catch (_: PatternSyntaxException) {
                Outcome.failure(DiagCode.RULE_INVALID, "regex не поддерживается RE2: проверьте синтаксис; обратные ссылки и lookaround запрещены", subject)
            }
        }
    }
}
