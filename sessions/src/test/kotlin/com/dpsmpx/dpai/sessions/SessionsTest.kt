package com.dpsmpx.dpai.sessions

import com.dpsmpx.dpai.accounts.AccountEvent
import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.accounts.SlotEnvironment
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.execution.claude.ToolCount
import com.dpsmpx.dpai.execution.claude.TurnTools
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.state.HeartbeatFreshness
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Сессии (критерии этапа 6; ТЗ, §8, §15, §29, §66): многие сессии сразу,
 * heartbeat без поспешного «упала», идемпотентность, сверка слота.
 */
class SessionsTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(21))
    private val accountsLogic = Accounts()
    private var accounts = AccountsState.EMPTY
    private val sessionsLogic = Sessions()
    private var state = SessionsState.EMPTY
    private val scenarios = HashMap<SessionId, MockScenario>()
    private val runner = MockRunner.create(clock, RandomSource.seeded(22)) { s, _ -> scenarios[s] ?: MockScenario.SUCCESS }
    private val driver = SessionDriver(sessionsLogic, runner, clock)

    private fun acc(out: Outcome<List<AccountEvent>>) {
        accounts = Accounts.applyAll(accounts, assertIs<Outcome.Success<List<AccountEvent>>>(out).value)
    }

    /** Все события сессий теста по порядку — как журнал. */
    private val log = mutableListOf<SessionEvent>()

    private fun apply(es: List<SessionEvent>) {
        log += es
        state = Sessions.applyAll(state, es)
    }

    /** Подписка и [n] слотов на разных targets, все с выполненным входом. */
    private fun slots(n: Int): List<AccountId> {
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        acc(accountsLogic.registerSubscription(accounts, sub, "основная", clock.now(), maxParallelSessions = 64))
        return List(n) {
            val slot = ids.next(IdKinds.ACCOUNT)
            acc(accountsLogic.registerSlot(accounts, slot, sub, ids.next(IdKinds.TARGET), "/home/dev/.claude-$it", clock.now()))
            val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org-1", "author@example.org"), "max")
            acc(accountsLogic.observeAuthStatus(accounts, slot, status, clock.now()))
            slot
        }
    }

    /** Аренда слота и создание сессии под задачу. */
    private fun newSession(task: TaskId = ids.next(IdKinds.TASK), key: String = "k-" + ids.next(IdKinds.OPERATION).value): SessionId {
        val id = ids.next(IdKinds.SESSION)
        val lease = assertIs<Outcome.Success<Accounts.Lease>>(accountsLogic.lease(accounts, id, task, clock.now())).value
        accounts = Accounts.applyAll(accounts, lease.events)
        val target = accounts.slots.getValue(lease.slot).targetId
        val created = assertIs<Outcome.Success<Sessions.Created>>(
            sessionsLogic.create(state, accounts, id, key, task, lease.slot, target, ProviderSessionId.newUuid(RandomSource.seeded(id.hashCode().toLong())), clock.now()),
        ).value
        apply(created.events)
        return id
    }

    private fun applyFacts(facts: List<AccountFact>) {
        for (f in facts) {
            val slot = accounts.slotOf(f.session)?.id ?: continue
            when (f) {
                is AccountFact.Release -> acc(accountsLogic.release(accounts, f.session, clock.now()))
                is AccountFact.Success -> acc(accountsLogic.observeSuccess(accounts, slot, clock.now()))
                is AccountFact.AuthFailure -> acc(accountsLogic.observeAuthFailure(accounts, slot, clock.now()))
                is AccountFact.Error -> acc(accountsLogic.observeError(accounts, slot, f.code, clock.now()))
                is AccountFact.Limit -> acc(accountsLogic.observeLimit(accounts, slot, f.window, f.level, f.resetAt, clock.now()))
                is AccountFact.Usage -> acc(accountsLogic.observeUsage(accounts, slot, f.window, f.utilization, f.resetAt, clock.now()))
            }
        }
    }

    private fun step(out: Outcome<SessionDriver.Step>) {
        val s = assertIs<Outcome.Success<SessionDriver.Step>>(out, out.diagnostics.toString()).value
        apply(s.events)
        applyFacts(s.facts)
    }

    @Test
    fun twentyFiveConcurrentSyntheticSessions() {
        slots(25)
        val mix = listOf(MockScenario.SUCCESS, MockScenario.FAILURE, MockScenario.TIMEOUT, MockScenario.CRASH, MockScenario.DUPLICATE_EVENTS, MockScenario.PARTIAL_COMPLETION)
        val all = List(25) { i -> newSession().also { scenarios[it] = mix[i % mix.size] } }
        for (s in all) step(driver.launch(state, accounts, s, ClaudeRequest("задача")))
        assertEquals(25, state.sessions.values.count { it.state == SessionState.STARTING })
        // Опрос по кругу, как оркестратор: все сессии идут одновременно.
        repeat(3) { for (s in all) step(driver.poll(state, s)) }
        val expected = mapOf(
            MockScenario.SUCCESS to SessionState.COMPLETED,
            MockScenario.DUPLICATE_EVENTS to SessionState.COMPLETED,
            MockScenario.FAILURE to SessionState.FAILED,
            MockScenario.CRASH to SessionState.FAILED,
            MockScenario.TIMEOUT to SessionState.PAUSED,
            MockScenario.PARTIAL_COMPLETION to SessionState.PAUSED,
        )
        for (s in all) assertEquals(expected.getValue(scenarios.getValue(s)), state.sessions.getValue(s).state, "$s ${scenarios[s]}")
        // Конечные сессии вернули аренду; приостановленные держат слот.
        val held = all.filter { !state.sessions.getValue(it).terminal }.toSet()
        assertEquals(held, accounts.slots.values.flatMap { it.sessions.keys }.toSet())
    }

    @Test
    fun missingHeartbeatIsNotDeath() {
        slots(1)
        val s = newSession()
        step(driver.launch(state, accounts, s, ClaudeRequest("x")))
        apply(sessionsLogic.transition(state, s, SessionState.RUNNING, clock.now()).valueOrNull()!!)
        apply(sessionsLogic.heartbeat(state, s, clock.now()))
        val record = { state.sessions.getValue(s) }
        for ((after, want) in listOf(2.minutes to HeartbeatFreshness.AGING, 5.minutes to HeartbeatFreshness.STALE, 20.minutes to HeartbeatFreshness.UNKNOWN)) {
            clock.advance(after)
            assertEquals(want, sessionsLogic.freshness(record(), clock.now()))
            // Проба без подтверждения отсутствия процесса состояние не трогает.
            assertEquals(emptyList(), sessionsLogic.onProbe(state, s, clock.now(), processConfirmedAbsent = false).valueOrNull())
            assertEquals(SessionState.RUNNING, record().state)
        }
        // Только проба, подтвердившая отсутствие процесса, ведёт к сверке — и не к FAILED.
        apply(sessionsLogic.onProbe(state, s, clock.now(), processConfirmedAbsent = true).valueOrNull()!!)
        assertEquals(SessionState.RECOVERY_PENDING, record().state)
        assertEquals(DiagCode.AGENT_RUN_LOST, record().lastReason)
    }

    @Test
    fun repeatedCreateAndContinueAreIdempotent() {
        slots(3)
        val task = ids.next(IdKinds.TASK)
        val s = newSession(task, key = "задача-1:попытка-1")
        // Повтор того же запроса: та же сессия, событий нет.
        val again = sessionsLogic.create(state, accounts, ids.next(IdKinds.SESSION), "задача-1:попытка-1", task, state.sessions.getValue(s).slot, state.sessions.getValue(s).target, ProviderSessionId.newUuid(RandomSource.seeded(1)), clock.now())
        assertEquals(s, again.valueOrNull()!!.session)
        assertEquals(emptyList(), again.valueOrNull()!!.events)
        assertTrue(again.diagnostics.any { it.code == DiagCode.SESSION_ALREADY_EXISTS })
        assertEquals(1, state.sessions.size)

        scenarios[s] = MockScenario.PARTIAL_COMPLETION
        step(driver.launch(state, accounts, s, ClaudeRequest("x")))
        step(driver.poll(state, s))
        assertEquals(SessionState.PAUSED, state.sessions.getValue(s).state)
        // Продолжение: новая сессия над той же сессией провайдера; второй запрос — то же продолжение.
        val prev = state.sessions.getValue(s)
        val cont = ids.next(IdKinds.SESSION)
        val lease = accountsLogic.lease(accounts, cont, task, clock.now()).valueOrNull()!!
        accounts = Accounts.applyAll(accounts, lease.events)
        val target = accounts.slots.getValue(lease.slot).targetId
        val c1 = sessionsLogic.create(state, accounts, cont, "задача-1:продолжение", task, lease.slot, target, prev.provider, clock.now(), continues = s).valueOrNull()!!
        apply(c1.events)
        val c2 = sessionsLogic.create(state, accounts, ids.next(IdKinds.SESSION), "задача-1:продолжение-повтор", task, lease.slot, target, prev.provider, clock.now(), continues = s)
        assertEquals(cont, c2.valueOrNull()!!.session, "второе продолжение той же сессии — то же")
        scenarios[cont] = MockScenario.SUCCESS
        step(driver.launch(state, accounts, cont, ClaudeRequest("продолжай")))
        assertEquals(prev.provider, runner.inspect(cont).valueOrNull()!!.provider, "продолжение возобновляет сессию провайдера")
        step(driver.poll(state, cont))
        assertEquals(SessionState.COMPLETED, state.sessions.getValue(cont).state)
    }

    @Test
    fun sessionCannotUseAnotherSessionsSlot() {
        val (slotA, slotB) = slots(2)
        val a = newSession()
        val aSlot = state.sessions.getValue(a).slot
        val other = if (aSlot == slotA) slotB else slotA
        // Создать сессию на неарендованном ею слоте нельзя.
        val stray = ids.next(IdKinds.SESSION)
        val r = sessionsLogic.create(state, accounts, stray, "чужой", ids.next(IdKinds.TASK), other, accounts.slots.getValue(other).targetId, ProviderSessionId.newUuid(RandomSource.seeded(3)), clock.now())
        assertEquals(DiagCode.SESSION_BINDING_MISMATCH, (r as Outcome.Failure).errors.single().code)
        // Окружение хода — только из слота этой сессии.
        val binding = sessionsLogic.bindingFor(state, accounts, a).valueOrNull()!!
        assertEquals(accounts.slots.getValue(aSlot).configDir, binding.setEnv[SlotEnvironment.CONFIG_DIR])
        assertTrue(binding.unsetEnv.containsAll(SlotEnvironment.OVERRIDING))
        // Аренда возвращена — ход не запускается.
        acc(accountsLogic.release(accounts, a, clock.now()))
        val launch = driver.launch(state, accounts, a, ClaudeRequest("x"))
        assertEquals(DiagCode.SESSION_BINDING_MISMATCH, (launch as Outcome.Failure).errors.single().code)
    }

    @Test
    fun authFailureBlocksAndRateLimitPausesAndFeedsAccounts() {
        slots(2)
        val auth = newSession().also { scenarios[it] = MockScenario.AUTH_ERROR }
        val limit = newSession().also { scenarios[it] = MockScenario.RATE_LIMIT }
        for (s in listOf(auth, limit)) {
            step(driver.launch(state, accounts, s, ClaudeRequest("x")))
            step(driver.poll(state, s))
        }
        assertEquals(SessionState.BLOCKED, state.sessions.getValue(auth).state)
        assertEquals(SessionState.PAUSED, state.sessions.getValue(limit).state)
        val sub = accounts.subscriptions.values.single()
        assertTrue(sub.limitedAt(clock.now()), "предел, замеченный сессией, — у подписки (D-30)")
    }

    @Test
    fun eventsAndStateSurviveTheJournalFormat() {
        slots(2)
        val s = newSession()
        scenarios[s] = MockScenario.SUCCESS
        step(driver.launch(state, accounts, s, ClaudeRequest("x")))
        clock.advance(10.seconds)
        step(driver.poll(state, s))
        assertTrue(log.map { it::class }.toSet().size >= 5, "в журнале события разных видов: $log")
        // Каждое событие переживает конверт dpai.event, а состояние из журнала — то же.
        val decoded = log.map { e -> SessionCodec.decode(SessionCodec.type(e), e.at, SessionCodec.payload(e))!!.valueOrNull()!! }
        assertEquals(log, decoded)
        assertEquals(state, Sessions.applyAll(SessionsState.EMPTY, decoded))
        val json = CanonicalJson.write(SessionCodec.encodeState(state))
        assertEquals(state, SessionCodec.decodeState(JsonParser().parse(json).valueOrNull() as JsonObject).valueOrNull())
    }

    @Test
    fun usageOfPromptAndTurnIsRecordedAndSurvivesSnapshot() {
        // D-40, п. 1: prompt — оценка оркестратора, ход — со слов провайдера; оба в журнале и снимке.
        slots(1)
        val s = newSession()
        scenarios[s] = MockScenario.SUCCESS
        apply(listOf(SessionEvent.PromptPrepared(s, PromptFacts(PromptMode.FULL, 1200, 400, 3, omittedSources = 1, omittedTokens = 900, context = "ctx@v1", taskClass = "NORMAL_ENGINEERING"), clock.now())))
        step(driver.launch(state, accounts, s, ClaudeRequest("x")))
        clock.advance(10.seconds)
        step(driver.poll(state, s))
        val usageEvents = log.filterIsInstance<SessionEvent.TurnUsage>()
        assertEquals(1, usageEvents.size)
        // Расход и замер инструментов (D-41) — перед итогом хода того же номера.
        val finished = log.indexOfFirst { it is SessionEvent.TurnFinished }
        assertEquals(finished - 2, log.indexOf(usageEvents.single()))
        assertIs<SessionEvent.TurnTools>(log[finished - 1])
        val u = state.sessions.getValue(s).usage
        assertEquals(1, u.turns)
        assertEquals(10, u.inputTokens)
        assertEquals(5, u.outputTokens)
        assertEquals(400, u.promptTokens)
        assertEquals(900, u.omittedTokens)
        assertEquals(PromptMode.FULL, u.lastPrompt?.mode)
        val decoded = log.map { e -> SessionCodec.decode(SessionCodec.type(e), e.at, SessionCodec.payload(e))!!.valueOrNull()!! }
        assertEquals(log, decoded)
        val json = CanonicalJson.write(SessionCodec.encodeState(state))
        assertEquals(state, SessionCodec.decodeState(JsonParser().parse(json).valueOrNull() as JsonObject).valueOrNull())
        // Снимок v1 (до D-40) читается: расхода в нём нет.
        val bare = SessionsState(state.sessions.mapValues { it.value.copy(usage = UsageTotals.EMPTY) })
        val v1 = CanonicalJson.write(SessionCodec.encodeState(bare)).replace("\"version\":3", "\"version\":1")
        assertEquals(bare, SessionCodec.decodeState(JsonParser().parse(v1).valueOrNull() as JsonObject).valueOrNull())
    }

    @Test
    fun toolMeasurementIsSummedAndSurvivesJournalAndSnapshot() {
        // D-41: замер инструментов хода — отдельное событие; суммы — в расходе сессии и снимке v3.
        slots(1)
        val s = newSession()
        val t1 = TurnTools(
            byTool = mapOf("Read" to ToolCount(3, 900, 0, 1, 300), "Bash" to ToolCount(1, 5000, 1, 0, 0)),
            subagentCalls = 2, subagentResultBytes = 700, filesRead = 2, filesEdited = 1, largestResultBytes = 5000,
        )
        val t2 = TurnTools(byTool = mapOf("Read" to ToolCount(1, 100)), filesRead = 1, largestResultBytes = 100, unmatchedResults = 1)
        apply(listOf(SessionEvent.TurnTools(s, 1, t1, clock.now()), SessionEvent.TurnTools(s, 2, t2, clock.now())))
        val tools = state.sessions.getValue(s).usage.tools
        assertEquals(2, tools.turns)
        assertEquals(ToolCount(4, 1000, 0, 1, 300), tools.byTool["Read"])
        assertEquals(ToolCount(5, 6000, 1, 1, 300), tools.total)
        assertEquals(3, tools.filesRead)
        assertEquals(5000, tools.largestResultBytes)
        assertEquals(1, tools.unmatchedResults)
        val decoded = log.map { e -> SessionCodec.decode(SessionCodec.type(e), e.at, SessionCodec.payload(e))!!.valueOrNull()!! }
        assertEquals(log, decoded)
        val json = CanonicalJson.write(SessionCodec.encodeState(state))
        assertEquals(state, SessionCodec.decodeState(JsonParser().parse(json).valueOrNull() as JsonObject).valueOrNull())
        // Снимок v2 (D-40, до D-41) читается: инструментов в нём нет.
        val noTools = SessionsState(state.sessions.mapValues { it.value.copy(usage = it.value.usage.copy(tools = ToolTotals.EMPTY)) })
        val v2 = CanonicalJson.write(SessionCodec.encodeState(noTools)).replace("\"version\":3", "\"version\":2")
        assertEquals(noTools, SessionCodec.decodeState(JsonParser().parse(v2).valueOrNull() as JsonObject).valueOrNull())
        // Испорченный замер — ошибка чтения, а не нули.
        val bad = SessionCodec.payload(SessionEvent.TurnTools(s, 3, t2, clock.now())).let { p ->
            JsonObject(p.fields + ("tools" to JsonObject((p["tools"] as JsonObject).fields + ("filesRead" to JsonNumber.of(-1)))))
        }
        assertIs<Outcome.Failure>(SessionCodec.decode(EventType.SESSION_TURN_TOOLS, clock.now(), bad))
    }
}
