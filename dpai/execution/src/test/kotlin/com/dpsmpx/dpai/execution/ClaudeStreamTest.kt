package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.measure.MeasureSource
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.claude.ClaudeEvent
import com.dpsmpx.dpai.execution.claude.ClaudeTurn
import com.dpsmpx.dpai.execution.claude.ClaudeTurnAssembler
import com.dpsmpx.dpai.execution.claude.ToolCount
import com.dpsmpx.dpai.execution.claude.ToolTracker
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
    fun usageOfRecordedTurnIsReadInFull() {
        // D-40, п. 1: расход хода — со слов провайдера, всё, что есть в итоге (Claude Code 2.1.291).
        val u = turn("success-structured.jsonl").usage
        assertEquals(Measure.observed(10L), u.inputTokens)
        assertEquals(Measure.observed(196L), u.outputTokens)
        assertEquals(Measure.observed(0L), u.cacheReadTokens)
        assertEquals(Measure.observed(9844L), u.cacheCreationTokens)
        assertEquals(Measure.observed(139L), u.thinkingTokens)
        // Контекст в конце хода — вход последнего вызова API с кешем.
        assertEquals(Measure.observed(9854L), u.contextTokens)
        assertEquals(Measure.observed(1L), u.apiCalls)
        assertEquals(Measure.observed(2L), u.numTurns)
        assertEquals(Measure.observed(0L), u.subagents)
        assertEquals(listOf("claude-haiku-4-5-20251001"), u.models.map { it.model })
        assertEquals(200_000L, u.contextWindow)
        assertFalse(u.compacted)
    }

    @Test
    fun turnWithoutResultHasUnknownUsageNotZero() {
        val a = ClaudeTurnAssembler(recorded)
        corpus("success-structured.jsonl").dropLast(1).forEach { a.feed(it) }
        val u = a.finish(null, ExitReason.TIMEOUT).usage
        assertIs<Measure.Unknown>(u.inputTokens)
        assertIs<Measure.Unknown>(u.contextTokens)
    }

    @Test
    fun compactionInTurnIsNoted() {
        // Событие сжатия истории — по описанию Agent SDK (образца нет): строка построена.
        val a = ClaudeTurnAssembler(recorded)
        val lines = corpus("success-structured.jsonl")
        lines.dropLast(1).forEach { a.feed(it) }
        a.feed("""{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto","pre_tokens":180000},"session_id":"${recorded.value}"}""")
        a.feed(lines.last())
        assertTrue(a.finish(0, ExitReason.EXITED).usage.compacted)
    }

    @Test
    fun promptTooLongIsOverflowThatCanContinue() {
        // Текст отказа API по размеру prompt (`invalid_request_error`); строка построена (D-40, п. 4).
        val a = ClaudeTurnAssembler(recorded)
        corpus("success-structured.jsonl").dropLast(1).forEach { a.feed(it) }
        a.feed("""{"type":"result","subtype":"success","is_error":true,"result":"Prompt is too long: 213000 tokens > 200000 maximum","session_id":"${recorded.value}"}""")
        val t = a.finish(1, ExitReason.EXITED)
        assertTrue(DiagCode.CLAUDE_CONTEXT_OVERFLOW in t.codes(), t.diagnostics.toString())
        assertEquals(TurnStatus.INCOMPLETE, t.status)
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

    @Test
    fun toolCallsOfTurnAreMeasuredFromStream() {
        // D-41: вызовы, байты результатов, повторы без правки между ними, файлы, субагент — по потоку.
        val t = turn("tool-calls.jsonl")
        assertEquals(TurnStatus.COMPLETED, t.status)
        val by = t.tools.byTool
        assertEquals(ToolCount(calls = 3, resultBytes = 57 + 57 + 68, duplicates = 1, duplicateBytes = 57), by["Read"])
        // Второй поиск — после правки и команды: результат мог измениться, это не повтор.
        assertEquals(ToolCount(calls = 2, resultBytes = 102), by["Grep"])
        assertEquals(ToolCount(calls = 1, resultBytes = 46), by["Edit"])
        assertEquals(ToolCount(calls = 1, resultBytes = 49, errors = 1), by["Bash"])
        assertEquals(ToolCount(calls = 1, resultBytes = 34), by["Agent"])
        assertEquals(1, t.tools.subagentCalls)
        assertEquals(42, t.tools.subagentResultBytes)
        assertEquals(2, t.tools.filesRead)
        assertEquals(1, t.tools.filesEdited)
        assertEquals(68, t.tools.largestResultBytes)
        assertEquals(0, t.tools.unmatchedResults)
        assertEquals(listOf("/work/src/Parser.kt", "/work/src/Main.kt"), t.files.read)
        assertEquals(listOf("/work/src/Parser.kt"), t.files.edited)
        assertEquals(ToolCount(calls = 8, resultBytes = 57 + 51 + 57 + 46 + 68 + 49 + 51 + 34, errors = 1, duplicates = 1, duplicateBytes = 57), t.tools.total)
    }

    @Test
    fun recordedTurnHasOneStructuredOutputCall() {
        val t = turn("success-structured.jsonl")
        assertEquals(ToolCount(calls = 1, resultBytes = "Structured output provided successfully".length.toLong()), t.tools.byTool["StructuredOutput"])
        assertEquals(0, t.tools.filesRead)
    }

    @Test
    fun interruptedTurnKeepsToolMeasurement() {
        // Расход провайдера неизвестен, а замер оркестратора по потоку — есть.
        val a = ClaudeTurnAssembler(recorded)
        corpus("tool-calls.jsonl").dropLast(1).forEach { a.feed(it) }
        val t = a.finish(null, ExitReason.TIMEOUT)
        assertEquals(TurnStatus.INCOMPLETE, t.status)
        assertIs<Measure.Unknown>(t.usage.inputTokens)
        assertEquals(8, t.tools.total.calls)
    }

    @Test
    fun duplicateNeedsNoPossibleChangeBetweenCalls() {
        fun use(id: String, name: String, input: String) = ClaudeEvent.ToolUse(id, name, JsonParser().parse(input).valueOrNull() as JsonObject)
        val grep = """{"pattern":"x","path":"/w"}"""
        val tr = ToolTracker()
        tr.onUse(use("1", "Grep", grep), subagent = false)
        tr.onUse(use("2", "TodoWrite", """{"todos":[]}"""), subagent = false)
        // TodoWrite ничего не меняет: тот же поиск — повтор.
        tr.onUse(use("3", "Grep", grep), subagent = false)
        tr.onUse(use("4", "Bash", """{"command":"ls  -la","description":"a"}"""), subagent = false)
        tr.onUse(use("5", "Bash", """{"command":"ls -la","description":"b"}"""), subagent = false)
        // Команда и инструмент MCP могли что-то изменить: после них и та же команда, и тот же поиск — не повтор.
        tr.onUse(use("6", "mcp__x__write", """{"a":1}"""), subagent = false)
        tr.onUse(use("7", "Grep", grep), subagent = false)
        // Субагент ведёт свой контекст: его вызов не сравнивается с вызовами хода.
        tr.onUse(use("8", "Grep", grep), subagent = true)
        tr.onResult(ClaudeEvent.ToolResult("3", 10, false))
        tr.onResult(ClaudeEvent.ToolResult("missing", 5, false))
        val r = tr.result()
        assertEquals(ToolCount(calls = 3, resultBytes = 10, duplicates = 1, duplicateBytes = 10), r.byTool["Grep"])
        assertEquals(0, r.byTool.getValue("Bash").duplicates)
        assertEquals(1, r.subagentCalls)
        assertEquals(1, r.unmatchedResults)
    }

    /** D-50: инструменты дерева на другом target считаются как встроенные того же действия, с файлом из `path`. */
    @Test
    fun workspaceToolsCountAsBuiltins() {
        fun use(id: String, name: String, input: String) = ClaudeEvent.ToolUse(id, name, JsonParser().parse(input).valueOrNull() as JsonObject)
        val tr = ToolTracker()
        tr.onUse(use("1", "mcp__dpai__read", """{"path":"src/a.kt"}"""), subagent = false)
        tr.onUse(use("2", "mcp__dpai__read", """{"path":"src/a.kt"}"""), subagent = false)
        tr.onUse(use("3", "mcp__dpai__edit", """{"path":"src/a.kt","old_string":"a","new_string":"b"}"""), subagent = false)
        tr.onUse(use("4", "mcp__dpai__run", """{"command":"make test"}"""), subagent = false)
        tr.onUse(use("5", "mcp__other__read", """{"path":"x"}"""), subagent = false)
        val r = tr.result()
        assertEquals(2, r.byTool.getValue("Read").calls)
        assertEquals(1, r.byTool.getValue("Read").duplicates, "то же чтение без правки — повтор")
        assertEquals(1, r.byTool.getValue("Edit").calls)
        assertEquals(1, r.byTool.getValue("Bash").calls)
        assertEquals(1, r.byTool.getValue("mcp__other__read").calls, "чужой сервер MCP — как есть")
        assertEquals(com.dpsmpx.dpai.execution.claude.TurnFiles(listOf("src/a.kt"), listOf("src/a.kt")), tr.files())
    }

    @Test
    fun malformedToolBlocksAreToleratedNotGuessed() {
        // Вход не объект, результат без id, содержимое не строка и не блоки — без исключений, с пометкой.
        val a = ClaudeTurnAssembler(recorded)
        val sid = recorded.value
        a.feed("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Read","input":"oops"}]},"session_id":"$sid","uuid":"x1"}""")
        a.feed("""{"type":"user","message":{"content":[{"type":"tool_result","content":42}]},"session_id":"$sid","uuid":"x2"}""")
        a.feed("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":{"a":1}}]},"session_id":"$sid","uuid":"x3"}""")
        val t = a.finish(0, ExitReason.EXITED)
        assertEquals(ToolCount(calls = 1), t.tools.byTool["Read"])
        assertEquals(1, t.tools.unmatchedResults)
        assertEquals(0, t.tools.filesRead)
    }
}
