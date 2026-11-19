package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.model.IdSource
import kotlin.test.Test
import kotlin.test.assertIs

/** Исторические образцы реестра сохраняют проверку шаблонов, кодек не входит в приложение. */
class LegacyRegistryPatternTest {
    @Test
    fun registryCannotSupplyBacktrackingExpressions() {
        for (pattern in listOf("D-\\d+", "Р\\d+", "(?:TR|[FNIALRQ])-\\d+")) {
            assertIs<Outcome.Success<*>>(IdSource("doc.md", pattern).regex(), pattern)
        }
        for (pattern in listOf("(a+)+$", "(?:a+)+\\d+", "(?:a|(?:aa))*\\d+", "a{999999}\\d+", "a.*\\d+")) {
            assertIs<Outcome.Failure>(IdSource("doc.md", pattern).regex(), pattern)
        }
    }

}
