package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.orchestrator.linux.AlpinePackages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Проверка обновлений окружения (D-197): вывод `apk version -l '<'` в виде apk-tools 3.0.8. */
class EnvUpdatesTest {
    private val inv = AlpinePackages.parse(
        "P:git\nV:2.54.0-r0\n\nP:git-init-template\nV:2.54.0-r0\n\nP:ncurses-terminfo-base\nV:6.6_p20260516-r0\n\nP:bash\nV:5.3.9-r1\n",
        "git bash\n", null, "3.24.3",
    )

    private val output = listOf(
        "Installed:                                Available:",
        "git-2.54.0-r0                           < 2.56.1-r0 ",
        "git-init-template-2.54.0-r0             < 2.56.1-r0 ",
        "ncurses-terminfo-base-6.6_p20260516-r0  < 6.6_p20261017-r0 ",
        "unknown-pkg-1.0-r0                      < 1.1-r0",
        "bash-5.3.9-r1                           = 5.3.9-r1",
    )

    @Test
    fun parsesApkOutputUsingDatabaseNames() {
        val u = EnvUpdates.parse(output, inv)
        assertEquals(listOf("git", "git-init-template", "ncurses-terminfo-base"), u.map { it.name }, "имя с дефисами делит база; чужая строка и «=» — пропуск")
        assertEquals(EnvUpdates.Update("git", "2.54.0-r0", "2.56.1-r0"), u[0])
    }

    @Test
    fun noticeOnlyWhenSomethingNewAndChanged() {
        val r = EnvUpdates.Result(1000, EnvUpdates.parse(output, inv), listOf(EnvUpdates.Update("Служба Python (pyright)", "1.1.300", "1.1.414")))
        val text = EnvUpdates.notice(r)!!
        assertTrue(text.startsWith("Пакетов Alpine: 3 — git 2.54.0-r0 → 2.56.1-r0"), text)
        assertTrue("Компонентов: 1 — Служба Python (pyright) → 1.1.414" in text, text)
        assertNull(EnvUpdates.notice(EnvUpdates.Result(1000, emptyList(), emptyList())))
        assertTrue(EnvUpdates.changed(r, null))
        assertFalse(EnvUpdates.changed(r.copy(at = 2000), r), "тот же набор — не напоминать каждый день")
        assertEquals(r, EnvUpdates.decode(EnvUpdates.encode(r)).valueOrNull())
    }
}
