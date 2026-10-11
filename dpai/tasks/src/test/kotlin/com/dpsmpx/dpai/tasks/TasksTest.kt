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
}
