package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ModelCapabilitiesTest {
    private val id = IdKinds.ACCOUNT.trusted("acc_01J9ZQ3K8M7N6P5R4S3T2V1W0X")
    private val profile = ApiProfiles.form(id, "API", ProviderKind.ANTHROPIC, "", "model-a", null, false).valueOrNull()!!

    @Test fun unknownsAndEvidenceRoundTripWithoutGuessedQuality() {
        val unknown = ModelCapabilities(ModelCapabilities.binding(profile))
        assertIs<Measure.Unknown>(unknown.maximumComplexity)
        val caps = unknown.copy(roles = setOf(ModelRole.IMPLEMENTER), contextTokens = Measure.estimated(32000L))
            .record(ModelVerification("a".repeat(64), 3, true)).record(ModelVerification("a".repeat(64), 3, false))
        assertEquals(1, caps.verified.size)
        assertEquals(caps, ModelCapabilities.read(caps.json()).valueOrNull())
        assertEquals(profile.copy(capabilities = caps), ApiProfileBook.fromJson(ApiProfileBook.toJson(listOf(profile.copy(capabilities = caps)))).valueOrNull()!!.single())
    }

    @Test fun identityChangeCannotInheritCapabilitiesButRenameCan() {
        val previous = profile.copy(capabilities = ModelCapabilities(ModelCapabilities.binding(profile)))
        assertEquals(previous.capabilities, ApiProfiles.retainCapabilities(previous, profile.copy(name = "Новое имя")).capabilities)
        assertNull(ApiProfiles.retainCapabilities(previous, profile.copy(model = "model-b")).capabilities)
        val mismatched = previous.copy(model = "model-b")
        assertIs<Outcome.Failure>(ApiProfileBook.fromJson(ApiProfileBook.toJson(listOf(mismatched))))
    }

    @Test fun oldProfilesReadAndFutureVersionRefused() {
        val current = ApiProfileBook.toJson(listOf(profile))
        val old = JsonObject(current.fields + ("version" to JsonNumber.of(1)))
        assertEquals(listOf(profile), ApiProfileBook.fromJson(old).valueOrNull())
        val bad = JsonObject(ModelCapabilities(ModelCapabilities.binding(profile)).json().fields + ("extra" to JsonNumber.of(1)))
        assertIs<Outcome.Failure>(ModelCapabilities.read(bad))
    }
}
