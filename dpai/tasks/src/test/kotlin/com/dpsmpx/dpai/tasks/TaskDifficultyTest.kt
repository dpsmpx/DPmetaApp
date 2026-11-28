package com.dpsmpx.dpai.tasks

import com.dpsmpx.dpai.core.measure.Measure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskDifficultyTest {
    private val small = TaskFacts(Measure.observed(1), Measure.observed(10), Measure.observed(1), Measure.observed(0),
        Measure.observed(false), Measure.observed(false), Measure.observed(false), Measure.observed(false),
        Measure.observed(true), Measure.observed(true), Measure.observed(false))

    @Test fun missingFactsAreNotSafeZeroes() {
        val unknown = TaskDifficultyClassifier.assess(TaskFacts())
        assertEquals(TaskDifficulty.MODERATE, unknown.difficulty)
        assertEquals(11, unknown.unknown.size)
        assertTrue(unknown.confirmation)
        assertFalse(TaskDifficultyClassifier.assess(small).confirmation)
        assertEquals(TaskDifficulty.TRIVIAL, TaskDifficultyClassifier.assess(small).difficulty)
    }

    @Test fun criticalRisksAndExplicitMinimumNeverDowngrade() {
        for (facts in listOf(small.copy(securitySensitive = Measure.observed(true)), small.copy(irreversible = Measure.observed(true)))) {
            val result = TaskDifficultyClassifier.assess(facts)
            assertEquals(TaskDifficulty.CRITICAL, result.difficulty)
            assertTrue(result.independentAudit)
            assertTrue(result.confirmation)
        }
        assertEquals(TaskDifficulty.CRITICAL, TaskDifficultyClassifier.assess(small, TaskDifficulty.CRITICAL).difficulty)
        assertEquals(TaskDifficulty.COMPLEX, TaskDifficultyClassifier.assess(small, taskClass = TaskClass.ARCHITECTURE).difficulty)
        assertEquals(TaskDifficulty.COMPLEX, TaskDifficultyClassifier.assess(small.copy(dependencies = Measure.observed(3))).difficulty)
    }
}
