package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.execution.process.ProcessTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Опознание процесса по идентификатору и времени старта (ТЗ, §119; ревью A-11). */
class ProcessTreeTest {
    /**
     * Ревью A-11: на Linux время старта процесса JDK считает от времени
     * загрузки, а оно сдвигается вместе со скачком часов. Записанное до
     * скачка узнаётся по старту относительно загрузки.
     */
    @Test
    fun liveProcessIsRecognisedAfterClockStep() {
        val self = ProcessHandle.current()
        val start = ProcessTree.startedAtMillis(self)!!
        val step = 60_000L
        val boot = 1_000_000L
        // Записано до скачка часов на минуту вперёд: старт — на минуту раньше нынешнего счёта.
        assertEquals(self, ProcessTree.find(self.pid(), start - step, boot, boot + step))
        assertNull(ProcessTree.find(self.pid(), start - step, null, null), "без записанной загрузки — по стенным часам, как раньше")
        assertNull(ProcessTree.find(self.pid(), start - step, boot, boot), "загрузка не сдвинулась — старт другой: чужой процесс")
        assertEquals(self, ProcessTree.find(self.pid(), start, boot, boot))
    }
}
