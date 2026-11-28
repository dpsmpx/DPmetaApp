package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AiRoutingCorpusTest {
    private fun sample(name: String) = JsonParser().parse(javaClass.getResource("/model-routing/$name.json")!!.readText()).valueOrNull()!!

    @Test fun policyAndNativeIntentHaveStrictRoundTrips() {
        for (name in listOf("single-api-v1", "pending-cli-v1")) {
            val raw = sample(name)
            val state = assertIs<Outcome.Success<AiRoutingState>>(AiRoutingState.read(raw)).value
            assertEquals(raw, state.json())
        }
        assertIs<Outcome.Failure>(AiRoutingState.read(sample("unknown-version")))
    }

    @Test fun capabilitiesUseExistingProfileRegistry() {
        val raw = sample("api-profiles-v2")
        val profiles = ApiProfileBook.fromJson(raw).valueOrNull()!!
        assertEquals(ModelCapabilities.binding(profiles.single()), profiles.single().capabilities!!.binding)
        assertEquals(raw, ApiProfileBook.toJson(profiles))
    }
}
