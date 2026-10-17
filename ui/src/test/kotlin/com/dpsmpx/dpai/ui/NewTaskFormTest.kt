package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Окно «Новая задача» (D-42, п. 1): поля разбираются в команду, неверное — отказ с полем. */
class NewTaskFormTest {
    private val dep = IdGenerator(ManualClock(), RandomSource.seeded(42)).next(IdKinds.TASK)

    private fun code(o: Outcome<*>) = (o as Outcome.Failure).diagnostics.first().code

    @Test
    fun everythingSetAtCreation() {
        val c = NewTaskForm.command(
            " Сборка ", " починить ", "k-1", Priority.HIGH, TaskClass.TRIVIAL, " haiku ", "low", "0,25", "src/a, docs\nREADME.md\n\n", setOf(dep),
        ).valueOrNull()!!
        assertEquals("Сборка", c.title)
        assertEquals("починить", c.objective)
        assertEquals(Priority.HIGH, c.priority)
        assertEquals(TaskClass.TRIVIAL, c.policy.taskClass)
        assertEquals("haiku", c.policy.model)
        assertEquals(25L, c.policy.maxBudgetUsdCents)
        assertEquals(setOf("src/a", "docs", "README.md"), c.scope)
        assertEquals(setOf(dep), c.dependencies)
        assertEquals("k-1", c.key)
    }

    @Test
    fun emptyFieldsAreDefaultsAndBadOnesAreRefused() {
        val c = NewTaskForm.command("t", "o", "k").valueOrNull()!!
        assertNull(c.policy.model)
        assertNull(c.policy.maxBudgetUsdCents)
        assertEquals(emptySet(), c.scope)
        assertEquals(DiagCode.VALUE_INVALID, code(NewTaskForm.command(" ", "o", "k")))
        assertEquals(DiagCode.VALUE_INVALID, code(NewTaskForm.command("t", "o", "k", budget = "-1")))
        assertEquals(DiagCode.VALUE_INVALID, code(NewTaskForm.command("t", "o", "k", budget = "много")))
        assertEquals(DiagCode.VALUE_INVALID, code(NewTaskForm.command("t", "o", "k", model = "opus; rm -rf")))
    }
}
