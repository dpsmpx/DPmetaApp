package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.contexts.ContextSource
import com.dpsmpx.dpai.contexts.SourceLevel
import com.dpsmpx.dpai.contexts.TokenEstimate
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.ClaudeRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.claude.ModelTokens
import com.dpsmpx.dpai.execution.claude.RunnerState
import com.dpsmpx.dpai.execution.claude.RunnerUpdate
import com.dpsmpx.dpai.execution.claude.TurnStatus
import com.dpsmpx.dpai.execution.claude.TurnUsage
import com.dpsmpx.dpai.orchestrator.efficiency.EfficiencyPolicy
import com.dpsmpx.dpai.orchestrator.efficiency.ResumePolicy
import com.dpsmpx.dpai.security.Origin
import com.dpsmpx.dpai.security.Sensitivity
import com.dpsmpx.dpai.tasks.TaskClass
import com.dpsmpx.dpai.tasks.TaskPolicy
import java.io.File
import java.math.BigDecimal
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Сравнение «до» ([EfficiencyPolicy.LEGACY]) и «после» ([EfficiencyPolicy.DEFAULT])
 * на восьми сценариях (D-40; `docs/EFFICIENCY.md`, §12). Оркестратор,
 * Git, проверка и разбор потока — настоящие; Claude — заглушка, а её
 * расход — **модель стоимости** [SimulatedClaude] с допущениями ниже. Это
 * симуляция: она сравнивает решения оркестратора при одних и тех же
 * допущениях, а не измеряет настоящую модель (замер — на устройстве и ПК).
 *
 * Главная мера — сделанная работа на расход: выполненные задачи на
 * условную стоимость. Отчёт — `build/audits/efficiency/benchmark.md`.
 */
class EfficiencyBenchmarkTest {
    @TempDir
    lateinit var dir: Path

    /**
     * Допущения модели стоимости. Цена — условные доллары за миллион
     * токенов входа по ступени модели (`haiku` 1, `sonnet` 3, `opus` 5;
     * модель аккаунта считается `sonnet`); выход — ×5, запись кеша — ×1,25,
     * чтение кеша — ×0,1 (соотношения прейскуранта API). Ход: системный
     * prompt и инструменты [system]; новая сессия изучает репозиторий
     * ([exploreFresh] токенов вывода инструментов за [callsFresh] вызовов),
     * продолжение помнит его ([exploreResumed], [callsResumed]); каждый
     * вызов перечитывает накопленный контекст из кеша. Системный prompt
     * делится между сессиями только с общим кешем prompt; в продолжении он —
     * часть истории. Контекст сверх [window] — отказ API («Prompt is too
     * long»), без работы.
     */
    data class CostModel(
        val system: Long = 12_000,
        val exploreFresh: Long = 20_000,
        val exploreResumed: Long = 5_000,
        val callsFresh: Int = 8,
        val callsResumed: Int = 3,
        val output: Long = 1_500,
        val window: Long = 200_000,
    )

    /** Ход в симуляции: модель, токены, стоимость. */
    data class SimTurn(val model: String, val input: Long, val cacheRead: Long, val cacheWrite: Long, val output: Long, val cost: Double, val overflow: Boolean)

    /** Заглушка Claude с расходом по [CostModel]: итог хода получает расход и, при переполнении, отказ. */
    class SimulatedClaude(private val inner: ClaudeRunner, private val m: CostModel) : ClaudeRunner by inner {
        private data class Pending(val request: ClaudeRequest, val provider: ProviderSessionId?, val resume: Boolean)

        private val pending = HashMap<SessionId, Pending>()
        private val history = HashMap<ProviderSessionId, Long>()
        private var sharedSystemWritten = false
        val turns = mutableListOf<SimTurn>()

        override fun start(session: SessionId, request: ClaudeRequest, provider: ProviderSessionId?, resume: Boolean): Outcome<RunnerState> {
            pending[session] = Pending(request, provider, resume)
            return inner.start(session, request, provider, resume)
        }

        override fun stream(session: SessionId): Outcome<List<RunnerUpdate>> =
            inner.stream(session).map { ups -> ups.map { u -> if (u is RunnerUpdate.TurnFinished) patch(session, u) else u } }

        private fun tier(model: String?): Double = when (model) {
            "haiku" -> 1.0
            "opus" -> 5.0
            else -> 3.0
        }

        private fun patch(session: SessionId, u: RunnerUpdate.TurnFinished): RunnerUpdate.TurnFinished {
            val p = pending.remove(session) ?: return u
            val model = p.request.model ?: "sonnet (аккаунт)"
            val prompt = TokenEstimate.of(p.request.prompt)
            val hist = if (p.resume) p.provider?.let { history[it] } ?: 0 else 0
            val start = m.system + hist + prompt
            if (start > m.window) {
                turns += SimTurn(model, 0, 0, 0, 0, 0.0, overflow = true)
                val why = Diagnostic.error(DiagCode.CLAUDE_CONTEXT_OVERFLOW, "Prompt is too long: $start tokens > ${m.window} maximum", "симуляция")
                return u.copy(result = u.result.copy(status = TurnStatus.INCOMPLETE, usage = TurnUsage.unknown("отказ по размеру"), diagnostics = u.result.diagnostics + why))
            }
            val fresh = !p.resume
            val explore = if (fresh) m.exploreFresh else m.exploreResumed
            val calls = if (fresh) m.callsFresh else m.callsResumed
            val systemCached = !fresh || (p.request.shareSystemPromptCache && sharedSystemWritten)
            if (p.request.shareSystemPromptCache) sharedSystemWritten = true
            val write = (if (systemCached) 0 else m.system) + prompt + explore
            val read = (if (systemCached) m.system else 0) + hist + (calls - 1) * (start + explore / 2)
            val input = calls * 50L
            val end = start + explore
            p.provider?.let { history[it] = end + m.output }
            val cost = tier(p.request.model) * (input + 1.25 * write + 0.1 * read + 5.0 * m.output) / 1_000_000
            turns += SimTurn(model, input, read, write, m.output, cost, overflow = false)
            val usage = TurnUsage(
                Measure.observed(input), Measure.observed(m.output), Measure.observed(read), Measure.observed(write), Measure.observed(0L),
                Measure.observed(end), Measure.observed(calls.toLong()), Measure.observed(calls.toLong()), Measure.observed(0L), Measure.observed(0L),
                Measure.observed(0L), Measure.estimated(BigDecimal.valueOf(cost)),
                listOf(ModelTokens(model, input, m.output, read, write, m.window, BigDecimal.valueOf(cost))),
            )
            return u.copy(result = u.result.copy(usage = usage))
        }
    }

    /** Итог прогона сценария под одной политикой. */
    data class Run(
        val done: Int, val tasks: Int, val turns: Int, val overflows: Int, val promptTokens: Long, val read: Long, val write: Long, val output: Long,
        val cost: Double, val ticks: Int, val trace: List<String>,
    ) {
        /** Сделанная работа на расход: выполненные задачи на условный доллар. */
        val workPerCost: Double get() = if (cost == 0.0) 0.0 else done / cost
    }

    /** Сценарий: задачи (заголовок → политика), источники, правило проверки, ход по попытке, политика «после». */
    data class Scenario(
        val name: String,
        val tasks: List<Pair<String, TaskPolicy>>,
        val sources: (ProjectId) -> List<ContextSource> = { emptyList() },
        val verify: (Int, String) -> List<String>? = { _, _ -> null },
        val turn: (Int) -> MockScenario = { MockScenario.SUCCESS },
        val model: CostModel = CostModel(),
        val after: EfficiencyPolicy = EfficiencyPolicy.DEFAULT,
        val sessionsPerTarget: Int = 2,
    )

    private var runs = 0

    private fun run(s: Scenario, policy: EfficiencyPolicy): Run {
        lateinit var sim: SimulatedClaude
        val h = EfficiencyHarness(dir.resolve("r${runs++}"), policy, s.sources, s.verify, s.turn, s.sessionsPerTarget) { inner ->
            SimulatedClaude(inner, s.model).also { sim = it }
        }
        for ((title, p) in s.tasks) h.orchestrator.addTask(title, "цель: $title", policy = p)
        val ticks = h.run(40)
        val ts = sim.turns
        val prompts = h.orchestrator.sessions.sessions.values.mapNotNull { it.usage.lastPrompt?.estimatedTokens }.sum()
        // Ход за ходом: режим prompt, размер, маршрут, продолжена ли сессия провайдера, чем кончилась сессия.
        val trace = h.starts.mapIndexed { i, st ->
            val r = h.orchestrator.sessions.sessions.getValue(st.session)
            val p = r.usage.lastPrompt
            "${i + 1}. ${p?.mode} [${p?.context}] ≈${p?.estimatedTokens} ток., модель ${st.request.model ?: "аккаунта"}, ${if (st.resume) "--resume" else "новая сессия"}, " +
                "итог ${r.state}/${r.lastReason ?: "—"}; ${p?.why ?: ""}"
        }
        return Run(
            done = h.orchestrator.tasks.tasks.values.count { it.state == TaskState.DONE }, tasks = s.tasks.size, turns = ts.size,
            overflows = ts.count { it.overflow }, promptTokens = prompts, read = ts.sumOf { it.cacheRead }, write = ts.sumOf { it.cacheWrite },
            output = ts.sumOf { it.output }, cost = ts.sumOf { it.cost }, ticks = ticks, trace = trace,
        )
    }

    private fun docs(project: ProjectId, count: Int, chars: Int) = (1..count).map { i ->
        ContextSource("doc:docs/part-$i.md", SourceLevel.DOCUMENT, project, Sensitivity.INTERNAL, Origin.REPOSITORY, "Section $i of the guide. ".repeat(chars / 25))
    }

    private val noisyFailure: (Int, String) -> List<String>? = { _, prompt ->
        // Исполнитель чинит, когда знает, что упало; вслепую — нет (допущение).
        if ("AssertionError" in prompt) {
            null
        } else {
            List(5_000) { "> Task :m$it:test UP-TO-DATE" } + listOf("ParserTest > exponent() FAILED", "    AssertionError: expected 1000 but was 1", "40 tests completed, 1 failed", "BUILD FAILED in 9s")
        }
    }

    private val scenarios = listOf(
        Scenario("1. Мелкая задача", listOf("переименовать константу" to TaskPolicy(taskClass = TaskClass.TRIVIAL))),
        Scenario("2. Обычная разработка", listOf("добавить разбор чисел" to TaskPolicy()), sources = { docs(it, 2, 8_000) }),
        Scenario("3. Анализ большого репозитория", listOf("описать устройство модулей" to TaskPolicy(taskClass = TaskClass.REPOSITORY_RECON)), sources = { docs(it, 6, 30_000) }),
        Scenario("4. Шумный журнал проверки", listOf("починить разбор экспоненты" to TaskPolicy()), verify = noisyFailure),
        Scenario(
            "5. Повторный контекст (продолжения)", listOf("перевести модуль на новый API" to TaskPolicy()), sources = { docs(it, 3, 20_000) },
            turn = { if (it <= 2) MockScenario.PARTIAL_COMPLETION else MockScenario.SUCCESS },
        ),
        Scenario("6. Параллельные сессии", (1..4).map { "независимая задача $it" to TaskPolicy() }, sessionsPerTarget = 4),
        Scenario(
            "7. Долгая сессия", listOf("большой рефакторинг" to TaskPolicy()),
            turn = { if (it <= 5) MockScenario.PARTIAL_COMPLETION else MockScenario.SUCCESS }, model = CostModel(exploreFresh = 40_000, exploreResumed = 30_000),
        ),
        Scenario(
            "8. Переполнение контекста", listOf("перенос большого модуля" to TaskPolicy()), sources = { docs(it, 2, 30_000) },
            turn = { if (it <= 1) MockScenario.PARTIAL_COMPLETION else MockScenario.SUCCESS }, model = CostModel(exploreFresh = 175_000),
            // Порог истории выключен: проверяется именно восстановление после отказа по размеру.
            after = EfficiencyPolicy.DEFAULT.copy(resume = ResumePolicy(checkpointAt = null)),
        ),
    )

    @Test
    fun beforeAndAfterOnEightScenarios() {
        val rows = scenarios.map { s -> Triple(s, run(s, EfficiencyPolicy.LEGACY), run(s, s.after)) }
        val report = buildString {
            appendLine("# Слой экономии D-40: «до» и «после» (симуляция)")
            appendLine()
            appendLine("Оркестратор, Git, проверка и разбор потока — настоящие; расход Claude — модель стоимости")
            appendLine("CostModel в EfficiencyBenchmarkTest (допущения — в KDoc). Стоимость — условные доллары.")
            appendLine("Главная мера — выполненные задачи на условный доллар (`работа/$`).")
            appendLine()
            appendLine("| Сценарий | Политика | Выполнено | Ходов | Переполнений | Prompt, ток. (оценка) | Чтение кеша | Запись кеша | Выход | Стоимость, $ | Работа/$ | Тактов |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
            for ((s, before, after) in rows) {
                for ((name, r) in listOf("до" to before, "после" to after)) {
                    appendLine(
                        "| ${s.name} | $name | ${r.done}/${r.tasks} | ${r.turns} | ${r.overflows} | ${r.promptTokens} | ${r.read} | ${r.write} | ${r.output} | " +
                            String.format(Locale.ROOT, "%.4f | %.1f | ${r.ticks} |", r.cost, r.workPerCost),
                    )
                }
            }
            val b = rows.map { it.second }
            val a = rows.map { it.third }
            appendLine()
            appendLine(
                String.format(
                    Locale.ROOT,
                    "Всего: до — ${b.sumOf { it.done }}/${b.sumOf { it.tasks }} задач за %.4f $; после — ${a.sumOf { it.done }}/${a.sumOf { it.tasks }} за %.4f $; ",
                    b.sumOf { it.cost }, a.sumOf { it.cost },
                ) +
                    String.format(
                        Locale.ROOT, "работа/$: %.1f → %.1f.",
                        b.sumOf { it.done } / b.sumOf { it.cost }, a.sumOf { it.done } / a.sumOf { it.cost },
                    ),
            )
        }
        val out = File(System.getProperty("dpai.repoRoot") ?: ".", "build/audits/efficiency").apply { mkdirs() }
        File(out, "benchmark.md").writeText(report)
        File(out, "benchmark-turns.md").writeText(
            rows.joinToString("\n\n") { (s, b, a) -> "## ${s.name}\n\nДо:\n" + b.trace.joinToString("\n") { "- $it" } + "\n\nПосле:\n" + a.trace.joinToString("\n") { "- $it" } },
        )
        for ((s, before, after) in rows) {
            // Правильность не меняется на экономию: «после» выполняет не меньше задач.
            assertTrue(after.done >= before.done, "${s.name}: выполнено ${after.done} < ${before.done}\n$report")
            // Работа на расход не хуже (запас 5 % — на строки «фактов среды» в prompt).
            assertTrue(after.done == 0 || after.workPerCost >= before.workPerCost * 0.95, "${s.name}: работа/$ ${after.workPerCost} < ${before.workPerCost}\n$report")
        }
        val total = { rs: List<Run> -> rs.sumOf { it.done } / rs.sumOf { it.cost } }
        assertTrue(total(rows.map { it.third }) > total(rows.map { it.second }), report)
    }
}
