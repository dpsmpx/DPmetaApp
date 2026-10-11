package com.dpsmpx.dpai.core.state

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/** Как запрошен переход: обычным ходом дел или явной операцией пользователя (D-11). */
enum class TransitionMode { ORDINARY, EXPLICIT }

/** Совершённый (или повторённый) переход: [changed] = false — состояние уже было таким. */
data class Transition<S : Enum<S>>(val from: S, val to: S, val changed: Boolean, val mode: TransitionMode)

/**
 * Формальный автомат состояний (D-11; ТЗ, §8, §101).
 *
 * - Переход вне таблицы — `INVALID_TRANSITION`; сущность не попадает в
 *   невозможное состояние.
 * - Переход, разрешённый только явной операцией (повтор упавшей задачи,
 *   включение аккаунта), обычным ходом — `EXPLICIT_OPERATION_REQUIRED`.
 * - Повтор перехода в текущее состояние — успех без изменения: повторное
 *   событие после переподключения не портит состояние (ТЗ, §102).
 * - Из [terminal] нет обычных переходов.
 *
 * Таблица проверяет себя при создании: каждое состояние достижимо из
 * [initial], обычные и явные переходы не пересекаются, петель нет.
 */
class TransitionTable<S : Enum<S>>(
    val name: String,
    val states: List<S>,
    val initial: S,
    val terminal: Set<S>,
    ordinary: Map<S, Set<S>>,
    explicit: Map<S, Set<S>> = emptyMap(),
) {
    private val ordinary: Map<S, Set<S>> = states.associateWith { ordinary[it].orEmpty() }
    private val explicit: Map<S, Set<S>> = states.associateWith { explicit[it].orEmpty() }

    init {
        for (s in states) {
            val o = this.ordinary.getValue(s)
            val e = this.explicit.getValue(s)
            require(s !in o && s !in e) { "$name: петля $s → $s в таблице" }
            require((o intersect e).isEmpty()) { "$name: переход из $s и обычный, и явный" }
            require(s !in terminal || o.isEmpty()) { "$name: из конечного $s есть обычные переходы" }
        }
        val reached = mutableSetOf(initial)
        val queue = ArrayDeque(listOf(initial))
        while (queue.isNotEmpty()) {
            val s = queue.removeFirst()
            for (t in this.ordinary.getValue(s) + this.explicit.getValue(s)) if (reached.add(t)) queue += t
        }
        require(reached.size == states.size) { "$name: недостижимы ${states - reached}" }
    }

    fun ordinaryTargets(from: S): Set<S> = ordinary.getValue(from)

    fun explicitTargets(from: S): Set<S> = explicit.getValue(from)

    fun isAllowed(from: S, to: S, mode: TransitionMode = TransitionMode.ORDINARY): Boolean =
        from == to || to in ordinary.getValue(from) || (mode == TransitionMode.EXPLICIT && to in explicit.getValue(from))

    fun transition(from: S, to: S, mode: TransitionMode = TransitionMode.ORDINARY, subject: String? = null): Outcome<Transition<S>> =
        when {
            from == to -> Outcome.success(Transition(from, to, changed = false, mode = mode))
            to in ordinary.getValue(from) -> Outcome.success(Transition(from, to, changed = true, mode = mode))
            to in explicit.getValue(from) ->
                if (mode == TransitionMode.EXPLICIT) {
                    Outcome.success(Transition(from, to, changed = true, mode = mode))
                } else {
                    Outcome.failure(
                        DiagCode.EXPLICIT_OPERATION_REQUIRED,
                        "$name: $from → $to — только явной операцией",
                        subject,
                    )
                }
            else -> Outcome.failure(DiagCode.INVALID_TRANSITION, "$name: переход $from → $to недопустим", subject)
        }
}
