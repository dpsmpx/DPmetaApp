package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.Feed
import com.dpsmpx.dpmeta.metrics.FeedEvent
import com.dpsmpx.dpmeta.metrics.ProjectState
import com.dpsmpx.dpmeta.metrics.Rules
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Модели экранов пульта (ТЗ, §9; этап 3) — чистый Kotlin, без Android:
 * экран Compose только рисует их, снимки на JVM сверяют их текст. Всё
 * берётся из готового состояния (D-34) — экран ничего не считает заново.
 * Неизвестное показывается словом «неизвестно», а не нулём.
 */
object Screens {
    /**
     * Отказ на экране (ТЗ, §6.9, N-11; UX-13): причина — словом из словаря
     * ядра (`Words`, DP_AI_Orcestrator:D-89), код и сообщение — подробностью
     * («Подробнее»), а не заголовком.
     */
    data class Failure(val text: String, val details: String)

    fun failure(d: com.dpsmpx.dpai.core.diagnostics.Diagnostic): Failure =
        Failure(com.dpsmpx.dpai.core.text.Words.reason(d.code.name), "${d.code.name} — ${d.message}")

    /** Пояс показа по умолчанию (ТЗ, §6.3): +03:00. */
    val DEFAULT_ZONE: ZoneOffset = ZoneOffset.ofHours(3)

    /**
     * Часовой пояс показа (ТЗ, §6.3; UX-22) — один на приложение
     * ([com.dpsmpx.dpai.core.clock.DisplayZone]): его задаёт настройка «Ещё»,
     * «AI» показывает время в нём же.
     */
    val ZONE: java.time.ZoneId get() = com.dpsmpx.dpai.core.clock.DisplayZone.current
    private val TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    fun time(t: Timestamp): String = TIME.withZone(ZONE).format(Instant.ofEpochMilli(t.epochMillis))

    /** Пояс строкой: «UTC+03:00». */
    fun zoneName(minutes: Int): String = "UTC" + ZoneOffset.ofTotalSeconds(minutes * 60).id.let { if (it == "Z") "+00:00" else it }

    fun <T> show(m: Measure<T>, unit: String = ""): String = when (m) {
        is Measure.Known -> "${m.value}$unit"
        is Measure.Unknown -> UNKNOWN
    }

    const val UNKNOWN = "неизвестно"

    fun working(p: ProjectState, now: Timestamp): String = when (val w = p.working(now)) {
        is Measure.Known -> if (w.value) "работает" else "свободен"
        is Measure.Unknown -> UNKNOWN
    }

    private fun payload(p: ProjectState, kind: String): JsonObject? = p.sections[kind]?.payload as? JsonObject

    private fun objects(o: JsonObject?, key: String): List<JsonObject> = (o?.get(key) as? JsonArray)?.items.orEmpty().mapNotNull { it as? JsonObject }

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonString)?.value

    private fun long(o: JsonObject, k: String) = (o[k] as? JsonNumber)?.toLongExact()

    // --- Сводка (F-04) ---

    data class Summary(
        val asOf: String,
        val active: String,
        val pullsWaiting: String,
        val projects: Int,
        val sourceErrors: Int,
        /** Доля удачных источников последней синхронизации (ТЗ, §8, `app.sync.health`). */
        val syncHealth: String,
        /** Открытых PR во всех проектах (D-176). */
        val pullsOpen: String = UNKNOWN,
        /** Красный CI (D-176): голов основных веток и открытых PR. */
        val ciRed: String = UNKNOWN,
        /** Ждут меня (D-176, п. 4): запросы ИИ, ревью, назначенные issues. */
        val waiting: String = UNKNOWN,
    )

    fun summary(s: EcosystemState, me: String? = null, ai: AiCounts? = null): Summary = Summary(
        waiting = waitingCount(waiting(s, me, ai), me),
        asOf = time(s.asOf),
        active = show(s.activeProjects),
        // PR ждут автора (ТЗ, S-01, F-39; `repo.pr.waiting_author`): открыт и не черновик — как фильтр «Ждут автора» F-11.
        pullsWaiting = s.projects.map { it.waitingAuthor(s.asOf) }.let { ms ->
            if (ms.all { it is Measure.Unknown }) UNKNOWN else ms.sumOf { it.orNull()?.first ?: 0L }.toString()
        },
        projects = s.projects.size,
        sourceErrors = s.projects.sumOf { it.errors.size },
        syncHealth = s.syncHealth.orNull()?.let { "$it % источников" } ?: UNKNOWN,
        pullsOpen = s.projects.map { it.openPulls }.let { ms -> if (ms.all { it is Measure.Unknown }) UNKNOWN else ms.sumOf { it.orNull() ?: 0L }.toString() },
        ciRed = ciRed(s),
    )

    /** Красный CI во всех проектах (D-176): головы основных веток и открытые PR; CI нигде не известен — неизвестно. */
    private fun ciRed(s: EcosystemState): String {
        var known = false
        var red = 0
        for (p in s.projects) {
            (payload(p, "commits")?.get("ci") as? JsonObject)?.let { c ->
                known = true
                if (str(c, "verdict") == "red") red++
            }
            for (o in objects(payload(p, "pulls"), "pulls")) {
                val c = o["ci"] as? JsonObject ?: continue
                known = true
                if (str(c, "verdict") == "red") red++
            }
        }
        return if (known) red.toString() else UNKNOWN
    }

    // --- Левая панель приложения (D-112; прежде — нижняя панель D-73) ---

    /** Разделы пульта со своими экранами; графиков нет (D-112): «Статистика» убрана, сводка — числами. */
    enum class Section(val title: String) { SUMMARY("Сводка"), DEV("Проекты"), MORE("Настройки"), HELP("Справка") }

    /** Вкладки раздела «Проекты» — в верхней панели (D-113): проекты, PR (F-11), вопросы автору. */
    enum class DevTab(val title: String) { PROJECTS("Проекты"), PULLS("PR"), WAITING("Ждут меня") }

    /**
     * Пункт левой панели: раздел пульта или вход в окно «AI» ([section] `null`):
     * IDE — «AI» на вкладке «Файлы», AI — окно «AI» (D-53, D-112).
     */
    enum class Entry(val title: String, val section: Section?) {
        SUMMARY("Сводка", Section.SUMMARY),
        PROJECTS("Проекты", Section.DEV),
        IDE("IDE", null),
        AI(AI_TITLE, null),
        SETTINGS("Настройки", Section.MORE),
        HELP("Справка", Section.HELP),
    }

    /** Пункты левой панели по порядку; без подсистемы «AI» — без IDE и AI. */
    fun panelItems(ai: Boolean): List<Entry> = Entry.entries.filter { ai || it.section != null }

    /** Подписи панели — для снимка. */
    fun panel(ai: Boolean): List<String> = panelItems(ai).map { it.title }

    /** Подпись входа в подсистему «AI» на панели (D-53). */
    const val AI_TITLE = "AI"

    /** Куда ведёт касание плитки сводки (UX-6; ТЗ, F-04: «каждая плитка ведёт на свой экран»). */
    enum class Goes(val title: String) { PROJECTS("Проекты"), PULLS("PR"), WAITING("Ждут меня"), SYNC("Настройки") }

    /** Плитка сводки: [metric] — ряд истории для изменения и спарклайна (F-34), нет — без них. */
    data class Tile(val title: String, val value: String, val goes: Goes, val metric: String? = null)

    /**
     * Сводка — одной сеткой плиток (UX-6): «Работают N из M» вместо двух строк,
     * у каждой — свой экран. «PR ждут автора» (ТЗ, S-01) — к списку PR (F-11) на фильтре
     * «Ждут автора»: плитка считает те же PR (`repo.pr.waiting_author` — открыт и не черновик).
     */
    fun tiles(s: Summary): List<Tile> = listOf(
        Tile("Работают", if (s.active == UNKNOWN) UNKNOWN else "${s.active} из ${s.projects}", Goes.PROJECTS, "eco.active_projects"),
        Tile("Ждут меня", s.waiting, Goes.WAITING),
        Tile("PR ждут автора", s.pullsWaiting, Goes.PULLS),
        Tile("PR открыто", s.pullsOpen, Goes.PULLS),
        Tile("Красный CI", s.ciRed, Goes.PROJECTS),
        Tile("Ошибок источников", s.sourceErrors.toString(), Goes.PROJECTS),
        Tile("Синхронизация", s.syncHealth, Goes.SYNC, "app.sync.health"),
    )

    // --- Строка «AI» (D-76) ---

    /**
     * Числа «AI» для сводки: их передаёт оболочка `:shell` из состояния
     * контура — пульт об «AI» не знает (D-53). [configured] — `false`: демо
     * или не настроено.
     */
    data class AiCounts(val configured: Boolean, val waiting: Measure<Long>, val running: Measure<Long>, val nearLimit: Measure<Long>, val problems: Int = 0)

    /**
     * Работа студии по рабочему проекту (D-176) — от оболочки `:shell` из подсистемы «AI»: задачи,
     * запросы, идущие сессии, ходы и расход ИИ, ручные сборки и проверки за сутки. `null` — неизвестно.
     */
    data class StudioWork(
        val root: String,
        val tasksOpen: Long?,
        val requests: Long?,
        val running: Long?,
        val turns: Long?,
        val costUsd: java.math.BigDecimal?,
        val costEstimated: Boolean,
        val buildsOk: Long?,
        val buildsFailed: Long?,
        /** Что идёт сейчас вручную: «сборка», «проверка» или `null`. */
        val manualRunning: String? = null,
    )

    private fun n(v: Long?) = v?.toString() ?: UNKNOWN

    private fun usd(v: java.math.BigDecimal, estimated: Boolean) =
        "$" + v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + if (estimated) " (оценка)" else ""

    /**
     * Работа студии в карточке (D-176): `null` — подсистемы IDE нет; [w] `null` — проект ещё не
     * рабочий проект IDE (D-177: он станет им при открытии документа в IDE).
     */
    fun studioSection(w: StudioWork?, ide: Boolean): CardSection? {
        if (!ide) return null
        if (w == null) return CardSection(CardSection.Kind.STUDIO, "Студия: не рабочий проект IDE", listOf("Откройте документ в IDE — проект станет рабочим: сборка, проверки, задачи ИИ."))
        return CardSection(
            CardSection.Kind.STUDIO,
            "Студия: задач ${n(w.tasksOpen)} · ждут вас ${n(w.requests)} · сессий ИИ ${n(w.running)}",
            listOf(
                "Ходы ИИ: ${n(w.turns)}" + (w.costUsd?.let { " · расход ${usd(it, w.costEstimated)}" } ?: ""),
                "Сборки и проверки за сутки: успешно ${n(w.buildsOk)}, с ошибкой ${n(w.buildsFailed)}" + (w.manualRunning?.let { " · идёт $it" } ?: ""),
            ),
        )
    }

    /** Строка студии на сводке (D-176): сумма по рабочим проектам; неизвестное у всех — словом. */
    fun studioLine(list: List<StudioWork>): String {
        if (list.isEmpty()) return "Студия: рабочих проектов нет"
        fun sum(f: (StudioWork) -> Long?): String = list.mapNotNull(f).let { if (it.isEmpty()) UNKNOWN else it.sum().toString() }
        val cost = list.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
        return "Студия: задач ${sum { it.tasksOpen }} · ждут вас ${sum { it.requests }} · сборки за сутки ✓${sum { it.buildsOk }} ✗${sum { it.buildsFailed }}" +
            (cost?.let { " · расход ${usd(it, list.any { w -> w.costEstimated })}" } ?: "")
    }

    /**
     * Рабочий проект на устройстве или target (D-150): GitHub ему не нужен. Список
     * даёт оболочка `:shell`; [selected] — его показывают экраны «AI» и IDE.
     */
    data class LocalProject(val id: String, val name: String, val place: String, val root: String, val selected: Boolean)

    /**
     * Строка «AI» сводки; неизвестное — словом, не нулём. Проблемы проверки приложения
     * (D-123) — числом в конце, пока они есть: и когда «AI» ещё не настроен.
     */
    fun aiLine(c: AiCounts): String {
        val line = if (!c.configured) {
            "AI: не настроено"
        } else {
            "AI: ждут ответа ${show(c.waiting)} · в работе ${show(c.running)} · подписки у предела ${show(c.nearLimit)}"
        }
        return if (c.problems > 0) "$line · проблем: ${c.problems}" else line
    }

    /** Касание строки «AI» — на «Запросы», если ответа ждут (D-76). */
    fun aiToRequests(c: AiCounts): Boolean = c.configured && (c.waiting.orNull() ?: 0L) > 0L

    // --- Проекты по местам (F-02, F-06) ---

    data class ProjectRow(val name: String, val working: String, val pulls: String, val errors: Int)

    data class Group(val title: String, val rows: List<ProjectRow>)

    /** PR в строке проекта; остальные документы выбирает пользователь (D-176). */
    fun projectLine(r: ProjectRow): String = if (r.pulls == UNKNOWN) "данных пока нет" else "PR ${r.pulls}"

    /** Места старого снимка не фильтруют список пользователя и не меняют его порядок (D-176). */
    fun projects(s: EcosystemState): List<Group> {
        val rows = s.projects.map { p ->
            ProjectRow(
                name = p.name,
                working = working(p, s.asOf),
                pulls = show(p.openPulls),
                errors = p.errors.size,
            )
        }
        return if (rows.isEmpty()) emptyList() else listOf(Group("Проекты", rows))
    }

    // --- Карточка проекта (F-06…F-08, F-11, F-14, F-16) ---

    /**
     * Карточка на экране (UX-8): сверху — «Внимание» (ошибки источников,
     * красные проверки последнего и влитого PR), если
     * есть что сказать; ниже — разделы свёрнутыми строками с итогом.
     */
    data class CardView(val attention: List<String>, val sections: List<CardSection>)

    /** Раздел карточки: [title] — строка с итогом, [lines] — что под раскрытием; [kind] — что рисует экран. */
    data class CardSection(val kind: Kind, val title: String, val lines: List<String>) {
        enum class Kind { STUDIO, PULLS, BRANCHES, ISSUES, COMMITS, CHECKLISTS, ABOUT }
    }

    fun cardView(c: Card): CardView {
        val attention = buildList {
            if (c.errors.isNotEmpty()) add("Ошибки источников: ${c.errors.size}")
            c.lastPull?.takeIf { it.verdict == verdictTitle("red") }?.let { add("Проверки PR #${it.number} — красные") }
            c.lastMerged?.takeIf { it.verdict == verdictTitle("red") }?.let { add("Проверки влитого PR #${it.number} — красные") }
            // CI (D-176): красная голова основной ветки и красные PR — сверху.
            c.headCi?.takeIf { it.verdict == "red" }?.let { add("CI основной ветки — красный" + it.failingLine()) }
            for (p in c.pulls) p.ci?.takeIf { it.verdict == "red" }?.let { add("CI PR #${p.number} — красный" + it.failingLine()) }
        }
        val fresh = c.branches.count { it.recent }
        val pullVerdict = c.lastPull?.let { " · последние проверки: ${it.verdict}" }.orEmpty()
        return CardView(
            attention,
            listOf(
                CardSection(
                    CardSection.Kind.PULLS,
                    "PR ${c.pulls.size}$pullVerdict",
                    c.pulls.map { p -> "#${p.number} ${p.title}" + (if (p.draft) " (черновик)" else "") + " — ${p.head}, ${p.updated}" + (p.ci?.let { " · CI: ${it.word}" } ?: "") } +
                        "Проверки последнего PR: ${checkedLine(c.lastPull)}" + "Проверки последнего влитого: ${checkedLine(c.lastMerged)}",
                ),
                // «●» и «○» без легенды не читались (UX-8): свежесть — словом.
                CardSection(CardSection.Kind.BRANCHES, "Ветки ${c.branches.size}, свежих $fresh", c.branches.map { b -> "${b.name} — ${b.updated}" + if (b.recent) " · свежая" else "" }),
            ) + listOfNotNull(c.issues?.let { issuesSection(it, c.issuesAccess) }, c.commits?.let { commitsSection(it, c.headCi) }, c.checklists?.let(::checklistsSection)) + listOf(
                CardSection(CardSection.Kind.ABOUT, "О себе", c.panel.flatMap { listOf("${it.name}:") + it.text.lines().map { l -> "  $l" } }),
            ),
        )
    }

    /** CI коммита для показа (D-176): итог кодом, упавшие, видно ли токену. */
    data class Ci(val verdict: String, val total: Long, val failing: List<String>, val access: Boolean) {
        val word: String get() = when {
            !access -> "нет прав токена"
            verdict == "green" -> "зелёный"
            verdict == "red" -> "красный"
            verdict == "pending" -> "идёт"
            verdict == "none" -> "проверок нет"
            else -> UNKNOWN
        }

        fun failingLine(): String = if (failing.isEmpty()) "" else ": " + failing.joinToString(", ")
    }

    data class IssueRow(val number: Long, val title: String, val author: String, val updated: String, val labels: List<String>, val assignees: List<String>, val comments: Long)

    data class CommitRow(val sha: String, val title: String, val author: String?, val time: String)

    /** Открытые issues (D-176): число (из первых [com.dpsmpx.dpmeta.github.GitHub.ISSUES_KEEP] — «N+»), под раскрытием — по обновлению; [access] — видно ли токену. */
    fun issuesSection(issues: List<IssueRow>, access: Boolean = true): CardSection {
        if (!access) return CardSection(CardSection.Kind.ISSUES, "Issues: нет прав токена", emptyList())
        val count = if (issues.size >= com.dpsmpx.dpmeta.github.GitHub.ISSUES_KEEP) "${issues.size}+" else "${issues.size}"
        return CardSection(
            CardSection.Kind.ISSUES,
            "Issues: открытых $count",
            issues.map { i ->
                "#${i.number} ${i.title} — ${i.author}, ${i.updated}" +
                    (if (i.labels.isEmpty()) "" else " · " + i.labels.joinToString(", ")) +
                    (if (i.assignees.isEmpty()) "" else " · назначен: " + i.assignees.joinToString(", ")) +
                    (if (i.comments > 0) " · комментариев ${i.comments}" else "")
            },
        )
    }

    /** Последние коммиты основной ветки и CI её головы (D-176). */
    fun commitsSection(commits: List<CommitRow>, ci: Ci?): CardSection = CardSection(
        CardSection.Kind.COMMITS,
        "Коммиты: " + (commits.firstOrNull()?.let { "последний ${it.time}" } ?: "нет") + (ci?.let { " · CI: ${it.word}" } ?: ""),
        commits.map { c -> "${c.sha.take(7)} ${c.title} — ${c.author ?: UNKNOWN}, ${c.time}" } + listOfNotNull(ci?.takeIf { it.failing.isNotEmpty() }?.let { "Упало: " + it.failing.joinToString(", ") }),
    )

    private fun ci(o: JsonObject?): Ci? = o?.let {
        Ci(str(it, "verdict") ?: return@let null, long(it, "total") ?: 0, (it["failing"] as? JsonArray)?.items.orEmpty().mapNotNull { f -> (f as? JsonString)?.value }, (it["access"] as? JsonBool)?.value != false)
    }

    /**
     * Прогресс по чекбоксам (D-176): итог по найденным файлам — отмечено из всех и доля вниз до
     * целого; строки — файл с его счётом или «файла нет», под ним — первые открытые пункты.
     */
    fun checklistsSection(files: List<ProjectState.ChecklistFile>): CardSection {
        val found = files.filterNot { it.missing }
        val done = found.sumOf { it.done }
        val total = found.sumOf { it.total }
        val title = when {
            found.isEmpty() -> "Чек-листы: файлов нет"
            total == 0L -> "Чек-листы: чекбоксов нет"
            else -> "Чек-листы: $done из $total (${done * 100 / total} %)"
        }
        val lines = files.flatMap { f ->
            if (f.missing) listOf("${f.path} — файла нет") else listOf("${f.path} — ${f.done}/${f.total}") + f.open.map { (text, _) -> "  ☐ $text" }
        }
        return CardSection(CardSection.Kind.CHECKLISTS, title, lines)
    }

    /** Итог проверок PR строкой (F-15). */
    fun checkedLine(c: Checked?): String =
        c?.let { "#${it.number} — ${it.verdict}, ${it.age}" + (it.line?.let { l -> "; «$l»" } ?: "") } ?: UNKNOWN

    /** PR в карточке; [checks] — вердикт раздела «Проверки» описания (`green`, `red`, `null`, F-15) — для «Влить» (F-13). */
    data class Pull(val number: Long, val title: String, val draft: Boolean, val head: String, val updated: String, val checks: String? = null, val ci: Ci? = null)

    data class BranchRow(val name: String, val updated: String, val recent: Boolean)

    /** Итог проверок PR (F-15): номер, итог, первая строка раздела «Проверки», возраст. */
    data class Checked(val number: Long, val verdict: String, val line: String?, val age: String)

    /** Блок состояния README — «что проект пишет о себе» (F-10). */
    data class Panel(val name: String, val text: String)

    data class Card(
        val name: String,
        val working: String,
        val pulls: List<Pull>,
        val branches: List<BranchRow>,
        val lastPull: Checked?,
        val lastMerged: Checked?,
        val panel: List<Panel>,
        val files: List<Pair<String, String>>,
        val errors: List<String>,
        /** Чек-листы пользователя (D-176); `null` — не выбраны или ещё не прочитаны. */
        val checklists: List<ProjectState.ChecklistFile>? = null,
        /** Открытые issues (D-176); `null` — источник их не читал. */
        val issues: List<IssueRow>? = null,
        /** Видны ли issues токену (права Issues — по желанию). */
        val issuesAccess: Boolean = true,
        /** Последние коммиты основной ветки (D-176); `null` — источник их не читал. */
        val commits: List<CommitRow>? = null,
        /** CI головы основной ветки (D-176); `null` — неизвестно. */
        val headCi: Ci? = null,
    )

    fun card(s: EcosystemState, name: String): Card? {
        val p = s.project(name) ?: return null
        val pulls = objects(payload(p, "pulls"), "pulls").map { o ->
            Pull(long(o, "number") ?: 0, str(o, "title").orEmpty(), (o["draft"] as? JsonBool)?.value ?: false, str(o, "head").orEmpty(), long(o, "updatedAt")?.let { time(Timestamp(it)) }.orEmpty(), str(o, "checks"), ci(o["ci"] as? JsonObject))
        }
        val recentFrom = (s.asOf - Rules.BRANCH_OR_PR_WINDOW).epochMillis
        val branches = objects(payload(p, "branches"), "branches").map { o ->
            val date = long(o, "date")
            BranchRow(str(o, "name").orEmpty(), date?.let { time(Timestamp(it)) } ?: UNKNOWN, date != null && date >= recentFrom)
        }.sortedWith(compareByDescending<BranchRow> { it.recent }.thenBy { it.name })
        fun checked(o: JsonObject?, timeKey: String): Checked? = o?.let {
            val at = long(it, timeKey) ?: return@let null
            Checked(long(it, "number") ?: 0, verdictTitle(str(it, "checks")), str(it, "checksLine"), age(s.asOf, Timestamp(at)))
        }
        val openPulls = objects(payload(p, "pulls"), "pulls")
        val merged = objects(payload(p, "merged"), "pulls")
        return Card(
            name = p.name,
            working = working(p, s.asOf),
            pulls = pulls,
            branches = branches,
            lastPull = checked(openPulls.maxByOrNull { long(it, "updatedAt") ?: 0 }, "updatedAt"),
            lastMerged = checked(merged.maxByOrNull { long(it, "mergedAt") ?: 0 }, "mergedAt"),
            panel = objects(payload(p, "readme"), "blocks").map { Panel(str(it, "name").orEmpty(), str(it, "text").orEmpty()) },
            files = listOf("readme" to "README").mapNotNull { (kind, title) ->
                payload(p, kind)?.let { o -> str(o, "path")?.let { title to it } }
            },
            errors = p.errors.map { "${it.kind}: ${com.dpsmpx.dpai.core.text.Words.reason(it.code)} — ${it.message} (${it.code}, ${time(it.at)})" },
            checklists = p.checklists,
            issues = payload(p, "issues")?.let { pl ->
                objects(pl, "issues").map { o ->
                    fun names(k: String) = (o[k] as? JsonArray)?.items.orEmpty().mapNotNull { (it as? JsonString)?.value }
                    IssueRow(long(o, "number") ?: 0, str(o, "title").orEmpty(), str(o, "author") ?: UNKNOWN, long(o, "updatedAt")?.let { time(Timestamp(it)) } ?: UNKNOWN, names("labels"), names("assignees"), long(o, "comments") ?: 0)
                }
            },
            issuesAccess = (payload(p, "issues")?.get("access") as? JsonBool)?.value != false,
            commits = payload(p, "commits")?.let { pl ->
                objects(pl, "commits").map { o -> CommitRow(str(o, "sha").orEmpty(), str(o, "title").orEmpty(), str(o, "author"), long(o, "date")?.let { time(Timestamp(it)) } ?: UNKNOWN) }
            },
            headCi = ci(payload(p, "commits")?.get("ci") as? JsonObject),
        )
    }


    fun verdictTitle(code: String?): String = when (code) {
        "green" -> "зелёный"
        "red" -> "красный"
        else -> UNKNOWN
    }

    /** Возраст для показа: минуты, часы или дни от среза. */
    fun age(now: Timestamp, at: Timestamp): String {
        val minutes = (now.epochMillis - at.epochMillis) / 60_000
        return when {
            minutes < 0 -> "только что"
            minutes < 60 -> "$minutes мин назад"
            minutes < 48 * 60 -> "${minutes / 60} ч назад"
            else -> "${minutes / (24 * 60)} дн назад"
        }
    }

    // --- PR всех проектов (F-11) ---

    /**
     * Строка списка PR (F-11). [waitingMillis] — сколько ждёт с последнего обновления (как
     * `repo.pr.waiting_author`); [stale] — ждёт автора дольше [Rules.PR_WAITING_LIMIT].
     */
    data class PullRow(
        val project: String,
        val number: Long,
        val title: String,
        val draft: Boolean,
        val head: String,
        val author: String,
        val checks: String?,
        val waitingMillis: Long?,
        val stale: Boolean,
        /** Когда PR открыт (мс UTC); `null` — GitHub не сообщил. */
        val createdAt: Long? = null,
    )

    /** Фильтры списка (F-11): «ждут автора» — открыт и не черновик; «давние» — ждут дольше срока шлюза. */
    enum class PullFilter(val title: String) { ALL("Все"), WAITING("Ждут автора"), DRAFTS("Черновики"), STALE("Давние") }

    fun pulls(s: EcosystemState): List<PullRow> = s.projects.flatMap { p ->
        objects(payload(p, "pulls"), "pulls").map { o ->
            val draft = (o["draft"] as? JsonBool)?.value == true
            val since = long(o, "updatedAt") ?: long(o, "createdAt")
            val waiting = since?.let { s.asOf.epochMillis - it }
            PullRow(
                p.name, long(o, "number") ?: 0, str(o, "title").orEmpty(), draft, str(o, "head").orEmpty(), str(o, "author").orEmpty(),
                str(o, "checks"), waiting, !draft && waiting != null && waiting > Rules.PR_WAITING_LIMIT.inWholeMilliseconds,
                long(o, "createdAt"),
            )
        }
    }

    /**
     * Отбор и порядок (F-11): ждущие и давние — дольше ждущие сверху; остальные — свежие
     * сверху. [project] — только этот проект, `null` — все.
     */
    fun filterPulls(rows: List<PullRow>, filter: PullFilter, project: String?): List<PullRow> {
        val picked = rows.filter { project == null || it.project == project }.filter {
            when (filter) {
                PullFilter.ALL -> true
                PullFilter.WAITING -> !it.draft
                PullFilter.DRAFTS -> it.draft
                PullFilter.STALE -> it.stale
            }
        }
        return when (filter) {
            PullFilter.WAITING, PullFilter.STALE -> picked.sortedByDescending { it.waitingMillis ?: -1 }
            else -> picked.sortedBy { it.waitingMillis ?: Long.MAX_VALUE }
        }
    }

    /** Вторая строка PR в списке: проект, ветка, автор, сколько ждёт, проверки. */
    fun pullLine(r: PullRow, now: Timestamp): String = listOfNotNull(
        r.project,
        r.head.takeIf { it.isNotEmpty() },
        r.author.takeIf { it.isNotEmpty() },
        if (r.draft) "черновик" else null,
        r.waitingMillis?.let { "обновлён " + age(now, Timestamp(now.epochMillis - it)) },
        "проверки: " + verdictTitle(r.checks),
        if (r.stale) "ждёт автора дольше ${Rules.PR_WAITING_LIMIT.inWholeHours} ч" else null,
    ).joinToString(" · ")

    // --- Лента (F-05) ---

    data class FeedRow(val time: String, val project: String, val kind: String, val text: String)

    /** Лента с фильтрами (F-05): [project] и [kind] — null, если не выбраны. */
    fun feed(events: List<FeedEvent>, project: String? = null, kind: String? = null): List<FeedRow> =
        events.filter { (project == null || it.project == project) && (kind == null || it.kind == kind) }
            .map { FeedRow(time(it.at), it.project, Feed.KINDS[it.kind] ?: it.kind, it.text) }

    // --- Ждут меня (D-176) ---

    /**
     * Что ждёт пользователя (D-176, п. 4): запросы ИИ, PR с запрошенным у него ревью, назначенные
     * ему issues — по всем проектам. [pull] — номер PR (касание — экран PR), [issue] — номер issue
     * (касание — issue на GitHub), оба `null` — запросы ИИ (касание — «AI» на «Запросах»).
     */
    data class Waiting(val project: String, val title: String, val pull: Long? = null, val issue: Long? = null)

    /** Список «Ждут меня»; [me] `null` — логин ещё не известен: ревью и issues не отобрать. */
    fun waiting(s: EcosystemState?, me: String?, ai: AiCounts?): List<Waiting> = buildList {
        ai?.waiting?.orNull()?.takeIf { it > 0 }?.let { add(Waiting("", "Запросы ИИ ждут ответа: $it")) }
        if (s == null || me == null) return@buildList
        fun mine(o: JsonObject, key: String) = (o[key] as? JsonArray)?.items.orEmpty().any { (it as? JsonString)?.value.equals(me, ignoreCase = true) }
        for (p in s.projects) {
            for (o in objects(payload(p, "pulls"), "pulls")) {
                if (mine(o, "reviewers")) add(Waiting(p.name, "Ревью PR #${long(o, "number")}: ${str(o, "title").orEmpty()}", pull = long(o, "number")))
            }
            for (o in objects(payload(p, "issues"), "issues")) {
                if (mine(o, "assignees")) add(Waiting(p.name, "Issue #${long(o, "number")}: ${str(o, "title").orEmpty()}", issue = long(o, "number")))
            }
        }
    }

    /** Число для плитки «Ждут меня»: логин неизвестен и запросов ИИ нет — неизвестно, а не ноль. */
    fun waitingCount(list: List<Waiting>, me: String?): String = if (me == null && list.isEmpty()) UNKNOWN else list.size.toString()

}
