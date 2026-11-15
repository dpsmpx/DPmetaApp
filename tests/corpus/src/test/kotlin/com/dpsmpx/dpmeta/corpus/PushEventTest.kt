package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.net.PushSignature
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpmeta.notify.PushChannel
import com.dpsmpx.dpmeta.notify.PushEvents
import com.dpsmpx.dpmeta.notify.SeenIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Мгновенный канал (D-152; F-55): подпись, срок, повтор — и совместимость с хуком на Python. */
class PushEventTest {
    private val key = Secret("test-key-0123456789abcdef")
    private val mac: (String) -> String = { PushSignature.hex(key, it) }
    private val id = "0123456789abcdef0123456789abcdef"

    /** Тот же вектор, что в `tests/test_notify_hook.py`: подпись скрипта проходит проверку приложения. */
    private val signed = """{"v":1,"type":"claude.turn.done","project":"DPlang","branch":"main","at":1763200000,"session":"session_01ABCdef234","reason":"stop","id":"$id","sig":"20489adb752e711c3775ad3cda62bae21ed9c8857ee4a8031cc057fc56944e87"}"""
    private val now = Timestamp(1_763_200_030_000)

    private fun code(r: Outcome<*>) = (r as Outcome.Failure).errors.single().code

    @Test
    fun hookVectorVerifies() {
        val e = (PushEvents.verify(signed, now, emptySet(), mac) as Outcome.Success).value
        assertEquals("claude.turn.done", e.type)
        val event = PushEvents.event(e)
        assertEquals("Claude закончил ответ · ветка main", event.text)
        assertEquals("https://claude.ai/code/session_01ABCdef234", PushEvents.sessionUrl(event))
        assertEquals("DPlang", event.project)
    }

    @Test
    fun forgedOldOrRepeatedIsDropped() {
        assertEquals(DiagCode.PUSH_SIGNATURE_INVALID, code(PushEvents.verify(signed.replace("main", "evil"), now, emptySet(), mac)), "подменённое поле")
        assertEquals(DiagCode.PUSH_SIGNATURE_INVALID, code(PushEvents.verify(signed, now, emptySet()) { PushSignature.hex(Secret("другой-ключ"), it) }))
        assertEquals(DiagCode.PUSH_SIGNATURE_INVALID, code(PushEvents.verify(signed, Timestamp(1_763_200_000_000 + 11 * 60_000), emptySet(), mac)), "старше 10 минут")
        assertEquals(DiagCode.PUSH_SIGNATURE_INVALID, code(PushEvents.verify(signed, now, setOf(id), mac)), "повтор")
        assertEquals(DiagCode.PUSH_SIGNATURE_INVALID, code(PushEvents.verify(signed.replace(",\"sig\":\"20489adb752e711c3775ad3cda62bae21ed9c8857ee4a8031cc057fc56944e87\"", ""), now, emptySet(), mac)), "без подписи")
        val other = signed.replace("claude.turn.done", "pr.merged")
        assertTrue("не для канала" in (PushEvents.verify(other, now, emptySet(), mac) as Outcome.Failure).errors.single().message)
    }

    @Test
    fun ntfyStreamLines() {
        assertNull(PushEvents.message("""{"id":"a","time":1,"event":"open","topic":"t"}"""))
        assertNull(PushEvents.message("""{"id":"b","time":2,"event":"keepalive","topic":"t"}"""))
        assertEquals("x", PushEvents.message("""{"id":"c","time":3,"event":"message","topic":"t","message":"x"}"""))
        assertNull(PushEvents.message("мусор"))
    }

    @Test
    fun serverTopicAndUrls() {
        fun ok(t: String) = (PushChannel.server(t) as Outcome.Success).value
        fun bad(t: String) = assertTrue(PushChannel.server(t) is Outcome.Failure, t)
        assertEquals("https://ntfy.sh", ok(""))
        assertEquals("https://ntfy.example.org", ok("ntfy.example.org/"))
        assertEquals("https://push.example.org:8443", ok("https://Push.Example.org:8443"))
        assertEquals("https://ntfy.sh", ok("https://ntfy.sh:443"))
        bad("http://ntfy.sh")
        bad("https://user:pass@ntfy.sh")
        bad("https://ntfy.sh/topic")
        bad("https://localhost")
        bad("https://ntfy.sh?x=1")
        val topic = PushChannel.newTopic(RandomSource.seeded(7))
        assertTrue(topic.startsWith("dpmeta-") && topic.length == 31 && PushChannel.topicValid(topic), topic)
        assertFalse(PushChannel.topicValid("short"))
        assertEquals(64, PushChannel.newKeyText(RandomSource.seeded(7)).length)
        assertEquals("https://ntfy.sh/$topic/json?since=AbC123xyz", PushChannel.streamUrl("https://ntfy.sh", topic, "AbC123xyz").toString())
        assertEquals("https://ntfy.sh/$topic/json", PushChannel.streamUrl("https://ntfy.sh", topic, "../x").toString(), "чужой id — без since")
        assertEquals("https://ntfy.sh/$topic", PushChannel.publishUrl("https://ntfy.sh", topic))
        assertEquals("ntfy.example.org", PushChannel.host("https://ntfy.example.org"))
    }

    @Test
    fun backoffFramesAndSeen() {
        assertEquals(listOf(5L, 10, 30, 60, 120, 300, 300), (1..7).map(PushChannel::backoffSeconds))
        assertEquals(PushChannel.Frame("AbC123xyz", "x"), PushChannel.frame("""{"id":"AbC123xyz","event":"message","message":"x"}"""))
        assertEquals(PushChannel.Frame(null, "x"), PushChannel.frame("""{"id":"a/b","event":"message","message":"x"}"""))
        val seen = SeenIds(limit = 2)
        seen.add("a"); seen.add("b"); seen.add("c")
        assertEquals(setOf("b", "c"), seen.snapshot())
    }

    @Test
    fun needsInputKeepsKindField() {
        val e = com.dpsmpx.dpmeta.notify.PushEvent("claude.needs_input", "DPlang", "", 1, "session_01ABCdef234", "permission", id)
        val event = PushEvents.event(e)
        assertEquals("permission", event.fields["kind"])
        assertEquals("Claude ждёт разрешения", event.text)
    }

    @Test
    fun testEventRoundTrip() {
        val e = PushEvents.test(1_763_200_000, id)
        val text = PushEvents.json(e, PushSignature.hex(key, e.canonical))
        val back = (PushEvents.verify(text, now, emptySet(), mac) as Outcome.Success).value
        assertEquals(e, back)
        assertEquals("Проверка мгновенного канала", PushEvents.event(back).text)
        assertNull(PushEvents.sessionUrl(PushEvents.event(back)))
        assertEquals("DPMETA_PUSH_URL=https://ntfy.sh/t\nDPMETA_PUSH_KEY=test-key-0123456789abcdef", PushSignature.hookEnv("https://ntfy.sh/t", key))
    }
}
