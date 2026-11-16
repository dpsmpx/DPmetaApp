package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.orchestrator.linux.AlpinePackages
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Раздел «Alpine» экрана «Окружение» (D-191): сводка, строки, подробности, удаление и починка. */
class AlpineViewTest {
    private val db = """
        P:musl
        V:1.2.6-r3
        I:800000
        p:so:libc.musl-aarch64.so.1=1

        P:busybox
        V:1.37.0-r31
        I:900000
        D:so:libc.musl-aarch64.so.1

        P:git
        V:2.54.0-r0
        I:7032728
        T:Distributed version control system
        L:GPL-2.0-only
        C:Q1KlCtqvgihl9rC6XaaIME3EjkW8c=
        t:1776707195
        D:so:libc.musl-aarch64.so.1 pcre2

        P:pcre2
        V:10.49-r0
        I:700000

        P:npm
        V:11.12.1-r0
        D:nodejs
        f:f
    """.trimIndent()

    private val inv = AlpinePackages.parse(db, "busybox git npm python3\n", null, "3.24.3")
    private val problems = AlpinePackages.problems(inv)

    @Test
    fun summaryCountsAndHonestTotal() {
        val s = AlpineView.summary(inv, problems)
        assertTrue(s.startsWith("Alpine 3.24.3 · пакетов 5, ваших 3"), s)
        assertTrue("не меньше" in s && "у 1 размер неизвестен" in s, "у npm нет I: — итог не выдаётся за точный: $s")
        assertTrue("с проблемами: 2" in s, s)
    }

    @Test
    fun rowsFilterSortAndGhost() {
        val mine = AlpineView.rows(inv, problems, "", AlpineView.Filter.MINE, AlpineView.Sort.NAME).map { it.name }
        assertEquals(listOf("busybox", "git", "npm", "python3"), mine, "python3 в world без пакета — строкой-призраком")
        val bySize = AlpineView.rows(inv, problems, "", AlpineView.Filter.ALL, AlpineView.Sort.SIZE).map { it.name }
        assertEquals(listOf("git", "busybox", "musl", "pcre2", "npm", "python3"), bySize)
        assertEquals(listOf("git"), AlpineView.rows(inv, problems, "version control", AlpineView.Filter.ALL, AlpineView.Sort.NAME).map { it.name }, "поиск и по описанию")
        val bad = AlpineView.rows(inv, problems, "", AlpineView.Filter.PROBLEMS, AlpineView.Sort.NAME)
        assertEquals(listOf("npm", "python3"), bad.map { it.name })
        assertTrue(bad[0].problems.any { "nodejs" in it } && bad[0].problems.any { "повреждён" in it })
        val git = AlpineView.rows(inv, problems, "git", AlpineView.Filter.ALL, AlpineView.Sort.NAME).single()
        assertEquals("6,7 МБ", git.size)
        assertTrue(AlpineView.rows(inv, problems, "busybox", AlpineView.Filter.ALL, AlpineView.Sort.NAME).single().system)
    }

    @Test
    fun detailsAndSizes() {
        val d = AlpineView.details(inv.byName["git"]!!, inv).toMap()
        assertEquals("2026-04-20", d["Собран"])
        assertEquals("Q1KlCtqvgihl9rC6XaaIME3EjkW8c=", d["Сумма apk (SHA-1 в base64)"])
        assertEquals("вами", d["Поставлен"])
        assertEquals("неизвестно", AlpineView.details(inv.byName["npm"]!!, inv).toMap()["Занимает"])
        assertEquals("git", AlpineView.details(inv.byName["pcre2"]!!, inv).toMap()["Нужен для"])
        assertEquals("1 КБ", AlpineView.size(1000))
        assertEquals("1,0 ГБ", AlpineView.size(1L shl 30))
    }

    @Test
    fun removalTexts() {
        assertTrue(AlpineView.removalText(AlpinePackages.removal(inv, "git")).startsWith("Уйдут пакеты (2): git (6,7 МБ), pcre2 (684 КБ). Освободится: 7,3 МБ."))
        assertTrue(AlpineView.removalText(AlpinePackages.removal(inv, "busybox")).startsWith("Убрать «busybox» нельзя: часть системы"))
    }

    @Test
    fun repairsWithoutAi() {
        val r = AlpineView.repairs(problems)
        assertEquals(listOf(LinuxEnvironment.Apk.REPAIR to listOf("npm"), LinuxEnvironment.Apk.FIX to listOf("npm"), LinuxEnvironment.Apk.ADD to listOf("python3")), r.map { it.action to it.packages })
        val conflict = listOf(AlpinePackages.Problem(AlpinePackages.ProblemKind.CONFLICT, "git", "несовместим"))
        assertEquals(emptyList(), AlpineView.repairs(conflict), "конфликт сам не чинится")
    }
}
