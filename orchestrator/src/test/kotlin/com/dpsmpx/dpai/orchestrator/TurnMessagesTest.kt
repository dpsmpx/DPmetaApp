package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import kotlin.test.Test
import kotlin.test.assertEquals

/** Итоги ходов задачи (D-42): целиком, без урезания до 4000 знаков; последние 50; кодек туда и обратно. */
class TurnMessagesTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(5))

    @Test
    fun fullTextBoundedListAndRoundTrip() {
        val s = ids.next(IdKinds.SESSION)
        val long = "строка итога хода\n".repeat(1000)
        var list = emptyList<TurnMessage>()
        for (i in 1..60) list = TurnMessages.append(list, TurnMessage(s, i, clock.now(), "COMPLETED", "haiku", if (i == 60) long else "ход $i"))
        assertEquals(TurnMessages.MAX, list.size)
        assertEquals(11, list.first().turn, "хранятся последние 50")
        assertEquals(long, list.last().text, "полный текст: ${long.length} знаков")
        // Тот же ход второй раз — замена, а не дубль.
        assertEquals(list.size, TurnMessages.append(list, list.last().copy(text = "заново")).size)
        assertEquals(list, TurnMessages.fromJson(TurnMessages.toJson(list)).valueOrNull())
    }
}
