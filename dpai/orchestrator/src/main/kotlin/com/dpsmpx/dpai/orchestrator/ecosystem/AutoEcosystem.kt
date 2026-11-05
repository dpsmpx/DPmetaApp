package com.dpsmpx.dpai.orchestrator.ecosystem

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonPath
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.ecosystem.EcosystemProject
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.tasks.Priority

/**
 * Политика прогона (D-106, ТЗ, §44): из каких проектов, видов работ и
 * приоритетов контур ставит задачи сам, сколько за 24 часа и сколько
 * открытых сразу, до какого срока ([until] `null` — без срока). Задаёт её
 * только человек на экране; пустой список — не «всё», а ошибка ([problem]).
 */
data class AutoPolicy(
    val projects: Set<String>,
    val operations: Set<String>,
    val priorities: Set<Priority>,
    val maxPerDay: Int,
    val maxOpen: Int,
    val until: Timestamp?,
) {
    /** Что не так с политикой; `null` — годится. */
    fun problem(): String? = when {
        projects.isEmpty() || projects.any { it.isBlank() } -> "нет проектов: политика без проектов ничего не разрешает"
        operations.isEmpty() || operations.any { it.isBlank() } -> "нет видов работ: политика без них ничего не разрешает"
        priorities.isEmpty() -> "нет приоритетов: политика без них ничего не разрешает"
        maxPerDay !in 1..MAX_PER_DAY -> "предел за 24 часа — от 1 до $MAX_PER_DAY"
        maxOpen !in 1..MAX_OPEN -> "предел открытых задач — от 1 до $MAX_OPEN"
        else -> null
    }

    fun expired(now: Timestamp): Boolean = until != null && now >= until

    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "projects" to JsonArray(projects.sorted().map(::JsonString)),
            "operations" to JsonArray(operations.sorted().map(::JsonString)),
            "priorities" to JsonArray(priorities.sortedBy { it.ordinal }.map { JsonString(it.name) }),
            "maxPerDay" to JsonNumber.of(maxPerDay),
            "maxOpen" to JsonNumber.of(maxOpen),
        ).also { m -> until?.let { m["until"] = JsonNumber.of(it.epochMillis) } },
    )

    /** Отпечаток политики — в каждой записи о задаче: видно, по какой версии рамок её поставили. */
    fun digest(): String = Hashing.sha256(toJson()).hex.take(16)

    companion object {
        const val FORMAT = "dpai.auto-policy"
        const val VERSION = 1
        const val MAX_PER_DAY = 20
        const val MAX_OPEN = 5

        /** Чтение строгое (D-09): неизвестное поле, пустой список, чужая версия — отказ, а не «всё». */
        fun read(o: JsonObject): Outcome<AutoPolicy> {
            val d = DiagnosticCollector()
            val f = JsonFields.root(o, d) ?: return Outcome.Failure(d.all)
            FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
            val projects = strings(f, "projects")
            val operations = strings(f, "operations")
            val priorities = strings(f, "priorities")?.mapNotNull { name ->
                Priority.entries.firstOrNull { it.name == name } ?: null.also { d.error(DiagCode.VALUE_INVALID, "неизвестный приоритет $name", JsonPath.field("$", "priorities")) }
            }
            val perDay = f.int("maxPerDay")
            val open = f.int("maxOpen")
            val until = f.optLong("until")?.let(::Timestamp)
            f.finish()
            if (d.hasErrors || projects == null || operations == null || priorities == null || perDay == null || open == null) return Outcome.Failure(d.all)
            val p = AutoPolicy(projects.toSet(), operations.toSet(), priorities.toSet(), perDay, open, until)
            return p.problem()?.let { Outcome.failure(DiagCode.VALUE_INVALID, it, FORMAT) } ?: Outcome.success(p)
        }

        private fun strings(f: JsonFields, name: String): List<String>? = f.array(name)?.items?.map { v ->
            (v as? JsonString)?.value ?: return null.also { f.diagnostics.error(DiagCode.FIELD_TYPE, "ожидалась строка", JsonPath.field(f.path, name)) }
        }
    }
}

/**
 * Задача, которую поставила политика (D-106): когда, откуда, какая и по
 * какой версии рамок. [task] `null` — команда ждёт в очереди контура
 * (`COMMAND_QUEUED`, D-94): id появится, когда контур её исполнит.
 */
data class AutoEntry(val at: Timestamp, val project: String, val suggestion: String, val task: TaskId?, val policy: String) {
    val key: String get() = EcosystemPlan.key(project, suggestion)
}

/**
 * Итог прохода: поставленные задачи (с id — о каждой человеку сообщают);
 * команды, ждущие очереди контура; предложения, которые политика не
 * пустила, с причиной (для экрана); отказы постановки.
 */
data class AutoPass(val placed: List<AutoEntry>, val queued: List<AutoEntry>, val refused: List<Pair<String, String>>, val failures: List<Diagnostic>) {
    companion object {
        val NONE = AutoPass(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * Задачи из экосистемы без подтверждения (D-106, ТЗ, §44): политика —
 * документ `dpai.auto-policy`, след — `dpai.ecosystem-auto` в [store].
 * Отбор — тот же [EcosystemPlan], что у окна и `dpai ecosystem`, плюс рамки
 * политики; задача ставится той же командой с ключом
 * `ecosystem:<проект>/<предложение>` (D-42) — одно предложение, одна задача.
 * Без подтверждения — только постановка: исполнение идёт по обычным
 * правилам контура (подтверждения по уровням, проверка, D-23).
 */
class AutoEcosystem(private val store: DocumentStore, private val clock: Clock) {
    /** Политика; нет её — `null`. Документ не читается — отказ: выключенной её считает [pass]. */
    @Synchronized
    fun policy(): Outcome<AutoPolicy?> = when (val r = store.read(POLICY_KEY)) {
        is Outcome.Failure -> r
        is Outcome.Success -> r.value?.let { AutoPolicy.read(it.content) } ?: Outcome.success(null)
    }

    /** Включить или заменить политику — только слово человека на экране (D-106). */
    @Synchronized
    fun enable(policy: AutoPolicy): Outcome<Unit> {
        policy.problem()?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it, AutoPolicy.FORMAT) }
        if (policy.expired(clock.now())) return Outcome.failure(DiagCode.VALUE_INVALID, "срок политики уже прошёл", AutoPolicy.FORMAT)
        return store.write(POLICY_KEY, policy.toJson()).map { }
    }

    @Synchronized
    fun disable(): Outcome<Unit> = store.delete(POLICY_KEY)

    /** Записи о задачах политики, старые — первыми. Документ не читается — отказ, а не пустой след. */
    @Synchronized
    fun entries(): Outcome<List<AutoEntry>> = when (val r = store.read(LEDGER_KEY)) {
        is Outcome.Failure -> r
        is Outcome.Success -> r.value?.let { readLedger(it.content) } ?: Outcome.success(emptyList())
    }

    /**
     * Проход: предложения [projects] с контуром в [connected] → рамки
     * политики → [place] каждой разрешённой задачи. [isOpen] — задача ещё
     * не кончилась (для предела открытых). [known] — итог команды с этим
     * ключом, если контур её уже исполнил (D-42): так ждавшая очереди
     * команда политики получает id, а задача, которую человек поставил сам
     * («Поставить задачу…», `dpai ecosystem run`), не записывается задачей
     * политики. Политики нет, срок вышел или след не читается — ни одной
     * задачи.
     */
    @Synchronized
    fun pass(
        projects: List<EcosystemProject>,
        connected: Set<String>,
        isOpen: (TaskId) -> Boolean,
        place: (Command.AddTask) -> Outcome<TaskId>,
        known: (String) -> Outcome<TaskId>? = { null },
    ): Outcome<AutoPass> {
        val policy = when (val p = policy()) {
            is Outcome.Failure -> return p
            is Outcome.Success -> p.value ?: return Outcome.success(AutoPass.NONE)
        }
        val now = clock.now()
        if (policy.expired(now)) return Outcome.success(AutoPass(emptyList(), emptyList(), listOf("политика" to "срок политики истёк"), emptyList()))
        val ledger = when (val e = entries()) {
            is Outcome.Failure -> return e
            is Outcome.Success -> e.value.toMutableList()
        }
        val placed = mutableListOf<AutoEntry>()
        val queued = mutableListOf<AutoEntry>()
        val refused = mutableListOf<Pair<String, String>>()
        val failures = mutableListOf<Diagnostic>()
        var changed = false
        // Команды, ждавшие очереди: исполнены — с id (и сообщение человеку), отказ — из следа вон.
        val cursor = ledger.listIterator()
        while (cursor.hasNext()) {
            val e = cursor.next()
            if (e.task != null) continue
            when (val k = known(e.key)) {
                null -> queued += e
                is Outcome.Success -> e.copy(task = k.value).also { filled -> cursor.set(filled); placed += filled; changed = true }
                is Outcome.Failure -> {
                    cursor.remove()
                    failures += k.errors.ifEmpty { k.diagnostics }
                    changed = true
                }
            }
        }
        val done = ledger.mapTo(HashSet()) { it.key }
        val dayAgo = Timestamp(now.epochMillis - DAY_MS)
        var recent = ledger.count { it.at > dayAgo }
        var open = ledger.count { e -> e.task?.let(isOpen) ?: true }
        for (c in EcosystemPlan.of(projects, connected, policy.projects, policy.operations)) {
            if (c.key in done) continue
            // Команду с этим ключом контур уже исполнил без политики — её поставил человек.
            if (known(c.key) != null) continue
            val why = c.refusal
                ?: EcosystemPlan.priority(c.suggestion)?.takeIf { it !in policy.priorities }?.let { "приоритет ${it.name} не разрешён политикой" }
                ?: "предел ${policy.maxPerDay} задач за 24 часа исчерпан".takeIf { recent >= policy.maxPerDay }
                ?: "предел ${policy.maxOpen} открытых задач политики".takeIf { open >= policy.maxOpen }
            if (why != null) {
                refused += c.key to why
                continue
            }
            val command = c.command() ?: continue
            val t = place(command)
            val waiting = t is Outcome.Failure && t.diagnostics.isNotEmpty() && t.diagnostics.all { it.code == DiagCode.COMMAND_QUEUED }
            if (t is Outcome.Failure && !waiting) {
                failures += t.errors.ifEmpty { t.diagnostics }
                continue
            }
            val e = AutoEntry(now, c.project.key, c.suggestion.key, t.valueOrNull(), policy.digest())
            ledger += e
            if (e.task != null) placed += e else queued += e
            done += e.key
            changed = true
            recent++
            open++
        }
        if (changed) {
            when (val w = store.write(LEDGER_KEY, ledgerJson(ledger.takeLast(KEEP)))) {
                is Outcome.Failure -> failures += w.errors
                is Outcome.Success -> Unit
            }
        }
        return Outcome.success(AutoPass(placed, queued, refused, failures))
    }

    companion object {
        const val POLICY_KEY = "auto-policy"
        const val LEDGER_KEY = "ecosystem-auto"
        const val LEDGER_FORMAT = "dpai.ecosystem-auto"
        const val LEDGER_VERSION = 1

        /** Сколько записей хранить: предел за 24 часа смотрит только на последние, повтор задачи держит ключ D-42. */
        const val KEEP = 500
        private const val DAY_MS = 24L * 60 * 60 * 1000

        internal fun ledgerJson(entries: List<AutoEntry>): JsonObject = JsonObject(
            linkedMapOf(
                *FormatHeader.write(LEDGER_FORMAT, LEDGER_VERSION),
                "entries" to JsonArray(
                    entries.map { e ->
                        JsonObject(
                            linkedMapOf<String, JsonValue>(
                                "at" to JsonNumber.of(e.at.epochMillis), "project" to JsonString(e.project), "suggestion" to JsonString(e.suggestion),
                                "policy" to JsonString(e.policy),
                            ).also { m -> e.task?.let { m["task"] = JsonString(it.value) } },
                        )
                    },
                ),
            ),
        )

        internal fun readLedger(o: JsonObject): Outcome<List<AutoEntry>> {
            val d = DiagnosticCollector()
            val f = JsonFields.root(o, d) ?: return Outcome.Failure(d.all)
            FormatHeader.read(f, LEDGER_FORMAT, LEDGER_VERSION..LEDGER_VERSION, d) ?: return Outcome.Failure(d.all)
            val items = f.array("entries")?.items.orEmpty()
            f.finish()
            val out = items.mapIndexedNotNull { i, v -> entry(v, JsonPath.index("$.entries", i), d) }
            return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(out)
        }

        private fun entry(v: JsonValue, path: String, d: DiagnosticCollector): AutoEntry? {
            val f = (v as? JsonObject)?.let { JsonFields(it, path, d) } ?: return null.also { d.error(DiagCode.FIELD_TYPE, "ожидался объект", path) }
            val at = f.long("at")
            val project = f.string("project")
            val suggestion = f.string("suggestion")
            val task = f.optString("task")?.let { t -> IdKinds.TASK.parse(t).valueOrNull() ?: null.also { d.error(DiagCode.ID_INVALID, "не id задачи", JsonPath.field(path, "task")) } }
            val policy = f.string("policy")
            f.finish()
            if (at == null || project == null || suggestion == null || policy == null) return null
            return AutoEntry(Timestamp(at), project, suggestion, task, policy)
        }
    }
}
