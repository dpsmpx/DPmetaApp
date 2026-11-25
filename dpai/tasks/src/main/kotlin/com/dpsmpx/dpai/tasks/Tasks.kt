package com.dpsmpx.dpai.tasks

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Retryability
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskLifecycle
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.core.state.TransitionMode

/**
 * Задачи и планировщик (`docs/SCHEDULER.md`; ТЗ, §17, §18, §21, §22,
 * §83–§86, §120). Решения — чистые функции: состояние и время → события;
 * состояние меняет только [apply]. Порядок детерминирован (D-22): ключ
 * упорядочивания — целые числа и `TaskId` для развязки равенства.
 */
class Tasks(val policy: SchedulerPolicy = SchedulerPolicy()) {
    fun create(
        state: TasksState,
        id: TaskId,
        title: String,
        objective: String,
        priority: Priority,
        now: Timestamp,
        dependencies: Set<TaskId> = emptySet(),
        capabilities: Set<String> = emptySet(),
        taskPolicy: TaskPolicy = TaskPolicy(),
    ): Outcome<List<TaskEvent>> {
        if (id in state.tasks) return Outcome.failure(DiagCode.VALUE_INVALID, "задача уже есть", "task $id")
        if (title.isBlank() || title.length > 200) return Outcome.failure(DiagCode.VALUE_INVALID, "заголовок — 1…200 знаков", "task $id")
        // Без цели контекст хода не собирается (ContextAssembler): отказ — здесь, а не посреди такта.
        if (objective.isBlank()) return Outcome.failure(DiagCode.VALUE_INVALID, "цель задачи пуста", "task $id")
        if (id in dependencies) return Outcome.failure(DiagCode.DEPENDENCY_CYCLE, "задача зависит от самой себя", "task $id")
        dependencies.firstOrNull { it !in state.tasks }?.let { return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет задачи-зависимости $it", "task $id") }
        return Outcome.success(listOf(TaskEvent.Created(id, title.trim(), objective, priority, dependencies, capabilities, taskPolicy, now)))
    }

    /**
     * Повтор постановки (D-40; аудит, W10): незавершённая задача с тем же
     * заголовком и целью — без различия регистра и пробелов. Вторая такая
     * задача — второй ход с той же работой (двойное нажатие, повтор из
     * оболочки); разные заголовки при одной цели — разные задачи.
     */
    fun duplicateOf(state: TasksState, title: String, objective: String): TaskId? {
        val t = normalize(title)
        val o = normalize(objective)
        return state.tasks.values.filter { !it.terminal && normalize(it.title) == t && normalize(it.objective) == o }.minByOrNull { it.id.value }?.id
    }

    private fun normalize(s: String) = s.trim().lowercase().replace(SPACES, " ")

    /** Новая зависимость; если она замыкает цикл — `DEPENDENCY_CYCLE` с перечнем задач цикла (ТЗ, §120). */
    fun addDependency(state: TasksState, task: TaskId, dependsOn: TaskId, now: Timestamp): Outcome<List<TaskEvent>> {
        val t = state.tasks[task] ?: return notFound(task)
        if (dependsOn !in state.tasks) return notFound(dependsOn)
        if (dependsOn in t.dependencies) return Outcome.success(emptyList())
        val graph = state.tasks.mapValues { it.value.dependencies } + (task to (t.dependencies + dependsOn))
        findCycle(graph)?.let { cycle ->
            return Outcome.failure(DiagCode.DEPENDENCY_CYCLE, "цикл зависимостей: ${cycle.joinToString(" → ")}", "task $task")
        }
        return Outcome.success(listOf(TaskEvent.DependenciesChanged(task, t.dependencies + dependsOn, now)))
    }

    /**
     * Зависимости целиком (D-42, п. 1: изменить у задачи, которая ещё не
     * идёт): несуществующая задача, сама задача и цикл — отказ.
     */
    fun setDependencies(state: TasksState, task: TaskId, dependencies: Set<TaskId>, now: Timestamp): Outcome<List<TaskEvent>> {
        val t = state.tasks[task] ?: return notFound(task)
        if (t.state !in EDITABLE) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "зависимости меняют до запуска: задача ${t.state}", "task $task")
        if (task in dependencies) return Outcome.failure(DiagCode.DEPENDENCY_CYCLE, "задача зависит от самой себя", "task $task")
        dependencies.firstOrNull { it !in state.tasks }?.let { return notFound(it) }
        if (dependencies == t.dependencies) return Outcome.success(emptyList())
        val graph = state.tasks.mapValues { it.value.dependencies } + (task to dependencies)
        findCycle(graph)?.let { cycle ->
            return Outcome.failure(DiagCode.DEPENDENCY_CYCLE, "цикл зависимостей: ${cycle.joinToString(" → ")}", "task $task")
        }
        val changed = TaskEvent.DependenciesChanged(task, dependencies, now)
        // Готовность решает promote по зависимостям (`docs/SCHEDULER.md`, §2): готовая задача с новой
        // незавершённой зависимостью и заблокированная упавшей зависимостью — снова в очередь, иначе
        // готовая начала бы раньше зависимости (ревью 18 октября 2026).
        val requeue = when (t.state) {
            TaskState.READY -> dependencies.any { state.tasks.getValue(it).state != TaskState.DONE }
            TaskState.BLOCKED -> t.lastReason == DiagCode.DEPENDENCY_FAILED
            else -> false
        }
        if (!requeue) return Outcome.success(listOf(changed))
        return when (val m = move(apply(state, changed), task, TaskState.QUEUED, now)) {
            is Outcome.Failure -> m
            is Outcome.Success -> Outcome.success(listOf(changed) + m.value)
        }
    }

    fun enqueue(state: TasksState, id: TaskId, now: Timestamp): Outcome<List<TaskEvent>> = move(state, id, TaskState.QUEUED, now)

    /**
     * Политика задачи — только пока она не идёт (D-42): у идущей задачи
     * ход уже запущен с прежней моделью и бюджетом, и молча поменять их
     * нельзя (D-05). Конечная — тоже нет: менять нечего.
     */
    fun setPolicy(state: TasksState, id: TaskId, policy: TaskPolicy, now: Timestamp): Outcome<List<TaskEvent>> {
        val t = state.tasks[id] ?: return notFound(id)
        if (t.state !in EDITABLE) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "политику меняют до запуска: задача ${t.state}", "task $id")
        if (t.policy == policy) return Outcome.success(emptyList())
        return Outcome.success(listOf(TaskEvent.PolicyChanged(id, policy, now)))
    }

    /** Спрятать завершённую задачу в архив или вернуть (D-42); незавершённую прятать нельзя — она ещё работает. */
    fun archive(state: TasksState, id: TaskId, archived: Boolean, now: Timestamp): Outcome<List<TaskEvent>> {
        val t = state.tasks[id] ?: return notFound(id)
        if (archived && !t.terminal) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "в архив — только завершённую: задача ${t.state}", "task $id")
        if (t.archived == archived) return Outcome.success(emptyList())
        return Outcome.success(listOf(TaskEvent.ArchiveChanged(id, archived, now)))
    }

    /** Удержать или отпустить задачу (D-42); конечную — нельзя. */
    fun hold(state: TasksState, id: TaskId, held: Boolean, now: Timestamp): Outcome<List<TaskEvent>> {
        val t = state.tasks[id] ?: return notFound(id)
        if (t.terminal) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "задача завершена: ${t.state}", "task $id")
        if (t.held == held) return Outcome.success(emptyList())
        return Outcome.success(listOf(TaskEvent.HoldChanged(id, held, now)))
    }

    /**
     * Повтор человеком (D-42): упавшая — снова в очередь (явный переход
     * `FAILED → QUEUED`), отменённая — в очередь через `BACKLOG`,
     * заблокированная — `READY`; счётчики повторов, продолжений и петель
     * сброшены: решение человека — новый счёт, а не продолжение старого.
     */
    fun retry(state: TasksState, id: TaskId, now: Timestamp): Outcome<List<TaskEvent>> {
        val t = state.tasks[id] ?: return notFound(id)
        val reset = TaskEvent.CountersReset(id, now)
        val s1 = apply(state, reset)
        val moves: Outcome<List<TaskEvent>> = when (t.state) {
            TaskState.FAILED -> move(s1, id, TaskState.QUEUED, now, mode = TransitionMode.EXPLICIT)
            TaskState.CANCELLED -> move(s1, id, TaskState.BACKLOG, now, mode = TransitionMode.EXPLICIT).let { r ->
                if (r is Outcome.Failure) r else move(applyAll(s1, r.valueOrNull()!!), id, TaskState.QUEUED, now).let { q -> if (q is Outcome.Failure) q else Outcome.success(r.valueOrNull()!! + q.valueOrNull()!!) }
            }
            // Готова, только если все зависимости выполнены; иначе — в очередь, их ждёт promote.
            TaskState.BLOCKED -> move(s1, id, if (t.dependencies.all { state.tasks[it]?.state == TaskState.DONE }) TaskState.READY else TaskState.QUEUED, now)
            else -> return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "повторяют упавшую, отменённую или заблокированную: задача ${t.state}", "task $id")
        }
        return moves.let { r -> if (r is Outcome.Failure) r else Outcome.success(listOf(reset) + r.valueOrNull()!!) }
    }

    /**
     * «Запустить первой» (D-77; ТЗ, §73): ручной приоритет выше всех открытых
     * задач и снятие паузы. Только у задачи, которая ещё не идёт; пределы
     * подписки и аренда — как у всех: задача идёт следующей, когда есть слот.
     */
    fun runFirst(state: TasksState, id: TaskId, now: Timestamp): Outcome<List<TaskEvent>> {
        val t = state.tasks[id] ?: return notFound(id)
        if (t.state !in WAITS_TO_RUN) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "«Запустить первой» — у задачи в очереди: задача ${t.state}", "task $id")
        if (!policy.manualPriorityAllowed) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "политика планировщика не разрешает ручной приоритет", "task $id")
        val top = state.tasks.values.filter { !it.terminal && it.id != id }.maxOfOrNull { it.manualPriority ?: 0 } ?: 0
        val events = mutableListOf<TaskEvent>()
        if (t.manualPriority == null || t.manualPriority <= top) events += TaskEvent.PriorityChanged(id, t.priority, top + 1, now)
        if (t.held) events += TaskEvent.HoldChanged(id, false, now)
        return Outcome.success(events)
    }

    /** Перенести задачу со слота (D-77): следующая сессия на него не идёт; повтор — без второго события. */
    fun excludeSlot(state: TasksState, id: TaskId, slot: AccountId, now: Timestamp): Outcome<List<TaskEvent>> {
        val t = state.tasks[id] ?: return notFound(id)
        if (t.terminal) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "задача завершена: ${t.state}", "task $id")
        if (t.excludedSlot == slot) return Outcome.success(emptyList())
        return Outcome.success(listOf(TaskEvent.SlotExcluded(id, slot, now)))
    }

    fun setPriority(state: TasksState, id: TaskId, priority: Priority, manual: Int?, now: Timestamp): Outcome<List<TaskEvent>> {
        val t = state.tasks[id] ?: return notFound(id)
        if (t.priority == priority && t.manualPriority == manual) return Outcome.success(emptyList())
        return Outcome.success(listOf(TaskEvent.PriorityChanged(id, priority, manual, now)))
    }

    /**
     * Такт планировщика: задачи в очереди, у которых все зависимости в
     * `DONE`, — `READY`; у которых зависимость упала или отменена —
     * `BLOCKED` (`DEPENDENCY_FAILED`).
     */
    fun promote(state: TasksState, now: Timestamp): List<TaskEvent> {
        val changes = ArrayList<TaskEvent>()
        for (task in state.tasks.values) {
            if (task.state != TaskState.QUEUED) continue
            var ready = true
            var failed = false
            for (id in task.dependencies) {
                val dependency = state.tasks.getValue(id).state
                if (dependency != TaskState.DONE) ready = false
                if (dependency == TaskState.FAILED || dependency == TaskState.CANCELLED) failed = true
            }
            if (failed) changes += TaskEvent.StateChanged(task.id, task.state, TaskState.BLOCKED, DiagCode.DEPENDENCY_FAILED, null, now)
            else if (ready) changes += TaskEvent.StateChanged(task.id, task.state, TaskState.READY, null, task.notBefore, now)
        }
        // Порядок событий прежний; ждать зависимостей не требует сортировки всей очереди.
        changes.sortBy { it.task.value }
        return changes
    }

    /** Ключ упорядочивания (`docs/SCHEDULER.md`, §3): меньше — раньше. */
    fun orderKey(state: TasksState, t: TaskRecord, now: Timestamp): List<Comparable<*>> = orderKey(t, now, state.blockingImpact(t.id))

    private fun orderKey(t: TaskRecord, now: Timestamp, impact: Int): List<Comparable<*>> {
        val waited = t.queuedAt?.let { now - it }?.takeIf { it.isPositive() }
        val steps = waited?.let { (it.inWholeMilliseconds / policy.agingStep.inWholeMilliseconds).toInt() } ?: 0
        val level = (t.priority.ordinal - steps).coerceAtLeast(0)
        val manual = if (policy.manualPriorityAllowed && t.manualPriority != null) -t.manualPriority.toLong() else Long.MAX_VALUE
        return listOf(manual, level, -impact, t.queuedAt?.epochMillis ?: Long.MAX_VALUE, t.id.value)
    }

    /**
     * Готовые к назначению задачи в порядке планировщика (пауза перед
     * повтором учтена). Влияние на блокировку и ключи считаются один раз за
     * вызов, а не при каждом сравнении (этап 17: O(n) вместо O(n² log n)).
     */
    fun ready(state: TasksState, now: Timestamp): List<TaskRecord> {
        val impact = HashMap<TaskId, Int>()
        for (t in state.tasks.values) if (!t.terminal) for (d in t.dependencies) impact.merge(d, 1, Int::plus)
        return state.tasks.values
            .filter { it.state == TaskState.READY && !it.held && (it.notBefore == null || it.notBefore <= now) }
            .map { it to orderKey(it, now, impact[it.id] ?: 0) }
            .sortedWith { a, b -> compareKeys(a.second, b.second) }
            .map { it.first }
    }

    /** Свободная ёмкость target: сколько сессий ещё можно и что он умеет (ТЗ, §51). */
    data class TargetOffer(val target: ExecutionTargetId, val capabilities: Set<String>, val free: Int)

    data class Assignment(val task: TaskId, val target: ExecutionTargetId)

    /**
     * План назначений: задачи по порядку, каждой — первый target с
     * нужными возможностями и свободным местом. Задача без подходящего
     * target ждёт, не занимая места других (ТЗ, §50, §51, §85).
     */
    /**
     * [accepts] — может ли задача пойти на target сверх возможностей:
     * например, у перенесённой задачи (D-77) там есть слот, кроме
     * исключённого.
     */
    fun plan(
        state: TasksState,
        now: Timestamp,
        offers: List<TargetOffer>,
        accepts: (TaskRecord, ExecutionTargetId) -> Boolean = { _, _ -> true },
    ): List<Assignment> {
        val free = offers.associate { it.target to it.free }.toMutableMap()
        val out = mutableListOf<Assignment>()
        for (t in ready(state, now)) {
            val o = offers.firstOrNull { (free[it.target] ?: 0) > 0 && it.capabilities.containsAll(t.capabilities) && accepts(t, it.target) } ?: continue
            free[o.target] = free.getValue(o.target) - 1
            out += Assignment(t.id, o.target)
        }
        return out
    }

    fun start(state: TasksState, id: TaskId, now: Timestamp): Outcome<List<TaskEvent>> = move(state, id, TaskState.RUNNING, now)

    /** Исполнитель сообщил о завершении: заявление, не результат (D-23). */
    fun implemented(state: TasksState, id: TaskId, now: Timestamp): Outcome<List<TaskEvent>> = move(state, id, TaskState.IMPLEMENTED, now)

    /**
     * Ход кончился без результата (предел, handoff): задача ждёт следующего
     * исполнителя (ТЗ, §28). Продолжений больше политики —
     * `CONTINUATION_LIMIT_REACHED`, задача `BLOCKED`.
     */
    fun continueLater(state: TasksState, id: TaskId, now: Timestamp): Outcome<List<TaskEvent>> {
        val t = state.tasks[id] ?: return notFound(id)
        if (t.continuations >= t.policy.maxContinuations) {
            return move(state, id, TaskState.BLOCKED, now, DiagCode.CONTINUATION_LIMIT_REACHED)
        }
        return move(state, id, TaskState.READY, now).let { r ->
            if (r is Outcome.Success) Outcome.success(listOf(TaskEvent.Continued(id, now)) + r.value) else r
        }
    }

    /**
     * Неудачная попытка (ТЗ, §21, §22; `docs/SCHEDULER.md`, §5): повтор — по
     * классу причины; один и тот же ключ отказа [loopKey] (задача + хеш
     * промпта + хеш состояния + код) [SchedulerPolicy.loopThreshold] раз —
     * `LOOP_DETECTED`, ресурсы дальше не тратятся.
     */
    fun attemptFailed(
        state: TasksState,
        id: TaskId,
        code: DiagCode,
        retryability: Retryability,
        loopKey: String,
        now: Timestamp,
    ): Outcome<List<TaskEvent>> {
        val t = state.tasks[id] ?: return notFound(id)
        val failed = TaskEvent.AttemptFailed(id, loopKey, code, now)
        val s1 = apply(state, failed)
        val attempt = t.retries + 1
        val sameKey = (t.failures[loopKey] ?: 0) + 1
        val next: Outcome<List<TaskEvent>> = when {
            sameKey >= policy.loopThreshold -> move(s1, id, TaskState.BLOCKED, now, DiagCode.LOOP_DETECTED)
            code == DiagCode.CLAUDE_AUTH_REQUIRED -> move(s1, id, TaskState.BLOCKED, now, code)
            retryability == Retryability.NON_RETRYABLE -> move(s1, id, TaskState.BLOCKED, now, code)
            retryability == Retryability.UNKNOWN && attempt > minOf(1, t.policy.maxRetries) -> move(s1, id, TaskState.BLOCKED, now, code)
            attempt > t.policy.maxRetries -> move(s1, id, TaskState.FAILED, now, DiagCode.RETRY_LIMIT_REACHED)
            else -> {
                val backoff = t.policy.retryBackoff * (1 shl (attempt - 1).coerceAtMost(10))
                move(s1, id, TaskState.READY, now, code, notBefore = now + backoff)
            }
        }
        return next.let { r -> if (r is Outcome.Success) Outcome.success(listOf(failed) + r.value) else r }
    }

    /**
     * Итог проверки (ТЗ, §32; D-23): из `IMPLEMENTED` или `VERIFYING` —
     * `PASSED` → `VERIFIED`, `FAILED` → `READY` (нужна работа),
     * `INCOMPLETE` → `COMPLETION_UNVERIFIED`. Из `IMPLEMENTED` сначала
     * `VERIFYING`.
     */
    fun recordVerification(state: TasksState, id: TaskId, verdict: String, reportHash: String?, now: Timestamp): Outcome<List<TaskEvent>> {
        val t = state.tasks[id] ?: return notFound(id)
        val to = when (verdict) {
            "PASSED" -> TaskState.VERIFIED
            "FAILED" -> TaskState.READY
            "INCOMPLETE" -> TaskState.COMPLETION_UNVERIFIED
            else -> return Outcome.failure(DiagCode.VALUE_INVALID, "итог проверки — PASSED, FAILED или INCOMPLETE", "task $id")
        }
        val events = mutableListOf<TaskEvent>()
        var s = state
        if (t.state == TaskState.IMPLEMENTED || t.state == TaskState.COMPLETION_UNVERIFIED) {
            when (val v = move(s, id, TaskState.VERIFYING, now)) {
                is Outcome.Failure -> return v
                is Outcome.Success -> { events += v.value; s = applyAll(s, v.value) }
            }
        }
        val record = TaskEvent.VerificationRecorded(id, VerificationRecord(verdict, reportHash), now)
        if (verdict == "FAILED") {
            return when (val f = verificationFailed(apply(s, record), t, id, reportHash, now)) {
                is Outcome.Failure -> f
                is Outcome.Success -> Outcome.success(events + record + f.value)
            }
        }
        val reason = when (verdict) {
            "FAILED" -> DiagCode.VERIFICATION_FAILED
            "INCOMPLETE" -> DiagCode.VERIFICATION_STEP_UNAVAILABLE
            else -> null
        }
        return when (val m = move(apply(s, record), id, to, now, reason)) {
            is Outcome.Failure -> m
            is Outcome.Success -> Outcome.success(events + record + m.value)
        }
    }

    /**
     * Проверка не прошла — неудачная попытка, а не бесплатный повтор (ревью
     * 8 октября 2026): счётчик попыток, пауза перед следующим ходом и ключ
     * петли по хешу отчёта. Тот же провал [SchedulerPolicy.loopThreshold] раз
     * — `LOOP_DETECTED` (`BLOCKED`), предел попыток — `FAILED`. Раньше каждый
     * провал сразу запускал новый ход Claude с тем же промптом, расходуя
     * подписку без конца, если проверка падала стабильно.
     */
    private fun verificationFailed(s: TasksState, t: TaskRecord, id: TaskId, reportHash: String?, now: Timestamp): Outcome<List<TaskEvent>> {
        val loopKey = "verification:${reportHash ?: "?"}"
        val failed = TaskEvent.AttemptFailed(id, loopKey, DiagCode.VERIFICATION_FAILED, now)
        val s1 = apply(s, failed)
        val attempt = t.retries + 1
        if (attempt > t.policy.maxRetries) {
            return move(s1, id, TaskState.FAILED, now, DiagCode.RETRY_LIMIT_REACHED).let { r ->
                if (r is Outcome.Success) Outcome.success(listOf(failed) + r.value) else r
            }
        }
        val backoff = t.policy.retryBackoff * (1 shl (attempt - 1).coerceAtMost(10))
        val ready = when (val r = move(s1, id, TaskState.READY, now, DiagCode.VERIFICATION_FAILED, notBefore = now + backoff)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if ((t.failures[loopKey] ?: 0) + 1 < policy.loopThreshold) return Outcome.success(listOf(failed) + ready)
        return when (val b = move(applyAll(s1, ready), id, TaskState.BLOCKED, now, DiagCode.LOOP_DETECTED)) {
            is Outcome.Failure -> b
            is Outcome.Success -> Outcome.success(listOf(failed) + ready + b.value)
        }
    }

    /**
     * Завершение (D-23; инвариант `docs/DATA_MODEL.md`, §5): `VERIFIED` —
     * только с записанной успешной проверкой; `COMPLETION_UNVERIFIED` —
     * только с исключением политики: кто и почему ([waiver]).
     */
    fun complete(state: TasksState, id: TaskId, now: Timestamp, waiver: Pair<String, String>? = null): Outcome<List<TaskEvent>> {
        val t = state.tasks[id] ?: return notFound(id)
        return when (t.state) {
            TaskState.VERIFIED -> when {
                t.verification?.verdict != "PASSED" -> Outcome.failure(DiagCode.VERIFICATION_REQUIRED, "нет записанной успешной проверки", "task $id")
                // Ревью политики (D-73) — шаг проверки: без его итога DONE нет.
                t.reviewsLeft().isNotEmpty() -> Outcome.failure(DiagCode.REVIEW_REQUIRED, "ждёт ревью: ${t.reviewsLeft().joinToString { it.name }}", "task $id")
                else -> move(state, id, TaskState.DONE, now)
            }
            TaskState.COMPLETION_UNVERIFIED -> {
                val (who, why) = waiver ?: return Outcome.failure(DiagCode.VERIFICATION_REQUIRED, "без проверки DONE — только с исключением политики", "task $id")
                if (who.isBlank() || why.isBlank()) return Outcome.failure(DiagCode.VALUE_INVALID, "у исключения — кто и почему", "task $id")
                val waived = TaskEvent.VerificationRecorded(id, VerificationRecord("WAIVED", t.verification?.reportHash, who, why), now)
                move(apply(state, waived), id, TaskState.DONE, now, DiagCode.VERIFICATION_WAIVED).let { r ->
                    if (r is Outcome.Success) Outcome.success(listOf(waived) + r.value) else r
                }
            }
            else -> Outcome.failure(DiagCode.INVALID_TRANSITION, "в DONE — из VERIFIED или COMPLETION_UNVERIFIED, а задача ${t.state}", "task $id")
        }
    }

    /**
     * Итог ревью роли (D-73): только у `VERIFIED` и только роли из
     * политики задачи. Блокирующий итог сам задачу не двигает — это
     * [reviewBlocked], когда ревью всех ролей кончились.
     */
    fun recordReview(state: TasksState, id: TaskId, record: ReviewRecord, now: Timestamp): Outcome<List<TaskEvent>> {
        val t = state.tasks[id] ?: return notFound(id)
        if (t.state != TaskState.VERIFIED) return Outcome.failure(DiagCode.INVALID_TRANSITION, "ревью — у проверенной задачи, а эта ${t.state}", "task $id")
        if (record.role !in t.policy.reviews) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "ревью ${record.role} нет в политике задачи", "task $id")
        return Outcome.success(listOf(TaskEvent.ReviewRecorded(id, record, now)))
    }

    /**
     * Принять ревью роли без итога (D-73, п. 3): решение человека — кто и
     * почему; только роли, у которых итога нет или он `FAILED`.
     */
    fun waiveReview(state: TasksState, id: TaskId, role: ReviewRole, by: String, why: String, now: Timestamp): Outcome<List<TaskEvent>> {
        val t = state.tasks[id] ?: return notFound(id)
        if (by.isBlank() || why.isBlank()) return Outcome.failure(DiagCode.VALUE_INVALID, "у исключения — кто и почему", "task $id")
        val last = t.reviews[role]
        if (last != null && last.verdict != ReviewVerdict.FAILED) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "у ревью $role уже есть итог ${last.verdict}", "task $id")
        val record = ReviewRecord(role, ReviewVerdict.WAIVED, last?.attempt ?: 1, last?.session, by = by.take(ReviewRecord.MAX_TITLE), reason = why.take(ReviewRecord.MAX_DETAIL))
        return recordReview(state, id, record, now)
    }

    /**
     * Ревью нашло блокирующее (D-73, п. 3): попытка засчитывается, как у
     * проваленной проверки, задача — снова `READY` после паузы. Попытки
     * кончились или тот же итог повторяется — `BLOCKED`: решает человек
     * (из `VERIFIED` в `FAILED` пути нет, а молча бросать работу нельзя).
     */
    fun reviewBlocked(state: TasksState, id: TaskId, now: Timestamp): Outcome<List<TaskEvent>> {
        val t = state.tasks[id] ?: return notFound(id)
        val roles = t.reviews.values.filter { it.verdict == ReviewVerdict.BLOCKED }.map { it.role }.sorted()
        if (t.state != TaskState.VERIFIED || roles.isEmpty()) return Outcome.failure(DiagCode.INVALID_TRANSITION, "нет блокирующего ревью у проверенной задачи", "task $id")
        val loopKey = "review:${roles.joinToString(",")}"
        val failed = TaskEvent.AttemptFailed(id, loopKey, DiagCode.REVIEW_BLOCKING, now)
        val s1 = apply(state, failed)
        val attempt = t.retries + 1
        val backoff = t.policy.retryBackoff * (1 shl (attempt - 1).coerceAtMost(10))
        val ready = when (val r = move(s1, id, TaskState.READY, now, DiagCode.REVIEW_BLOCKING, notBefore = now + backoff)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val stop = when {
            attempt > t.policy.maxRetries -> DiagCode.RETRY_LIMIT_REACHED
            (t.failures[loopKey] ?: 0) + 1 >= policy.loopThreshold -> DiagCode.LOOP_DETECTED
            else -> return Outcome.success(listOf(failed) + ready)
        }
        return when (val b = move(applyAll(s1, ready), id, TaskState.BLOCKED, now, stop)) {
            is Outcome.Failure -> b
            is Outcome.Success -> Outcome.success(listOf(failed) + ready + b.value)
        }
    }

    /** Явная операция пользователя (D-11): повтор упавшей, возобновление отменённой, переоткрытие. */
    fun explicit(state: TasksState, id: TaskId, to: TaskState, now: Timestamp): Outcome<List<TaskEvent>> =
        move(state, id, to, now, mode = TransitionMode.EXPLICIT)

    fun move(
        state: TasksState,
        id: TaskId,
        to: TaskState,
        now: Timestamp,
        reason: DiagCode? = null,
        notBefore: Timestamp? = null,
        mode: TransitionMode = TransitionMode.ORDINARY,
    ): Outcome<List<TaskEvent>> {
        val t = state.tasks[id] ?: return notFound(id)
        if (to == TaskState.DONE && t.state != TaskState.DONE) {
            val verified = t.state == TaskState.VERIFIED && t.verification?.verdict == "PASSED" && t.reviewsLeft().isEmpty()
            val waived = t.state == TaskState.COMPLETION_UNVERIFIED && reason == DiagCode.VERIFICATION_WAIVED &&
            t.verification?.let { it.verdict == "WAIVED" && !it.waivedBy.isNullOrBlank() && !it.reason.isNullOrBlank() } == true
            if (!verified && !waived) {
                return Outcome.failure(DiagCode.VERIFICATION_REQUIRED, "в DONE — через complete: нужна проверка или исключение", "task $id")
            }
        }
        return when (val r = TaskLifecycle.table.transition(t.state, to, mode, "task $id")) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(if (r.value.changed) listOf(TaskEvent.StateChanged(id, t.state, to, reason, notBefore, now)) else emptyList())
        }
    }

    private fun notFound(id: TaskId) = Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой задачи", "task $id")

    companion object {
        private val SPACES = Regex("\\s+")

        /** Где политику задачи ещё можно менять: ход не идёт и не начат. */
        /** Ещё не идёт и ждёт запуска — у неё есть смысл «Запустить первой» (D-77). */
        private val WAITS_TO_RUN = setOf(TaskState.QUEUED, TaskState.READY, TaskState.WAITING)
        private val EDITABLE = setOf(TaskState.BACKLOG, TaskState.QUEUED, TaskState.READY, TaskState.WAITING, TaskState.BLOCKED, TaskState.FAILED, TaskState.CANCELLED)

        fun apply(state: TasksState, e: TaskEvent): TasksState = applyAll(state, listOf(e))

        /** Одна приватная копия карты на пачку; исходное состояние не меняется даже при отказе перехода. */
        fun applyAll(state: TasksState, events: List<TaskEvent>): TasksState {
            if (events.isEmpty()) return state
            val tasks = state.tasks.toMutableMap()
            for (e in events) applyTo(tasks, e)
            return TasksState(tasks)
        }

        private fun applyTo(tasks: MutableMap<TaskId, TaskRecord>, e: TaskEvent) {
            when (e) {
                is TaskEvent.Created -> tasks[e.task] = TaskRecord(e.task, e.title, e.objective, e.priority, e.dependencies, e.capabilities, e.policy, e.at)
                is TaskEvent.DependenciesChanged -> tasks.computeIfPresent(e.task) { _, t -> t.copy(dependencies = e.dependencies, updatedAt = e.at) }
                is TaskEvent.StateChanged -> tasks.computeIfPresent(e.task) { _, t ->
                    check(TaskLifecycle.table.isAllowed(t.state, e.to, TransitionMode.EXPLICIT)) { "задача ${e.task}: ${t.state} → ${e.to}" }
                    t.copy(
                        state = e.to,
                        // Положительное доказательство устаревает; прежний провал остаётся контекстом следующего хода.
                        verification = if ((e.to == TaskState.QUEUED || e.to == TaskState.READY || e.to == TaskState.RUNNING) &&
                            (t.verification?.verdict == "PASSED" || t.verification?.verdict == "WAIVED")) null else t.verification,
                        // Снова в работе (повтор, переоткрытие) — из архива: прятать идущее нельзя.
                        archived = t.archived && e.to in TaskLifecycle.table.terminal,
                        updatedAt = e.at,
                        lastReason = e.reason ?: t.lastReason,
                        notBefore = e.notBefore,
                        queuedAt = if (e.to == TaskState.QUEUED && e.from == TaskState.BACKLOG) e.at else t.queuedAt,
                        // Новая попытка — ревью заново (D-73): итог прошлой попытки к новой работе не относится.
                        reviews = if (e.to == TaskState.RUNNING) emptyMap() else t.reviews,
                        // Запуск — уже на другом слоте (D-77): исключение своё отработало.
                        excludedSlot = if (e.to == TaskState.RUNNING) null else t.excludedSlot,
                    )
                }
                is TaskEvent.PriorityChanged -> tasks.computeIfPresent(e.task) { _, t -> t.copy(priority = e.priority, manualPriority = e.manual, updatedAt = e.at) }
                is TaskEvent.AttemptFailed -> tasks.computeIfPresent(e.task) { _, t ->
                    t.copy(retries = t.retries + 1, failures = t.failures + (e.loopKey to (t.failures[e.loopKey] ?: 0) + 1), lastReason = e.code, updatedAt = e.at)
                }
                is TaskEvent.Continued -> tasks.computeIfPresent(e.task) { _, t -> t.copy(continuations = t.continuations + 1, updatedAt = e.at) }
                is TaskEvent.VerificationRecorded -> tasks.computeIfPresent(e.task) { _, t -> t.copy(verification = e.record, updatedAt = e.at) }
                // Пустая область — «без области» (как при создании), а не пустой список (ревью 18 октября 2026).
                is TaskEvent.ScopeChanged -> tasks.computeIfPresent(e.task) { _, t -> t.copy(scope = e.scope.ifEmpty { null }, updatedAt = e.at) }
                is TaskEvent.PolicyChanged -> tasks.computeIfPresent(e.task) { _, t -> t.copy(policy = e.policy, updatedAt = e.at) }
                is TaskEvent.HoldChanged -> tasks.computeIfPresent(e.task) { _, t -> t.copy(held = e.held, updatedAt = e.at) }
                is TaskEvent.CountersReset -> tasks.computeIfPresent(e.task) { _, t -> t.copy(retries = 0, continuations = 0, failures = emptyMap(), updatedAt = e.at) }
                is TaskEvent.ArchiveChanged -> tasks.computeIfPresent(e.task) { _, t -> t.copy(archived = e.archived, updatedAt = e.at) }
                is TaskEvent.ReviewRecorded -> tasks.computeIfPresent(e.task) { _, t -> t.copy(reviews = t.reviews + (e.record.role to e.record), updatedAt = e.at) }
                is TaskEvent.SlotExcluded -> tasks.computeIfPresent(e.task) { _, t -> t.copy(excludedSlot = e.slot, updatedAt = e.at) }
            }
        }

        private val SCOPE_PATH = Regex("[A-Za-z0-9._@+-]+(/[A-Za-z0-9._@+-]+)*")

        /** Путь области (ТЗ, §31): относительный внутри репозитория, без `..`, `.` и абсолютного начала. */
        fun validScopePath(p: String): Boolean = SCOPE_PATH.matches(p) && p.split('/').none { it == ".." || it == "." }

        /**
         * Заявить область задачи (ТЗ, §31): относительные пути внутри
         * репозитория, без `..` и абсолютных путей, не больше 500.
         */
        fun setScope(state: TasksState, task: TaskId, scope: Set<String>, now: Timestamp): Outcome<List<TaskEvent>> {
            val t = state.tasks[task] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой задачи", "task $task")
            if (t.terminal) return Outcome.failure(DiagCode.INVALID_TRANSITION, "задача завершена", "task $task")
            // Область меняют до запуска (D-42, п. 1): у идущего хода она уже в его контексте.
            if (t.state !in EDITABLE) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "область меняют до запуска: задача ${t.state}", "task $task")
            val bad = scope.firstOrNull { !validScopePath(it) }
            if (bad != null || scope.size > 500) return Outcome.failure(DiagCode.VALUE_INVALID, "область — относительные пути внутри репозитория, не больше 500", "task $task")
            if (t.scope.orEmpty() == scope) return Outcome.success(emptyList())
            return Outcome.success(listOf(TaskEvent.ScopeChanged(task, scope, now)))
        }

        /** Цикл в графе «задача → зависимости» или `null`; перечень — задачи цикла по порядку. */
        fun findCycle(graph: Map<TaskId, Set<TaskId>>): List<TaskId>? {
            val color = HashMap<TaskId, Int>()
            val path = ArrayList<TaskId>()
            fun dfs(v: TaskId): List<TaskId>? {
                color[v] = 1
                path += v
                for (w in graph[v].orEmpty().sortedBy { it.value }) {
                    when (color[w] ?: 0) {
                        1 -> return path.subList(path.indexOf(w), path.size).toList() + w
                        0 -> dfs(w)?.let { return it }
                    }
                }
                path.removeAt(path.size - 1)
                color[v] = 2
                return null
            }
            for (v in graph.keys.sortedBy { it.value }) if ((color[v] ?: 0) == 0) dfs(v)?.let { return it }
            return null
        }

        @Suppress("UNCHECKED_CAST") // ключи сравниваются поэлементно, элементы одного места — одного типа
        private fun compareKeys(a: List<Comparable<*>>, b: List<Comparable<*>>): Int {
            for (i in a.indices) {
                val c = (a[i] as Comparable<Any>).compareTo(b[i])
                if (c != 0) return c
            }
            return 0
        }
    }
}
