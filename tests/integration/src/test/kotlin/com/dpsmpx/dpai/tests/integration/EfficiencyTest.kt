package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.contexts.ContextSource
import com.dpsmpx.dpai.contexts.HandoffReason
import com.dpsmpx.dpai.contexts.SourceLevel
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.claude.ClaudeCodeCli
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.ClaudeRunner
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.RunnerState
import com.dpsmpx.dpai.execution.claude.RunnerUpdate
import com.dpsmpx.dpai.execution.claude.ModelTokens
import com.dpsmpx.dpai.execution.claude.ToolCount
import com.dpsmpx.dpai.execution.claude.TurnFiles
import com.dpsmpx.dpai.execution.claude.TurnStatus
import com.dpsmpx.dpai.execution.claude.TurnTools
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.git.GitCommands
import com.dpsmpx.dpai.git.Repository
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.orchestrator.efficiency.EfficiencyPolicy
import com.dpsmpx.dpai.orchestrator.git.GitService
import com.dpsmpx.dpai.security.ApprovalLedger
import com.dpsmpx.dpai.security.Origin
import com.dpsmpx.dpai.security.Sensitivity
import com.dpsmpx.dpai.sessions.PromptMode
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.tasks.TaskClass
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.io.TempDir

/**
 * Слой экономии D-40 в контуре (`docs/EFFICIENCY.md`): проваленная
 * проверка, продолжение, маршрут модели, повтор постановки, бюджет — с
 * настоящим Git и заглушкой Claude; рядом — то же с
 * [EfficiencyPolicy.LEGACY] (как до D-40).
 */
class EfficiencyTest {
    @TempDir
    lateinit var dir: Path

    private var n = 0

    /** Первая проверка падает длинным выводом Gradle с одной ошибкой, следующие проходят. */
    private val failOnce: (Int, String) -> List<String>? = { attempt, _ ->
        if (attempt > 1) {
            null
        } else {
            List(3_000) { "> Task :m$it:compileKotlin UP-TO-DATE" } +
                listOf("ParserTest > exponent() FAILED", "    AssertionError: ожидалось 1000, получено 1", "40 tests completed, 1 failed", "BUILD FAILED in 9s")
        }
    }

    private fun Harness(efficiency: EfficiencyPolicy, sources: (com.dpsmpx.dpai.core.ids.ProjectId) -> List<ContextSource> = { emptyList() }) =
        EfficiencyHarness(dir.resolve("h${n++}"), efficiency, sources, failOnce)

    private val guide = "руководство по проекту ".repeat(400)

    private fun guideDoc(project: com.dpsmpx.dpai.core.ids.ProjectId) =
        listOf(ContextSource("doc:guide.md", SourceLevel.DOCUMENT, project, Sensitivity.INTERNAL, Origin.REPOSITORY, guide))

    @Test
    fun failedVerificationContinuesTheSameSessionWithTheDigestOnly() {
        val h = Harness(EfficiencyPolicy.DEFAULT, sources = ::guideDoc)
        val t = h.orchestrator.addTask("парсер", "поддержать экспоненту", policy = TaskPolicy(taskClass = TaskClass.TRIVIAL)).valueOrNull()!!
        h.run()
        assertEquals(TaskState.DONE, h.orchestrator.tasks.tasks.getValue(t).state)
        assertEquals(2, h.starts.size)
        val (first, second) = h.starts
        // Первая попытка: дешёвый маршрут класса, без субагентов, общий кеш prompt, факты среды.
        assertEquals("haiku", first.request.model)
        assertEquals("low", first.request.effort)
        assertTrue(ClaudeCodeCli.SUBAGENT_TOOL in first.request.disallowedTools)
        assertTrue(first.request.shareSystemPromptCache)
        assertTrue("После хода оркестратор сам" in first.request.prompt && guide.take(200) in first.request.prompt)
        assertFalse(first.resume)
        // Вторая: та же сессия провайдера, ступенью выше, выжимка вместо 3000 строк, документ — не повторно.
        assertTrue(second.resume)
        assertEquals(first.provider, second.provider)
        assertEquals("sonnet", second.request.model)
        assertTrue("AssertionError: ожидалось 1000, получено 1" in second.request.prompt, second.request.prompt)
        assertTrue("40 tests completed, 1 failed" in second.request.prompt)
        // Из 3000 успешных строк — только края вывода; пропуск назван.
        assertTrue(second.request.prompt.lines().count { "UP-TO-DATE" in it } <= 11, "успешные строки не идут в prompt")
        assertTrue("… пропущено строк:" in second.request.prompt)
        assertFalse(guide.take(200) in second.request.prompt)
        assertTrue("# Без изменений с прошлого хода этой сессии" in second.request.prompt)
        val prompts = h.prompts(t)
        assertEquals(listOf(PromptMode.FULL, PromptMode.DELTA), prompts.map { it.mode })
        assertTrue(prompts[1].reusedTokens > 0)
        assertEquals("escalated:1", prompts[1].route)
        assertEquals(HandoffReason.VERIFICATION_FAILED, h.orchestrator.handoffs.latest(t)?.reason)
    }

    @Test
    fun legacyRetriesBlindInAFreshSession() {
        val h = Harness(EfficiencyPolicy.LEGACY, sources = ::guideDoc)
        val t = h.orchestrator.addTask("парсер", "поддержать экспоненту", policy = TaskPolicy(taskClass = TaskClass.TRIVIAL)).valueOrNull()!!
        h.run()
        assertEquals(2, h.starts.size)
        val (first, second) = h.starts
        assertNull(first.request.model)
        assertFalse(first.request.shareSystemPromptCache)
        assertFalse(second.resume)
        assertNotEquals(first.provider, second.provider)
        assertFalse("AssertionError" in second.request.prompt, "до D-40 следующая попытка не знает, что упало")
        assertEquals(listOf(PromptMode.FULL, PromptMode.FULL), h.prompts(t).map { it.mode })
    }

    @Test
    fun sameTaskTwiceIsRefusedUnlessLegacy() {
        val h = Harness(EfficiencyPolicy.DEFAULT)
        assertIs<Outcome.Success<TaskId>>(h.orchestrator.addTask("парсер", "поддержать экспоненту"))
        val again = h.orchestrator.addTask(" Парсер ", "поддержать  экспоненту")
        assertIs<Outcome.Failure>(again)
        assertEquals(DiagCode.TASK_DUPLICATE, again.diagnostics.first().code)
        assertIs<Outcome.Success<TaskId>>(h.orchestrator.addTask("парсер 2", "поддержать экспоненту"))
        val legacy = Harness(EfficiencyPolicy.LEGACY)
        legacy.orchestrator.addTask("парсер", "поддержать экспоненту")
        assertIs<Outcome.Success<TaskId>>(legacy.orchestrator.addTask("парсер", "поддержать экспоненту"))
    }

    @Test
    fun rulesThatDoNotFitBlockTheTaskInsteadOfBeingCut() {
        val rules = ContextSource("rules", SourceLevel.GLOBAL, null, Sensitivity.INTERNAL, Origin.ORCHESTRATOR, "правило ".repeat(40_000))
        val h = Harness(EfficiencyPolicy.DEFAULT, sources = { listOf(rules) })
        val t = h.orchestrator.addTask("парсер", "поддержать экспоненту").valueOrNull()!!
        h.run(3)
        val task = h.orchestrator.tasks.tasks.getValue(t)
        assertEquals(TaskState.BLOCKED, task.state)
        assertEquals(DiagCode.CONTEXT_BUDGET_EXCEEDED, task.lastReason)
        assertEquals(0, h.starts.size)
    }

    @Test
    fun flagUnknownToTargetClaudeIsDroppedAndTurnRetried() {
        // Старый Claude Code на target: «unknown option» и выход до вызова API (commander.js).
        var first = true
        val h = EfficiencyHarness(dir.resolve("flags"), EfficiencyPolicy.DEFAULT, verify = { _, _ -> null }) { inner ->
            object : ClaudeRunner by inner {
                override fun stream(session: SessionId): Outcome<List<RunnerUpdate>> = inner.stream(session).let { out ->
                    val ups = out.valueOrNull() ?: return@let out
                    if (!first || ups.none { it is RunnerUpdate.TurnFinished }) return@let out
                    first = false
                    val failed = ups.map { u ->
                        if (u is RunnerUpdate.TurnFinished) u.copy(result = u.result.copy(status = TurnStatus.FAILED, text = null)) else u
                    }
                    val stderr = RunnerUpdate.Stderr("error: unknown option '--exclude-dynamic-system-prompt-sections'")
                    Outcome.success(failed.filter { it !is RunnerUpdate.TurnFinished } + stderr + failed.filter { it is RunnerUpdate.TurnFinished })
                }
            }
        }
        val t = h.orchestrator.addTask("парсер", "поддержать экспоненту").valueOrNull()!!
        h.run()
        assertEquals(TaskState.DONE, h.orchestrator.tasks.tasks.getValue(t).state)
        assertEquals(2, h.starts.size)
        assertTrue(h.starts[0].request.shareSystemPromptCache)
        assertFalse(h.starts[1].request.shareSystemPromptCache, "второй ход — без флага, которого target не знает")
        assertEquals(DiagCode.CLAUDE_FLAG_UNSUPPORTED, h.orchestrator.sessions.sessions.getValue(h.starts[0].session).lastReason)
        // Отказ флага — не вина аккаунта: слот доступен.
        assertTrue(h.orchestrator.accounts.slots.values.all { it.state == com.dpsmpx.dpai.core.state.AccountState.AVAILABLE }, h.orchestrator.accounts.slots.toString())
    }

    /**
     * Ход «изнутри» (D-41): заглушка дописывает в итог файлы, которые ход
     * читал и правил (в дереве задачи и вне его), замер инструментов и
     * историю [context] токенов из окна 200 000.
     */
    private class WithTools(private val inner: ClaudeRunner, private val context: Long) : ClaudeRunner by inner {
        private val dirs = HashMap<SessionId, String>()

        override fun start(session: SessionId, request: ClaudeRequest, provider: ProviderSessionId?, resume: Boolean): Outcome<RunnerState> {
            dirs[session] = request.workDir!!
            return inner.start(session, request, provider, resume)
        }

        override fun stream(session: SessionId): Outcome<List<RunnerUpdate>> = inner.stream(session).map { ups ->
            ups.map { u ->
                if (u !is RunnerUpdate.TurnFinished) return@map u
                val d = dirs.getValue(session)
                val files = TurnFiles(read = listOf("$d/src/Parser.kt", "$d/src/Main.kt", "/home/dev/.gradle/caches/lib.jar"), edited = listOf("$d/src/Parser.kt"))
                val tools = TurnTools(byTool = mapOf("Read" to ToolCount(4, 2_000, 0, 1, 500), "Edit" to ToolCount(1, 40)), filesRead = 3, filesEdited = 1)
                val usage = u.result.usage.copy(
                    contextTokens = Measure.observed(context),
                    models = listOf(ModelTokens("mock", 10, 5, 0, 0, 200_000, null)),
                )
                u.copy(result = u.result.copy(files = files, tools = tools, usage = usage, text = "Поправил разбор в src/Parser.kt; тест падал до правки."))
            }
        }
    }

    private fun withTools(policy: EfficiencyPolicy, context: Long): EfficiencyHarness =
        EfficiencyHarness(dir.resolve("h${n++}"), policy, verify = failOnce, wrap = { WithTools(it, context) })

    @Test
    fun checkpointAfterFailedCheckCarriesTheWorkingSetNotTheSearch() {
        // История 150 000 из 200 000 — выше порога: свежая сессия с handoff (D-40, п. 4) и рабочим набором (D-41).
        val h = withTools(EfficiencyPolicy.DEFAULT, 150_000)
        val t = h.orchestrator.addTask("парсер", "поддержать экспоненту").valueOrNull()!!
        h.run()
        assertEquals(TaskState.DONE, h.orchestrator.tasks.tasks.getValue(t).state)
        val (first, second) = h.starts
        assertFalse(second.resume)
        assertEquals(PromptMode.CHECKPOINT, h.prompts(t)[1].mode)
        val p = second.request.prompt
        assertTrue("Файлы, с которыми работали прежние сессии" in p, p)
        // Изменённый — первым, пути — от рабочего дерева; файл вне дерева не уходит в prompt.
        assertTrue(p.indexOf("- src/Parser.kt") in 0 until p.indexOf("- src/Main.kt"), p)
        assertFalse(".gradle" in p, p)
        assertTrue("Поправил разбор в src/Parser.kt" in p, p)
        val handoff = h.orchestrator.handoffs.latest(t)!!
        assertEquals(listOf("src/Parser.kt", "src/Main.kt"), handoff.relevantFiles)
        // Замер инструментов — в расходе сессии (журнал), без путей.
        val first1 = h.orchestrator.sessions.sessions.getValue(first.session).usage.tools
        assertEquals(1, first1.turns)
        assertEquals(ToolCount(5, 2_040, 0, 1, 500), first1.total)
        assertTrue("## Инструменты в ходе" in h.orchestrator.usage().render())
    }

    @Test
    fun resumedSessionDoesNotGetTheWorkingSetAgain() {
        // История мала — та же сессия провайдера (`--resume`): она помнит файлы и итог сама.
        val h = withTools(EfficiencyPolicy.DEFAULT, 20_000)
        val t = h.orchestrator.addTask("парсер", "поддержать экспоненту").valueOrNull()!!
        h.run()
        val second = h.starts[1]
        assertTrue(second.resume)
        assertFalse("Файлы, с которыми работали прежние сессии" in second.request.prompt, second.request.prompt)
        assertFalse("Поправил разбор" in second.request.prompt)
        // Handoff всё равно хранит рабочий набор — на случай контрольной точки позже.
        assertEquals(listOf("src/Parser.kt", "src/Main.kt"), h.orchestrator.handoffs.latest(t)!!.relevantFiles)
    }

    @Test
    fun legacyHasNoWorkingSet() {
        val h = withTools(EfficiencyPolicy.LEGACY, 150_000)
        val t = h.orchestrator.addTask("парсер", "поддержать экспоненту").valueOrNull()!!
        h.run()
        assertTrue(h.starts.none { "Файлы, с которыми работали прежние сессии" in it.request.prompt })
        assertTrue(h.orchestrator.handoffs.all().filter { it.task == t }.all { it.relevantFiles.isEmpty() && it.summary == null })
    }
}
