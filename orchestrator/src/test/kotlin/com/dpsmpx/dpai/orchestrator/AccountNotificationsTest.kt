package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.accounts.AccountEvent
import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.accounts.LimitLevel
import com.dpsmpx.dpai.accounts.LimitWindow
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SubscriptionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.notifications.NotificationCategory
import com.dpsmpx.dpai.notifications.NotificationDecision
import com.dpsmpx.dpai.notifications.NotificationEngine
import com.dpsmpx.dpai.orchestrator.accounts.AccountNotifications
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Уведомления аккаунтов (`docs/ACCOUNT_MODEL.md`, §7): режим одной подписки
 * получает весь набор — предел, близость к пределу, сброс, вход, истечение
 * входа, запрещённый способ, пересечение, пауза; переход между подписками
 * — только при нескольких (D-30).
 */
class AccountNotificationsTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(8))
    private val accounts = Accounts()
    private val engine = NotificationEngine()
    private var state = AccountsState.EMPTY
    private val shown = mutableListOf<NotificationDecision>()

    private fun commit(out: Outcome<List<AccountEvent>>): Outcome<List<AccountEvent>> {
        for (e in out.valueOrNull().orEmpty()) {
            val before = state
            state = Accounts.apply(state, e)
            AccountNotifications.resolvedKeys(e).forEach(engine::resolve)
            AccountNotifications.requests(e, before, state).forEach { shown += engine.submit(it) }
        }
        return out
    }

    private fun status(email: String, method: String = AuthStatus.CLAUDE_AI) =
        AuthStatus(true, method, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", email), "max")

    private fun posts(category: NotificationCategory) =
        shown.filterIsInstance<NotificationDecision.Post>().map { it.notification }.filter { it.category == category }

    private fun subscription(label: String, rotation: Boolean = false): SubscriptionId =
        ids.next(IdKinds.SUBSCRIPTION).also { commit(accounts.registerSubscription(state, it, label, clock.now(), rotationAllowed = rotation)) }

    @Test
    fun singleSubscriptionGetsFullControl() {
        val sub = subscription("основная")
        val slot = ids.next(IdKinds.ACCOUNT).also { commit(accounts.registerSlot(state, it, sub, ids.next(IdKinds.TARGET), "~/.claude", clock.now())) }
        commit(accounts.observeAuthStatus(state, slot, status("a@example.org"), clock.now()))

        // Близко к пределу → предупреждение; предел → блокировка с временем сброса.
        commit(accounts.observeLimit(state, slot, LimitWindow.SESSION, LimitLevel.NEAR, clock.now() + 1.hours, clock.now()))
        assertContains(posts(NotificationCategory.WARNING).single().body, "Новые задачи отложены")
        commit(accounts.observeLimit(state, slot, LimitWindow.SESSION, LimitLevel.REACHED, clock.now() + 1.hours, clock.now()))
        val blocked = posts(NotificationCategory.BLOCKED).single()
        assertContains(blocked.body, "сброс через 1 ч")
        assertEquals("dpai://subscription/${sub.value}", blocked.link!!.toUri())

        // Сброс → «снят», ключ предела закрыт.
        clock.advance(1.hours)
        commit(Outcome.success(accounts.tick(state, clock.now())))
        assertEquals(1, posts(NotificationCategory.SUCCESS).size)

        // Предел без времени сброса: повторные наблюдения не плодят уведомлений.
        commit(accounts.observeLimit(state, slot, LimitWindow.WEEKLY, LimitLevel.REACHED, null, clock.now()))
        val shownBeforeProbe = shown.size
        clock.advance(31.minutes)
        val probe = commit(accounts.observeLimit(state, slot, LimitWindow.WEEKLY, LimitLevel.REACHED, null, clock.now()))
        assertTrue(probe.valueOrNull()!!.isNotEmpty(), "проба записана в журнал")
        assertEquals(shownBeforeProbe, shown.size, "проба без изменений — не уведомление")
        val weekly = posts(NotificationCategory.BLOCKED).filter { it.title.contains("недельный") }
        assertEquals(1, weekly.size)
        assertContains(weekly.single().body, "время сброса неизвестно")
        commit(accounts.observeSuccess(state, slot, clock.now()))

        // Вход: истекает → предупреждение; истёк → инструкция с каталогом слота.
        commit(accounts.observeLoginExpiring(state, slot, clock.now() + 72.hours, clock.now()))
        assertTrue(posts(NotificationCategory.WARNING).any { it.title == "Вход скоро истечёт" })
        commit(accounts.observeAuthFailure(state, slot, clock.now()))
        assertContains(posts(NotificationCategory.BLOCKED).last().body, "CLAUDE_CONFIG_DIR=~/.claude claude")

        // Серия ошибок → пауза.
        commit(accounts.observeAuthStatus(state, slot, status("a@example.org"), clock.now()))
        repeat(3) { commit(accounts.observeError(state, slot, DiagCode.EXECUTION_HOST_UNAVAILABLE, clock.now())) }
        assertTrue(posts(NotificationCategory.WARNING).any { it.title.contains("паузе") })

        // Вход не формой Claude и вход другим аккаунтом — безопасность.
        commit(accounts.observeAuthStatus(state, slot, status("a@example.org", method = "api_key"), clock.now()))
        commit(accounts.observeAuthStatus(state, slot, status("b@example.org"), clock.now()))
        val security = posts(NotificationCategory.SECURITY)
        assertEquals(listOf("Слот остановлен: вход не формой Claude", "Пересечение аккаунтов"), security.map { it.title })
        assertTrue(posts(NotificationCategory.INFO).isEmpty(), "переходов между подписками при одной подписке нет")
    }

    @Test
    fun switchingIsAnnouncedWithMultipleSubscriptions() {
        val a = subscription("A", rotation = true)
        val b = subscription("B", rotation = true)
        val sa = ids.next(IdKinds.ACCOUNT).also { commit(accounts.registerSlot(state, it, a, ids.next(IdKinds.TARGET), "~/.claude-a", clock.now())) }
        val sb = ids.next(IdKinds.ACCOUNT).also { commit(accounts.registerSlot(state, it, b, ids.next(IdKinds.TARGET), "~/.claude-b", clock.now())) }
        commit(accounts.observeAuthStatus(state, sa, status("a@example.org"), clock.now()))
        commit(accounts.observeAuthStatus(state, sb, status("b@example.org"), clock.now()))
        val task = ids.next(IdKinds.TASK)
        val first = accounts.lease(state, ids.next(IdKinds.SESSION), task, clock.now()).valueOrNull()!!
        commit(Outcome.success(first.events))
        val limited = if (first.subscription == a) sa else sb
        val session = state.slots.getValue(first.slot).sessions.keys.single()
        commit(accounts.release(state, session, clock.now()))
        commit(accounts.observeLimit(state, limited, LimitWindow.SESSION, LimitLevel.REACHED, clock.now() + 2.hours, clock.now()))
        commit(Outcome.success(accounts.lease(state, ids.next(IdKinds.SESSION), task, clock.now()).valueOrNull()!!.events))
        val info = posts(NotificationCategory.INFO).single()
        assertEquals("Задача продолжена на другой подписке", info.title)
        assertEquals("dpai://task/${task.value}", info.link!!.toUri())
    }
}
