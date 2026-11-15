package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ошибки ручной сборки и проверки → варианты без ИИ (D-188; `DPmetaApp:D-168`). */
class FailureOptionsTest {
    private val steps = listOf(ManualStep("Сборка", listOf("./gradlew", "assembleDebug"), 600), ManualStep("Тесты", listOf("pytest", "-q"), 300))
    private val inTree = { p: String -> p.removePrefix("/root/trees/t1/").takeIf { it != p } }

    private fun kinds(results: List<ManualStepResult>, output: String, problems: BuildProblemList = BuildProblemList(emptyList(), false)) =
        FailureOptions.of(steps, results, output, problems, inTree).map { it.kind }

    @Test
    fun successHasNoOptions() {
        assertEquals(emptyList(), FailureOptions.of(steps, listOf(ManualStepResult(1, 0, "код выхода 0")), "", BuildProblemList(emptyList(), false), inTree))
    }

    @Test
    fun knownFailuresBecomeOptionsAiLast() {
        val fail = listOf(ManualStepResult(1, 1, "код выхода 1"))
        assertEquals(listOf(FailureOptions.Kind.INSTALL_SDK, FailureOptions.Kind.ASK_AI), kinds(fail, "SDK location not found. Define a valid SDK location with an ANDROID_HOME"))
        assertEquals(listOf(FailureOptions.Kind.FREE_SPACE, FailureOptions.Kind.ASK_AI), kinds(fail, "write failed: No space left on device"))
        assertEquals(listOf(FailureOptions.Kind.NETWORK_RETRY, FailureOptions.Kind.ASK_AI), kinds(fail, "> Could not GET 'https://repo.maven.apache.org/x.pom'"))
        assertEquals(listOf(FailureOptions.Kind.MEMORY, FailureOptions.Kind.ASK_AI), kinds(fail, "java.lang.OutOfMemoryError: Java heap space"))
        // Таймаут — по итогу шага, а не по тексту.
        val timeout = FailureOptions.of(steps, listOf(ManualStepResult(1, null, "истёк таймаут")), "", BuildProblemList(emptyList(), false), inTree)
        assertEquals(FailureOptions.Kind.RAISE_TIMEOUT, timeout.first().kind)
        assertTrue("600 с" in timeout.first().detail)
        // Нет программы: код 127 у второго шага — его argv[0].
        val missing = FailureOptions.of(steps, listOf(ManualStepResult(1, 0, "код выхода 0"), ManualStepResult(2, 127, "код выхода 127")), "sh: pytest: not found", BuildProblemList(emptyList(), false), inTree)
        assertEquals(listOf("Нет программы: pytest", "Поручить ИИ"), missing.map { it.title })
        // Ничего не узнано — только ИИ.
        assertEquals(listOf(FailureOptions.Kind.ASK_AI), kinds(fail, "something odd"))
    }

    @Test
    fun firstErrorInTreeOpensItOutsideDoesNot() {
        val fail = listOf(ManualStepResult(1, 1, "код выхода 1"))
        val problems = BuildProblemList(listOf(
            BuildProblem("/usr/include/x.h", 3, null, ProblemSeverity.ERROR, "outside"),
            BuildProblem("/root/trees/t1/src/main.cpp", 12, 5, ProblemSeverity.ERROR, "expected ';'"),
        ), false)
        val first = FailureOptions.of(steps, fail, "", problems, inTree).first()
        // Первая ошибка вне дерева — варианта «открыть» для неё нет; берётся первая ошибка, и она вне дерева.
        assertEquals(FailureOptions.Kind.ASK_AI, first.kind)
        val inside = BuildProblemList(problems.items.reversed(), false)
        val open = FailureOptions.of(steps, fail, "", inside, inTree).first()
        assertEquals(FailureOptions.Kind.OPEN_PROBLEM, open.kind)
        assertEquals("src/main.cpp" to 12, open.path to open.line)
    }

    @Test
    fun aiTaskCarriesOnlyExcerpt() {
        val output = (1..500).joinToString("\n") { "line $it" }
        val problems = BuildProblemList(listOf(BuildProblem("src/a.kt", 4, null, ProblemSeverity.ERROR, "Unresolved reference: foo")), false)
        val (title, objective) = FailureOptions.aiTask(ManualProfileKind.BUILD, steps, listOf(ManualStepResult(1, 1, "код выхода 1")), output, problems, "main")
        assertEquals("Исправить: сборка не прошла на шаге «Сборка»", title)
        assertTrue("src/a.kt:4 — Unresolved reference: foo" in objective)
        assertTrue("line 500" in objective && "line 461" in objective)
        assertFalse("line 460\n" in objective, "только 40 последних строк")
        assertTrue("./gradlew assembleDebug" in objective)
    }
}
