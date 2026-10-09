package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.ApprovalId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.notifications.ApprovalAction
import com.dpsmpx.dpai.notifications.NotificationCategory
import com.dpsmpx.dpai.notifications.NotificationRequest
import com.dpsmpx.dpai.security.Approval
import com.dpsmpx.dpai.security.Proposal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Одобрения из уведомлений (ТЗ, §37, §68): чувствительное действие —
 * запрос с кнопками; одобрение выдаётся, только если нажата кнопка
 * текущего запроса по тому же предмету и с тем же хешем предложения, и
 * запрос не истёк. Новое предложение по предмету заменяет прежнее: его
 * кнопки больше ничего не одобряют.
 */
class ApprovalDesk(val ttl: Duration = 30.minutes) {
    private data class Pending(val id: ApprovalId, val proposal: Proposal, val expiresAt: Timestamp)

    private val bySubject = HashMap<String, Pending>()

    @Synchronized
    /**
     * [title] и [body] — для человека (что и зачем); без них — само
     * предложение. Хеш и кнопки — всегда от [proposal]: текст подписи
     * одобряемое не меняет.
     */
    fun request(
        id: ApprovalId,
        proposal: Proposal,
        now: Timestamp,
        title: String? = null,
        body: String? = null,
        link: com.dpsmpx.dpai.notifications.DeepLink? = null,
    ): NotificationRequest {
        bySubject[proposal.subject] = Pending(id, proposal, now + ttl)
        return NotificationRequest(
            key = "approval:${proposal.subject}",
            category = NotificationCategory.APPROVAL,
            title = title ?: "Нужно одобрение: ${proposal.action}",
            body = body ?: "${proposal.subject}\n${proposal.arguments.joinToString(" ")}".trim(),
            link = link,
            at = now,
            actions = ApprovalAction.buttons(id, proposal.hash),
        )
    }

    /** Нажатие кнопки: одобрение — [Approval] для `ApprovalLedger`; отказ — снятие запроса. */
    @Synchronized
    fun respond(uri: String, by: String, now: Timestamp): Outcome<Approval?> {
        val action = when (val a = ApprovalAction.parse(uri)) {
            is Outcome.Failure -> return a
            is Outcome.Success -> a.value
        }
        val pending = bySubject.values.firstOrNull { it.id == action.request }
            ?: return Outcome.failure(DiagCode.APPROVAL_INVALID, "запрос одобрения не ждёт ответа: заменён, отвечен или неизвестен")
        val why = when {
            pending.proposal.hash != action.proposalHash -> "кнопка относится к другому предложению"
            now >= pending.expiresAt -> "запрос одобрения истёк"
            else -> null
        }
        if (why != null) return Outcome.failure(DiagCode.APPROVAL_INVALID, why, pending.proposal.subject)
        bySubject.remove(pending.proposal.subject)
        return Outcome.success(if (action.approve) Approval(pending.id, pending.proposal.hash, by, now, pending.expiresAt) else null)
    }

    @Synchronized
    fun pending(): Int = bySubject.size

    /** Запрос, ждавший ответа до перезапуска, — снова ждёт, с прежним сроком: старая кнопка действует. */
    @Synchronized
    fun restore(id: ApprovalId, proposal: Proposal, expiresAt: Timestamp) {
        bySubject[proposal.subject] = Pending(id, proposal, expiresAt)
    }
}
