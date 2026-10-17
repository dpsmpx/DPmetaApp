package com.dpsmpx.dpai.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals

/** Последние записи со сквозным номером (этап 19): память не растёт, читатель не теряет и не повторяет. */
class RecentLogTest {
    @Test
    fun readerGetsEachRecordOnceAndMemoryIsBounded() {
        val log = RecentLog<Int>(3)
        var seen = 0L
        val got = mutableListOf<Int>()
        for (i in 1..5) {
            log.add(i)
            val (fresh, next) = log.since(seen)
            got += fresh
            seen = next
        }
        assertEquals(listOf(1, 2, 3, 4, 5), got)
        assertEquals(listOf(3, 4, 5), log.toList())
        assertEquals(5, log.total)
        // Отставший читатель получает последние, а не исключение и не дубли.
        for (i in 6..10) log.add(i)
        assertEquals(listOf(8, 9, 10) to 10L, log.since(seen))
        assertEquals(emptyList<Int>() to 10L, log.since(10))
    }
}
