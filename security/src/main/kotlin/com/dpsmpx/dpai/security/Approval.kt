package com.dpsmpx.dpai.security

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.ApprovalId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString

/**
 * Предложение чувствительного действия (ТЗ, §68; `docs/SECURITY_MODEL.md`):
 * что именно будет сделано и где. Одобрение привязано к хешу
 * канонического вида предложения — одобрение `reset --hard` в одном
 * дереве не подходит к другому дереву или к другой операции.
 */
data class Proposal(val action: String, val subject: String, val arguments: List<String>) {
    val hash: ContentHash
        get() = Hashing.sha256(
            JsonObject.of("action" to JsonString(action), "subject" to JsonString(subject), "arguments" to JsonArray(arguments.map(::JsonString))),
        )
}

/**
 * Одобрение пользователя: на предложение с хешем [proposalHash], до
 * [expiresAt]. Одноразовое — повторное использование ловит [ApprovalLedger].
 */
data class Approval(val id: ApprovalId, val proposalHash: ContentHash, val approvedBy: String, val approvedAt: Timestamp, val expiresAt: Timestamp)

/**
 * Проверка одобрений (ТЗ, §68): нет одобрения — `APPROVAL_REQUIRED`;
 * одобрено другое предложение, истекло или уже использовано —
 * `APPROVAL_INVALID`. Использованное одобрение запоминается.
 */
class ApprovalLedger {
    private val used = HashSet<ApprovalId>()

    @Synchronized
    fun consume(proposal: Proposal, approval: Approval?, now: Timestamp): Outcome<Approval> {
        if (approval == null) {
            return Outcome.failure(DiagCode.APPROVAL_REQUIRED, "действие ${proposal.action} требует одобрения пользователя", proposal.subject)
        }
        val why = when {
            approval.proposalHash != proposal.hash -> "одобрено другое предложение"
            now >= approval.expiresAt -> "одобрение истекло"
            approval.id in used -> "одобрение уже использовано"
            else -> null
        }
        if (why != null) return Outcome.failure(DiagCode.APPROVAL_INVALID, why, proposal.subject)
        used += approval.id
        return Outcome.success(approval)
    }
}
