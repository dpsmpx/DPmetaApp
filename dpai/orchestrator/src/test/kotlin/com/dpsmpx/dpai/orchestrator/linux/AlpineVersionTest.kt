package com.dpsmpx.dpai.orchestrator.linux

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Сравнение версий Alpine (D-191). Корпус `alpine/versions.txt` — пары версий и ответ
 * `apk version -t` apk-tools 3.0.8 (`?` — apk отверг запись): версии из баз пакетов
 * Alpine 3.24 и синтетические краевые записи. `-Ddpai.test.alpineVersions=<файл>` —
 * тот же разбор на большем корпусе.
 */
class AlpineVersionTest {
    private fun check(lines: List<String>) {
        val wrong = lines.filter { it.isNotBlank() }.mapNotNull { line ->
            val (a, b, want) = line.trim().split(' ')
            val got = when (AlpineVersion.compare(a, b)) { null -> "?"; -1 -> "<"; 0 -> "="; else -> ">" }
            if (got != want) "$a $b: apk $want, здесь $got" else null
        }
        assertEquals(emptyList(), wrong.take(30), "расхождений: ${wrong.size}")
    }

    @Test
    fun matchesApkToolsOnCorpus() {
        val text = javaClass.getResource("/alpine/versions.txt")!!.readText()
        check(text.lines())
    }

    @Test
    fun matchesApkToolsOnLargeCorpusWhenGiven() {
        val path = System.getProperty("dpai.test.alpineVersions") ?: return
        check(Files.readAllLines(Path.of(path)))
    }

    @Test
    fun constraints() {
        assertEquals(true, AlpineVersion.satisfies("2.54.0-r0", ">=", "2.50"), "случай 1")
        assertEquals(false, AlpineVersion.satisfies("2.54.0-r0", "<", "2.54"), "случай 2")
        assertEquals(true, AlpineVersion.satisfies("1.37.0-r31", "~", "1.37"), "случай 3")
        assertEquals(false, AlpineVersion.satisfies("1.38.0-r0", "~", "1.37"), "случай 4")
        assertEquals(true, AlpineVersion.satisfies("1.0_rc1", "<", "1.0"), "случай 5")
        assertNull(AlpineVersion.satisfies("1.0", "!!", "1.0"), "неизвестный знак — не сверено")
        assertNull(AlpineVersion.satisfies("1..0", ">=", "1.0"), "неверная запись — не сверено")
        assertTrue(AlpineVersion.valid("15.2.0_git20250101-r0"))
        assertFalse(AlpineVersion.valid("1.0-x"))
    }
}
