package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.git.GitCommands
import com.dpsmpx.dpai.git.GitOp
import com.dpsmpx.dpai.notifications.NotificationDecision
import com.dpsmpx.dpai.notifications.NotificationEngine
import com.dpsmpx.dpai.security.ApprovalLedger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Одобрение из уведомления (критерий этапа 12; ТЗ, §37, §68): кнопка
 * действует только на то предложение, хеш которого несёт.
 */
class ApprovalDeskTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(91))
    private val ledger = ApprovalLedger()
    private val commands = GitCommands(ledger)
    private val desk = ApprovalDesk(ttl = 10.minutes)
    private val engine = NotificationEngine()

    @Test
    fun buttonApprovesExactlyItsProposal() {
        val proposal = commands.proposal(GitOp.RESET_HARD, "/w/task-1", emptyList())
        val request = desk.request(ids.next(IdKinds.APPROVAL), proposal, clock.now())
        val posted = assertIs<NotificationDecision.Post>(engine.submit(request)).notification
        assertEquals(listOf("Одобрить", "Отклонить"), posted.actions.map { it.label })
        val approval = desk.respond(posted.actions.first().uri, "автор", clock.now()).valueOrNull()!!
        assertTrue(commands.argv(GitOp.RESET_HARD, "/w/task-1", emptyList(), clock.now(), approval).isSuccess)
        assertEquals(0, desk.pending())
    }

    @Test
    fun staleButtonOfReplacedProposalApprovesNothing() {
        val first = commands.proposal(GitOp.CLEAN_FORCE, "/w/task-2", listOf("build"))
        val old = assertIs<NotificationDecision.Post>(engine.submit(desk.request(ids.next(IdKinds.APPROVAL), first, clock.now()))).notification
        clock.advance(1.minutes)
        // Предложение по тому же дереву изменилось: уведомление обновлено, кнопки — новые.
        val second = commands.proposal(GitOp.CLEAN_FORCE, "/w/task-2", listOf("build", "src"))
        val updated = assertIs<NotificationDecision.Update>(engine.submit(desk.request(ids.next(IdKinds.APPROVAL), second, clock.now()))).notification
        assertTrue(updated.actions != old.actions)
        val stale = desk.respond(old.actions.first().uri, "автор", clock.now())
        assertEquals(DiagCode.APPROVAL_INVALID, (stale as Outcome.Failure).errors.single().code)
        val fresh = desk.respond(updated.actions.first().uri, "автор", clock.now()).valueOrNull()!!
        // Одобрение второго предложения не подходит к первому.
        assertEquals(DiagCode.APPROVAL_INVALID, (commands.argv(GitOp.CLEAN_FORCE, "/w/task-2", listOf("build"), clock.now(), fresh) as Outcome.Failure).errors.single().code)
    }

    @Test
    fun denyExpiryAndTampering() {
        val p = commands.proposal(GitOp.PUSH_FORCE, "/w/task-3", listOf("origin"))
        val n = desk.request(ids.next(IdKinds.APPROVAL), p, clock.now())
        val tampered = n.actions.first().uri.replace(Regex("hash=sha256:[0-9a-f]{4}"), "hash=sha256:0000")
        assertEquals(DiagCode.APPROVAL_INVALID, (desk.respond(tampered, "кто-то", clock.now()) as Outcome.Failure).errors.single().code)
        assertNull(desk.respond(n.actions.last().uri, "автор", clock.now()).valueOrNull(), "отказ — не одобрение")
        assertEquals(0, desk.pending())
        val again = desk.request(ids.next(IdKinds.APPROVAL), p, clock.now())
        clock.advance(11.minutes)
        assertEquals(DiagCode.APPROVAL_INVALID, (desk.respond(again.actions.first().uri, "автор", clock.now()) as Outcome.Failure).errors.single().code)
        assertEquals(DiagCode.VALUE_INVALID, (desk.respond("dpai://approve/x", "автор", clock.now()) as Outcome.Failure).errors.single().code)
    }
}
