package com.dpsmpx.dpai.core.state

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.testkit.Gen
import com.dpsmpx.dpai.testkit.Property
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Жизненные циклы: перебор всех пар состояний (ТЗ, §101), инварианты D-23 и
 * совпадение таблиц в коде с таблицами документов — документ и код не
 * расходятся молча.
 */
class LifecycleTablesTest {
    private val tables: List<TransitionTable<*>> =
        listOf(SessionLifecycle.table, TaskLifecycle.table, AccountLifecycle.table, CircuitLifecycle.table, TargetLifecycle.table)

    @Test
    fun everyPairIsEitherInTableOrRejected() {
        for (table in tables) exhaustive(table)
    }

    private fun <S : Enum<S>> exhaustive(table: TransitionTable<S>) {
        for (from in table.states) for (to in table.states) {
            val ordinary = table.transition(from, to)
            val explicit = table.transition(from, to, TransitionMode.EXPLICIT)
            val inOrdinary = from == to || to in table.ordinaryTargets(from)
            val inExplicit = inOrdinary || to in table.explicitTargets(from)
            assertEquals(inOrdinary, ordinary.isSuccess, "${table.name}: $from → $to обычным")
            assertEquals(inExplicit, explicit.isSuccess, "${table.name}: $from → $to явным")
            if (!ordinary.isSuccess) assertTrue(ordinary.errors.single().subject == null)
        }
        for (s in table.terminal) assertTrue(table.ordinaryTargets(s).isEmpty(), "${table.name}: выход из конечного $s")
    }

    /** D-23: в DONE — только из VERIFIED или COMPLETION_UNVERIFIED. */
    @Test
    fun doneOnlyAfterVerificationOrPolicyException() {
        val into = TaskState.entries.filter { TaskState.DONE in TaskLifecycle.table.ordinaryTargets(it) }
        assertEquals(setOf(TaskState.VERIFIED, TaskState.COMPLETION_UNVERIFIED), into.toSet())
        assertTrue(TaskState.DONE !in TaskLifecycle.table.ordinaryTargets(TaskState.IMPLEMENTED))
    }

    /** ТЗ, §8: COMPLETED → RUNNING недопустимо; продолжение — новая сессия. */
    @Test
    fun sessionTerminalStatesHaveNoExits() {
        for (s in SessionLifecycle.table.terminal) {
            assertTrue(SessionLifecycle.table.ordinaryTargets(s).isEmpty())
            assertTrue(SessionLifecycle.table.explicitTargets(s).isEmpty())
        }
        assertTrue(!SessionLifecycle.table.transition(SessionState.COMPLETED, SessionState.RUNNING, TransitionMode.EXPLICIT).isSuccess)
    }

    /** Свойство: случайные запросы переходов не выводят сущность за таблицу. */
    @Test
    fun randomWalksStayInsideTheTable() {
        val steps = Gen.list(Gen.element(TaskState.entries.flatMap { a -> TransitionMode.entries.map { a to it } }), 1..60)
        Property.forAll(steps) { requests ->
            var state = TaskLifecycle.table.initial
            for ((to, mode) in requests) {
                val r = TaskLifecycle.table.transition(state, to, mode)
                if (r is Outcome.Success) {
                    assertTrue(TaskLifecycle.table.isAllowed(state, to, mode))
                    state = r.value.to
                } else {
                    assertTrue(!TaskLifecycle.table.isAllowed(state, to, mode))
                }
            }
        }
    }

    @Test
    fun documentsDescribeTheSameTables() {
        assertDoc("docs/SESSION_MODEL.md", SessionLifecycle.table)
        assertDoc("docs/SCHEDULER.md", TaskLifecycle.table)
        assertDoc("docs/ACCOUNT_MODEL.md", AccountLifecycle.table)
        assertDoc("docs/EXECUTION_MODEL.md", CircuitLifecycle.table, after = "## 2. Предохранитель")
        assertDoc("docs/EXECUTION_MODEL.md", TargetLifecycle.table, after = "### 1.1. Здоровье target")
    }

    /** Таблица «Из | В» документа — первая после заголовка [after], если он задан. */
    private fun <S : Enum<S>> assertDoc(rel: String, table: TransitionTable<S>, after: String? = null) {
        val root = System.getProperty("dpai.repoRoot") ?: error("нет dpai.repoRoot")
        val lines = File(root, rel).readLines()
        val from = after?.let { h -> lines.indexOf(h).also { assertTrue(it >= 0, "$rel: нет заголовка $h") } } ?: 0
        val start = lines.withIndex().indexOfFirst { (i, l) -> i >= from && l.startsWith("| Из |") }
        assertTrue(start >= 0, "$rel: нет таблицы переходов")
        val byName = table.states.associateBy { it.name }
        val ordinary = mutableMapOf<S, Set<S>>()
        val explicit = mutableMapOf<S, MutableSet<S>>()
        fun names(cell: String): List<S> = Regex("`([A-Z_]+)`").findAll(cell).map { byName.getValue(it.groupValues[1]) }.toList()
        for (line in lines.drop(start + 2)) {
            if (!line.startsWith("|")) break
            val cells = line.trim().trim('|').split('|').map { it.trim() }
            val targets = cells[1]
            val main = targets.substringBefore("(")
            for (from in names(cells[0])) {
                ordinary[from] = names(main).toSet()
                if ("явно" in targets) explicit.getOrPut(from) { mutableSetOf() } += names(targets.substringAfter("("))
            }
        }
        // Явные переходы задачи — отдельная таблица «Операция | Переход».
        for (line in lines) {
            val m = Regex("\\| `([A-Z_]+)` → `([A-Z_]+)` \\|\\s*$").find(line) ?: continue
            explicit.getOrPut(byName.getValue(m.groupValues[1])) { mutableSetOf() } += byName.getValue(m.groupValues[2])
        }
        for (s in table.states) {
            assertEquals(table.ordinaryTargets(s), ordinary[s].orEmpty(), "$rel: обычные переходы из $s")
            assertEquals(table.explicitTargets(s), explicit[s].orEmpty().toSet(), "$rel: явные переходы из $s")
        }
    }
}
