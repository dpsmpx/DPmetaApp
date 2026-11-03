package com.dpsmpx.dpai.orchestrator.ecosystem

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.contexts.ContextSource
import com.dpsmpx.dpai.contexts.SourceLevel
import com.dpsmpx.dpai.ecosystem.EcosystemProject
import com.dpsmpx.dpai.ecosystem.EcosystemProvider
import com.dpsmpx.dpai.ecosystem.SuggestedTask
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.security.Origin
import com.dpsmpx.dpai.security.Sensitivity
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.tasks.Priority

/**
 * Ограничители глобального прогона (ТЗ, §46): какие проекты и виды работ
 * разрешены, сколько задач одновременно и всего за прогон, сколько тактов
 * (бюджет ресурсов). [confirm] — подтверждение создания задачи из
 * предложения: в V1 без него задача не создаётся
 * (`docs/direction/V1_SCOPE.md`, §3).
 */
data class RunPolicy(
    val allowedProjects: Set<String>,
    val allowedOperations: Set<String>,
    val maxConcurrency: Int = 2,
    val maxTasks: Int = 10,
    val maxTicks: Int = 200,
    val confirm: (EcosystemProject, SuggestedTask) -> Boolean,
) {
    init {
        require(maxConcurrency >= 1 && maxTasks >= 1 && maxTicks >= 1) { "ограничители — положительные" }
    }
}

/** Итог задачи из экосистемы для записи обратно (ТЗ, §46: record). */
data class RunRecord(val project: String, val suggestion: String, val task: TaskId, val state: String, val verdict: String?, val reportHash: String?)

/**
 * Источники контекста из документов проекта экосистемы (ТЗ, §25): файлы
 * репозитория — данные с пометкой происхождения (T3). Нечитаемый файл —
 * пропуск, а не догадка.
 */
object EcosystemSources {
    fun of(project: EcosystemProject, id: ProjectId, read: (String) -> String?): List<ContextSource> =
        project.documents.sorted().mapNotNull { doc ->
            read(doc)?.let { ContextSource("doc:$doc", SourceLevel.DOCUMENT, id, Sensitivity.INTERNAL, Origin.REPOSITORY, it) }
        } + ContextSource(
            "ecosystem:${project.key}:state", SourceLevel.PROJECT, id, Sensitivity.INTERNAL, Origin.EXTERNAL,
            project.state.toSortedMap().entries.joinToString("\n") { "${it.key}: ${it.value}" },
        )
}

/**
 * Глобальный режим (ТЗ, §43–§46; этапы 15, 16): осмотр экосистемы →
 * выбор следующей допустимой задачи → исполнение контуром проекта →
 * проверка → запись итога → следующая. Без источника экосистемы
 * ([EcosystemProvider.None]) прогон просто не находит предложений, а
 * контуры проектов работают как обычно (ТЗ, §148).
 */
class EcosystemRun(
    private val provider: EcosystemProvider,
    private val orchestrators: Map<String, Orchestrator>,
    private val policy: RunPolicy,
    private val results: DocumentStore,
    private val clock: Clock,
) {
    private val imported = LinkedHashMap<Pair<String, String>, TaskId>()
    private val recorded = HashSet<TaskId>()
    val records = mutableListOf<RunRecord>()
    val notes = mutableListOf<Diagnostic>()
    var ticks = 0
        private set

    /** Осмотр: проекты экосистемы и их состояние (ТЗ, §43). */
    fun inspect(): Outcome<List<EcosystemProject>> = provider.projects()

    /**
     * Следующая допустимая задача (ТЗ, §45): разрешённый проект и вид
     * работы, подтверждение, свободное место под предел одновременности;
     * порядок — приоритет, затем проект и ключ (D-22).
     */
    fun selectNext(projects: List<EcosystemProject>): Pair<EcosystemProject, SuggestedTask>? {
        if (imported.size >= policy.maxTasks) {
            if (notes.none { it.code == DiagCode.RUN_BUDGET_EXHAUSTED }) notes += Diagnostic.warning(DiagCode.RUN_BUDGET_EXHAUSTED, "предел задач прогона ${policy.maxTasks} исчерпан")
            return null
        }
        if (active() >= policy.maxConcurrency) return null
        // Проекты без контура прогон не видит вовсе (их задачи ставить некуда), а не отказывает им.
        val candidates = EcosystemPlan.of(projects.filter { it.key in orchestrators }, orchestrators.keys, policy.allowedProjects, policy.allowedOperations)
            .filter { (it.project.key to it.suggestion.key) !in imported }
        for (c in candidates) {
            val (p, s) = c.project to c.suggestion
            if (c.refusal != null) {
                note(Diagnostic.warning(DiagCode.OPERATION_NOT_ALLOWED, c.refusal, "${p.key}/${s.key}"))
                continue
            }
            if (!policy.confirm(p, s)) {
                note(Diagnostic.info(DiagCode.OPERATION_NOT_ALLOWED, "создание задачи не подтверждено", "${p.key}/${s.key}"))
                continue
            }
            return p to s
        }
        return null
    }

    /** Один шаг прогона: выбрать и создать задачу (если можно), такт всех контуров, записать итоги. */
    fun step(): Outcome<Unit> {
        val projects = when (val r = inspect()) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        selectNext(projects)?.let { (p, s) ->
            val o = orchestrators.getValue(p.key)
            when (val t = o.addTask(s.title, s.objective, priority(s)!!)) {
                is Outcome.Success -> imported[p.key to s.key] = t.value
                is Outcome.Failure -> notes += t.errors
            }
        }
        orchestrators.values.forEach { it.tick() }
        ticks++
        record()
        return Outcome.success(Unit)
    }

    /** Прогон до тишины или исчерпания бюджета тактов (ТЗ, §46: resource budget). */
    fun run(): List<RunRecord> {
        while (ticks < policy.maxTicks) {
            if (step() is Outcome.Failure) break
            val idle = active() == 0 && selectNext(inspect().valueOrNull().orEmpty()) == null
            if (idle) break
        }
        if (ticks >= policy.maxTicks) notes += Diagnostic.warning(DiagCode.RUN_BUDGET_EXHAUSTED, "бюджет тактов ${policy.maxTicks} исчерпан")
        return records.toList()
    }

    private fun active(): Int = imported.entries.count { (k, t) ->
        val state = orchestrators.getValue(k.first).tasks.tasks[t]?.state
        state != null && state !in DONE_STATES
    }

    private fun record() {
        for ((k, t) in imported) {
            if (t in recorded) continue
            val task = orchestrators.getValue(k.first).tasks.tasks[t] ?: continue
            if (task.state !in DONE_STATES) continue
            recorded += t
            records += RunRecord(k.first, k.second, t, task.state.name, task.verification?.verdict, task.verification?.reportHash)
        }
        results.write(RESULTS_KEY, JsonObject(linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "updatedAt" to JsonNumber.of(clock.now().epochMillis),
            "records" to JsonArray(records.map { r ->
                JsonObject.of(
                    "project" to JsonString(r.project), "suggestion" to JsonString(r.suggestion), "task" to JsonString(r.task.value),
                    "state" to JsonString(r.state), "verdict" to (r.verdict?.let(::JsonString) ?: JsonNull),
                    "reportHash" to (r.reportHash?.let(::JsonString) ?: JsonNull),
                )
            }),
        )))
    }

    private fun note(d: Diagnostic) {
        if (notes.none { it.code == d.code && it.subject == d.subject && it.message == d.message }) notes += d
    }

    private fun priority(s: SuggestedTask): Priority? = EcosystemPlan.priority(s)

    companion object {
        const val FORMAT = "dpai.ecosystem-results"
        const val VERSION = 1
        const val RESULTS_KEY = "ecosystem-results"
        private val DONE_STATES = setOf(
            com.dpsmpx.dpai.core.state.TaskState.DONE,
            com.dpsmpx.dpai.core.state.TaskState.FAILED,
            com.dpsmpx.dpai.core.state.TaskState.CANCELLED,
            com.dpsmpx.dpai.core.state.TaskState.BLOCKED,
            com.dpsmpx.dpai.core.state.TaskState.COMPLETION_UNVERIFIED,
        )
    }
}
