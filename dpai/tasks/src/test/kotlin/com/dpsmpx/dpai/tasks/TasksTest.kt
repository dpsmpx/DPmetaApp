package com.dpsmpx.dpai.tasks

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Retryability
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.testkit.Gen
import com.dpsmpx.dpai.testkit.Property
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Планировщик (критерии этапа 7; ТЗ, §17, §21, §22, §84, §120): граф
 * зависимостей исполняется по порядку, порядок устойчив, повторы
 * ограничены, голодания нет, циклы и петли останавливаются.
 */
class TasksTest {
    @Test
    fun batchProjectionPreservesInputAndAppliesTransitionsInOrder() {
        val id = task()
        val before = state
        val events = listOf(TaskEvent.ScopeChanged(id, setOf("src"), clock.now()),
            TaskEvent.StateChanged(id, TaskState.QUEUED, TaskState.READY, null, null, clock.now()),
            TaskEvent.StateChanged(id, TaskState.READY, TaskState.RUNNING, null, null, clock.now()))
        val result = Tasks.applyAll(before, events)
        assertEquals(TaskState.RUNNING, result.tasks.getValue(id).state)
        assertEquals(setOf("src"), result.tasks.getValue(id).scope)
        kotlin.test.assertFailsWith<IllegalStateException> {
            Tasks.applyAll(before, listOf(events.first(), TaskEvent.StateChanged(id, TaskState.QUEUED, TaskState.VERIFIED, null, null, clock.now())))
        }
        assertEquals(TaskState.QUEUED, before.tasks.getValue(id).state)
        assertEquals(null, before.tasks.getValue(id).scope)
    }

    @Test
    fun promotionDependsOnDependencyStatesAndKeepsStableEventOrder() {
        val done = task()
        val failed = task()
        val ready = task(deps = setOf(done))
        val blocked = task(deps = setOf(done, failed))
        val waiting = task(deps = setOf(ready))
        val records = state.tasks + (done to state.tasks.getValue(done).copy(state = TaskState.DONE)) +
            (failed to state.tasks.getValue(failed).copy(state = TaskState.FAILED))
        val before = TasksState(records)
        val reversed = TasksState(records.entries.reversed().associate { it.toPair() })
        val changes = tasks.promote(before, clock.now())
        assertEquals(changes, tasks.promote(reversed, clock.now()))
        assertEquals(listOf(ready, blocked).sortedBy { it.value }, changes.map { it.task })
        val after = Tasks.applyAll(before, changes)
        assertEquals(TaskState.READY, after.tasks.getValue(ready).state)
        assertEquals(TaskState.BLOCKED, after.tasks.getValue(blocked).state)
        assertEquals(TaskState.QUEUED, after.tasks.getValue(waiting).state)
    }

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(31))
    private val tasks = Tasks()
    private var state = TasksState.EMPTY
    private val target = ids.next(IdKinds.TARGET)

    private val log = mutableListOf<TaskEvent>()

    private fun ok(out: Outcome<List<TaskEvent>>): List<TaskEvent> {
        val es = assertIs<Outcome.Success<List<TaskEvent>>>(out, out.diagnostics.toString()).value
        log += es
        state = Tasks.applyAll(state, es)
        return es
    }

    private fun task(priority: Priority = Priority.NORMAL, deps: Set<TaskId> = emptySet(), caps: Set<String> = emptySet(), policy: TaskPolicy = TaskPolicy()): TaskId {
        val id = ids.next(IdKinds.TASK)
        ok(tasks.create(state, id, "задача", "цель", priority, clock.now(), deps, caps, policy))
        ok(tasks.enqueue(state, id, clock.now()))
        return id
    }

    /** Один такт: продвинуть очередь, назначить, «исполнить» назначенное до DONE. */
    private fun runTick(capacity: Int): List<TaskId> {
        ok(Outcome.success(tasks.promote(state, clock.now())))
        val plan = tasks.plan(state, clock.now(), listOf(Tasks.TargetOffer(target, emptySet(), capacity)))
        for (a in plan) {
            ok(tasks.start(state, a.task, clock.now()))
            ok(tasks.implemented(state, a.task, clock.now()))
            ok(tasks.recordVerification(state, a.task, "PASSED", null, clock.now()))
            ok(tasks.complete(state, a.task, clock.now()))
        }
        clock.advance(1.minutes)
        return plan.map { it.task }
    }

    /** Ревью 8 октября 2026: задача без цели — отказ при создании, а не падение такта при сборке контекста. */
    @Test
    fun blankObjectiveIsRejectedAtCreation() {
        val r = tasks.create(state, ids.next(IdKinds.TASK), "заголовок", "   ", Priority.NORMAL, clock.now())
        assertEquals(DiagCode.VALUE_INVALID, (r as Outcome.Failure).errors.single().code)
    }

    @Test
    fun dependencyGraphRunsInOrder() {
        val a = task()
        val b = task(deps = setOf(a))
        val c = task(deps = setOf(a))
        val d = task(deps = setOf(b, c))
        assertEquals(listOf(a), runTick(10))
        assertEquals(setOf(b, c), runTick(10).toSet())
        assertEquals(listOf(d), runTick(10))
        assertTrue(state.tasks.values.all { it.state == TaskState.DONE })
    }

    /** Свойство: на случайном DAG задача стартует только после всех своих зависимостей, и все доходят до DONE. */
    @Test
    fun randomDagExecutesRespectingDependencies() {
        Property.forAll(Gen.list(Gen.list(Gen.int(0..30), 0..3), 1..25)) { spec ->
            state = TasksState.EMPTY
            val created = mutableListOf<TaskId>()
            for (deps in spec) created += task(deps = deps.filter { it < created.size }.map { created[it] }.toSet())
            val finishedAt = HashMap<TaskId, Int>()
            var tick = 0
            while (finishedAt.size < created.size) {
                tick++
                assertTrue(tick <= created.size + 1, "граф не исполнился: $spec")
                for (t in runTick(3)) {
                    for (dep in state.tasks.getValue(t).dependencies) assertTrue((finishedAt[dep] ?: Int.MAX_VALUE) < tick, "$t раньше $dep")
                    finishedAt[t] = tick
                }
            }
        }
    }

    /** Свойство: порядок не зависит от порядка создания записей и одинаков при повторе (D-22). */
    @Test
    fun orderIsStable() {
        Property.forAll(Gen.list(Gen.int(0..4), 2..20)) { levels ->
            state = TasksState.EMPTY
            levels.forEach { task(Priority.entries[it]) }
            ok(Outcome.success(tasks.promote(state, clock.now())))
            val order = tasks.ready(state, clock.now()).map { it.id }
            val shuffled = TasksState(state.tasks.entries.reversed().associate { it.key to it.value })
            assertEquals(order, tasks.ready(shuffled, clock.now()).map { it.id })
            assertEquals(order, tasks.ready(state, clock.now()).map { it.id })
            // Уровни не возрастают вдоль порядка (без старения — одно время постановки).
            val lv = order.map { state.tasks.getValue(it).priority.ordinal }
            assertEquals(lv.sorted(), lv)
        }
    }

    /** Свойство: при старении задача низкого приоритета назначается, даже если высокие приходят каждый такт. */
    @Test
    fun agingPreventsStarvation() {
        Property.forAll(Gen.int(1..3)) { burst ->
            state = TasksState.EMPTY
            val low = task(Priority.BACKGROUND)
            var started = false
            var ticks = 0
            while (!started) {
                repeat(burst) { task(Priority.HIGH) }
                ticks++
                assertTrue(ticks < 300, "задача BACKGROUND голодает")
                // Ёмкость 1 на такт; такт — 10 минут: старение поднимает уровень раз в 3 такта.
                started = low in runTick(1)
                clock.advance(9.minutes)
            }
        }
    }

    /** Свойство: повторов не больше политики при любой серии временных отказов. */
    @Test
    fun retryLimitIsRespected() {
        Property.forAll(Gen.int(0..5)) { maxRetries ->
            state = TasksState.EMPTY
            val t = task(policy = TaskPolicy(maxRetries = maxRetries))
            var attempts = 0
            while (!state.tasks.getValue(t).terminal && state.tasks.getValue(t).state != TaskState.BLOCKED) {
                ok(Outcome.success(tasks.promote(state, clock.now())))
                clock.advance(1_000.minutes)
                if (state.tasks.getValue(t).state != TaskState.READY) break
                ok(tasks.start(state, t, clock.now()))
                attempts++
                // Разные ключи отказа: проверяется предел повторов, а не петля.
                ok(tasks.attemptFailed(state, t, DiagCode.NETWORK_UNREACHABLE, Retryability.RETRYABLE, "k$attempts", clock.now()))
            }
            assertEquals(maxRetries + 1, attempts)
            assertEquals(TaskState.FAILED, state.tasks.getValue(t).state)
            assertEquals(DiagCode.RETRY_LIMIT_REACHED, state.tasks.getValue(t).lastReason)
        }
    }

    @Test
    fun retryWaitsForBackoff() {
        val t = task()
        runTickUntilReady(t)
        ok(tasks.start(state, t, clock.now()))
        ok(tasks.attemptFailed(state, t, DiagCode.NETWORK_UNREACHABLE, Retryability.RETRYABLE, "k", clock.now()))
        assertEquals(TaskState.READY, state.tasks.getValue(t).state)
        assertEquals(emptyList(), tasks.ready(state, clock.now()), "до паузы не назначается")
        clock.advance(1.minutes)
        assertEquals(listOf(t), tasks.ready(state, clock.now()).map { it.id })
    }

    private fun runTickUntilReady(t: TaskId) {
        ok(Outcome.success(tasks.promote(state, clock.now())))
        assertEquals(TaskState.READY, state.tasks.getValue(t).state)
    }

    @Test
    fun sameFailureRepeatedIsLoop() {
        val t = task(policy = TaskPolicy(maxRetries = 10, retryBackoff = 0.minutes))
        runTickUntilReady(t)
        repeat(3) {
            ok(tasks.start(state, t, clock.now()))
            ok(tasks.attemptFailed(state, t, DiagCode.SESSION_FAILED, Retryability.RETRYABLE, "одна и та же ошибка", clock.now()))
        }
        assertEquals(TaskState.BLOCKED, state.tasks.getValue(t).state)
        assertEquals(DiagCode.LOOP_DETECTED, state.tasks.getValue(t).lastReason)
    }

    /**
     * Ревью 8 октября 2026: провал проверки — неудачная попытка с паузой; тот
     * же провал раз за разом — LOOP_DETECTED, а не новый ход Claude каждый такт.
     */
    @Test
    fun repeatedVerificationFailureIsCountedAndStopsAsLoop() {
        val t = task(policy = TaskPolicy(maxRetries = 10, retryBackoff = 1.minutes))
        runTickUntilReady(t)
        ok(tasks.start(state, t, clock.now()))
        ok(tasks.implemented(state, t, clock.now()))
        ok(tasks.recordVerification(state, t, "FAILED", "sha256:abc", clock.now()))
        val first = state.tasks.getValue(t)
        assertEquals(TaskState.READY, first.state)
        assertEquals(1, first.retries, "провал проверки — попытка")
        assertTrue(log.filterIsInstance<TaskEvent.StateChanged>().last().notBefore != null, "следующий ход — после паузы")
        repeat(2) {
            clock.advance(10.minutes)
            ok(tasks.start(state, t, clock.now()))
            ok(tasks.implemented(state, t, clock.now()))
            ok(tasks.recordVerification(state, t, "FAILED", "sha256:abc", clock.now()))
        }
        assertEquals(TaskState.BLOCKED, state.tasks.getValue(t).state)
        assertEquals(DiagCode.LOOP_DETECTED, state.tasks.getValue(t).lastReason)
    }

    @Test
    fun nonRetryableBlocksImmediately() {
        val t = task()
        runTickUntilReady(t)
        ok(tasks.start(state, t, clock.now()))
        ok(tasks.attemptFailed(state, t, DiagCode.CLAUDE_AUTH_REQUIRED, Retryability.NON_RETRYABLE, "k", clock.now()))
        assertEquals(TaskState.BLOCKED, state.tasks.getValue(t).state)
    }

    @Test
    fun dependencyCycleIsDetected() {
        val a = task()
        val b = task(deps = setOf(a))
        val c = task(deps = setOf(b))
        val r = tasks.addDependency(state, a, c, clock.now())
        assertIs<Outcome.Failure>(r)
        assertEquals(DiagCode.DEPENDENCY_CYCLE, r.errors.single().code)
        assertTrue(listOf(a, b, c).all { it.value in r.errors.single().message })
        val self = ids.next(IdKinds.TASK)
        assertEquals(DiagCode.DEPENDENCY_CYCLE, (tasks.create(state, self, "x", "y", Priority.LOW, clock.now(), setOf(self)) as Outcome.Failure).errors.single().code)
    }

    /** D-42, п. 1: зависимости и область меняют целиком, но только у задачи, которая ещё не идёт. */
    @Test
    fun dependenciesAndScopeChangeOnlyBeforeStart() {
        val a = task()
        val b = task()
        val c = task()
        ok(tasks.setDependencies(state, c, setOf(a, b), clock.now()))
        assertEquals(setOf(a, b), state.tasks.getValue(c).dependencies)
        ok(tasks.setDependencies(state, c, setOf(b), clock.now()))
        assertEquals(setOf(b), state.tasks.getValue(c).dependencies, "лишняя зависимость снята")
        assertEquals(emptyList(), ok(tasks.setDependencies(state, c, setOf(b), clock.now())), "повтор — без события")
        assertEquals(DiagCode.DEPENDENCY_CYCLE, (tasks.setDependencies(state, b, setOf(c), clock.now()) as Outcome.Failure).errors.single().code)
        assertEquals(DiagCode.DEPENDENCY_CYCLE, (tasks.setDependencies(state, c, setOf(c), clock.now()) as Outcome.Failure).errors.single().code)
        assertEquals(DiagCode.ENTITY_NOT_FOUND, (tasks.setDependencies(state, c, setOf(ids.next(IdKinds.TASK)), clock.now()) as Outcome.Failure).errors.single().code)
        runTickUntilReady(a)
        ok(tasks.start(state, a, clock.now()))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, (tasks.setDependencies(state, a, setOf(b), clock.now()) as Outcome.Failure).errors.single().code)
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, (Tasks.setScope(state, a, setOf("src"), clock.now()) as Outcome.Failure).errors.single().code)
    }

    /** Ревью 18 октября 2026: новая зависимость у готовой задачи возвращает её в очередь — она не начнётся раньше. */
    @Test
    fun newDependencyRequeuesReadyAndUnblocksDependencyBlocked() {
        val dep = task()
        val t = task()
        runTickUntilReady(t)
        assertEquals(TaskState.READY, state.tasks.getValue(t).state)
        ok(tasks.setDependencies(state, t, setOf(dep), clock.now()))
        assertEquals(TaskState.QUEUED, state.tasks.getValue(t).state, "готовая с незавершённой зависимостью — снова в очереди")
        assertTrue(tasks.ready(state, clock.now()).none { it.id == t })
        // Заблокирована упавшей зависимостью: зависимость сняли — снова в очереди и затем готова.
        val bad = task(policy = TaskPolicy(maxRetries = 0))
        val blocked = task(deps = setOf(bad))
        runTickUntilReady(bad)
        ok(tasks.start(state, bad, clock.now()))
        ok(tasks.attemptFailed(state, bad, DiagCode.NETWORK_UNREACHABLE, Retryability.RETRYABLE, "k", clock.now()))
        ok(Outcome.success(tasks.promote(state, clock.now())))
        assertEquals(TaskState.BLOCKED, state.tasks.getValue(blocked).state)
        ok(tasks.setDependencies(state, blocked, emptySet(), clock.now()))
        assertEquals(TaskState.QUEUED, state.tasks.getValue(blocked).state)
        ok(Outcome.success(tasks.promote(state, clock.now())))
        assertEquals(TaskState.READY, state.tasks.getValue(blocked).state)
    }

    @Test
    fun failedDependencyBlocksDependents() {
        val a = task(policy = TaskPolicy(maxRetries = 0))
        val b = task(deps = setOf(a))
        runTickUntilReady(a)
        ok(tasks.start(state, a, clock.now()))
        ok(tasks.attemptFailed(state, a, DiagCode.NETWORK_UNREACHABLE, Retryability.RETRYABLE, "k", clock.now()))
        ok(Outcome.success(tasks.promote(state, clock.now())))
        assertEquals(TaskState.BLOCKED, state.tasks.getValue(b).state)
        assertEquals(DiagCode.DEPENDENCY_FAILED, state.tasks.getValue(b).lastReason)
    }

    @Test
    fun capabilitiesSelectTarget() {
        val gpu = ids.next(IdKinds.TARGET)
        val t = task(caps = setOf("gpu"))
        val plain = task()
        ok(Outcome.success(tasks.promote(state, clock.now())))
        val plan = tasks.plan(state, clock.now(), listOf(Tasks.TargetOffer(target, emptySet(), 5), Tasks.TargetOffer(gpu, setOf("gpu"), 5)))
        assertEquals(gpu, plan.single { it.task == t }.target)
        assertEquals(target, plan.single { it.task == plain }.target)
        // Нет подходящего target — задача ждёт, другие назначаются.
        val noGpu = tasks.plan(state, clock.now(), listOf(Tasks.TargetOffer(target, emptySet(), 5)))
        assertEquals(listOf(plain), noGpu.map { it.task })
    }

    @Test
    fun eventsAndStateSurviveTheJournalFormat() {
        val a = task(Priority.HIGH, caps = setOf("git"), policy = TaskPolicy(maxBudgetUsdCents = 150))
        val b = task(deps = setOf(a))
        ok(tasks.setPriority(state, b, Priority.LOW, manual = 5, clock.now()))
        runTickUntilReady(a)
        ok(tasks.start(state, a, clock.now()))
        ok(tasks.attemptFailed(state, a, DiagCode.NETWORK_UNREACHABLE, Retryability.RETRYABLE, "k", clock.now()))
        ok(tasks.continueLater(state, a, clock.now()).let { if (it is Outcome.Failure) Outcome.success(emptyList()) else it })
        val c = task()
        ok(tasks.addDependency(state, c, b, clock.now()))
        ok(Tasks.setScope(state, c, setOf("src/a.kt", "manual/b.md"), clock.now()))
        val decoded = log.map { TaskCodec.decode(TaskCodec.type(it), it.at, TaskCodec.payload(it))!!.valueOrNull()!! }
        assertEquals(log, decoded)
        assertEquals(state, Tasks.applyAll(TasksState.EMPTY, decoded))
        val json = com.dpsmpx.dpai.core.serialization.CanonicalJson.write(TaskCodec.encodeState(state))
        val parsed = com.dpsmpx.dpai.core.serialization.JsonParser().parse(json).valueOrNull() as com.dpsmpx.dpai.core.serialization.JsonObject
        assertEquals(state, TaskCodec.decodeState(parsed).valueOrNull())
    }

    /** Область задачи (ТЗ, §31): только относительные пути внутри репозитория; снимок v1 читается без неё. */
    @Test
    fun scopeIsValidatedAndOldSnapshotStillReads() {
        val t = task()
        assertEquals(DiagCode.VALUE_INVALID, (Tasks.setScope(state, t, setOf("../etc/passwd"), clock.now()) as Outcome.Failure).diagnostics.first().code)
        assertEquals(DiagCode.VALUE_INVALID, (Tasks.setScope(state, t, setOf("/abs/path"), clock.now()) as Outcome.Failure).diagnostics.first().code)
        ok(Tasks.setScope(state, t, setOf("src/main/A.kt"), clock.now()))
        assertEquals(setOf("src/main/A.kt"), state.tasks.getValue(t).scope)
        assertEquals(emptyList(), ok(Tasks.setScope(state, t, setOf("src/main/A.kt"), clock.now())), "повтор — без события")
        val v2 = TaskCodec.encodeState(state)
        val v1 = com.dpsmpx.dpai.core.serialization.JsonObject(
            v2.fields + ("version" to com.dpsmpx.dpai.core.serialization.JsonNumber.of(1)) +
                ("tasks" to com.dpsmpx.dpai.core.serialization.JsonArray(
                    (v2["tasks"] as com.dpsmpx.dpai.core.serialization.JsonArray).items.map { com.dpsmpx.dpai.core.serialization.JsonObject((it as com.dpsmpx.dpai.core.serialization.JsonObject).fields - "scope") },
                )),
        )
        assertEquals(null, TaskCodec.decodeState(v1).valueOrNull()!!.tasks.getValue(t).scope)
    }

    /** D-73: задача доводится до VERIFIED; с ревью — до ревью. */
    private fun verified(policy: TaskPolicy): TaskId {
        val t = task(policy = policy)
        runTickUntilReady(t)
        ok(tasks.start(state, t, clock.now()))
        ok(tasks.implemented(state, t, clock.now()))
        ok(tasks.recordVerification(state, t, "PASSED", "sha256:ok", clock.now()))
        assertEquals(TaskState.VERIFIED, state.tasks.getValue(t).state)
        return t
    }

    private val session = ids.next(IdKinds.SESSION)

    /** D-73: у TRIVIAL ревью нет; DONE ждёт ревью всех ролей; блокирующее — повтор, новая попытка — ревью заново. */
    @Test
    fun reviewsGateDoneAndBlockingReviewIsAnAttempt() {
        val refused = runCatching { TaskPolicy(taskClass = TaskClass.TRIVIAL, reviews = setOf(ReviewRole.CODE)) }
        assertTrue(refused.isFailure && "простых задач" in refused.exceptionOrNull()!!.message!!)
        val t = verified(TaskPolicy(retryBackoff = 0.minutes, reviews = setOf(ReviewRole.SECURITY, ReviewRole.CODE)))
        assertEquals(listOf(ReviewRole.CODE, ReviewRole.SECURITY), state.tasks.getValue(t).reviewsLeft())
        assertEquals(DiagCode.REVIEW_REQUIRED, (tasks.complete(state, t, clock.now()) as Outcome.Failure).errors.single().code)
        val major = ReviewFinding(FindingSeverity.MAJOR, "нет проверки длины", "src/a.kt", 12, "строка без предела")
        ok(tasks.recordReview(state, t, ReviewRecord(ReviewRole.CODE, ReviewVerdict.PASSED, 1, session, listOf(major)), clock.now()))
        val blocking = ReviewFinding(FindingSeverity.BLOCKING, "секрет в журнале")
        ok(tasks.recordReview(state, t, ReviewRecord(ReviewRole.SECURITY, ReviewVerdict.BLOCKED, 1, session, listOf(blocking)), clock.now()))
        ok(tasks.reviewBlocked(state, t, clock.now()))
        val back = state.tasks.getValue(t)
        assertEquals(TaskState.READY, back.state)
        assertEquals(DiagCode.REVIEW_BLOCKING, back.lastReason)
        assertEquals(1, back.retries, "блокирующее ревью — попытка")
        assertEquals(2, back.reviews.size, "итоги видны до новой попытки")
        ok(tasks.start(state, t, clock.now()))
        assertEquals(emptyMap(), state.tasks.getValue(t).reviews, "новая попытка — ревью заново")
        ok(tasks.implemented(state, t, clock.now()))
        ok(tasks.recordVerification(state, t, "PASSED", "sha256:ok2", clock.now()))
        ok(tasks.recordReview(state, t, ReviewRecord(ReviewRole.CODE, ReviewVerdict.PASSED, 1, session), clock.now()))
        ok(tasks.recordReview(state, t, ReviewRecord(ReviewRole.SECURITY, ReviewVerdict.FAILED, 2, session, reason = "ответ не по схеме"), clock.now()))
        assertEquals(listOf(ReviewRole.SECURITY), state.tasks.getValue(t).reviewsLeft(), "без итога — не пройдено")
        assertEquals(DiagCode.VALUE_INVALID, (tasks.waiveReview(state, t, ReviewRole.SECURITY, "автор", " ", clock.now()) as Outcome.Failure).errors.single().code)
        ok(tasks.waiveReview(state, t, ReviewRole.SECURITY, "автор", "ревью недоступно, проверил сам", clock.now()))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, (tasks.waiveReview(state, t, ReviewRole.CODE, "автор", "x", clock.now()) as Outcome.Failure).errors.single().code, "у прошедшего — не исключение")
        ok(tasks.complete(state, t, clock.now()))
        assertEquals(TaskState.DONE, state.tasks.getValue(t).state)
        // Чужая роль и задача не в VERIFIED — отказ.
        val other = verified(TaskPolicy())
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, (tasks.recordReview(state, other, ReviewRecord(ReviewRole.CODE, ReviewVerdict.PASSED, 1, session), clock.now()) as Outcome.Failure).errors.single().code)
        ok(tasks.complete(state, other, clock.now()))
        assertEquals(DiagCode.INVALID_TRANSITION, (tasks.recordReview(state, t, ReviewRecord(ReviewRole.CODE, ReviewVerdict.PASSED, 1, session), clock.now()) as Outcome.Failure).errors.single().code)
    }

    /** D-73: то же блокирующее ревью раз за разом — BLOCKED (из VERIFIED в FAILED пути нет), решает человек. */
    @Test
    fun repeatedBlockingReviewStopsAsLoop() {
        val t = verified(TaskPolicy(maxRetries = 10, retryBackoff = 0.minutes, reviews = setOf(ReviewRole.CODE)))
        val blocking = ReviewRecord(ReviewRole.CODE, ReviewVerdict.BLOCKED, 1, session, listOf(ReviewFinding(FindingSeverity.BLOCKING, "гонка")))
        repeat(3) { i ->
            if (i > 0) {
                ok(tasks.start(state, t, clock.now()))
                ok(tasks.implemented(state, t, clock.now()))
                ok(tasks.recordVerification(state, t, "PASSED", "sha256:$i", clock.now()))
            }
            ok(tasks.recordReview(state, t, blocking, clock.now()))
            ok(tasks.reviewBlocked(state, t, clock.now()))
        }
        assertEquals(TaskState.BLOCKED, state.tasks.getValue(t).state)
        assertEquals(DiagCode.LOOP_DETECTED, state.tasks.getValue(t).lastReason)
        // Запись ревью строгая: BLOCKED — только с блокирующей находкой, длины ограничены.
        assertTrue(runCatching { ReviewRecord(ReviewRole.CODE, ReviewVerdict.BLOCKED, 1, session) }.isFailure)
        assertTrue(runCatching { ReviewFinding(FindingSeverity.NOTE, "x".repeat(ReviewRecord.MAX_TITLE + 1)) }.isFailure)
    }

    /** D-73: роли в политике и итоги ревью — в событиях и снимке v6; снимок v5 читается без них. */
    @Test
    fun reviewsSurviveTheJournalFormat() {
        val t = verified(TaskPolicy(reviews = setOf(ReviewRole.CODE)))
        val f = ReviewFinding(FindingSeverity.BLOCKING, "SQL без параметров", "db/q.kt", 7, "конкатенация строки запроса")
        ok(tasks.recordReview(state, t, ReviewRecord(ReviewRole.CODE, ReviewVerdict.BLOCKED, 2, session, listOf(f), omitted = 3), clock.now()))
        val decoded = log.map { TaskCodec.decode(TaskCodec.type(it), it.at, TaskCodec.payload(it))!!.valueOrNull()!! }
        assertEquals(log, decoded)
        val json = com.dpsmpx.dpai.core.serialization.CanonicalJson.write(TaskCodec.encodeState(state))
        val parsed = com.dpsmpx.dpai.core.serialization.JsonParser().parse(json).valueOrNull() as com.dpsmpx.dpai.core.serialization.JsonObject
        assertEquals(state, TaskCodec.decodeState(parsed).valueOrNull())
        val plain = task()
        val v6 = TaskCodec.encodeState(TasksState(mapOf(plain to state.tasks.getValue(plain))))
        val v5 = com.dpsmpx.dpai.core.serialization.JsonObject(v6.fields + ("version" to com.dpsmpx.dpai.core.serialization.JsonNumber.of(5)))
        assertEquals(state.tasks.getValue(plain), TaskCodec.decodeState(v5).valueOrNull()!!.tasks.getValue(plain))
    }

    @Test
    fun continuationsAreLimited() {
        val t = task(policy = TaskPolicy(maxContinuations = 2))
        runTickUntilReady(t)
        repeat(2) {
            ok(tasks.start(state, t, clock.now()))
            ok(tasks.continueLater(state, t, clock.now()))
        }
        ok(tasks.start(state, t, clock.now()))
        ok(tasks.continueLater(state, t, clock.now()))
        assertEquals(TaskState.BLOCKED, state.tasks.getValue(t).state)
        assertEquals(DiagCode.CONTINUATION_LIMIT_REACHED, state.tasks.getValue(t).lastReason)
    }

    /** D-77: «Запустить первой» — выше всех открытых и без паузы; только у ждущей запуска; повтор — без событий. */
    @Test
    fun runFirstLiftsAboveAllAndUnholds() {
        val a = task()
        val b = task(priority = Priority.CRITICAL)
        val c = task(priority = Priority.LOW)
        ok(tasks.setPriority(state, b, Priority.CRITICAL, 5, clock.now()))
        ok(tasks.hold(state, c, true, clock.now()))
        ok(Outcome.success(tasks.promote(state, clock.now())))
        ok(tasks.runFirst(state, c, clock.now()))
        assertEquals(6, state.tasks.getValue(c).manualPriority)
        assertEquals(false, state.tasks.getValue(c).held)
        assertEquals(listOf(c, b, a), tasks.ready(state, clock.now()).map { it.id }, "первой — она, дальше — как было")
        assertEquals(emptyList(), ok(tasks.runFirst(state, c, clock.now())), "уже первая — повтор без событий")
        ok(tasks.start(state, a, clock.now()))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, (tasks.runFirst(state, a, clock.now()) as Outcome.Failure).errors.single().code, "идущую — нет")
        val strict = Tasks(SchedulerPolicy(manualPriorityAllowed = false))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, (strict.runFirst(state, b, clock.now()) as Outcome.Failure).errors.single().code, "политика без ручного приоритета — отказ, а не тишина")
    }

    /** D-77: исключённый слот — до запуска; план ставит задачу только туда, где она может пойти; формат v7, v6 читается. */
    @Test
    fun excludedSlotLastsUntilStartAndSurvivesTheJournal() {
        val t = task()
        ok(Outcome.success(tasks.promote(state, clock.now())))
        val slot = ids.next(IdKinds.ACCOUNT)
        ok(tasks.excludeSlot(state, t, slot, clock.now()))
        assertEquals(slot, state.tasks.getValue(t).excludedSlot)
        assertEquals(emptyList(), ok(tasks.excludeSlot(state, t, slot, clock.now())), "повтор — без второго события")
        val other = ids.next(IdKinds.TARGET)
        val offers = listOf(Tasks.TargetOffer(target, emptySet(), 1), Tasks.TargetOffer(other, emptySet(), 1))
        assertEquals(listOf(other), tasks.plan(state, clock.now(), offers) { r, tg -> r.excludedSlot == null || tg != target }.map { it.target })
        val decoded = log.map { TaskCodec.decode(TaskCodec.type(it), it.at, TaskCodec.payload(it))!!.valueOrNull()!! }
        assertEquals(log, decoded)
        val json = TaskCodec.encodeState(state)
        assertEquals(state, TaskCodec.decodeState(json).valueOrNull())
        // Снимок v6 — без поля исключения: читается, исключения нет.
        val records = (json["tasks"] as com.dpsmpx.dpai.core.serialization.JsonArray).items.map { r ->
            com.dpsmpx.dpai.core.serialization.JsonObject((r as com.dpsmpx.dpai.core.serialization.JsonObject).fields - "excludedSlot")
        }
        val v6 = com.dpsmpx.dpai.core.serialization.JsonObject(
            json.fields + ("version" to com.dpsmpx.dpai.core.serialization.JsonNumber.of(6)) + ("tasks" to com.dpsmpx.dpai.core.serialization.JsonArray(records)),
        )
        assertEquals(null, TaskCodec.decodeState(v6).valueOrNull()!!.tasks.getValue(t).excludedSlot, "в v6 исключения нет")
        ok(tasks.start(state, t, clock.now()))
        assertEquals(null, state.tasks.getValue(t).excludedSlot, "запуск снимает исключение")
    }
    @Test fun diagnosticReasonCannotBypassVerificationAndRestartForgetsOldProof() {
        val id = task()
        val unverified = state.copy(tasks = state.tasks + (id to state.tasks.getValue(id).copy(state = TaskState.COMPLETION_UNVERIFIED)))
        assertIs<Outcome.Failure>(tasks.move(unverified, id, TaskState.DONE, clock.now(), DiagCode.INTERNAL_ERROR))
        val verified = state.copy(tasks = state.tasks + (id to state.tasks.getValue(id).copy(state = TaskState.VERIFIED,
            verification = VerificationRecord("PASSED", "old-proof"))))
        val reopened = Tasks.applyAll(verified, tasks.move(verified, id, TaskState.READY, clock.now()).valueOrNull()!!)
        assertEquals(null, reopened.tasks.getValue(id).verification)
        assertIs<Outcome.Failure>(tasks.move(reopened, id, TaskState.DONE, clock.now(), DiagCode.VERIFICATION_FAILED))
    }

}
