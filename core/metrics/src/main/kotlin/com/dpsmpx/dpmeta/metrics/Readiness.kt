package com.dpsmpx.dpmeta.metrics

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString

/**
 * Стадия и шлюз готовности проекта (D-64, вместо отдельной системы
 * портфеля — D-58): только из того, что приложение уже читает, без ручных
 * отметок. Стадия — текущий этап ROADMAP; шлюз — критерии [Criterion],
 * каждый «да», «нет» или «неизвестно»; пороги — в [Rules].
 */
object Readiness {
    /** Критерий шлюза: [id] — устойчивый ключ (только добавляются), [title] — для экрана. */
    enum class Criterion(val id: String, val title: String) {
        DECISIONS("gate.decisions", "Решения записаны в журнале"),
        PROCESS("gate.process", "TODO.md и ROADMAP есть"),
        CHECKS("gate.checks", "Проверки последнего влитого PR — зелёные"),
        AUTHOR("gate.author", "Вопросов за автором нет"),
        PULLS("gate.pulls", "PR не ждут автора дольше срока"),
        STAGE("gate.stage", "Шаги текущего этапа ROADMAP отмечены"),
    }

    /** Текущий этап ROADMAP: [number] — по порядку с 1, из [total]; все закрыты — последний. */
    data class Stage(val number: Int, val total: Int, val title: String, val stepsDone: Int, val stepsTotal: Int, val allDone: Boolean)

    /**
     * Шлюз: итог каждого применимого критерия; [ready] — все «да»; хоть одно
     * «нет» — «нет»; иначе неизвестно. [notApplicable] — критерии, источника
     * которых у проекта нет по реестру (PR и проверки подсистемы — у
     * DPmetaApp; журнала решений нет в реестре): в итог не входят.
     */
    data class Gate(val stage: Measure<Stage>, val results: Map<Criterion, Measure<Boolean>>, val notApplicable: List<Criterion> = emptyList()) {
        val passed: Int get() = results.values.count { it.orNull() == true }

        /** Хоть один критерий известен — иначе число выполненных не пишется в историю (0 из неизвестного — не 0). */
        val anyKnown: Boolean get() = results.values.any { it is Measure.Known }

        val ready: Measure<Boolean>
            get() = when {
                results.values.any { it.orNull() == false } -> Measure.observed(false)
                results.values.all { it.orNull() == true } -> Measure.observed(true)
                else -> Measure.unknown("не все критерии известны")
            }

        /** Что держит шлюз: критерии «нет» по порядку каталога. */
        val blocking: List<Criterion> get() = Criterion.entries.filter { results[it]?.orNull() == false }
    }

    private val NOT_FOUND = setOf(DiagCode.GITHUB_NOT_FOUND.name, DiagCode.ENTITY_NOT_FOUND.name)

    fun gate(p: ProjectState, now: Timestamp): Gate {
        val stage = stage(p)
        val skip = buildList {
            if ("decisions" in p.skipped) add(Criterion.DECISIONS)
            if ("pulls" in p.skipped) addAll(listOf(Criterion.CHECKS, Criterion.PULLS))
        }
        val all = linkedMapOf(
            Criterion.DECISIONS to p.decisions.let { d -> d.orNull()?.let { Measure.observed(it > 0) } ?: absent(p, "decisions") ?: Measure.unknown("журнал решений не наблюдается") },
            Criterion.PROCESS to process(p),
            Criterion.CHECKS to p.lastChecksGreen,
            Criterion.AUTHOR to (p.forAuthor.orNull()?.let { Measure.observed(it == 0L) } ?: absent(p, "todo") ?: Measure.unknown("нет TODO.md")),
            Criterion.PULLS to (p.waitingAuthor(now).orNull()?.let { (_, oldest) -> Measure.observed(oldest == null || oldest < Rules.PR_WAITING_LIMIT.inWholeMilliseconds) } ?: Measure.unknown("нет данных о PR")),
            // Нет ROADMAP — этапа нет: «неизвестно», а отсутствие файла уже держит [Criterion.PROCESS].
            Criterion.STAGE to stageDone(stage),
        )
        return Gate(stage, all.filterKeys { it !in skip }, skip)
    }

    /**
     * Шаги текущего этапа отмечены. У этапа без шагов (ROADMAP таблицей
     * «Состояние» — DPpass, «AI») отметок нет — «неизвестно», а не «нет».
     */
    private fun stageDone(stage: Measure<Stage>): Measure<Boolean> {
        val s = stage.orNull() ?: return Measure.unknown("нет ROADMAP")
        return when {
            s.allDone -> Measure.observed(true)
            s.stepsTotal == 0 -> Measure.unknown("у этапа нет шагов")
            else -> Measure.observed(s.stepsDone == s.stepsTotal)
        }
    }

    /** Текущий этап: первый «идёт», иначе первый не сделанный; все сделаны — последний. */
    fun stage(p: ProjectState): Measure<Stage> {
        val stages = ((p.sections["roadmap"]?.payload as? JsonObject)?.get("stages") as? JsonArray)?.items?.mapNotNull { it as? JsonObject }
            ?: return Measure.unknown("нет ROADMAP")
        if (stages.isEmpty()) return Measure.unknown("в ROADMAP нет этапов")
        fun flag(o: JsonObject) = (o["flag"] as? JsonString)?.value
        val index = stages.indexOfFirst { flag(it) == "active" }.takeIf { it >= 0 }
            ?: stages.indexOfFirst { flag(it) != "done" }.takeIf { it >= 0 }
        val i = index ?: stages.lastIndex
        val o = stages[i]
        fun int(k: String) = (o[k] as? JsonNumber)?.toLongExact()?.toInt() ?: 0
        return Measure.observed(Stage(i + 1, stages.size, (o["title"] as? JsonString)?.value.orEmpty(), int("stepsDone"), int("stepsTotal"), index == null))
    }

    /** Файла процесса нет (404 у источника, секции нет) — известное «нет», а не неизвестно. */
    private fun absent(p: ProjectState, kind: String): Measure<Boolean>? =
        if (kind !in p.sections && p.errors.any { it.kind == kind && it.code in NOT_FOUND }) Measure.observed(false) else null

    private fun process(p: ProjectState): Measure<Boolean> {
        val todo = if ("todo" in p.sections) true else absent(p, "todo")?.orNull()
        val roadmap = if ("roadmap" in p.sections) true else absent(p, "roadmap")?.orNull()
        return when {
            todo == false || roadmap == false -> Measure.observed(false)
            todo == true && roadmap == true -> Measure.observed(true)
            else -> Measure.unknown("TODO.md или ROADMAP не наблюдались")
        }
    }
}
