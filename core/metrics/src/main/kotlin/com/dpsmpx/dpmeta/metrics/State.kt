package com.dpsmpx.dpmeta.metrics

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpmeta.model.Observation
import com.dpsmpx.dpmeta.model.Registry
import com.dpsmpx.dpmeta.model.SourceFailure

/**
 * Последнее удачное наблюдение источника проекта (ТЗ, §6.4): [kind] —
 * вид источника, [asOf] — когда получено, [payload] — нормализованный JSON.
 * Источник отказал — секция остаётся прежней, а отказ пишется в [ProjectState.errors]:
 * прежнее не перекрывается пустым.
 */
data class SourceSection(val kind: String, val schema: String, val asOf: Timestamp, val payload: JsonValue) {
    fun toJson(): JsonObject = JsonObject.of(
        "kind" to JsonString(kind),
        "schema" to JsonString(schema),
        "asOf" to JsonNumber.of(asOf.epochMillis),
        "payload" to payload,
    )
}

/** Отказ источника: код, сообщение (`Diagnostic`, без стека) и когда случился. */
data class SourceError(val kind: String, val code: String, val message: String, val at: Timestamp) {
    fun toJson(): JsonObject = JsonObject.of(
        "kind" to JsonString(kind),
        "code" to JsonString(code),
        "message" to JsonString(message),
        "at" to JsonNumber.of(at.epochMillis),
    )
}

/** Состояние проекта: секции источников и отказы последнего прогона. */
data class ProjectState(val name: String, val place: String, val sections: Map<String, SourceSection>, val errors: List<SourceError>) {
    private fun payload(kind: String): JsonObject? = sections[kind]?.payload as? JsonObject

    private fun long(kind: String, field: String): Measure<Long> =
        (payload(kind)?.get(field) as? JsonNumber)?.toLongExact()?.let { Measure.observed(it) }
            ?: Measure.unknown("нет данных источника $kind")

    val decisions: Measure<Long> get() = long("decisions", "count")
    val todoOpen: Measure<Long> get() = long("todo", "open")
    val forAuthor: Measure<Long> get() = long("todo", "forAuthor")

    val openPulls: Measure<Long>
        get() = (payload("pulls")?.get("pulls") as? JsonArray)?.let { Measure.observed(it.items.size.toLong()) }
            ?: Measure.unknown("нет данных о PR")

    /**
     * Доля отмеченного в ROADMAP, в процентах вниз до целого (ТЗ, §8,
     * `repo.roadmap.progress`): по шагам, а если шагов нет — по этапам.
     */
    val roadmapPercent: Measure<Long>
        get() {
            val steps = long("roadmap", "stepsTotal").orNull() ?: 0L
            val (done, total) = if (steps > 0) {
                long("roadmap", "stepsDone").orNull() to steps
            } else {
                long("roadmap", "stagesDone").orNull() to long("roadmap", "stagesTotal").orNull()
            }
            return if (done == null || total == null || total == 0L) Measure.unknown("нет шагов и этапов ROADMAP") else Measure.observed(done * 100 / total)
        }

    /**
     * «Кто работает» (F-16, `DPmeta:M-08`): коммит в `main` за
     * [Rules.MAIN_COMMIT_WINDOW] или ветка, или открытый PR, обновлённые за
     * [Rules.BRANCH_OR_PR_WINDOW]. Нет ни веток, ни PR — неизвестно, а не
     * «свободен».
     */
    fun working(now: Timestamp): Measure<Boolean> {
        val branches = (payload("branches")?.get("branches") as? JsonArray)?.items?.mapNotNull { it as? JsonObject }
        val pulls = (payload("pulls")?.get("pulls") as? JsonArray)?.items?.mapNotNull { it as? JsonObject }
        if (branches == null && pulls == null) return Measure.unknown("нет данных о ветках и PR")
        fun date(o: JsonObject, k: String) = (o[k] as? JsonNumber)?.toLongExact()
        val mainRecent = branches.orEmpty().any { b ->
            (b["name"] as? JsonString)?.value == Rules.MAIN_BRANCH && (date(b, "date") ?: Long.MIN_VALUE) >= (now - Rules.MAIN_COMMIT_WINDOW).epochMillis
        }
        val branchRecent = branches.orEmpty().any { b ->
            (b["name"] as? JsonString)?.value != Rules.MAIN_BRANCH && (date(b, "date") ?: Long.MIN_VALUE) >= (now - Rules.BRANCH_OR_PR_WINDOW).epochMillis
        }
        val prRecent = pulls.orEmpty().any { (date(it, "updatedAt") ?: Long.MIN_VALUE) >= (now - Rules.BRANCH_OR_PR_WINDOW).epochMillis }
        return Measure.observed(mainRecent || branchRecent || prRecent)
    }

    fun toJson(now: Timestamp): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>(
            "name" to JsonString(name),
            "place" to JsonString(place),
            "sections" to JsonArray(sections.keys.sorted().map { sections.getValue(it).toJson() }),
            "errors" to JsonArray(errors.map { it.toJson() }),
        ).apply {
            working(now).orNull()?.let { put("working", JsonBool(it)) }
        },
    )
}

/** Точка истории чисел (ТЗ, §6.3): только добавляется; версия правил — при ней. */
data class MetricPoint(val metric: String, val subject: String, val value: Long, val asOf: Timestamp, val rulesVersion: Int) {
    fun toJson(): JsonObject = JsonObject.of(
        "metric" to JsonString(metric),
        "subject" to JsonString(subject),
        "value" to JsonNumber.of(value),
        "asOf" to JsonNumber.of(asOf.epochMillis),
        "rulesVersion" to JsonNumber.of(rulesVersion),
    )
}

/**
 * Состояние экосистемы (D-34): хранится готовым, текущее заменяется новым.
 * Порядок проектов — порядок реестра (порядок показа, F-01).
 */
data class EcosystemState(val asOf: Timestamp, val rulesVersion: Int, val projects: List<ProjectState>) {
    fun project(name: String): ProjectState? = projects.firstOrNull { it.name == name }

    val activeProjects: Measure<Long>
        get() {
            val known = projects.map { it.working(asOf) }
            return if (known.all { it is Measure.Unknown }) Measure.unknown("ни у одного проекта нет данных об активности") else Measure.observed(known.count { it.orNull() == true }.toLong())
        }

    /** Открытые вопросы автору — пункты «За автором» всех `TODO.md` (F-24). */
    val questionsOpen: Measure<Long>
        get() {
            val known = projects.mapNotNull { it.forAuthor.orNull() }
            return if (known.isEmpty()) Measure.unknown("нет ни одного TODO.md") else Measure.observed(known.sum())
        }

    /** Точки истории этого среза: неизвестное — без точки, а не ноль (D-13 подсистемы, D-34). */
    fun points(): List<MetricPoint> {
        val out = mutableListOf<MetricPoint>()
        fun add(metric: String, subject: String, m: Measure<Long>) {
            m.orNull()?.let { out += MetricPoint(metric, subject, it, asOf, rulesVersion) }
        }
        for (p in projects) {
            add("repo.pr.open", p.name, p.openPulls)
            add("repo.activity.state", p.name, p.working(asOf).let { w -> w.orNull()?.let { Measure.observed(if (it) 1L else 0L) } ?: Measure.unknown("") })
            add("repo.decisions.count", p.name, p.decisions)
            add("repo.todo.items", p.name, p.todoOpen)
            add("repo.roadmap.progress", p.name, p.roadmapPercent)
        }
        add("eco.active_projects", "eco", activeProjects)
        add("eco.questions_open", "eco", questionsOpen)
        return out
    }

    fun toJson(): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>(
            *FormatHeader.write(FORMAT, VERSION),
            "asOf" to JsonNumber.of(asOf.epochMillis),
            "rulesVersion" to JsonNumber.of(rulesVersion),
            "projects" to JsonArray(projects.map { it.toJson(asOf) }),
        ).apply {
            activeProjects.orNull()?.let { put("activeProjects", JsonNumber.of(it)) }
            questionsOpen.orNull()?.let { put("questionsOpen", JsonNumber.of(it)) }
        },
    )

    companion object {
        const val FORMAT = "dpmeta.state"
        const val VERSION = 1

        /**
         * Новое состояние (ТЗ, §6.3, §6.4): для каждого проекта реестра,
         * кроме архива, — последнее наблюдение каждого источника из
         * [observations], иначе прежняя секция из [previous]; [failures] —
         * отказы прогона по паре «проект → вид источника». Результат не
         * зависит от порядка наблюдений (N-01).
         */
        fun derive(
            registry: Registry,
            observations: List<Observation>,
            failures: List<SourceFailure>,
            previous: EcosystemState?,
            now: Timestamp,
        ): EcosystemState {
            val latest = observations
                .groupBy { it.subject to it.kind }
                .mapValues { (_, list) -> list.maxWith(compareBy<Observation> { it.asOf }.thenBy { it.id.toString() }) }
            val projects = registry.shown.map { entry ->
                val before = previous?.project(entry.name)?.sections.orEmpty()
                val fresh = latest.filterKeys { it.first == entry.name }.values.associate { o ->
                    o.kind to SourceSection(o.kind, o.schema, o.asOf, o.payload)
                }
                val errors = failures.filter { it.project == entry.name }
                    .sortedWith(compareBy({ it.kind }, { it.diagnostic.code.name }, { it.at }))
                    .map { f -> SourceError(f.kind, f.diagnostic.code.name, f.diagnostic.message, f.at) }
                ProjectState(entry.name, entry.place.code, (before + fresh).toSortedMap(), errors)
            }
            return EcosystemState(now, Rules.RULES_VERSION, projects)
        }

        /** Прежнее состояние из документа: другая версия формата — отказ, а не догадка. */
        fun fromJson(value: JsonValue): Outcome<EcosystemState> {
            val d = DiagnosticCollector()
            val f = JsonFields.root(value, d) ?: return Outcome.Failure(d.all)
            FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
            val asOf = f.long("asOf")
            val rules = f.long("rulesVersion")
            val projects = f.array("projects")
            f.raw("activeProjects", required = false)
            f.raw("questionsOpen", required = false)
            f.finish()
            if (d.hasErrors || asOf == null || rules == null || projects == null) return Outcome.Failure(d.all)
            val out = projects.items.map { p ->
                val o = p as? JsonObject ?: return Outcome.failure(DiagCode.FIELD_TYPE, "проект — не объект", "projects")
                val name = (o["name"] as? JsonString)?.value ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет имени проекта", "projects")
                val place = (o["place"] as? JsonString)?.value ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет места проекта", name)
                val sections = (o["sections"] as? JsonArray)?.items.orEmpty().map { s ->
                    val so = s as? JsonObject ?: return Outcome.failure(DiagCode.FIELD_TYPE, "секция — не объект", name)
                    SourceSection(
                        (so["kind"] as? JsonString)?.value ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет вида секции", name),
                        (so["schema"] as? JsonString)?.value ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет схемы секции", name),
                        Timestamp((so["asOf"] as? JsonNumber)?.toLongExact() ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет времени секции", name)),
                        so["payload"] ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет содержимого секции", name),
                    )
                }
                val errors = (o["errors"] as? JsonArray)?.items.orEmpty().map { e ->
                    val eo = e as? JsonObject ?: return Outcome.failure(DiagCode.FIELD_TYPE, "отказ — не объект", name)
                    SourceError(
                        (eo["kind"] as? JsonString)?.value ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет вида отказа", name),
                        (eo["code"] as? JsonString)?.value ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет кода отказа", name),
                        (eo["message"] as? JsonString)?.value.orEmpty(),
                        Timestamp((eo["at"] as? JsonNumber)?.toLongExact() ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет времени отказа", name)),
                    )
                }
                ProjectState(name, place, sections.associateBy { it.kind }.toSortedMap(), errors)
            }
            return Outcome.success(EcosystemState(Timestamp(asOf), rules.toInt(), out))
        }
    }
}
