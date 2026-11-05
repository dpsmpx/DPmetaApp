package com.dpsmpx.dpai.orchestrator.ecosystem

import com.dpsmpx.dpai.ecosystem.EcosystemProject
import com.dpsmpx.dpai.ecosystem.SuggestedTask
import com.dpsmpx.dpai.tasks.Priority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Отбор предложений экосистемы (ТЗ, §45, §46): порядок, причины отказа, команда с ключом (D-42). */
class EcosystemPlanTest {
    private fun s(key: String, priority: String = "LOW", operation: String = "docs") = SuggestedTask(key, "задача $key", "цель $key", priority, operation)

    private fun p(key: String, vararg s: SuggestedTask) = EcosystemProject(key, key.uppercase(), null, emptyMap(), emptyList(), s.toList())

    private val projects = listOf(
        p("beta", s("sq-2"), s("sq-1", priority = "HIGH")),
        p("alpha", s("sq-3"), s("sq-4", operation = "code"), s("sq-5", priority = "URGENT")),
        p("gamma", s("sq-6")),
    )

    @Test
    fun orderIsPriorityThenProjectThenKey() {
        val plan = EcosystemPlan.of(projects, setOf("alpha", "beta", "gamma"))
        assertEquals(listOf("sq-1", "sq-3", "sq-4", "sq-2", "sq-6", "sq-5"), plan.map { it.suggestion.key })
    }

    @Test
    fun refusalsNameTheReason() {
        val plan = EcosystemPlan.of(projects, connected = setOf("alpha", "beta"), allowedProjects = setOf("alpha"), allowedOperations = setOf("docs"))
            .associate { it.suggestion.key to it.refusal }
        assertNull(plan["sq-3"])
        assertEquals("вид работы code не разрешён прогону", plan["sq-4"])
        assertEquals("неизвестный приоритет URGENT", plan["sq-5"])
        assertEquals("проект beta не разрешён прогону", plan["sq-1"])
        assertEquals("проект gamma не подключён к контуру", plan["sq-6"])
    }

    @Test
    fun keyIsAcceptedByTheContour() {
        // Ключ с косой чертой контур отвергал (Command.KEY): задача из предложения не ставилась вовсе.
        val readable = EcosystemPlan.key("dpmetaapp", "sq-12")
        assertEquals("ecosystem:dpmetaapp:sq-12", readable)
        val odd = listOf(EcosystemPlan.key("проект", "sq-1"), EcosystemPlan.key("a:b", "c"), EcosystemPlan.key("a", "b:c"), EcosystemPlan.key("p".repeat(70), "s".repeat(20)))
        for (k in odd + readable) assertTrue(com.dpsmpx.dpai.orchestrator.commands.Command.KEY.matches(k), k)
        assertTrue(odd.all { it.startsWith("ecosystem.") }, "не латиница или длинно — отпечаток")
        assertEquals(odd.size, odd.toSet().size, "разные предложения — разные ключи, двоеточие внутри частей не склеивает их")
        assertEquals(EcosystemPlan.key("проект", "sq-1"), EcosystemPlan.key("проект", "sq-1"), "ключ устойчив")
    }

    @Test
    fun commandCarriesStableKeyAndOnlyForAllowed() {
        val plan = EcosystemPlan.of(projects, setOf("alpha"), setOf("alpha"), setOf("docs"))
        val ok = plan.single { it.suggestion.key == "sq-3" }.command()!!
        assertEquals("ecosystem:alpha:sq-3", ok.key)
        assertEquals(Priority.LOW, ok.priority)
        assertEquals("задача sq-3" to "цель sq-3", ok.title to ok.objective)
        assertNull(plan.single { it.suggestion.key == "sq-4" }.command(), "не разрешено — команды нет")
        // Повтор плана — тот же ключ: повторный прогон задачу не дублирует.
        assertEquals(ok.key, EcosystemPlan.of(projects, setOf("alpha"), setOf("alpha"), setOf("docs")).single { it.suggestion.key == "sq-3" }.command()!!.key)
    }
}
