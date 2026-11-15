package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Счётчики ручных сборок и проверок для виджета (`DPmetaApp:D-153`): какой запуск идёт
 * сейчас и итоги за сутки по всем проектам — из истории запусков (D-156), без запуска
 * чего-либо. Нечитаемая история хоть одного проекта — итоги неизвестны (`null`), а не
 * ноль (D-13).
 */
data class ManualCounts(val running: ManualProfileKind?, val ok: Int?, val failed: Int?) {
    companion object {
        /** Окно итогов: сутки до [of] `now`. */
        const val WINDOW_MILLIS = 24L * 60 * 60 * 1000

        fun of(active: ManualRun?, histories: List<Outcome<List<ManualHistoryEntry>>>, now: Timestamp): ManualCounts {
            val readable = histories.mapNotNull { (it as? Outcome.Success)?.value }
            if (readable.size != histories.size) return ManualCounts(active?.profile?.kind, null, null)
            val since = now.epochMillis - WINDOW_MILLIS
            val recent = readable.flatten().filter { it.finishedAt.epochMillis in since..now.epochMillis }
            return ManualCounts(
                active?.profile?.kind,
                recent.count { it.outcome == ManualOutcome.OK },
                recent.count { it.outcome == ManualOutcome.FAILED },
            )
        }
    }
}
