package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.SlotState
import com.dpsmpx.dpai.accounts.SubscriptionState
import com.dpsmpx.dpai.contexts.ContextBundle
import com.dpsmpx.dpai.contexts.Handoff
import com.dpsmpx.dpai.contexts.HandoffReason
import com.dpsmpx.dpai.contexts.IncludedSource
import com.dpsmpx.dpai.contexts.SourceLevel
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.git.Repository
import com.dpsmpx.dpai.git.Worktree
import com.dpsmpx.dpai.git.WorktreesState
import com.dpsmpx.dpai.notifications.DeepLink
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.security.Origin
import com.dpsmpx.dpai.security.Sensitivity
import com.dpsmpx.dpai.sessions.SessionRecord
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.tasks.TaskRecord
import com.dpsmpx.dpai.tasks.TasksState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Глобальный поиск (ТЗ, §75): по каждому виду — найдено и ведёт к сущности; чего не видно — сказано. */
class GlobalSearchTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(75))

    private val task = TaskRecord(ids.next(IdKinds.TASK), "Парсер выражений", "разобрать числа с экспонентой", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now(), state = TaskState.RUNNING, scope = setOf("core/Lexer.kt"))
    private val sub = SubscriptionState(ids.next(IdKinds.SUBSCRIPTION), "основная", 4, false, plan = "max")
    private val slot = SlotState(ids.next(IdKinds.ACCOUNT), sub.id, ids.next(IdKinds.TARGET), "/home/u/.claude-work")
    private val session = SessionRecord(
        ids.next(IdKinds.SESSION), "k", task.id, slot.id, sub.id, slot.targetId, slot.configDir,
        ProviderSessionId.parse("0b9d1c2e-1111-4222-8333-444455556666").valueOrNull()!!, null, createdAt = clock.now(), updatedAt = clock.now(), lastReason = DiagCode.NETWORK_UNREACHABLE,
    )
    private val handoff = Handoff(
        ids.next(IdKinds.HANDOFF), "h", HandoffReason.NEAR_LIMIT, session.id, task.id, ids.next(IdKinds.PROJECT), ids.next(IdKinds.CONTEXT), Hashing.sha256("c".toByteArray()),
        "наполовину", completed = listOf("лексер"), verified = emptyList(), failed = emptyList(), warnings = emptyList(), openProblems = listOf("числа с экспонентой"),
        currentAction = null, nextAction = "дописать разбор экспоненты", modifiedFiles = listOf("core/Lexer.kt"),
        commit = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678", branch = "feature/lexer", workspace = null, createdAt = clock.now(),
    )
    private val worktree = Worktree(ids.next(IdKinds.WORKTREE), ids.next(IdKinds.REPOSITORY), "/w/task-tree", "task/${task.id.value}", owner = session.id)

    private fun event(type: EventType, stream: StreamRef, n: Long, vararg fields: Pair<String, String>) =
        EventEnvelope(ids.next(IdKinds.EVENT), type, stream, n, clock.now(), clock.now(), payload = JsonObject(fields.associate { (k, v) -> k to JsonString(v) }))

    private val events = listOf(
        event(EventType.SESSION_OUTPUT, StreamRef.of(session.id), 1, "text" to "ошибка компиляции: unresolved reference Lexer, token=sk-ant-oat01-${"x".repeat(40)}"),
        event(EventType.TASK_VERIFICATION_RECORDED, StreamRef.of(task.id), 1, "verdict" to "VERIFIED", "commit" to "ffeeddccbbaa99887766554433221100ffeeddcc"),
        event(EventType.SESSION_HEARTBEAT, StreamRef.SYSTEM, 1, "text" to "системная строка"),
    )

    private fun source(events: List<EventEnvelope>? = this.events, from: Long = 1) = GlobalSearch.Source(
        TasksState(mapOf(task.id to task)),
        SessionsState(mapOf(session.id to session)),
        AccountsState(subscriptions = mapOf(sub.id to sub), slots = mapOf(slot.id to slot)),
        WorktreesState(worktrees = mapOf(worktree.id to worktree)),
        listOf(handoff),
        events = events,
        eventsFrom = from,
        contexts = emptyList(),
        problems = listOf(Diagnostic.error(DiagCode.SSH_AUTH_FAILED, "вход по ключу отклонён", "session ${session.id.value}")),
        commits = mapOf(task.id to com.dpsmpx.dpai.orchestrator.TaskCommit("9a8b7c6d5e4f30211203f4e5d6c7b8a990817263", "task/${task.id.value}", clock.now())),
    )

    private fun find(q: String, s: GlobalSearch.Source = source()) = GlobalSearch.search(s, q)

    private fun kinds(q: String) = find(q).hits.map { it.kind }.toSet()

    /** Итог для `dpai` (`POST /v1/search`): вид, заголовок, ссылка `dpai://…`, сколько ещё и пометки. */
    @Test
    fun resultAsJsonForDpai() {
        val r = find("экспонент")
        assertTrue(r.hits.isNotEmpty())
        val j = GlobalSearch.toJson(r)
        val hits = (j["hits"] as com.dpsmpx.dpai.core.serialization.JsonArray).items.map { it as JsonObject }
        assertEquals(r.hits.size, hits.size)
        assertEquals(r.hits.map { it.kind.name }, hits.map { (it["kind"] as JsonString).value })
        assertEquals(r.hits.mapNotNull { it.link?.toUri() }, hits.mapNotNull { (it["link"] as? JsonString)?.value })
        assertEquals(r.notes, (j["notes"] as com.dpsmpx.dpai.core.serialization.JsonArray).items.map { (it as JsonString).value })
    }

    @Test
    fun everyKindIsFoundAndLeadsToItsEntity() {
        val byTitle = find("экспонент").hits
        assertEquals(setOf(GlobalSearch.Kind.TASK, GlobalSearch.Kind.HANDOFF), byTitle.map { it.kind }.toSet(), "цель задачи и handoff")
        assertTrue(byTitle.all { it.link == DeepLink.of(DeepLink.Kind.TASK, task.id) })

        val bySession = find("0b9d1c2e").hits.single()
        assertEquals(GlobalSearch.Kind.SESSION, bySession.kind)
        assertEquals(DeepLink.of(DeepLink.Kind.SESSION, session.id), bySession.link)
        assertEquals("Парсер выражений", bySession.title, "сессия названа задачей")

        assertEquals(setOf(GlobalSearch.Kind.SUBSCRIPTION), kinds("основная"))
        assertEquals(DeepLink.of(DeepLink.Kind.ACCOUNT, slot.id), find("claude-work").hits.single { it.kind == GlobalSearch.Kind.SLOT }.link)

        // Ветка дерева задачи ведёт к задаче; ветка из handoff — тоже.
        assertEquals(DeepLink.of(DeepLink.Kind.TASK, task.id), find("task/").hits.single { it.kind == GlobalSearch.Kind.BRANCH }.link)
        assertEquals(listOf("feature/lexer"), find("feature/").hits.filter { it.kind == GlobalSearch.Kind.BRANCH }.map { it.title })

        // Коммиты — контура по задачам (`GIT_COMMIT_CREATED`), из handoff и из событий с полем commit.
        val own = find("9a8b7c").hits.single { it.kind == GlobalSearch.Kind.COMMIT }
        assertEquals("9a8b7c6d5e4f", own.title)
        assertEquals(DeepLink.of(DeepLink.Kind.TASK, task.id), own.link)
        assertEquals("a1b2c3d4e5f6", find("a1b2c3").hits.single { it.kind == GlobalSearch.Kind.COMMIT }.title)
        assertEquals(DeepLink.of(DeepLink.Kind.TASK, task.id), find("ffeedd").hits.single { it.kind == GlobalSearch.Kind.COMMIT }.link)

        // Диагностика: отказ контура со ссылкой из предмета и причина сессии.
        val auth = find("SSH_AUTH").hits.single()
        assertEquals(DeepLink.of(DeepLink.Kind.SESSION, session.id), auth.link)
        assertEquals(DeepLink.of(DeepLink.Kind.SESSION, session.id), find("NETWORK_UNREACHABLE").hits.single().link)
    }

    @Test
    fun eventsShowTextAroundTheMatchRedacted() {
        val hit = find("unresolved").hits.single { it.kind == GlobalSearch.Kind.EVENT }
        assertEquals(DeepLink.of(DeepLink.Kind.SESSION, session.id), hit.link, "событие ведёт к своей сессии")
        assertTrue("unresolved reference" in hit.detail, hit.detail)
        assertTrue("sk-ant-oat01" !in hit.detail, "секрет в найденном тексте скрыт (D-14): ${hit.detail}")
        // Секрет на краю вырезанного куска: скрывается до обрезки, иначе Redactor его бы не узнал.
        val edge = event(EventType.SESSION_OUTPUT, StreamRef.of(session.id), 2, "text" to "export password=Hunter2Secret!! and more filler xx unresolved reference here")
        val cut = GlobalSearch.search(source(events = listOf(edge)), "unresolved").hits.single { it.kind == GlobalSearch.Kind.EVENT }
        assertTrue("Hunter2Secret" !in cut.detail, cut.detail)
        // Событие системного потока — ссылкой на само событие.
        val system = find("системная").hits.single()
        assertEquals(DeepLink.Kind.EVENT, system.link?.kind)
        // Номер события ищется точно.
        assertEquals(events[2].id.value, find(events[2].id.value).hits.single().link?.id)
    }

    @Test
    fun whatIsNotSearchedIsSaid() {
        assertEquals(emptyList(), find("x").hits)
        assertTrue(find("x").notes.single().contains("от 2"))
        assertTrue(find("unresolved", source(events = null)).let { r -> r.hits.none { it.kind == GlobalSearch.Kind.EVENT } && r.notes.single().contains("шёл такт") })
        assertTrue(find("unresolved", source(from = 20_001)).notes.any { it.contains("записи журнала №20001") })
        assertEquals(listOf("контексты и handoff — только задач в памяти контура, отказы — последние 200"), find("unresolved").notes)
        // Похожее на хеш без найденного коммита — сказано, какие коммиты ищутся (коммиты Claude в дереве — нет).
        assertTrue(find("deadbeef").notes.any { it.contains("коммит не найден") })
        assertTrue(find("a1b2c3").notes.none { it.contains("коммит не найден") }, "коммит из handoff найден — пометки нет")
    }

    @Test
    fun projectAndContextAreFoundButSourceContentIsNot() {
        val project = ProjectConfig(ids.next(IdKinds.PROJECT), Repository(ids.next(IdKinds.REPOSITORY), slot.targetId, "/srv/repo-parser"), "/w", null, integrationBranch = "main")
        val content = "уникальная-строка-содержимого"
        val context = ContextBundle(
            ids.next(IdKinds.CONTEXT), 1, task.id, project.project, "цель", "дальше",
            listOf(IncludedSource("doc:ARCH.md", SourceLevel.DOCUMENT, Sensitivity.INTERNAL, Origin.REPOSITORY, Hashing.sha256(content.toByteArray()), content)), null, clock.now(),
        )
        val s = source().copy(project = project, contexts = listOf(context))
        assertEquals(DeepLink.of(DeepLink.Kind.PROJECT, project.project), GlobalSearch.search(s, "repo-parser").hits.single().link)
        assertEquals(DeepLink.of(DeepLink.Kind.TASK, task.id), GlobalSearch.search(s, "ARCH.md").hits.single { it.kind == GlobalSearch.Kind.CONTEXT }.link)
        assertEquals(emptyList(), GlobalSearch.search(s, "уникальная-строка").hits, "содержимое источников не ищется (ТЗ, §65)")
    }

    @Test
    fun manyHitsAreCappedPerKindNewestFirst() {
        val many = (1..45).associate { n ->
            clock.advance(1.minutes)
            val t = task.copy(id = ids.next(IdKinds.TASK), title = "сборка $n", updatedAt = clock.now())
            t.id to t
        }
        val r = GlobalSearch.search(source().copy(tasks = TasksState(many)), "сборка")
        assertEquals(GlobalSearch.PER_KIND, r.hits.size)
        assertEquals("сборка 45", r.hits.first().title)
        assertEquals(mapOf(GlobalSearch.Kind.TASK to 45 - GlobalSearch.PER_KIND), r.more)
    }

    /**
     * Бюджет «Поиск» (`docs/PERFORMANCE.md`, §3 — ≤ 200 мс): 30 тыс. задач и
     * сессий и полный активный сегмент журнала (20 тыс. событий, D-37) — без
     * индекса. Лучший из пяти прогонов: шум хоста не делает тест случайным.
     */
    @Test
    fun searchStaysWithinBudgetWithoutIndex() {
        val tasks = HashMap<com.dpsmpx.dpai.core.ids.TaskId, TaskRecord>()
        val sessions = HashMap<com.dpsmpx.dpai.core.ids.SessionId, SessionRecord>()
        repeat(30_000) { n ->
            val t = task.copy(id = ids.next(IdKinds.TASK), title = "задача $n", objective = "цель номер $n")
            tasks[t.id] = t
            val s = session.copy(id = ids.next(IdKinds.SESSION), task = t.id)
            sessions[s.id] = s
        }
        val stream = StreamRef.of(session.id)
        val log = (1L..20_000L).map { n -> event(EventType.SESSION_OUTPUT, stream, n, "text" to "строка вывода $n") }
        val src = source(events = log).copy(tasks = TasksState(tasks), sessions = SessionsState(sessions))
        repeat(2) { GlobalSearch.search(src, "редкое") }
        val best = (1..5).minOf {
            val start = com.dpsmpx.dpai.core.clock.Clock.SYSTEM.monotonicNanos()
            GlobalSearch.search(src, "редкое")
            (com.dpsmpx.dpai.core.clock.Clock.SYSTEM.monotonicNanos() - start) / 1_000_000
        }
        assertTrue(best <= 200, "поиск $best мс > 200")
    }
}
