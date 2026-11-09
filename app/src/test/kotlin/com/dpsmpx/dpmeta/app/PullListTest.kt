package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpmeta.app.screens.PullActions
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpmeta.app.screens.Screens.PullFilter
import com.dpsmpx.dpmeta.app.screens.Screens.PullRow
import com.dpsmpx.dpmeta.github.PullCommit
import com.dpsmpx.dpmeta.github.PullDetail
import com.dpsmpx.dpmeta.github.PullFile
import com.dpsmpx.dpmeta.github.PullNote
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PR всех проектов и карточка PR (F-11): фильтры, порядок, строки, разделы описания. */
class PullListTest {
    private val hour = 3_600_000L
    private val rows = listOf(
        PullRow("Alpha", 1, "свежий", draft = false, head = "a", author = "x", checks = "green", waitingMillis = 2 * hour, stale = false),
        PullRow("Alpha", 2, "черновик", draft = true, head = "b", author = "x", checks = null, waitingMillis = 200 * hour, stale = false),
        PullRow("Beta", 3, "давний", draft = false, head = "c", author = "y", checks = "red", waitingMillis = 100 * hour, stale = true),
    )

    @Test
    fun filtersAndOrder() {
        assertEquals(listOf(3L, 1L), Screens.filterPulls(rows, PullFilter.WAITING, null).map { it.number }, "ждут автора — дольше ждущие сверху")
        assertEquals(listOf(2L), Screens.filterPulls(rows, PullFilter.DRAFTS, null).map { it.number })
        assertEquals(listOf(3L), Screens.filterPulls(rows, PullFilter.STALE, null).map { it.number })
        assertEquals(listOf(1L, 2L), Screens.filterPulls(rows, PullFilter.ALL, "Alpha").map { it.number }, "все — свежие сверху, по проекту")
        assertEquals(emptyList(), Screens.filterPulls(rows, PullFilter.STALE, "Alpha"))
        val now = Timestamp(Instant.parse("2026-11-09T12:00:00Z").toEpochMilli())
        val line = Screens.pullLine(rows[2], now)
        assertTrue(line.startsWith("Beta · c · y · обновлён 4 дн назад"), line)
        assertTrue("ждёт автора дольше 72 ч" in line, line)
    }

    private fun pr(body: String) = PullDetail(
        7, "t", "open", false, false, true, "clean", "b".repeat(40), "x", "main", "PR_1", "https://github.com/o/r/pull/7",
        body = body, author = "dpsmpx", additions = 12, deletions = 3, changedFiles = 2, commits = 4,
    )

    @Test
    fun cardLines() {
        val p = pr("Вступление\n\n## Заметки\n\nпро всё\n\n## Проверки\n\n22 из 22\n\n## Сделано\n\nшаг")
        assertEquals(listOf("Сделано", "Проверки", "Заметки"), PullActions.sections(p).map { it.first }, "шаблон — первым и по порядку, прочее — после")
        assertEquals(listOf("Описание"), PullActions.sections(pr("без разделов")).map { it.first })
        assertEquals(emptyList(), PullActions.sections(pr(" ")))
        assertEquals("автор dpsmpx · +12 −3 · файлов 2 · коммитов 4", PullActions.sizeLine(p))
        assertEquals("", PullActions.sizeLine(p.copy(author = "", additions = null, changedFiles = null, commits = null)), "неизвестное — пропуск, не ноль")
        assertEquals("app/A.kt +10 −0 (новый)", PullActions.fileLine(PullFile("app/A.kt", "added", 10, 0)))
        assertEquals("bbbbbbb Этап 5 — Claude · 2026-11-09 10:00 UTC", PullActions.commitLine(PullCommit("b".repeat(40), "Этап 5", "Claude", "2026-11-09T10:00:00Z")))
        assertEquals("dpsmpx · одобрил · 2026-11-09 13:00 UTC", PullActions.noteHead(PullNote("dpsmpx", "2026-11-09T13:00:00Z", "", "APPROVED")))
    }
}
