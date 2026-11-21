package com.dpsmpx.dpai.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DevelopmentModelsTest {
    @Test fun setupDoesNotPickEmbeddingsOrAKnownToolIncompatibleModel() {
        val models = listOf(ModelInfo("embedding-x"), ModelInfo("mistral-embed"), ModelInfo("babbage-002"),
            ModelInfo("gpt-realtime", supportsTools = true), ModelInfo("gpt-audio"), ModelInfo("gemini-flash-image"),
            ModelInfo("chat-but-no-tools", supportsTools = false), ModelInfo("gpt-code", supportsTools = true))
        assertEquals(listOf("gpt-code"), DevelopmentModels.eligible(models).map { it.id })
        assertEquals("gpt-code", DevelopmentModels.preferred(models, "embedding-x"))
    }
    @Test fun savedModelIsKeptAndMissingModelsAreNeverInvented() {
        assertEquals("older", DevelopmentModels.preferred(listOf(ModelInfo("newer"), ModelInfo("older")), "older"))
        assertNull(DevelopmentModels.preferred(emptyList(), "not-in-catalog"))
    }
}
