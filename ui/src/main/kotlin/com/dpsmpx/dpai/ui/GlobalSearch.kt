package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.contexts.ContextBundle
import com.dpsmpx.dpai.contexts.Handoff
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.StreamKind
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.git.WorktreesState
import com.dpsmpx.dpai.monitoring.Timeline
import com.dpsmpx.dpai.notifications.DeepLink
import com.dpsmpx.dpai.orchestrator.EventQuery
import com.dpsmpx.dpai.orchestrator.EventSearch
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.TasksState

/**
 * Глобальный поиск (ТЗ, §75): задачи, сессии, подписки и слоты аккаунтов,
 * проекты, ветки, коммиты, события, контексты, handoff и диагностика — по
 * подстроке без учёта регистра, проходом по состоянию контура в памяти.
 * Индекса нет: источник истины — журнал и проекции (D-24), а проход по
 * 30 тыс. задач укладывается в бюджет «Поиск» (`docs/PERFORMANCE.md`, §3;
 * `GlobalSearchTest`).
 *
 * Чего поиск не видит, сказано в [Result.notes], а не скрыто (D-05):
 * события — только активный сегмент журнала (D-37), контексты — последние
 * версии в памяти, отказы — последние [com.dpsmpx.dpai.orchestrator.Orchestrator.KEPT_PROBLEMS].
 * Содержимое источников контекста не ищется: в нём может быть то, что
 * показывать не следует (ТЗ, §65); найденный текст событий — через
 * [Redactor] (D-14).
 */
object GlobalSearch {
    enum class Kind(val label: String) {
        TASK("Задачи"),
        SESSION("Сессии"),
        SUBSCRIPTION("Подписки"),
        SLOT("Слоты"),
        PROJECT("Проекты"),
        BRANCH("Ветки"),
        COMMIT("Коммиты"),
        EVENT("События"),
        CONTEXT("Контексты"),
        HANDOFF("Handoff"),
        DIAGNOSTIC("Диагностика"),
    }

    /** Найденное: что показать и куда ведёт касание; [link] `null` — некуда. */
    data class Hit(val kind: Kind, val title: String, val detail: String, val link: DeepLink?, val at: Timestamp? = null)

    /** [more] — сколько ещё найдено сверх показанных [PER_KIND] по виду. */
    data class Result(val hits: List<Hit>, val more: Map<Kind, Int>, val notes: List<String>)

    /**
     * Что просматривается. Неизменяемые проекции читаются без замка; события,
     * контексты и отказы контур меняет в такте — `null`, если их не удалось
     * прочитать под замком такта.
     */
    data class Source(
        val tasks: TasksState,
        val sessions: SessionsState,
        val accounts: AccountsState,
        val worktrees: WorktreesState,
        val handoffs: List<Handoff>,
        val project: ProjectConfig? = null,
        val events: List<EventEnvelope>? = null,
        val eventsFrom: Long = 1,
        val contexts: List<ContextBundle>? = null,
        val problems: List<Diagnostic>? = null,
    )

    const val MIN_QUERY = 2
    const val PER_KIND = 20

    /** Вокруг найденного в тексте события — столько символов с каждой стороны. */
    private const val AROUND = 40

    private val TYPED_ID = Regex("""\b(?:tsk|ses|acc|sub|req)_[0-9A-Za-z]+\b""")

    fun search(source: Source, query: String): Result {
        val q = query.trim()
        if (q.length < MIN_QUERY) return Result(emptyList(), emptyMap(), listOf("запрос — от $MIN_QUERY символов"))
        val hits = ArrayList<Hit>()
        val more = LinkedHashMap<Kind, Int>()
        // Строчные и `contains`, как у `EventSearch`: `contains(ignoreCase = true)` сравнивает посимвольно с переводом
        // регистра каждого знака: на 30 тыс. задач и сессий — 85 мс против 15 (замер `GlobalSearchTest`, 18 октября 2026).
        val needle = q.lowercase()
        fun m(s: String?) = s != null && s.lowercase().contains(needle)
        fun add(kind: Kind, found: List<Hit>) {
            // Новые первыми; без времени — в порядке нахождения, после датированных.
            val sorted = found.sortedWith(compareByDescending<Hit> { it.at?.epochMillis ?: Long.MIN_VALUE })
            hits += sorted.take(PER_KIND)
            if (sorted.size > PER_KIND) more[kind] = sorted.size - PER_KIND
        }
        val tasks = source.tasks.tasks
        fun taskTitle(t: TaskId?) = t?.let { tasks[it]?.title ?: it.value } ?: "—"

        add(Kind.TASK, tasks.values.filter { t -> m(t.id.value) || m(t.title) || m(t.objective) || t.scope.orEmpty().any(::m) }
            .map { Hit(Kind.TASK, it.title, "${it.state.name} · ${it.id.value}" + if (it.archived) " · в архиве" else "", DeepLink.of(DeepLink.Kind.TASK, it.id), it.updatedAt) })

        add(Kind.SESSION, source.sessions.sessions.values.filter { s -> m(s.id.value) || m(s.provider.value) || m(s.configDir) || m(s.task.value) || m(s.target.value) }
            .map { Hit(Kind.SESSION, taskTitle(it.task), "${it.state.name} · ход ${it.turn} · ${it.id.value}", DeepLink.of(DeepLink.Kind.SESSION, it.id), it.updatedAt) })

        add(Kind.SUBSCRIPTION, source.accounts.subscriptions.values.filter { s -> m(s.id.value) || m(s.label) || m(s.plan) }
            .map { Hit(Kind.SUBSCRIPTION, it.label, listOfNotNull(it.plan, it.id.value).joinToString(" · "), DeepLink.of(DeepLink.Kind.SUBSCRIPTION, it.id)) })

        add(Kind.SLOT, source.accounts.slots.values.filter { s -> m(s.id.value) || m(s.configDir) || m(s.targetId.value) || m(s.subscriptionId.value) }
            .map { Hit(Kind.SLOT, it.configDir, "${it.state.name} · ${it.targetId.value} · ${it.id.value}", DeepLink.of(DeepLink.Kind.ACCOUNT, it.id)) })

        add(Kind.PROJECT, listOfNotNull(source.project).filter { p -> m(p.project.value) || m(p.repository?.root) || m(p.integrationBranch) || m(p.worktreeRoot) }
            .map { Hit(Kind.PROJECT, it.repository?.root ?: it.project.value, listOfNotNull(it.integrationBranch?.let { b -> "ветка интеграции $b" }, it.project.value).joinToString(" · "), DeepLink.of(DeepLink.Kind.PROJECT, it.project)) })

        add(Kind.BRANCH, branches(source, ::m, ::taskTitle))
        add(Kind.COMMIT, commits(source, ::m, ::taskTitle))

        source.events?.let { events ->
            // Номер события ищется точно, текст — в строках полезной нагрузки, как у истории (ТЗ, §42).
            val found = (events.filter { it.id.value.equals(q, ignoreCase = true) } + EventSearch.search(events, EventQuery(text = q, limit = 10_000))).distinctBy { it.id }
            add(Kind.EVENT, found.map { e -> Hit(Kind.EVENT, Timeline.describe(e) ?: e.type.name.lowercase(), eventDetail(e, q), link(e), e.occurredAt) })
        }

        source.contexts?.let { contexts ->
            add(Kind.CONTEXT, contexts.filter { c -> m(c.id.value) || m(c.objective) || m(c.nextAction) || m(c.hash.hex) || c.sources.any { m(it.key) } }
                .map { Hit(Kind.CONTEXT, "${taskTitle(it.task)} — контекст v${it.version}", "${it.sources.size} источников · ${it.id.value}", DeepLink.of(DeepLink.Kind.TASK, it.task), it.createdAt) })
        }

        add(Kind.HANDOFF, source.handoffs.filter { h ->
            m(h.id.value) || m(h.nextAction) || m(h.summary) || m(h.currentAction) || m(h.commit) || m(h.branch) || m(h.reason.name) ||
                (h.completed + h.failed + h.openProblems + h.warnings + h.modifiedFiles).any(::m)
        }.map { Hit(Kind.HANDOFF, it.nextAction, "${taskTitle(it.task)} · ${it.reason.name} · ${it.id.value}", DeepLink.of(DeepLink.Kind.TASK, it.task), it.createdAt) })

        add(Kind.DIAGNOSTIC, diagnostics(source, ::m, ::taskTitle))

        val notes = ArrayList<String>()
        if (source.events == null) {
            notes += "события, контексты и последние отказы не просмотрены: шёл такт контура — повторите поиск"
        } else if (source.eventsFrom > 1) {
            notes += "события — с №${source.eventsFrom}: старшие лежат в сегментах журнала на диске и в поиск не входят (D-37)"
        }
        return Result(hits, more, notes)
    }

    /** Ветки рабочих деревьев и из handoff — каждая один раз; ведут к задаче (ветка `task/<id>`) или к сессии-владельцу. */
    private fun branches(source: Source, m: (String?) -> Boolean, title: (TaskId?) -> String): List<Hit> {
        val seen = HashSet<String>()
        val out = ArrayList<Hit>()
        for (w in source.worktrees.worktrees.values) {
            if (!(m(w.branch) || m(w.path)) || !seen.add(w.branch)) continue
            val task = taskOfBranch(w.branch) ?: w.owner?.let { source.sessions.sessions[it]?.task }
            val link = task?.let { DeepLink.of(DeepLink.Kind.TASK, it) } ?: w.owner?.let { DeepLink.of(DeepLink.Kind.SESSION, it) }
            out += Hit(Kind.BRANCH, w.branch, listOfNotNull(task?.let(title), w.path, "удалено".takeIf { w.removed }).joinToString(" · "), link)
        }
        for (h in source.handoffs) {
            val b = h.branch ?: continue
            if (m(b) && seen.add(b)) out += Hit(Kind.BRANCH, b, title(h.task), DeepLink.of(DeepLink.Kind.TASK, h.task), h.createdAt)
        }
        return out
    }

    /** Коммиты — из handoff и из событий с полем `commit` (проверка, слияние); каждый один раз. */
    private fun commits(source: Source, m: (String?) -> Boolean, title: (TaskId?) -> String): List<Hit> {
        val seen = HashSet<String>()
        val out = ArrayList<Hit>()
        for (h in source.handoffs) {
            val c = h.commit ?: continue
            if (m(c) && seen.add(c)) out += Hit(Kind.COMMIT, c.take(12), title(h.task) + (h.branch?.let { " · $it" } ?: ""), DeepLink.of(DeepLink.Kind.TASK, h.task), h.createdAt)
        }
        for (e in source.events.orEmpty()) {
            val c = (e.payload["commit"] as? JsonString)?.value ?: continue
            if (m(c) && seen.add(c)) {
                val task = if (e.stream.kind == StreamKind.TASK) IdKinds.TASK.parse(e.stream.id).valueOrNull() else null
                out += Hit(Kind.COMMIT, c.take(12), task?.let(title) ?: e.stream.toString(), link(e), e.occurredAt)
            }
        }
        return out
    }

    /** Последние отказы контура и причины задач и сессий — код, текст, предмет. */
    private fun diagnostics(source: Source, m: (String?) -> Boolean, title: (TaskId?) -> String): List<Hit> {
        val out = ArrayList<Hit>()
        for (d in source.problems.orEmpty().asReversed()) {
            if (m(d.code.name) || m(d.message) || m(d.subject)) out += Hit(Kind.DIAGNOSTIC, d.code.name, Redactor.STANDARD.redact(listOfNotNull(d.message, d.subject).joinToString(" · ")), subjectLink(d.subject))
        }
        for (t in source.tasks.tasks.values) {
            val r = t.lastReason ?: continue
            if (m(r.name)) out += Hit(Kind.DIAGNOSTIC, r.name, "причина задачи · ${t.title}", DeepLink.of(DeepLink.Kind.TASK, t.id), t.updatedAt)
        }
        for (s in source.sessions.sessions.values) {
            val r = s.lastReason ?: continue
            if (m(r.name)) out += Hit(Kind.DIAGNOSTIC, r.name, "причина сессии · ${title(s.task)}", DeepLink.of(DeepLink.Kind.SESSION, s.id), s.updatedAt)
        }
        return out
    }

    /** Ветка дерева задачи — `task/<id задачи>` (`Orchestrator`, деревья узнаются по ней после перезапуска). */
    private fun taskOfBranch(branch: String): TaskId? =
        branch.removePrefix("task/").takeIf { it != branch }?.let { IdKinds.TASK.parse(it).valueOrNull() }

    /** Первая ссылка на сущность из предмета диагностики («task tsk_…»). */
    private fun subjectLink(subject: String?): DeepLink? =
        subject?.let { TYPED_ID.findAll(it) }?.firstNotNullOfOrNull { m -> kinds.firstNotNullOfOrNull { k -> DeepLink.parse("dpai://${k.path}/${m.value}").valueOrNull() } }

    private val kinds = listOf(DeepLink.Kind.TASK, DeepLink.Kind.SESSION, DeepLink.Kind.ACCOUNT, DeepLink.Kind.SUBSCRIPTION, DeepLink.Kind.REQUEST)

    /** Событие ведёт к сущности своего потока; у системных — к самому событию. */
    private fun link(e: EventEnvelope): DeepLink {
        val kind = when (e.stream.kind) {
            StreamKind.TASK -> DeepLink.Kind.TASK
            StreamKind.SESSION -> DeepLink.Kind.SESSION
            StreamKind.ACCOUNT -> DeepLink.Kind.ACCOUNT
            StreamKind.SUBSCRIPTION -> DeepLink.Kind.SUBSCRIPTION
            StreamKind.REQUEST -> DeepLink.Kind.REQUEST
            else -> null
        }
        return kind?.let { DeepLink.parse("dpai://${it.path}/${e.stream.id}").valueOrNull() } ?: DeepLink.of(DeepLink.Kind.EVENT, e.id)
    }

    /** Номер, поток и кусок текста вокруг найденного — через [Redactor] (D-14). */
    private fun eventDetail(e: EventEnvelope, q: String): String {
        val text = firstMatch(e.payload, q)?.let { s ->
            val i = s.indexOf(q, ignoreCase = true)
            val from = (i - AROUND).coerceAtLeast(0)
            val to = (i + q.length + AROUND).coerceAtMost(s.length)
            (if (from > 0) "…" else "") + s.substring(from, to).replace('\n', ' ') + (if (to < s.length) "…" else "")
        }
        return listOfNotNull("№${e.sequence}", e.stream.toString(), text?.let(Redactor.STANDARD::redact)).joinToString(" · ")
    }

    private fun firstMatch(v: JsonValue, q: String): String? = when (v) {
        is JsonString -> v.value.takeIf { it.contains(q, ignoreCase = true) }
        is JsonObject -> v.fields.values.firstNotNullOfOrNull { firstMatch(it, q) }
        is JsonArray -> v.items.firstNotNullOfOrNull { firstMatch(it, q) }
        else -> null
    }
}
