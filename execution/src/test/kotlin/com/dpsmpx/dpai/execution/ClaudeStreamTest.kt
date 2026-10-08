package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.measure.MeasureSource
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.claude.ClaudeEvent
import com.dpsmpx.dpai.execution.claude.ClaudeTurn
import com.dpsmpx.dpai.execution.claude.ClaudeTurnAssembler
import com.dpsmpx.dpai.execution.claude.TurnStatus
import com.dpsmpx.dpai.testkit.Gen
import com.dpsmpx.dpai.testkit.Property
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Разбор потока Claude Code на корпусе `tests/data/claude-stream/`
 * (критерий этапа 4): записанные образцы и испорченные — диагностика, а не
 * догадка (ТЗ, §106; D-05, D-23).
 */
class ClaudeStreamTest {
    private val recorded = ProviderSessionId.parse("00000000-0000-4000-8000-0000000000a1").valueOrNull()!!
    private val noAuth = ProviderSessionId.parse("6f1c2f9e-0d1a-4b8e-9a51-3c4d5e6f7a8b").valueOrNull()!!

    private fun corpus(name: String): List<String> {
        val root = System.getProperty("dpai.repoRoot") ?: error("нет dpai.repoRoot")
        return File(root, "tests/data/claude-stream/$name").readText(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
    }

    private fun turn(name: String, session: ProviderSessionId = recorded, exit: Int? = 0, reason: ExitReason? = ExitReason.EXITED): ClaudeTurn {
        val a = ClaudeTurnAssembler(session)
        corpus(name).forEach { a.feed(it) }
        return a.finish(exit, reason)
    }

    private fun ClaudeTurn.codes() = diagnostics.map { it.code }.toSet()

    @Test
    fun recordedSuccessWithStructuredOutputAndLimits() {
        val t = turn("success-structured.jsonl")
        assertEquals(TurnStatus.COMPLETED, t.status, t.diagnostics.toString())
        assertEquals(JsonObject.of("answer" to JsonString("OK")), t.structured)
        // Стоимость при подписке — оценка по прейскуранту, не наблюдение (D-13).
        assertEquals(MeasureSource.ESTIMATED, (t.costUsd as Measure.Known).source)
        assertEquals(Measure.observed(10L), t.inputTokens)
        val five = t.rateLimit!!.windows.getValue("five_hour")
        assertEquals(0.54, five.utilization.orNull())
        assertEquals(1_791_307_800_000L, five.resetsAt!!.epochMillis)
        assertTrue(t.diagnostics.none { it.isError })
    }

    @Test
    fun recordedAuthFailureIsAuthRequiredDespiteSuccessSubtype() {
        val t = turn("auth-failed.jsonl", noAuth, exit = 1)
        assertEquals(TurnStatus.FAILED, t.status)
        assertTrue(DiagCode.CLAUDE_AUTH_REQUIRED in t.codes())
    }

    @Test
    fun corruptedSamples() {
        val truncated = turn("truncated-result.jsonl", exit = 1)
        assertEquals(TurnStatus.FAILED, truncated.status)
        assertTrue(truncated.codes().containsAll(setOf(DiagCode.CLAUDE_STREAM_MALFORMED, DiagCode.CLAUDE_STREAM_INCOMPLETE)))

        val garbage = turn("garbage-line.jsonl")
        assertEquals(TurnStatus.COMPLETED, garbage.status)
        assertTrue(DiagCode.CLAUDE_STREAM_MALFORMED in garbage.codes())

        val dup = turn("duplicate-event.jsonl")
        assertEquals(TurnStatus.COMPLETED, dup.status)
        assertTrue(DiagCode.EVENT_DUPLICATE in dup.codes())

        val crashed = turn("no-result.jsonl", exit = 137)
        assertEquals(TurnStatus.FAILED, crashed.status)
        assertTrue(DiagCode.CLAUDE_STREAM_INCOMPLETE in crashed.codes())

        val timedOut = turn("no-result.jsonl", exit = null, reason = ExitReason.TIMEOUT)
        assertEquals(TurnStatus.INCOMPLETE, timedOut.status)

        val mismatch = turn("session-mismatch.jsonl")
        assertEquals(TurnStatus.FAILED, mismatch.status)
        assertTrue(DiagCode.CLAUDE_SESSION_MISMATCH in mismatch.codes())

        val limited = turn("rate-limited.jsonl", exit = 1)
        assertEquals(TurnStatus.FAILED, limited.status)
        assertTrue(DiagCode.CLAUDE_RATE_LIMITED in limited.codes())
        assertTrue(limited.rateLimit!!.rejected)
    }

    /**
     * Ревью 8 октября 2026: `allowed_warning` — предупреждение, а не отказ;
     * принятое за отказ, оно держало бы слот в RATE_LIMITED до сброса окна.
     * Неизвестный статус — не отказ, но и не тишина (D-05).
     */
    @Test
    fun limitWarningIsNotRejection() {
        fun withStatus(status: String): ClaudeTurn {
            val a = ClaudeTurnAssembler(recorded)
            corpus("success-structured.jsonl").forEach { a.feed(it.replace("\"status\":\"allowed\"", "\"status\":\"$status\"")) }
            return a.finish(0, ExitReason.EXITED)
        }
        val warned = withStatus("allowed_warning")
        assertTrue(warned.rateLimit!!.warning)
        assertFalse(warned.rateLimit.rejected)
        assertEquals(TurnStatus.COMPLETED, warned.status)
        assertFalse(DiagCode.CLAUDE_RATE_LIMITED in warned.codes())
        val unknown = withStatus("throttled_somehow")
        assertFalse(unknown.rateLimit!!.rejected)
        assertTrue(unknown.diagnostics.any { it.code == DiagCode.CLAUDE_STREAM_MALFORMED && "throttled_somehow" in it.message }, unknown.diagnostics.toString())
    }

    @Test
    fun textClaimWithoutResultIsNotCompletion() {
        // D-23: «готово» в тексте без итога потока — не завершение.
        val a = ClaudeTurnAssembler(recorded)
        corpus("success-structured.jsonl").dropLast(1).forEach { a.feed(it) }
        assertIs<ClaudeEvent.Assistant>(a.feed("""{"type":"assistant","message":{"content":[{"type":"text","text":"Готово!"}]},"session_id":"${recorded.value}"}"""))
        assertEquals(TurnStatus.FAILED, a.finish(0, ExitReason.EXITED).status)
    }

    /** Fuzz: порча любых строк корпуса не роняет разбор — только диагностика (ТЗ, §92). */
    @Test
    fun mutatedStreamsNeverThrow() {
        val lines = corpus("success-structured.jsonl")
        val mutation = Gen.list(Gen.int(0..10_000), 1..6)
        Property.forAll(mutation) { cuts ->
            val a = ClaudeTurnAssembler(recorded)
            val random = RandomSource.seeded(cuts.sum().toLong())
            for (l in lines) {
                val cut = cuts[random.nextInt(cuts.size)] % (l.length + 1)
                val mutated = when (random.nextInt(4)) {
                    0 -> l.substring(0, cut)
                    1 -> l.substring(cut)
                    2 -> l.replaceRange(cut, cut, "\u0000{")
                    else -> l
                }
                a.feed(mutated)
            }
            val t = a.finish(0, ExitReason.EXITED)
            assertTrue(t.status in TurnStatus.entries)
        }
    }
}
