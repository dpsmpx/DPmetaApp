package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.operation.OperationStatus
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.notifications.NotificationDecision
import com.dpsmpx.dpai.notifications.RequestAction
import com.dpsmpx.dpai.notifications.RequestKind
import com.dpsmpx.dpai.notifications.RequestState
import com.dpsmpx.dpai.notifications.UserRequest
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.snapshot.SnapshotStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.verification.Verifier
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.io.TempDir

/**
 * Запросы человеку в контуре (D-44): задача упёрлась — запрос в журнале и
 * уведомлением; ответ меняет состояние оркестратора, а не только закрывает
 * окно; повтор нажатия второго действия не делает; запрос переживает
 * перезапуск; срок, подмена хеша и одновременные запросы разных задач.
 */
class UserRequestsFlowTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(144))
    private val host = MockAgentHost(clock, IdGenerator(clock, RandomSource.seeded(145)))
    private val targets = List(2) { TargetSlot(ids.next(IdKinds.TARGET), emptySet(), maxSessions = 2) }
    private val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
    private val accounts: AccountsState = run {
        val logic = Accounts()
        var acc = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        acc = Accounts.applyAll(acc, logic.registerSubscription(acc, sub, "основная", clock.now(), maxParallelSessions = 8).valueOrNull()!!)
        for ((i, t) in targets.withIndex()) {
            val slot = ids.next(IdKinds.ACCOUNT)
            acc = Accounts.applyAll(acc, logic.registerSlot(acc, slot, sub, t.id, "/c/$i", clock.now()).valueOrNull()!!)
            acc = Accounts.applyAll(acc, logic.observeAuthStatus(acc, slot, status, clock.now()).valueOrNull()!!)
        }
        acc
    }
    private val project = ids.next(IdKinds.PROJECT)

    /** Сценарий хода по номеру запуска хода в тесте (с 1); по умолчанию — успех. */
    private var scenario: (Int) -> MockScenario = { MockScenario.SUCCESS }
    private var launches = 0

    /** Следующий запуск хода — отказ входа, дальше — успех. */
    private fun authFailsNextLaunch() {
        val k = launches + 1
        scenario = { n -> if (n == k) MockScenario.AUTH_ERROR else MockScenario.SUCCESS }
    }

    private lateinit var journal: EventJournal

    /** Что контур отдал журналу приложения (D-43). */
    private val logged = mutableListOf<EventType>()

    private fun boot(): Pair<Orchestrator, MockRunner> {
        val vfs = NioVfs(dir)
        journal = (EventJournal.open(vfs, "journal.log", segmentSize = 50) as Outcome.Success).value
        val runner = MockRunner.create(clock, RandomSource.seeded(clock.now().epochMillis), host) { _, _ -> scenario(++launches) }
        // Без профиля проверки: «готово» хода — честно COMPLETION_UNVERIFIED (D-23).
        val o = Orchestrator(
            clock, ids, RandomSource.seeded(146), journal, runner, ProjectConfig(project, null, dir.toString(), null), targets, null,
            Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), accounts,
            snapshots = SnapshotStore(DocumentStore(vfs, "snapshots")), snapshotEvery = 25, documents = DocumentStore(vfs, "docs"),
            requestTtl = 2.hours, onEvents = { es -> es.mapTo(logged) { it.type } },
        )
        o.recover { AgentClient(host, listOf("dpai"), null, "target mock") }
        return o to runner
    }

    private fun run(o: Orchestrator, runner: MockRunner, ticks: Int = 6) = repeat(ticks) {
        o.tick()
        for (s in host.runIds()) while (runner.advance(s)) continue
        o.tick()
        assertEquals(emptyList(), o.invariants())
        clock.advance(1.minutes)
    }

    /** Вход снова действует: слоты, которые ход объявил без входа, — снова доступны. */
    private fun relogin(o: Orchestrator) {
        val logic = Accounts()
        for (slot in o.accounts.slots.keys) o.commitAccounts(logic.observeAuthStatus(o.accounts, slot, status, clock.now()).valueOrNull().orEmpty())
    }

    private fun requestFor(o: Orchestrator, task: TaskId): UserRequest = assertNotNull(o.pendingRequests().singleOrNull { it.task == task }, o.pendingRequests().toString())

    private fun uri(r: UserRequest, option: String) = RequestAction(r.id, option, r.hash).toUri()

    private fun journalTypes() = journal.records(journal.activeFrom).map { it.event.type }

    @Test
    fun blockedTaskAsksAndAnswerRetriesOnce() {
        authFailsNextLaunch()
        val (o, runner) = boot()
        val task = o.addTask("починить сборку", "цель").valueOrNull()!!
        run(o, runner, 2)
        assertEquals(TaskState.BLOCKED, o.tasks.tasks.getValue(task).state)
        val r = requestFor(o, task)
        assertEquals(RequestKind.USER_RESOLUTION, r.kind)
        assertEquals("task.blocked", r.purpose)
        assertTrue(o.posted.any { it is NotificationDecision.Post && it.notification.request == r.id }, "запрос показан уведомлением")
        assertEquals(listOf("retry", "reply", "cancel"), r.options.map { it.id })
        // Причина — словами, без кода (D-89; аудит 1 ноября 2026, A-1): по запросу человек решает.
        assertTrue(DiagCode.entries.none { it.name in r.body }, "в запросе нет кода причины: ${r.body}")
        // И в чате сессии — «ход сорвался — <причина словами>», а не «ход прерван: failed · КОД».
        val turnEvents = o.chat(task).filter { it.role == com.dpsmpx.dpai.orchestrator.ChatRole.EVENT && it.text.startsWith("ход ") }
        assertTrue(turnEvents.isNotEmpty() && turnEvents.none { e -> DiagCode.entries.any { it.name in e.text } || "failed" in e.text }, turnEvents.toString())

        assertTrue(o.delivered(r.id, "notification").isSuccess)
        relogin(o)
        val first = o.answer(uri(r, "reply"), "используй gradle 9", "автор", "notification")
        assertEquals(OperationStatus.SUCCEEDED, first.status, first.diagnostics.toString())
        assertEquals(TaskState.READY, o.tasks.tasks.getValue(task).state, "ответ меняет состояние задачи")
        assertEquals(RequestState.ANSWERED, o.requests.requests.getValue(r.id).state)
        assertEquals("notification", o.requests.requests.getValue(r.id).answer?.channel, "канал ответа записан")

        // Повтор нажатия: тот же ответ, второго действия нет.
        val events = journal.position
        val again = o.answer(uri(r, "reply"), "используй gradle 9", "автор")
        assertEquals(OperationStatus.SUCCEEDED, again.status)
        assertEquals(first.value, again.value)
        assertEquals(events, journal.position, "повтор ничего не пишет")
        // Другой вариант после ответа — отказ.
        assertEquals(DiagCode.REQUEST_NOT_PENDING, o.answer(uri(r, "cancel"), null, "автор").diagnostics.single().code)

        run(o, runner, 4)
        // Нет профиля проверки — задача ждёт решения о непроверенном завершении.
        assertEquals(TaskState.COMPLETION_UNVERIFIED, o.tasks.tasks.getValue(task).state)
        assertTrue(o.lastContext(task)!!.sources.any { "используй gradle 9" in it.content }, "ответ человека — в контексте следующего хода")
        assertTrue(EventType.USER_REQUEST_ANSWERED in journalTypes())
    }

    @Test
    fun unverifiedWaiverAndPersistenceAcrossRestart() {
        val (first, runner) = boot()
        val task = first.addTask("задача", "цель").valueOrNull()!!
        run(first, runner, 3)
        assertEquals(TaskState.COMPLETION_UNVERIFIED, first.tasks.tasks.getValue(task).state)
        val r = requestFor(first, task)
        assertEquals("task.unverified", r.purpose)
        assertTrue(r.destructive("waive"), "принять без проверки — опасный вариант")

        // Перезапуск: запрос ждёт с тем же хешем — кнопка показанного уведомления действует.
        val (second, _) = boot()
        assertEquals(r.copy(), second.requests.requests.getValue(r.id).copy(), "запрос пережил перезапуск")
        val answer = second.answer(uri(r, "waive"), null, "автор")
        assertEquals(OperationStatus.SUCCEEDED, answer.status, answer.diagnostics.toString())
        assertEquals(TaskState.DONE, second.tasks.tasks.getValue(task).state)
        assertEquals("WAIVED", second.tasks.tasks.getValue(task).verification?.verdict)

        val (third, _) = boot()
        assertEquals(RequestState.ANSWERED, third.requests.requests.getValue(r.id).state, "ответ пережил перезапуск")
        assertEquals(OperationStatus.SUCCEEDED, third.answer(uri(r, "waive"), null, "автор").status, "повтор после перезапуска — тот же ответ")
        assertEquals(emptyList(), third.invariants())
    }

    /**
     * Аудит надёжности 2 ноября 2026, A-8: запрос истёк (телефон выключен
     * дольше срока), а задача всё ещё ждёт решения — тот же вопрос задаётся
     * снова; и после перезапуска тоже. «Решу позже» — ответ: сам не возвращается.
     */
    @Test
    fun expiredRequestIsAskedAgainWhileTaskWaits() {
        val (o, runner) = boot()
        val task = o.addTask("задача", "цель").valueOrNull()!!
        run(o, runner, 3)
        val first = requestFor(o, task)
        clock.advance(3.hours)
        o.tick()
        assertEquals(RequestState.EXPIRED, o.requests.requests.getValue(first.id).state)
        val again = requestFor(o, task)
        assertTrue(again.id != first.id && again.subject == first.subject && again.purpose == "task.unverified", again.toString())

        // Истёк, пока приложение не работало: первый такт после запуска спрашивает снова.
        clock.advance(3.hours)
        val (restarted, _) = boot()
        restarted.tick()
        val third = requestFor(restarted, task)
        assertTrue(third.id != again.id, "новый запрос после перезапуска")

        assertEquals(OperationStatus.SUCCEEDED, restarted.answer(uri(third, "later"), null, "автор").status)
        clock.advance(3.hours)
        restarted.tick()
        assertTrue(restarted.pendingRequests().none { it.task == task }, "«решу позже» не возвращается само")
        assertEquals(TaskState.COMPLETION_UNVERIFIED, restarted.tasks.tasks.getValue(task).state)
    }

    @Test
    fun concurrentRequestsOfDifferentTasksDoNotMix() {
        val (o, runner) = boot()
        val a = o.addTask("задача A", "цель A").valueOrNull()!!
        val b = o.addTask("задача B", "цель B").valueOrNull()!!
        run(o, runner, 3)
        val ra = requestFor(o, a)
        val rb = requestFor(o, b)
        assertTrue(ra.id != rb.id && ra.subject != rb.subject)
        // Кнопка запроса A с хешем запроса B — отказ, задача B не тронута.
        val forged = RequestAction(ra.id, "cancel", rb.hash).toUri()
        assertEquals(DiagCode.REQUEST_ANSWER_INVALID, o.answer(forged, null, "автор").diagnostics.single().code)
        assertEquals(OperationStatus.SUCCEEDED, o.answer(uri(rb, "cancel"), null, "автор").status)
        assertEquals(TaskState.CANCELLED, o.tasks.tasks.getValue(b).state)
        assertEquals(TaskState.COMPLETION_UNVERIFIED, o.tasks.tasks.getValue(a).state, "ответ на B не задел A")
        assertEquals(RequestState.CREATED, o.requests.requests.getValue(ra.id).state)
    }

    @Test
    fun expiryAndStaleRequests() {
        val (o, runner) = boot()
        val probe = o.probe(RequestKind.CONFIRMATION, 30.minutes).valueOrNull()!!
        val p = o.requests.requests.getValue(probe)
        clock.advance(31.minutes)
        val late = o.answer(uri(p, "yes"), null, "автор")
        assertEquals(DiagCode.REQUEST_NOT_PENDING, late.diagnostics.single().code)
        assertEquals(RequestState.EXPIRED, o.requests.requests.getValue(probe).state, "истёкший — EXPIRED сразу при ответе")

        // Запрос о задаче, которая ушла из того состояния, снимается тактом.
        authFailsNextLaunch()
        val task = o.addTask("задача", "цель").valueOrNull()!!
        run(o, runner, 2)
        val r = requestFor(o, task)
        val other = o.probe(RequestKind.ACKNOWLEDGEMENT).valueOrNull()!!
        assertEquals(OperationStatus.SUCCEEDED, o.answer(uri(o.requests.requests.getValue(other), "ack"), null, "автор").status)
        assertEquals(RequestState.CREATED, o.requests.requests.getValue(r.id).state)
        clock.advance(3.hours)
        o.tick()
        assertEquals(RequestState.EXPIRED, o.requests.requests.getValue(r.id).state)
        assertEquals(TaskState.BLOCKED, o.tasks.tasks.getValue(task).state, "истечение запроса задачу не трогает")
    }

    @Test
    fun everyKindHasAValidProbe() {
        val (o, _) = boot()
        for (k in RequestKind.entries) {
            val id = o.probe(k)
            assertTrue(id.isSuccess, "$k: ${id.diagnostics}")
        }
        assertEquals(RequestKind.entries.size, o.pendingRequests().size, "проверочные запросы не смешиваются")
        assertTrue(o.pendingRequests().none { "<redacted>" in it.title }, o.pendingRequests().map { it.title }.toString())
        val text = o.pendingRequests().single { it.kind == RequestKind.TEXT_INPUT }
        assertEquals(DiagCode.REQUEST_ANSWER_INVALID, o.answer(uri(text, "send"), null, "автор").diagnostics.single().code, "без текста — отказ")
        assertEquals("проверка", o.answer(uri(text, "send"), "проверка", "автор").value?.text)
    }

    /**
     * Очередь команд (D-42, п. 1): команда из другого потока исполняется
     * тактом в безопасной точке, ждущий получает итог; ключ
     * идемпотентности — вторая постановка той же задачи не создаёт её,
     * и после перезапуска тоже; события уходят в журнал приложения.
     */
    @Test
    fun commandsFromOtherThreadsRunInTickAndKeysAreIdempotent() {
        val (o, runner) = boot()
        val key = "tap-0001-new-task"
        val first = java.util.concurrent.CompletableFuture.supplyAsync { o.submit(com.dpsmpx.dpai.orchestrator.commands.Command.AddTask("из очереди", "цель", key = key)).get() }
        // Поток такта разбирает очередь; отправитель ждёт итог, не трогая контур сам.
        while (!first.isDone) {
            o.inbox.await(50)
            o.runCommands()
        }
        val task = first.get().value as com.dpsmpx.dpai.core.ids.TaskId
        assertEquals(OperationStatus.SUCCEEDED, first.get().status)
        val again = o.submit(com.dpsmpx.dpai.orchestrator.commands.Command.AddTask("из очереди", "цель", key = key))
        o.tick()
        assertEquals(task.value, again.get().value, "повтор с тем же ключом — тот же итог")
        assertEquals(1, o.tasks.tasks.values.count { it.title == "из очереди" })
        assertTrue(EventType.OPERATION_RECORDED in logged && EventType.TASK_CREATED in logged, "события — в журнал приложения")

        run(o, runner, 2)
        val (second, _) = boot()
        val afterRestart = second.submit(com.dpsmpx.dpai.orchestrator.commands.Command.AddTask("из очереди", "цель", key = key))
        second.runCommands()
        assertEquals(task.value, afterRestart.get().value, "ключ пережил перезапуск")
        assertEquals(1, second.tasks.tasks.values.count { it.title == "из очереди" })

        val bad = second.submit(com.dpsmpx.dpai.orchestrator.commands.Command.AddTask("x", "y", key = "плохой ключ"))
        second.runCommands()
        assertEquals(DiagCode.VALUE_INVALID, bad.get().diagnostics.single().code)
    }

    /** Ответ на запрос через очередь — тот же итог, что напрямую; такт исполняет его до опроса сессий. */
    @Test
    fun answerThroughInbox() {
        val (o, _) = boot()
        val id = o.probe(RequestKind.CONFIRMATION).valueOrNull()!!
        val r = o.requests.requests.getValue(id)
        val f = o.submit(com.dpsmpx.dpai.orchestrator.commands.Command.AnswerRequest(uri(r, "yes"), null, "автор", "overlay"))
        assertTrue(!f.isDone, "до такта команда ждёт")
        o.tick()
        assertEquals(OperationStatus.SUCCEEDED, f.get().status)
        assertEquals("overlay", o.requests.requests.getValue(id).answer?.channel)
    }
}
