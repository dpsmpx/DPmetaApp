package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.ApprovalId
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.security.Approval
import com.dpsmpx.dpai.security.ApprovalLedger
import com.dpsmpx.dpai.security.Proposal
import com.dpsmpx.dpai.ui.EnvironmentPlan
import com.dpsmpx.dpai.ui.ProjectEntry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration.Companion.minutes

/** Ожидание человеческого одобрения, не очередь исполнения. Модель не получает метода approve (D-216). */
internal class EnvironmentApprovalBroker(private val clock: Clock, private val ids: IdGenerator) {
    data class Request(val id: ApprovalId, val owner: String, val projectName: String, val root: String, val plan: EnvironmentPlan, val proposal: Proposal)
    class Grant internal constructor(private val proposal: Proposal, private val approval: Approval, private val clock: Clock) {
        private val ledger = ApprovalLedger()
        fun consume(): Outcome<Unit> = when (val result = ledger.consume(proposal, approval, clock.now())) {
            is Outcome.Failure -> result
            is Outcome.Success -> Outcome.success(Unit)
        }
    }
    private data class Waiting(val request: Request, val answer: CompletableFuture<Outcome<Grant?>>)
    private val waiting = HashMap<String, Waiting>()
    private val flow = MutableStateFlow<Map<String, Request>>(emptyMap())
    val pending: StateFlow<Map<String, Request>> = flow

    fun await(owner: String?, project: ProjectEntry, plan: EnvironmentPlan, revision: String, cancelled: () -> Boolean): Outcome<Grant?> {
        if (cancelled()) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "запрос отменён до подтверждения")
        if (plan.level == ActionLevel.READ) return Outcome.success(null)
        if (owner == null) return Outcome.failure(DiagCode.APPROVAL_REQUIRED, "откройте проектный чат или вкладку автоматизаций для подтверждения плана")
        if (plan.level == ActionLevel.SIGNIFICANT) return Outcome.failure(DiagCode.APPROVAL_REQUIRED, "действие уровня 2 требует штатного входа PIN/биометрией")
        val request = Request(ids.next(IdKinds.APPROVAL), owner, project.root.trimEnd('/').substringAfterLast('/'), project.root, plan, plan.proposal(project, revision))
        val held = Waiting(request, CompletableFuture())
        synchronized(this) {
            if (owner in waiting) return Outcome.failure(DiagCode.STATE_CONFLICT, "уже ожидается подтверждение другого плана")
            waiting[owner] = held
            flow.value = flow.value + (owner to request)
        }
        try {
            while (true) {
                if (cancelled()) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "ожидание подтверждения отменено; команды не запускались")
                try { return held.answer.get(100, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { /* Ждём человека, не считаем время согласием. */ }
            }
        } finally {
            synchronized(this) {
                if (waiting[owner] === held) { waiting.remove(owner); flow.value = flow.value - owner }
            }
        }
    }

    @Synchronized
    fun answer(owner: String, id: ApprovalId, accepted: Boolean, typedName: String = "", license: Boolean = false): Boolean {
        val held = waiting[owner]?.takeIf { it.request.id == id } ?: return false
        val r = held.request
        if (!accepted) return held.answer.complete(Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "пользователь отклонил план; не повторяйте его без нового поручения"))
        if (r.plan.level == ActionLevel.DANGEROUS && typedName != r.projectName) return false
        if (r.plan.sdkLicense && !license) return false
        val now = clock.now()
        val approval = Approval(r.id, r.proposal.hash, "user", now, now + 5.minutes)
        return held.answer.complete(Outcome.success(Grant(r.proposal, approval, clock)))
    }

    companion object {
        val shared = EnvironmentApprovalBroker(Clock.SYSTEM, IdGenerator(Clock.SYSTEM, RandomSource.secure()))
        fun chat(id: String) = "chat:$id"
    }
}
