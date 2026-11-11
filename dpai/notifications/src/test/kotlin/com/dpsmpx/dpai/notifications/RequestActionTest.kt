package com.dpsmpx.dpai.notifications

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** Кнопки запроса (D-44, D-128): «подтвердить» — у опасного варианта и у решающего «да», остальные — одним нажатием. */
class RequestActionTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(128))
    private val task = ids.next(IdKinds.TASK)

    private fun request(purpose: String, vararg options: RequestOption): UserRequest = UserRequest.create(
        ids.next(IdKinds.REQUEST), RequestKind.CONFIRMATION, purpose, "task:a", "Заголовок", "Текст", options.toList(), task, null, clock.now(), clock.now() + 10.minutes,
    ).valueOrNull()!!

    private fun confirms(r: UserRequest): Map<String, Boolean> = RequestAction.buttons(r).zip(r.options).associate { (b, o) -> o.id to b.confirm }

    private val accept = RequestOption("merge", "Слить", OptionRole.ACCEPT)
    private val reject = RequestOption("decline", "Не сливать", OptionRole.REJECT)

    @Test
    fun decisiveAcceptConfirmsLikeDestructive() {
        for (purpose in UserRequest.DECISIVE_PURPOSES) {
            val r = request(purpose, accept, reject)
            assertEquals(mapOf("merge" to true, "decline" to false), confirms(r), purpose)
            assertEquals(true, r.decisive("merge"), purpose)
            assertEquals(false, r.decisive("decline"), purpose)
        }
    }

    @Test
    fun acceptOfOtherPurposesIsOneTap() {
        val r = request("task.blocked", RequestOption("retry", "Повторить", OptionRole.ACCEPT), RequestOption("cancel", "Отменить", OptionRole.DESTRUCTIVE))
        assertEquals(mapOf("retry" to false, "cancel" to true), confirms(r))
        assertEquals(false, r.decisive("retry"))
        assertEquals(false, r.decisive("cancel"))
    }

    @Test
    fun unknownOptionIsNotDecisive() {
        val r = request("task.merge", accept, reject)
        assertEquals(false, r.decisive("absent"))
        assertEquals(false, r.confirms("absent"))
    }
}
