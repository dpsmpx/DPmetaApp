package com.dpsmpx.dpai.notifications

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.testkit.Gen
import com.dpsmpx.dpai.testkit.Property
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class NotificationEngineTest {
    private val clock = ManualClock()

    private fun req(key: String, cat: NotificationCategory = NotificationCategory.WARNING, title: String = key) =
        NotificationRequest(key, cat, title, "тело", null, clock.now())

    /** Уведомление видно на экране блокировки: текст — через строгий редактор (A-6). */
    @Test
    fun textIsStrictlyRedacted() {
        val key = "AbCdEfGhIjKlMnOpQrStUvWxYz"
        val d = NotificationEngine().submit(NotificationRequest("k", NotificationCategory.BLOCKED, "вход $key", "ключ $key в выводе", null, clock.now()))
        val n = (d as NotificationDecision.Post).notification
        assertFalse(key in n.title || key in n.body, n.toString())
    }

    /** Критерий этапа 12: 20 одинаковых событий — одно уведомление с числом повторов. */
    @Test
    fun twentyIdenticalEventsGiveOneNotificationWithCount() {
        val e = NotificationEngine()
        val decisions = List(20) {
            clock.advance(10.seconds)
            e.submit(req("session:x:heartbeat-missed"))
        }
        assertEquals(1, decisions.count { it is NotificationDecision.Post })
        assertEquals(20, (decisions.last() as NotificationDecision.Update).notification.count)
    }

    @Test
    fun escalationPassesImmediatelyAndResolveResets() {
        val e = NotificationEngine()
        assertIs<NotificationDecision.Post>(e.submit(req("sub:limit", NotificationCategory.WARNING)))
        assertIs<NotificationDecision.Update>(e.submit(req("sub:limit", NotificationCategory.INFO)))
        assertIs<NotificationDecision.Post>(e.submit(req("sub:limit", NotificationCategory.BLOCKED)), "рост серьёзности")
        e.resolve("sub:limit")
        assertIs<NotificationDecision.Post>(e.submit(req("sub:limit", NotificationCategory.BLOCKED)))
        clock.advance(11.minutes)
        assertIs<NotificationDecision.Post>(e.submit(req("sub:limit", NotificationCategory.BLOCKED)), "окно истекло — снова показ")
    }

    /** Шторм (ТЗ, §36, §93): много разных ключей разом — предел в минуту, затем одна сводка; безопасность не теряется. */
    /** Этап 19: ключи старше окна агрегации забываются — память не растёт с числом задач. */
    @Test
    fun keysOlderThanTheWindowAreForgotten() {
        val e = NotificationEngine(NotificationPolicy(maxPostsPerMinute = 1000))
        for (i in 1..100) {
            e.submit(req("task:$i:progress"))
            clock.advance(1.minutes)
        }
        assertTrue(e.tracked() <= 11, "помнятся только ключи последних 10 минут: ${e.tracked()}")
        // Повтор в окне — по-прежнему обновление со счётчиком.
        val again = e.submit(req("task:100:progress"))
        assertTrue(again is NotificationDecision.Update, again.toString())
    }

    @Test
    fun stormIsHeldAndDigested() {
        val e = NotificationEngine(NotificationPolicy(maxPostsPerMinute = 6))
        val d = List(300) { i -> e.submit(req("target:$i:offline")) }
        assertEquals(6, d.count { it is NotificationDecision.Post })
        assertEquals(294, d.count { it is NotificationDecision.Held })
        assertIs<NotificationDecision.Post>(e.submit(req("slot:y:overlap", NotificationCategory.SECURITY)), "SECURITY не держится")
        assertEquals(null, e.flush(clock.now()), "предел ещё не отпустил")
        clock.advance(61.seconds)
        val digest = assertNotNull(e.flush(clock.now())).notification
        assertEquals(294, digest.count)
        assertTrue(digest.body.contains("и ещё"))
        assertEquals(null, e.flush(clock.now()))
    }

    /** Свойство: за любую минуту показов (кроме SECURITY и APPROVAL) не больше предела. */
    @Test
    fun postsPerMinuteNeverExceedLimit() {
        val gen = Gen.list(Gen { r -> Triple(r.nextInt(20), r.nextInt(NotificationCategory.entries.size), r.nextInt(30)) }, 1..300)
        Property.forAll(gen) { stream ->
            val c = ManualClock()
            val e = NotificationEngine(NotificationPolicy(maxPostsPerMinute = 4))
            val posts = mutableListOf<Pair<Long, NotificationCategory>>()
            for ((key, cat, step) in stream) {
                c.advance(step.seconds)
                val category = NotificationCategory.entries[cat]
                val d = e.submit(NotificationRequest("k$key", category, "t", "b", null, c.now()))
                if (d is NotificationDecision.Post) posts += c.now().epochMillis to category
                e.flush(c.now())?.let { posts += c.now().epochMillis to it.notification.category }
            }
            val limited = posts.filter { it.second != NotificationCategory.SECURITY && it.second != NotificationCategory.APPROVAL }
            for ((t, _) in limited) assertTrue(limited.count { it.first in t until t + 60_000 } <= 4)
        }
    }

    @Test
    fun deepLinks() {
        val ids = IdGenerator(clock, RandomSource.seeded(1))
        val acc = ids.next(IdKinds.ACCOUNT)
        val link = DeepLink.of(DeepLink.Kind.ACCOUNT, acc)
        assertEquals("dpai://account/${acc.value}", link.toUri())
        assertEquals(link, DeepLink.parse(link.toUri()).valueOrNull())
        assertIs<Outcome.Failure>(DeepLink.parse("dpai://task/${acc.value}"), "чужой вид идентификатора")
        assertIs<Outcome.Failure>(DeepLink.parse("https://example.org"))
        assertIs<Outcome.Failure>(DeepLink.parse("dpai://galaxy/x"))
    }
}
