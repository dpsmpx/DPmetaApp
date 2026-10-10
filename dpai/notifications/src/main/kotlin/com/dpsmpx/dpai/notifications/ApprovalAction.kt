package com.dpsmpx.dpai.notifications

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.ids.ApprovalId
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Кнопки одобрения в уведомлении (ТЗ, §37, §68): ссылка несёт запрос и хеш
 * предложения — `dpai://approve/<apr_…>?hash=sha256:…` или
 * `dpai://deny/…`. Нажатие действует, только если хеш совпадает с
 * предложением, которое ждёт одобрения сейчас.
 */
data class ApprovalAction(val approve: Boolean, val request: ApprovalId, val proposalHash: ContentHash) {
    fun toUri(): String = "${DeepLink.SCHEME}://${if (approve) "approve" else "deny"}/${request.value}?hash=$proposalHash"

    companion object {
        private val URI = Regex("""dpai://(approve|deny)/([A-Za-z0-9_]+)\?hash=([a-z0-9]+:[0-9a-f]{64})""")

        fun buttons(request: ApprovalId, proposalHash: ContentHash): List<NotificationAction> = listOf(
            NotificationAction("Одобрить", ApprovalAction(true, request, proposalHash).toUri()),
            NotificationAction("Отклонить", ApprovalAction(false, request, proposalHash).toUri()),
        )

        fun parse(uri: String): Outcome<ApprovalAction> {
            val m = URI.matchEntire(uri) ?: return Outcome.failure(DiagCode.VALUE_INVALID, "не ссылка одобрения dpai://approve|deny/<id>?hash=…")
            val id = IdKinds.APPROVAL.parse(m.groupValues[2]).valueOrNull() ?: return Outcome.failure(DiagCode.ID_INVALID, "не ApprovalId")
            val hash = ContentHash.parse(m.groupValues[3]).valueOrNull() ?: return Outcome.failure(DiagCode.HASH_INVALID, "хеш предложения неверен")
            return Outcome.success(ApprovalAction(m.groupValues[1] == "approve", id, hash))
        }
    }
}
