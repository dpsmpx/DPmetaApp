package com.dpsmpx.dpmeta.model

import com.dpsmpx.dpai.core.result.Outcome
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AlertPatternTest {
    @Test
    fun nestedAlternativesDoNotOverflowTheStackOrBacktrack() {
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            val pattern = (AlertPattern.compile("(a|aa)+$") as Outcome.Success).value
            assertFalse(pattern.containsMatchIn("a".repeat(100000) + "!"))
            assertTrue(pattern.containsMatchIn("a".repeat(100000)))
        }
    }

    @Test
    fun unicodeSearchIsCaseInsensitiveAndUnsupportedSyntaxIsExplicit() {
        val pattern = (AlertPattern.compile("^привет\\s+\\p{L}+[0-9]{2}$") as Outcome.Success).value
        assertTrue(pattern.containsMatchIn("ПРИВЕТ мир42"))
        for (unsupported in listOf("(a)\\1", "(?<=foo)bar", "(?=x)", "a++")) {
            assertIs<Outcome.Failure>(AlertPattern.compile(unsupported))
        }
    }
}
