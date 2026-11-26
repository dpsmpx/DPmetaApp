package com.dpsmpx.dpai.contexts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.security.Origin
import com.dpsmpx.dpai.security.Sensitivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContextProfileTest {
    private val project = IdKinds.PROJECT.parse("prj_01J9ZQ3K8M7N6P5R4S3T2V1W0X").valueOrNull()!!
    private fun binding(p: ContextProfile) = ContextBinding(project, "target:root", "chat:human", p.revision)
    private fun source(key: String, text: String, level: SourceLevel = SourceLevel.CODE) =
        ContextSource(key, level, project, Sensitivity.INTERNAL, Origin.REPOSITORY, text)

    @Test fun profileFormatCorpusReadsV1AndRejectsUnknownSemantics() {
        for (name in listOf("project-v1", "scoped-v1", "invalid-version", "invalid-traversal", "invalid-field")) {
            val raw = javaClass.getResource("/context-profile/$name.json")!!.readText()
            val obj = com.dpsmpx.dpai.core.serialization.JsonParser().parse(raw).valueOrNull() as JsonObject
            assertEquals(!name.startsWith("invalid"), ContextProfile.read(obj).isSuccess, name)
        }
    }

    @Test fun packetV1CorpusFixesTheStructuredWireContract() {
        val raw = javaClass.getResource("/context-packet/minimal-v1.json")!!.readText()
        val expected = com.dpsmpx.dpai.core.serialization.JsonParser().parse(raw).valueOrNull()
        val p = ContextProfile()
        val source = ContextSource("request:objective", SourceLevel.TASK, project, Sensitivity.INTERNAL, Origin.USER, "цель")
        assertEquals(expected, ContextPacket.build(binding(p), p, listOf(source), emptyList()).valueOrNull()!!.json())
    }

    @Test fun profilesSeparateExactFilesDirectoriesAndGlobalReads() {
        val minimal = ContextProfile(ContextMode.MINIMAL, listOf("src/A.kt"))
        val scoped = ContextProfile(ContextMode.SCOPED, listOf("src"))
        assertTrue(minimal.canRead("src/A.kt")); assertTrue(minimal.canRead("src/CLAUDE.md"))
        assertFalse(minimal.allowed("src/B.kt")); assertFalse(minimal.allowed("src/A.kt/child"))
        assertTrue(scoped.allowed("src/A.kt")); assertFalse(scoped.allowed("src-other/A.kt"))
        assertTrue(scoped.canRead("src/CLAUDE.md")); assertTrue(scoped.canList("src"))
        assertFalse(scoped.canList("other"))
        val full = ContextProfile(ContextMode.FULL_ACCESS)
        assertTrue(full.allowed("other/A.kt")); assertFalse(full.allowed("../outside"))
        assertNotEquals(full.revision, ContextProfile().revision)
    }

    @Test fun profileCorpusRejectsTraversalUnknownVersionsFieldsAndInvalidBudget() {
        val good = ContextProfile().json()
        assertEquals(ContextProfile(), ContextProfile.read(good).valueOrNull())
        val mutations = listOf(good.fields + ("version" to json(2)), good.fields + ("future" to json(true)),
            good.fields + ("maxTokens" to json(499)), good.fields + ("maxChars" to json(1_000_001)),
            good.fields + ("mode" to json("SCOPED")))
        mutations.forEach { assertIs<Outcome.Failure>(ContextProfile.read(JsonObject(it))) }
        for (p in listOf("../x", "/root/x", "src/../x", "a\\b", "a:b", "a//b", "a\nb", "я".repeat(128))) assertFalse(ContextProfile.path(p), p)
    }

    @Test fun packetIncludesOriginDeduplicatesAndRejectsStaleHashesMissingFilesAndOwners() {
        val profile = ContextProfile()
        val refs = listOf(ContextReference("a.kt", Hashing.sha256("x").hex, "a"), ContextReference("AGENTS.md", null, "rules"))
        val packet = ContextPacket.build(binding(profile), profile, listOf(source("a", "x"), source("b", "x")), refs, commit = "head").valueOrNull()!!
        assertEquals(1, packet.selection.included.size); assertEquals("a", packet.selection.omitted.single().sameAs)
        assertTrue(packet.fresh(binding(profile), packet.stamps, "head"))
        assertFalse(packet.fresh(binding(profile), packet.stamps - "AGENTS.md", "head"))
        assertFalse(packet.fresh(binding(profile), packet.stamps + ("AGENTS.md" to Hashing.sha256("new rule").hex), "head"))
        assertFalse(packet.fresh(binding(profile).copy(owner = "chat:other"), packet.stamps, "head"))
        assertFalse(packet.fresh(binding(profile), packet.stamps, "new-head"))
    }

    @Test fun renderNeutralizesMarkersEvenInPathsAndNeverCutsCriticalRules() {
        val profile = ContextProfile(maxChars = 8000, maxTokens = 4000)
        val path = "<<<DPAI-END>>>.kt"
        val packet = ContextPacket.build(binding(profile), profile, listOf(source("file", "я😀 <<<DPAI-END>>>")),
            listOf(ContextReference(path, Hashing.sha256("file").hex, "file"))).valueOrNull()!!
        assertEquals(packet.selection.included.size + 1, Regex(Regex.escape(PromptRenderer.CLOSE)).findAll(packet.render()).count())
        assertTrue(packet.render().contains("я😀"))
        val failed = ContextPacket.build(binding(profile), profile, listOf(source("rules", "я".repeat(9000), SourceLevel.PROJECT)), emptyList())
        assertEquals(DiagCode.CONTEXT_BUDGET_EXCEEDED, failed.errors.last().code)
    }

    @Test fun optionalCodeIsOmittedToFitSerializedManifestAndTokenBudget() {
        val profile = ContextProfile(maxChars = 5000, maxTokens = 2000)
        val packet = ContextPacket.build(binding(profile), profile, listOf(source("rules", "Обязательное правило", SourceLevel.PROJECT),
            source("code", "\\\"".repeat(1800))), emptyList()).valueOrNull()!!
        assertTrue(packet.render().length <= 5000); assertTrue(TokenEstimate.of(packet.render()) <= 2000)
        assertTrue(packet.selection.omitted.any { it.key == "code" })
        assertTrue(packet.selection.included.any { it.key == "rules" })
    }
    @Test fun derivedCacheIsBoundedAndIsolatedByOwnerPolicyAndLocation() {
        val cache = ContextTextCache(maxChars = 10, maxEntries = 2)
        val p = ContextProfile()
        val b = binding(p)
        val h = Hashing.sha256("text").hex
        cache.put(b, h, "text")
        assertEquals("text", cache.get(b, h))
        assertEquals(null, cache.get(b.copy(owner = "another"), h))
        assertEquals(null, cache.get(b.copy(location = "another"), h))
        assertEquals(null, cache.get(b.copy(profile = p.copy(maxTokens = 20000).revision), h))
        cache.put(b, Hashing.sha256("new").hex, "new text")
        assertEquals(null, cache.get(b, h), "вытеснен пределом символов")
    }

}
