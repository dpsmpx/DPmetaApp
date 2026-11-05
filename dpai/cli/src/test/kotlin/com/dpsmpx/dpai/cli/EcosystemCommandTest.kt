package com.dpsmpx.dpai.cli

import com.dpsmpx.dpai.orchestrator.ecosystem.PlannedSuggestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `dpai ecosystem` (ТЗ, §43–§46; D-97): манифест файлом, отбор, подтверждение, ключ команды. */
class EcosystemCommandTest {
    private val manifest = """
        |{"format":"dpai.ecosystem","version":1,"projects":[
        |{"key":"alpha","name":"Alpha","state":{"place":"core","branch":"main"},"documents":["CLAUDE.md"],"suggestions":[
        |{"key":"sq-01","title":"SQ-01: строка в README","objective":"Где: README.md. Предложение: поправить (DPmeta:SQ-01)","priority":"LOW","operation":"docs"},
        |{"key":"sq-02","title":"SQ-02: код","objective":"…","priority":"LOW","operation":"code"},
        |{"key":"sq-03","title":"SQ-03: ещё строка","objective":"…","priority":"HIGH","operation":"docs"}]},
        |{"key":"beta","name":"Beta","state":{"place":"support"},"documents":[],"suggestions":[
        |{"key":"sq-04","title":"SQ-04","objective":"…","priority":"LOW","operation":"docs"}]}]}
    """.trimMargin()

    private val out = mutableListOf<String>()
    private val err = mutableListOf<String>()
    private val submitted = mutableListOf<PlannedSuggestion>()

    private fun cmd(answers: List<String?> = emptyList(), text: String? = manifest, result: Int = ControlCommand.OK): EcosystemCommand {
        val queue = ArrayDeque(answers)
        return EcosystemCommand(out::add, err::add, { queue.removeFirstOrNull() }, { s -> submitted += s; result }, { text })
    }

    @Test
    fun showListsProjectsAndVerdicts() {
        assertEquals(0, cmd().run(listOf("show", "m.json", "--project", "alpha")))
        val text = out.joinToString("\n")
        assertTrue(text.contains("alpha — Alpha (branch main, place core)"), text)
        assertTrue(text.contains("sq-03  HIGH  docs  SQ-03: ещё строка — можно поставить"), text)
        assertTrue(text.contains("sq-02  LOW  code  SQ-02: код — вид работы code не разрешён прогону"), text)
        assertTrue(text.contains("sq-04  LOW  docs  SQ-04 — проект beta не подключён к контуру"), text)
        assertTrue(submitted.isEmpty(), "show ничего не ставит")
    }

    @Test
    fun runAsksForEachAndSubmitsOnlyConfirmedWithStableKeys() {
        assertEquals(0, cmd(listOf("да", "нет")).run(listOf("run", "m.json", "--project", "alpha")))
        assertEquals(listOf("ecosystem:alpha:sq-03"), submitted.map { it.key }, "HIGH — первым, второе не подтверждено")
        assertTrue(out.any { it.startsWith("пропущено sq-02: вид работы code") })
        assertTrue(out.any { it == "не поставлено sq-01: нет подтверждения" })
    }

    @Test
    fun yesConfirmsAllWithinMaxAndOperations() {
        assertEquals(0, cmd().run(listOf("run", "m.json", "--project", "alpha", "--yes", "--max", "1")))
        assertEquals(listOf("sq-03"), submitted.map { it.suggestion.key })
        submitted.clear()
        assertEquals(0, cmd().run(listOf("run", "m.json", "--project", "alpha", "--yes", "--operations", "docs,code")))
        assertEquals(listOf("sq-03", "sq-01", "sq-02"), submitted.map { it.suggestion.key })
    }

    @Test
    fun endOfInputIsNo() {
        assertEquals(0, cmd(emptyList()).run(listOf("run", "m.json", "--project", "alpha")))
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun badCallsAndManifest() {
        assertEquals(2, cmd().run(listOf("run", "m.json")), "нужен --project")
        assertEquals(1, cmd().run(listOf("run", "m.json", "--project", "omega")))
        assertTrue(err.last().contains("нет проекта «omega»"))
        assertEquals(1, cmd(text = null).run(listOf("show", "нет.json")))
        assertEquals(1, cmd(text = """{"format":"dpai.ecosystem","version":2,"projects":[]}""").run(listOf("show", "m.json")), "версия новее — отказ (D-09)")
        assertEquals(2, cmd().run(listOf("show", "m.json", "--projekt", "x")), "опечатка в ключе — отказ")
        assertEquals(1, cmd(result = ControlCommand.FAILED).run(listOf("run", "m.json", "--project", "alpha", "--yes")), "отказ канала — код 1")
    }

    @Test
    fun cliRoutesEcosystemAndShowsUsage() {
        val outs = mutableListOf<String>()
        assertEquals(0, Cli(outs::add, err::add).run(listOf("help")))
        assertTrue(outs.joinToString("\n").contains("dpai ecosystem run <манифест> --project ключ"))
    }
}
