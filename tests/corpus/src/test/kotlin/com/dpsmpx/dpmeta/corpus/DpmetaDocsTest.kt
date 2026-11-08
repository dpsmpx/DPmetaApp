package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpmeta.formats.DpmetaDocs
import kotlin.test.Test
import kotlin.test.assertEquals

/** Счётчики документов DPmeta (`eco.*`, ТЗ, §8) на синтетических образцах формата. */
class DpmetaDocsTest {
    @Test
    fun countsHeadersLikeTheProgressScope() {
        val contracts = "# Контракты\n\n## C-01 — Первый\n\nтекст\n\n### C-02 — Второй\n\n#### C-03 — глубже не считается\n\nC-04 в тексте — не заголовок\n"
        assertEquals(2, DpmetaDocs.contracts(contracts))
        val queue = "## Сводка\n\n| SQ-01 | x |\n\n### SQ-01 — Пункт\n\n### SQ-02 — Пункт\n"
        assertEquals(2, DpmetaDocs.queue(queue), "строка сводки — не пункт")
    }

    @Test
    fun openRisksByStatusField() {
        val risks = """
            # Риски

            ## RISK-01 — Закрытый
            - **Статус:** закрыт решением M-11.

            ## RISK-02 — Открытый
            - **Статус:** открыт.

            ## RISK-03 — Частично
            - **Статус:** частично: одна часть решена

            ## RISK-04 — Без поля статуса

            ## Не риск
            - **Статус:** открыт.
        """.trimIndent()
        assertEquals(4 to 3, DpmetaDocs.risks(risks), "закрытый — нет; открытый, частично и без статуса — открыты; не RISK — не в счёте")
    }
}
