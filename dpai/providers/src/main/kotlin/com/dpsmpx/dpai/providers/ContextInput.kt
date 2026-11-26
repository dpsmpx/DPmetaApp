package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.contexts.TokenEstimate
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject

/** Оценивается фактическое тело каждого раунда: схемы, подписи рассуждений и ответы инструментов тоже стоят места. */
data class ContextInputBudget(val maxChars: Int, val maxTokens: Long) {
    init { require(maxChars > 0 && maxTokens > 0) }
}

internal fun ChatRequest.checkContext(body: JsonObject): Outcome<Unit> {
    beforeSend?.invoke()?.let { if (it is Outcome.Failure) return it }
    val budget = inputBudget ?: return Outcome.success(Unit)
    val text = CanonicalJson.write(body)
    return if (text.length > budget.maxChars || TokenEstimate.of(text) > budget.maxTokens)
        Outcome.failure(DiagCode.CONTEXT_BUDGET_EXCEEDED, "тело запроса с инструментами превышает бюджет контекста; завершите ход и подготовьте меньшую выборку")
    else Outcome.success(Unit)
}
