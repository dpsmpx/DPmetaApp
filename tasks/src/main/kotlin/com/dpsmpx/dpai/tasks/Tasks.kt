package com.dpsmpx.dpai.tasks

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Retryability
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

    private fun normalize(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

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

    fun enqueue(state: TasksState, id: TaskId, now: Timestamp): Outcome<List<TaskEvent>> = move(state, id, TaskState.QUEUED, now)

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
    fun promote(state: TasksState, now: Timestamp): List<TaskEvent> =
        state.tasks.values.filter { it.state == TaskState.QUEUED }.sortedBy { it.id.value }.mapNotNull { t ->
            val deps = t.dependencies.map { state.tasks.getValue(it).state }
            when {
                deps.any { it == TaskState.FAILED || it == TaskState.CANCELLED } ->
                    TaskEvent.StateChanged(t.id, t.state, TaskState.BLOCKED, DiagCode.DEPENDENCY_FAILED, null, now)
                deps.all { it == TaskState.DONE } -> TaskEvent.StateChanged(t.id, t.state, TaskState.READY, null, t.notBefore, now)
                else -> null
            }
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
            .filter { it.state == TaskState.READY && (it.notBefore == null || it.notBefore <= now) }
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
    fun plan(state: TasksState, now: Timestamp, offers: List<TargetOffer>): List<Assignment> {
        val free = offers.associate { it.target to it.free }.toMutableMap()
        val out = mutableListOf<Assignment>()
        for (t in ready(state, now)) {
            val o = offers.firstOrNull { (free[it.target] ?: 0) > 0 && it.capabilities.containsAll(t.capabilities) } ?: continue
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
            TaskState.VERIFIED ->
                if (t.verification?.verdict == "PASSED") {
                    move(state, id, TaskState.DONE, now)
                } else {
                    Outcome.failure(DiagCode.VERIFICATION_REQUIRED, "нет записанной успешной проверки", "task $id")
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
        if (to == TaskState.DONE && t.state != TaskState.DONE && reason == null && t.verification?.verdict != "PASSED") {
            return Outcome.failure(DiagCode.VERIFICATION_REQUIRED, "в DONE — через complete: нужна проверка или исключение", "task $id")
        }
        return when (val r = TaskLifecycle.table.transition(t.state, to, mode, "task $id")) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(if (r.value.changed) listOf(TaskEvent.StateChanged(id, t.state, to, reason, notBefore, now)) else emptyList())
        }
    }

    private fun notFound(id: TaskId) = Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой задачи", "task $id")

    companion object {
        fun apply(state: TasksState, e: TaskEvent): TasksState {
            val tasks = state.tasks.toMutableMap()
            when (e) {
                is TaskEvent.Created -> tasks[e.task] = TaskRecord(e.task, e.title, e.objective, e.priority, e.dependencies, e.capabilities, e.policy, e.at)
                is TaskEvent.DependenciesChanged -> tasks.computeIfPresent(e.task) { _, t -> t.copy(dependencies = e.dependencies, updatedAt = e.at) }
                is TaskEvent.StateChanged -> tasks.computeIfPresent(e.task) { _, t ->
                    check(TaskLifecycle.table.isAllowed(t.state, e.to, TransitionMode.EXPLICIT)) { "задача ${e.task}: ${t.state} → ${e.to}" }
                    t.copy(
                        state = e.to,
                        updatedAt = e.at,
                        lastReason = e.reason ?: t.lastReason,
                        notBefore = e.notBefore,
                        queuedAt = if (e.to == TaskState.QUEUED && e.from == TaskState.BACKLOG) e.at else t.queuedAt,
                    )
                }
                is TaskEvent.PriorityChanged -> tasks.computeIfPresent(e.task) { _, t -> t.copy(priority = e.priority, manualPriority = e.manual, updatedAt = e.at) }
                is TaskEvent.AttemptFailed -> tasks.computeIfPresent(e.task) { _, t ->
                    t.copy(retries = t.retries + 1, failures = t.failures + (e.loopKey to (t.failures[e.loopKey] ?: 0) + 1), lastReason = e.code, updatedAt = e.at)
                }
                is TaskEvent.Continued -> tasks.computeIfPresent(e.task) { _, t -> t.copy(continuations = t.continuations + 1, updatedAt = e.at) }
                is TaskEvent.VerificationRecorded -> tasks.computeIfPresent(e.task) { _, t -> t.copy(verification = e.record, updatedAt = e.at) }
                is TaskEvent.ScopeChanged -> tasks.computeIfPresent(e.task) { _, t -> t.copy(scope = e.scope, updatedAt = e.at) }
            }
            return TasksState(tasks)
        }

        fun applyAll(state: TasksState, events: List<TaskEvent>): TasksState = events.fold(state, ::apply)

        private val SCOPE_PATH = Regex("[A-Za-z0-9._@+-]+(/[A-Za-z0-9._@+-]+)*")

        /**
         * Заявить область задачи (ТЗ, §31): относительные пути внутри
         * репозитория, без `..` и абсолютных путей, не больше 500.
         */
        fun setScope(state: TasksState, task: TaskId, scope: Set<String>, now: Timestamp): Outcome<List<TaskEvent>> {
            val t = state.tasks[task] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой задачи", "task $task")
            if (t.terminal) return Outcome.failure(DiagCode.INVALID_TRANSITION, "задача завершена", "task $task")
            val bad = scope.firstOrNull { p -> !SCOPE_PATH.matches(p) || p.split('/').any { it == ".." || it == "." } }
            if (bad != null || scope.size > 500) return Outcome.failure(DiagCode.VALUE_INVALID, "область — относительные пути внутри репозитория, не больше 500", "task $task")
            if (t.scope == scope) return Outcome.success(emptyList())
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
