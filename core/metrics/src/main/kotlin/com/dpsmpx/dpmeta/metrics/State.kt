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
data class ProjectState(
    val name: String,
    val place: String,
    val sections: Map<String, SourceSection>,
    val errors: List<SourceError>,
    /** Виды источников, которых у проекта нет по реестру (D-64): PR подсистемы — у DPmetaApp, журнала решений нет в реестре. */
    val skipped: Set<String> = emptySet(),
) {
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

    private fun pulls(kind: String): List<JsonObject>? = (payload(kind)?.get("pulls") as? JsonArray)?.items?.mapNotNull { it as? JsonObject }

    private fun time(o: JsonObject, key: String): Long? = (o[key] as? JsonNumber)?.toLongExact()

    /** Черновики среди открытых PR (ТЗ, §8, `repo.pr.open`). */
    val draftPulls: Measure<Long>
        get() = pulls("pulls")?.let { l -> Measure.observed(l.count { (it["draft"] as? JsonBool)?.value == true }.toLong()) }
            ?: Measure.unknown("нет данных о PR")

    /**
     * PR ждут автора (ТЗ, §8, `repo.pr.waiting_author`; [Rules.PR_WAITING_LIMIT]):
     * открыт и не черновик. Сколько их и сколько миллисекунд ждёт самый старый —
     * с последнего обновления PR (правила 3: открытие черновиком сразу после
     * первого пуша — не ожидание автора); никто не ждёт — `null`.
     */
    fun waitingAuthor(now: Timestamp): Measure<Pair<Long, Long?>> {
        val list = pulls("pulls") ?: return Measure.unknown("нет данных о PR")
        val waiting = list.filter { (it["draft"] as? JsonBool)?.value != true }
        // С последнего обновления: черновик живёт с первого пуша, а ждать автора начинает, когда готов (правила 3).
        val oldest = waiting.mapNotNull { time(it, "updatedAt") ?: time(it, "createdAt") }.minOrNull()?.let { now.epochMillis - it }
        return Measure.observed(waiting.size.toLong() to oldest)
    }

    /**
     * Время до вливания (ТЗ, §8, `repo.pr.lead_time`), минуты: медиана и
     * перцентиль [Rules.LEAD_TIME_PERCENTILE] по последним влитым PR, у которых
     * известно время открытия.
     */
    val leadTime: Measure<Pair<Long, Long>>
        get() {
            val merged = pulls("merged") ?: return Measure.unknown("нет данных о влитых PR")
            val minutes = merged.mapNotNull { o ->
                val m = time(o, "mergedAt")
                val c = time(o, "createdAt")
                if (m != null && c != null && m >= c) (m - c) / 60_000 else null
            }.sorted()
            if (minutes.isEmpty()) return Measure.unknown("у влитых PR нет времени открытия")
            return Measure.observed(percentile(minutes, 50) to percentile(minutes, Rules.LEAD_TIME_PERCENTILE))
        }

    /** Итог проверок последнего влитого PR (ТЗ, §8, `repo.checks.last`): зелёный — да, красный — нет. */
    val lastChecksGreen: Measure<Boolean>
        get() {
            val last = pulls("merged")?.maxByOrNull { time(it, "mergedAt") ?: Long.MIN_VALUE } ?: return Measure.unknown("влитых PR нет")
            return when ((last["checks"] as? JsonString)?.value) {
                "green" -> Measure.observed(true)
                "red" -> Measure.observed(false)
                else -> Measure.unknown("итог проверок не распознан")
            }
        }

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

    /** Файл чек-листа (D-176): прогресс по чекбоксам или «файла нет»; открытые пункты — текст и строка. */
    data class ChecklistFile(val path: String, val missing: Boolean, val done: Long, val total: Long, val open: List<Pair<String, Long>>)

    /** Чек-листы проекта (`checklists.v1`); секции нет — `null`: файлы не выбраны или ещё не прочитаны. */
    val checklists: List<ChecklistFile>?
        get() = (payload("checklists")?.get("files") as? JsonArray)?.items?.mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            val path = (o["path"] as? JsonString)?.value ?: return@mapNotNull null
            if ((o["missing"] as? JsonBool)?.value == true) return@mapNotNull ChecklistFile(path, true, 0, 0, emptyList())
            val open = (o["open"] as? JsonArray)?.items.orEmpty().mapNotNull { i ->
                val io = i as? JsonObject ?: return@mapNotNull null
                ((io["text"] as? JsonString)?.value ?: return@mapNotNull null) to ((io["line"] as? JsonNumber)?.toLongExact() ?: 0L)
            }
            ChecklistFile(path, false, (o["done"] as? JsonNumber)?.toLongExact() ?: 0L, (o["total"] as? JsonNumber)?.toLongExact() ?: 0L, open)
        }

    /** День истории из клона (D-66): коммиты, из них ИИ и человек, в основной и только в ветках, слияния PR. */
    data class HistoryDay(val date: String, val commits: Long, val ai: Long, val human: Long, val mainline: Long, val branchOnly: Long, val merges: Long)

    /** Дни истории из клона (`clone.history.v1`); секции нет — `null` (неизвестно, а не «коммитов не было»). */
    val historyDays: List<HistoryDay>?
        get() = (payload("history")?.get("days") as? JsonArray)?.items?.mapNotNull { d ->
            val a = (d as? JsonArray)?.items ?: return@mapNotNull null
            val date = (a.getOrNull(0) as? JsonString)?.value ?: return@mapNotNull null
            val n = (1..6).map { (a.getOrNull(it) as? JsonNumber)?.toLongExact() ?: return@mapNotNull null }
            HistoryDay(date, n[0], n[1], n[2], n[3], n[4], n[5])
        }

    /** День `ГГГГ-ММ-ДД` по поясу истории ([Rules.HISTORY_ZONE]), [back] дней назад от [now]. */
    private fun day(now: Timestamp, back: Int): String =
        java.time.Instant.ofEpochMilli(now.epochMillis).atOffset(Rules.HISTORY_ZONE).toLocalDate().minusDays(back.toLong()).toString()

    /**
     * Доля коммитов ИИ (ТЗ, §8, `repo.commits.ai_share`), процентов вниз до
     * целого, за [Rules.AI_SHARE_DAYS] последних дней (сегодня включительно).
     * Коммитов за окно не было — неизвестно, а не 0 %.
     */
    fun aiShare(now: Timestamp): Measure<Long> {
        val days = historyDays ?: return Measure.unknown("нет истории из клона")
        val from = day(now, Rules.AI_SHARE_DAYS - 1)
        val window = days.filter { it.date >= from }
        val total = window.sumOf { it.commits }
        return if (total == 0L) Measure.unknown("коммитов за ${Rules.AI_SHARE_DAYS} дней нет") else Measure.observed(window.sumOf { it.ai } * 100 / total)
    }

    /** Ветки впереди основной (ТЗ, §8, `repo.branches.unmerged`): сколько и своих коммитов сверх неё. */
    val branchesAhead: Measure<Pair<Long, Long>>
        get() {
            val list = (payload("history")?.get("ahead") as? JsonArray)?.items?.mapNotNull { it as? JsonObject } ?: return Measure.unknown("нет истории из клона")
            return Measure.observed(list.size.toLong() to list.sumOf { (it["commits"] as? JsonNumber)?.toLongExact() ?: 0L })
        }

    /**
     * Новые решения (ТЗ, §8, `repo.decisions.new`): прирост записей журнала
     * за [Rules.DECISIONS_NEW_DAYS] последних дней по основной ветке клона.
     * Журнал появился внутри окна — все его записи новые; журнала в
     * истории нет — неизвестно.
     */
    fun decisionsNew(now: Timestamp): Measure<Long> {
        val points = (payload("history")?.get("decisions") as? JsonArray)?.items?.mapNotNull { p ->
            val a = (p as? JsonArray)?.items ?: return@mapNotNull null
            val d = (a.getOrNull(0) as? JsonString)?.value ?: return@mapNotNull null
            val n = (a.getOrNull(1) as? JsonNumber)?.toLongExact() ?: return@mapNotNull null
            d to n
        } ?: return Measure.unknown("нет истории из клона")
        if (points.isEmpty()) return Measure.unknown("журнала решений в истории нет")
        val today = day(now, 0)
        val before = day(now, Rules.DECISIONS_NEW_DAYS)
        val nowCount = points.lastOrNull { it.first <= today }?.second ?: return Measure.unknown("журнала решений ещё нет")
        val base = points.lastOrNull { it.first <= before }?.second ?: 0L
        return Measure.observed((nowCount - base).coerceAtLeast(0))
    }

    fun toJson(now: Timestamp): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>(
            "name" to JsonString(name),
            "place" to JsonString(place),
            "sections" to JsonArray(sections.keys.sorted().map { sections.getValue(it).toJson() }),
            "errors" to JsonArray(errors.map { it.toJson() }),
        ).apply {
            if (skipped.isNotEmpty()) put("skipped", JsonArray(skipped.sorted().map(::JsonString)))
            working(now).orNull()?.let { put("working", JsonBool(it)) }
        },
    )
}

/** Перцентиль по ближайшему рангу: [sorted] — по возрастанию, не пуст. */
internal fun percentile(sorted: List<Long>, p: Int): Long = sorted[((p * sorted.size + 99) / 100 - 1).coerceIn(0, sorted.lastIndex)]

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
data class EcosystemState(val asOf: Timestamp, val rulesVersion: Int, val projects: List<ProjectState>, val syncHealthPercent: Long? = null) {
    fun project(name: String): ProjectState? = projects.firstOrNull { it.name == name }

    /**
     * Доля удачных источников последней синхронизации (ТЗ, §8, `app.sync.health`),
     * процентов: файла нет (404) — не неудача, а ответ.
     */
    val syncHealth: Measure<Long> get() = syncHealthPercent?.let { Measure.observed(it) } ?: Measure.unknown("синхронизаций ещё не было")

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
        fun flag(m: Measure<Boolean>): Measure<Long> = m.orNull()?.let { Measure.observed(if (it) 1L else 0L) } ?: Measure.unknown("")
        for (p in projects) {
            add("repo.pr.open", p.name, p.openPulls)
            add("repo.activity.state", p.name, flag(p.working(asOf)))
            add("repo.decisions.count", p.name, p.decisions)
            add("repo.todo.items", p.name, p.todoOpen)
            add("repo.roadmap.progress", p.name, p.roadmapPercent)
            // С правил 2 (этап 4, D-64): PR, проверки, стадия и шлюз.
            add("repo.pr.open.drafts", p.name, p.draftPulls)
            p.waitingAuthor(asOf).orNull()?.let { (count, oldest) ->
                add("repo.pr.waiting_author", p.name, Measure.observed(count))
                oldest?.let { add("repo.pr.waiting_author.days", p.name, Measure.observed(it / DAY_MS)) }
            }
            p.leadTime.orNull()?.let { (median, high) ->
                add("repo.pr.lead_time.median", p.name, Measure.observed(median))
                // Имя точки — устойчивое (правила 3); перцентиль меняется только с новым именем.
                add("repo.pr.lead_time.p90", p.name, Measure.observed(high))
            }
            add("repo.checks.last", p.name, flag(p.lastChecksGreen))
            val gate = Readiness.gate(p, asOf)
            add("repo.stage", p.name, gate.stage.orNull()?.let { Measure.observed(it.number.toLong()) } ?: Measure.unknown(""))
            if (gate.anyKnown) add("repo.gate.passed", p.name, Measure.observed(gate.passed.toLong()))
            add("repo.gate.ready", p.name, flag(gate.ready))
            // С правил 5 (D-66): из истории клона.
            add("repo.commits.ai_share", p.name, p.aiShare(asOf))
            p.branchesAhead.orNull()?.let { (count, commits) ->
                add("repo.branches.unmerged", p.name, Measure.observed(count))
                add("repo.branches.unmerged.commits", p.name, Measure.observed(commits))
            }
            add("repo.decisions.new", p.name, p.decisionsNew(asOf))
        }
        add("eco.active_projects", "eco", activeProjects)
        add("eco.questions_open", "eco", questionsOpen)
        add("app.sync.health", "app", syncHealth)
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
            syncHealthPercent?.let { put("syncHealth", JsonNumber.of(it)) }
        },
    )

    companion object {
        const val FORMAT = "dpmeta.state"
        const val VERSION = 2
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private val NOT_FOUND = setOf(DiagCode.GITHUB_NOT_FOUND, DiagCode.ENTITY_NOT_FOUND)

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
                val skipped = buildSet {
                    // История подсистемы — в истории DPmetaApp (D-66): своей нет.
                    if (entry.dir != null) addAll(listOf("pulls", "merged", "branches", "history"))
                    if (entry.decisions == null) add("decisions")
                }
                // Чек-листы сняты пользователем (D-176) — прежняя секция не остаётся навсегда.
                // Прежний специальный источник DPmeta снят D-173/D-176.
                // История чисел в журнале остаётся; текущая секция больше не обновляется.
                val current = (before + fresh) - "dpmeta"
                val kept = if (entry.checklists.isEmpty()) current - "checklists" else current
                ProjectState(entry.name, entry.place.code, kept.toSortedMap(), errors, skipped)
            }
            val failed = failures.count { it.diagnostic.code !in NOT_FOUND }
            val health = (observations.size + failed).takeIf { it > 0 }?.let { observations.size * 100L / it }
            return EcosystemState(now, Rules.RULES_VERSION, projects, health)
        }

        /** v1 сохраняет прежние числа и версию правил; неизвестная версия — отказ. */
        fun fromJson(value: JsonValue): Outcome<EcosystemState> {
            val d = DiagnosticCollector()
            val f = JsonFields.root(value, d) ?: return Outcome.Failure(d.all)
            FormatHeader.read(f, FORMAT, 1..VERSION, d) ?: return Outcome.Failure(d.all)
            val asOf = f.long("asOf")
            val rules = f.long("rulesVersion")
            val projects = f.array("projects")
            f.raw("activeProjects", required = false)
            f.raw("questionsOpen", required = false)
            val health = f.optLong("syncHealth")
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
                val skipped = (o["skipped"] as? JsonArray)?.items.orEmpty().mapNotNull { (it as? JsonString)?.value }.toSet()
                ProjectState(name, place, sections.associateBy { it.kind }.toSortedMap(), errors, skipped)
            }
            return Outcome.success(EcosystemState(Timestamp(asOf), rules.toInt(), out, health))
        }
    }
}
