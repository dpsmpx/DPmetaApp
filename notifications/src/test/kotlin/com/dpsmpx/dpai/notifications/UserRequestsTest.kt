package com.dpsmpx.dpai.notifications

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonFields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Модель запроса человеку (D-44): форма по виду, автомат, проверка ответа, повтор, срок, кодек. */
class UserRequestsTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(44))
    private val task = ids.next(IdKinds.TASK)

    private fun request(
        kind: RequestKind = RequestKind.CONFIRMATION,
        options: List<RequestOption> = listOf(RequestOption("yes", "Да", OptionRole.ACCEPT), RequestOption("no", "Нет", OptionRole.REJECT)),
        subject: String = "task:a",
    ): UserRequest = UserRequest.create(
        ids.next(IdKinds.REQUEST), kind, "probe", subject, "Заголовок", "Текст", options, task, null, clock.now(), clock.now() + 10.minutes,
    ).valueOrNull()!!

    private fun created(r: UserRequest, state: UserRequestsState = UserRequestsState.EMPTY) =
        UserRequests.applyAll(state, UserRequests.create(state, r).valueOrNull()!!)

    @Test
    fun shapeIsCheckedByKind() {
        fun shape(kind: RequestKind, vararg o: RequestOption) = UserRequest.shapeError(kind, o.toList())
        val yes = RequestOption("yes", "Да", OptionRole.ACCEPT)
        val no = RequestOption("no", "Нет", OptionRole.REJECT)
        val boom = RequestOption("do", "Удалить", OptionRole.DESTRUCTIVE)
        val text = RequestOption("send", "Ответить", OptionRole.ACCEPT, needsText = true)
        assertNull(shape(RequestKind.CONFIRMATION, yes, no))
        assertNotNull(shape(RequestKind.CONFIRMATION, yes, boom), "подтверждение не прячет опасный вариант")
        assertNull(shape(RequestKind.DESTRUCTIVE_CONFIRMATION, boom, no))
        assertNotNull(shape(RequestKind.DESTRUCTIVE_CONFIRMATION, yes, no), "опасное подтверждение — с вариантом DESTRUCTIVE")
        assertNotNull(shape(RequestKind.QUESTION, yes), "вопрос — не меньше двух вариантов")
        assertNotNull(shape(RequestKind.QUESTION, yes, boom), "опасное — не в простом вопросе")
        assertNull(shape(RequestKind.TEXT_INPUT, text, no))
        assertNotNull(shape(RequestKind.TEXT_INPUT, yes, no), "ввод текста — с вариантом needsText")
        assertNull(shape(RequestKind.ACKNOWLEDGEMENT, yes))
        assertNotNull(shape(RequestKind.ACKNOWLEDGEMENT, yes, no))
        assertNull(shape(RequestKind.USER_RESOLUTION, yes, boom, text))
        assertNotNull(shape(RequestKind.CHOICE, yes, yes.copy(label = "ещё")), "id вариантов не повторяются")
    }

    @Test
    fun textIsRedactedAndClipped() {
        val r = UserRequest.create(
            ids.next(IdKinds.REQUEST), RequestKind.ACKNOWLEDGEMENT, "probe", "s", "токен " + "sk-ant-" + "api03-" + "A".repeat(32), "слово ".repeat(1000),
            listOf(RequestOption("ack", "Понятно", OptionRole.ACCEPT)), null, null, clock.now(), null,
        ).valueOrNull()!!
        assertTrue("sk-ant" !in r.title, r.title)
        assertEquals(UserRequest.MAX_BODY, r.body.length)
        assertIs<Outcome.Failure>(
            UserRequest.create(ids.next(IdKinds.REQUEST), RequestKind.ACKNOWLEDGEMENT, "Bad Purpose", "s", "t", "b", listOf(RequestOption("ack", "ok", OptionRole.ACCEPT)), null, null, clock.now(), null),
        )
    }

    @Test
    fun lifecycleAndAnswerOnce() {
        val r = request()
        var s = created(r)
        assertEquals(RequestState.CREATED, s.requests.getValue(r.id).state)
        s = UserRequests.applyAll(s, UserRequests.delivered(s, r.id, "notification", clock.now()).valueOrNull()!!)
        s = UserRequests.applyAll(s, UserRequests.delivered(s, r.id, "overlay", clock.now()).valueOrNull()!!)
        assertEquals(RequestState.DELIVERED, s.requests.getValue(r.id).state)
        assertEquals(2, s.requests.getValue(r.id).deliveries)

        val check = UserRequests.check(s, r.id, "yes", null, r.hash, clock.now()).valueOrNull()
        assertIs<AnswerCheck.Apply>(check)
        s = UserRequests.apply(s, RequestEvent.Answered(r.id, RequestAnswer("yes", null, "автор", clock.now(), "записано")))

        // Повтор того же нажатия — тот же ответ, без нового действия.
        assertIs<AnswerCheck.Repeat>(UserRequests.check(s, r.id, "yes", null, r.hash, clock.now()).valueOrNull())
        // Другой вариант после ответа — отказ.
        assertEquals(DiagCode.REQUEST_NOT_PENDING, UserRequests.check(s, r.id, "no", null, r.hash, clock.now()).errors.single().code)
        // Доставка закрытого — без события.
        assertEquals(emptyList(), UserRequests.delivered(s, r.id, "notification", clock.now()).valueOrNull())
    }

    @Test
    fun answerIsBoundToRequestHashAndOption() {
        val a = request(subject = "task:a")
        val b = request(subject = "task:b")
        val s = created(b, created(a))
        assertEquals(2, s.pending.size, "запросы разных предметов не смешиваются")
        assertEquals(DiagCode.REQUEST_ANSWER_INVALID, UserRequests.check(s, a.id, "yes", null, b.hash, clock.now()).errors.single().code, "чужой хеш")
        assertEquals(DiagCode.REQUEST_ANSWER_INVALID, UserRequests.check(s, a.id, "maybe", null, a.hash, clock.now()).errors.single().code, "чужой вариант")
        assertEquals(DiagCode.REQUEST_ANSWER_INVALID, UserRequests.check(s, a.id, "yes", "текст", a.hash, clock.now()).errors.single().code, "текст без поля")
        assertEquals(DiagCode.ENTITY_NOT_FOUND, UserRequests.check(s, ids.next(IdKinds.REQUEST), "yes", null, a.hash, clock.now()).errors.single().code)
    }

    @Test
    fun textOptionNeedsText() {
        val r = request(RequestKind.TEXT_INPUT, listOf(RequestOption("send", "Ответить", OptionRole.ACCEPT, needsText = true), RequestOption("no", "Нет", OptionRole.REJECT)))
        val s = created(r)
        assertEquals(DiagCode.REQUEST_ANSWER_INVALID, UserRequests.check(s, r.id, "send", "  ", r.hash, clock.now()).errors.single().code)
        val ok = UserRequests.check(s, r.id, "send", "  ответ ", r.hash, clock.now()).valueOrNull()
        assertEquals("ответ", (ok as AnswerCheck.Apply).text)
    }

    @Test
    fun expiryAndReplacement() {
        val r = request()
        var s = created(r)
        clock.advance(11.minutes)
        assertEquals(DiagCode.REQUEST_NOT_PENDING, UserRequests.check(s, r.id, "yes", null, r.hash, clock.now()).errors.single().code)
        val expired = UserRequests.expire(s, clock.now())
        assertEquals(listOf(r.id), expired.map { it.request })
        s = UserRequests.applyAll(s, expired)
        assertEquals(RequestState.EXPIRED, s.requests.getValue(r.id).state)

        // Тот же вопрос по ждущему предмету — без нового; другой — заменяет прежний.
        val first = request(subject = "task:x")
        s = created(first, s)
        val same = first.copy(id = ids.next(IdKinds.REQUEST))
        assertEquals(emptyList(), UserRequests.create(s, same).valueOrNull())
        val other = request(subject = "task:x", kind = RequestKind.ACKNOWLEDGEMENT, options = listOf(RequestOption("ack", "Понятно", OptionRole.ACCEPT)))
        s = UserRequests.applyAll(s, UserRequests.create(s, other).valueOrNull()!!)
        assertEquals(RequestState.CANCELLED, s.requests.getValue(first.id).state)
        assertEquals(other.id, s.pendingFor("task:x")?.id)
    }

    @Test
    fun hashCoversWhatTheHumanSaw() {
        val r = request()
        assertTrue(r.hash != r.copy(body = "другой текст").hash)
        assertTrue(r.hash != r.copy(options = r.options.reversed()).hash)
        // Имя вида в тексте не нужно: строгий редактор скрыл бы длинное (DESTRUCTIVE_CONFIRMATION — 24 знака).
        assertTrue(RequestKind.entries.all { it.label.isNotBlank() && it.label != it.name })
        // Состояние и доставки хеш не меняют: кнопка действует после показа и перезапуска.
        assertEquals(r.hash, r.copy(state = RequestState.DELIVERED, deliveries = 3).hash)
    }

    @Test
    fun actionLinkRoundTripAndStrictParse() {
        val r = request()
        val buttons = RequestAction.buttons(r)
        assertEquals(listOf("Да", "Нет"), buttons.map { it.label })
        val a = RequestAction.parse(buttons.first().uri).valueOrNull()!!
        assertEquals(RequestAction(r.id, "yes", r.hash), a)
        assertIs<Outcome.Failure>(RequestAction.parse(buttons.first().uri + "&x=1"))
        assertIs<Outcome.Failure>(RequestAction.parse("dpai://answer/tsk_01/yes?hash=${Hashing.sha256("x")}"))
        val destructive = request(RequestKind.DESTRUCTIVE_CONFIRMATION, listOf(RequestOption("do", "Удалить", OptionRole.DESTRUCTIVE), RequestOption("no", "Нет", OptionRole.REJECT)))
        assertEquals(listOf(true, false), RequestAction.buttons(destructive).map { it.confirm })
    }

    @Test
    fun notificationMapping() {
        val r = request()
        val n = RequestNotifications.of(r)
        assertEquals("request:task:a", n.key)
        assertEquals(NotificationCategory.APPROVAL, n.category)
        assertEquals(DeepLink(DeepLink.Kind.TASK, task.value), n.link)
        assertEquals(r.id, n.request)
        val ack = request(RequestKind.ACKNOWLEDGEMENT, listOf(RequestOption("ack", "Понятно", OptionRole.ACCEPT)), subject = "s")
        assertEquals(NotificationCategory.WARNING, RequestNotifications.of(ack).category)
        // Запрос не теряется пределом частоты.
        val engine = NotificationEngine(NotificationPolicy(maxPostsPerMinute = 1))
        engine.submit(NotificationRequest("other", NotificationCategory.INFO, "t", "b", null, clock.now()))
        assertIs<NotificationDecision.Post>(engine.submit(n))
        assertEquals(DeepLink.Kind.REQUEST, DeepLink.parse(DeepLink.of(DeepLink.Kind.REQUEST, r.id).toUri()).valueOrNull()?.kind)
    }

    @Test
    fun codecRoundTrip() {
        val r = request(RequestKind.TEXT_INPUT, listOf(RequestOption("send", "Ответить", OptionRole.ACCEPT, needsText = true), RequestOption("no", "Нет", OptionRole.REJECT)))
        var s = created(r)
        val events = listOf(
            RequestEvent.Delivered(r.id, "notification", clock.now()),
            RequestEvent.Answered(r.id, RequestAnswer("send", "текст", "автор", clock.now(), "записано", "notification")),
        )
        s = UserRequests.applyAll(s, events)
        for (e in listOf(RequestEvent.Created(r)) + events + listOf(RequestEvent.Expired(r.id, clock.now()), RequestEvent.Cancelled(r.id, null, clock.now()), RequestEvent.Failed(r.id, "send", DiagCode.INTERNAL_ERROR, clock.now()))) {
            val kind = when (e) {
                is RequestEvent.Created -> "CREATED"
                is RequestEvent.Delivered -> "DELIVERED"
                is RequestEvent.Answered -> "ANSWERED"
                is RequestEvent.Expired -> "EXPIRED"
                is RequestEvent.Cancelled -> "CANCELLED"
                is RequestEvent.Failed -> "FAILED"
            }
            assertEquals(e, UserRequestCodec.decode(kind, e.at, UserRequestCodec.payload(e)).valueOrNull(), kind)
        }
        val json = UserRequestCodec.requestJson(s.requests.getValue(r.id))
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        val back = UserRequestCodec.readRequest(f, d)
        f.finish()
        assertEquals(s.requests.getValue(r.id), back, d.all.toString())
    }
}
