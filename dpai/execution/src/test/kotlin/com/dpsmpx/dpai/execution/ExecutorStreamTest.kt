package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.execution.claude.ClaudeEvent
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.ClaudeTurn
import com.dpsmpx.dpai.execution.claude.ClaudeTurnAssembler
import com.dpsmpx.dpai.execution.claude.CodexCli
import com.dpsmpx.dpai.execution.claude.ExecutorCli
import com.dpsmpx.dpai.execution.claude.GeminiCli
import com.dpsmpx.dpai.execution.claude.TurnStatus
import com.dpsmpx.dpai.execution.agent.ExitReason
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Потоки Codex и Gemini — в нормальную форму хода (D-100): итог, расход,
 * инструменты и файлы хода, отказы входа и предела — на корпусах
 * `tests/data/codex-stream/` и `tests/data/gemini-stream/`.
 */
class ExecutorStreamTest {
    private val session = ProviderSessionId.parse("00000000-0000-4000-8000-0000000000b1").valueOrNull()!!

    private fun corpus(dir: String, name: String): List<String> {
        val root = System.getProperty("dpai.repoRoot") ?: error("нет dpai.repoRoot")
        return File(root, "tests/data/$dir/$name").readText(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
    }

    private fun turn(executor: Executor, dir: String, name: String, exit: Int? = 0, stderr: List<String> = emptyList()): Pair<ClaudeTurn, List<ClaudeEvent>> {
        val a = ClaudeTurnAssembler(session, executor)
        val events = corpus(dir, name).flatMap { a.feedAll(it) }
        stderr.forEach(a::stderr)
        return a.finish(exit, ExitReason.EXITED) to events
    }

    private fun ClaudeTurn.codes() = diagnostics.map { it.code }.toSet()

    @Test
    fun codexSuccess() {
        val (t, events) = turn(Executor.CODEX, "codex-stream", "success.jsonl")
        assertEquals(TurnStatus.COMPLETED, t.status, t.diagnostics.toString())
        assertEquals("Готово: README дополнен, NOTES.md создан.", t.text)
        assertEquals(Measure.observed(400L), t.inputTokens, "вход без кеша")
        assertEquals(Measure.observed(800L), t.usage.cacheReadTokens)
        assertEquals(Measure.observed(150L), t.outputTokens)
        assertIs<Measure.Unknown>(t.costUsd, "цены в потоке Codex нет — не ноль")
        assertEquals(setOf("README.md", "NOTES.md"), t.files.edited.toSet())
        assertEquals(1L, t.tools.byTool["Bash"]?.calls)
        assertTrue(events.first() is ClaudeEvent.Init)
        assertTrue(events.filterIsInstance<ClaudeEvent.Assistant>().any { it.thinking.isNotEmpty() }, "рассуждение Codex — блоком thinking")
        assertFalse(DiagCode.CLAUDE_SESSION_MISMATCH in t.codes(), "id Codex — не id оркестратора, но это не чужая сессия")
    }

    @Test
    fun codexAuthAndLimit() {
        val (auth, _) = turn(Executor.CODEX, "codex-stream", "auth-failed.jsonl", exit = 1)
        assertEquals(TurnStatus.FAILED, auth.status)
        assertTrue(DiagCode.CLAUDE_AUTH_REQUIRED in auth.codes(), auth.diagnostics.toString())
        val (limit, _) = turn(Executor.CODEX, "codex-stream", "usage-limit.jsonl", exit = 1)
        assertEquals(TurnStatus.FAILED, limit.status)
        assertTrue(DiagCode.CLAUDE_RATE_LIMITED in limit.codes(), limit.diagnostics.toString())
        val (cut, _) = turn(Executor.CODEX, "codex-stream", "no-result.jsonl", exit = null)
        assertTrue(DiagCode.CLAUDE_STREAM_INCOMPLETE in cut.codes())
        assertEquals(1L, cut.tools.byTool["Bash"]?.calls)
    }

    @Test
    fun geminiSuccess() {
        val (t, events) = turn(Executor.GEMINI, "gemini-stream", "success.jsonl")
        assertEquals(TurnStatus.COMPLETED, t.status, t.diagnostics.toString())
        assertEquals("README дополнен; тесты не запускал.", t.text)
        assertEquals(Measure.observed(1900L), t.inputTokens)
        assertEquals(Measure.observed(200L), t.outputTokens)
        assertIs<Measure.Unknown>(t.costUsd)
        assertEquals(listOf("/work/README.md"), t.files.read)
        assertEquals(listOf("/work/README.md"), t.files.edited)
        // Куски ответа — одним сообщением перед инструментом, а не десятком.
        val texts = events.filterIsInstance<ClaudeEvent.Assistant>().flatMap { it.texts }
        assertEquals(listOf("Сначала прочитаю файл.", "README дополнен; тесты не запускал."), texts)
    }

    @Test
    fun geminiQuotaAndAuthOnlyInStderr() {
        val (q, _) = turn(Executor.GEMINI, "gemini-stream", "quota.jsonl", exit = 1)
        assertEquals(TurnStatus.FAILED, q.status)
        assertTrue(DiagCode.CLAUDE_RATE_LIMITED in q.codes(), q.diagnostics.toString())
        // Без входа Gemini ничего не пишет в поток — причина из stderr.
        val a = ClaudeTurnAssembler(session, Executor.GEMINI)
        corpus("gemini-stream", "auth-stderr.txt").forEach(a::stderr)
        val auth = a.finish(41, ExitReason.EXITED)
        assertEquals(TurnStatus.FAILED, auth.status)
        assertTrue(DiagCode.CLAUDE_AUTH_REQUIRED in auth.codes(), auth.diagnostics.toString())
    }

    @Test
    fun claudeStreamUnchanged() {
        val a = ClaudeTurnAssembler(session, Executor.CLAUDE)
        val line = """{"type":"result","subtype":"success","is_error":false,"result":"ok","session_id":"${session.value}"}"""
        assertEquals(1, a.feedAll(line).size)
        assertEquals("ok", a.finish(0, ExitReason.EXITED).text)
    }

    @Test
    fun commands() {
        val r = ClaudeRequest("задание", model = "gpt-x", effort = "max", executor = Executor.CODEX)
        assertEquals(
            listOf("codex", "exec", "--json", "--skip-git-repo-check", "-c", "sandbox_mode=\"workspace-write\"", "-m", "gpt-x", "-c", "model_reasoning_effort=\"xhigh\"", "-"),
            ExecutorCli.argv(listOf("codex"), r, session, resume = false),
        )
        assertEquals(
            listOf("codex", "exec", "resume", "--last", "--json", "--skip-git-repo-check", "-c", "sandbox_mode=\"read-only\"", "--ephemeral", "-"),
            CodexCli.argv(listOf("codex"), ClaudeRequest("ревью", executor = Executor.CODEX, readOnly = true), resume = true),
        )
        val g = ClaudeRequest("задание", model = "gemini-x-pro", executor = Executor.GEMINI)
        assertEquals(
            listOf("gemini", "-p", " ", "--output-format", "stream-json", "--session-id", session.value, "--skip-trust", "--approval-mode", "auto_edit", "-m", "gemini-x-pro"),
            ExecutorCli.argv(listOf("gemini"), g, session, resume = false),
        )
        val gr = GeminiCli.argv(listOf("gemini"), g.copy(readOnly = true, model = null), session, resume = true)
        assertTrue(gr.containsAll(listOf("--resume", session.value, "plan")) && "--session-id" !in gr)
        // Только документированные флаги (docs/research/CODEX_CLI.md, GEMINI_CLI.md).
        val codexFlags = CodexCli.argv(listOf("codex"), r, resume = true).drop(1).filter { it.startsWith("-") || it in setOf("exec", "resume") }
        assertTrue(CodexCli.DOCUMENTED_FLAGS.containsAll(codexFlags), codexFlags.toString())
        val geminiFlags = GeminiCli.argv(listOf("gemini"), g, session, resume = true).filter { it.startsWith("-") }
        assertTrue(GeminiCli.DOCUMENTED_FLAGS.containsAll(geminiFlags), geminiFlags.toString())
    }

    @Test
    fun documentedFlagsAreInResearch() {
        val root = System.getProperty("dpai.repoRoot") ?: error("нет dpai.repoRoot")
        val codex = File(root, "docs/research/CODEX_CLI.md").readText()
        val gemini = File(root, "docs/research/GEMINI_CLI.md").readText()
        for (f in CodexCli.DOCUMENTED_FLAGS - setOf("-")) assertTrue(f in codex, "нет $f в CODEX_CLI.md")
        for (f in GeminiCli.DOCUMENTED_FLAGS) assertTrue(f in gemini, "нет $f в GEMINI_CLI.md")
    }
}
