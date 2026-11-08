package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpmeta.formats.DpaiManifest
import com.dpsmpx.dpmeta.formats.RegistryFormat
import com.dpsmpx.dpmeta.model.Registry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Манифест `dpai.ecosystem` v1 из реестра пульта (`DP_AI_Orcestrator:D-114`):
 * на синтетическом реестре и очереди — то же, что строит
 * `DPmeta:tools/manifest.py` (образец `manifest/expected.json` — его
 * вывод на том же входе); поля клона — по правилам DPmeta.
 */
class DpaiManifestTest {
    private val dir = File(System.getProperty("corpus.manifest") ?: error("нет corpus.manifest"))

    private fun registry(): Registry = (RegistryFormat.parse(File(dir, "registry.toml").readText(), "registry.toml") as Outcome.Success).value

    private fun json(text: String): JsonValue = (JsonParser().parse(text) as Outcome.Success).value

    @Test
    fun sameAsDpmetaGeneratorWithoutClones() {
        val built = DpaiManifest.build(registry(), emptyMap(), File(dir, "queue.txt").readText())
        assertTrue(built is Outcome.Success, built.toString())
        assertEquals(json(File(dir, "expected.json").readText()), json(built.value))
    }

    @Test
    fun cloneFactsGoToStateAndDocuments() {
        val clones = mapOf(
            "Alpha" to DpaiManifest.Clone("main", "abc1234", "2026-11-07", listOf("README.md", "docs/DECISIONS.md", "TODO.md", "OTHER.md")),
            "Beta Tool" to DpaiManifest.Clone(null),
        )
        val built = (DpaiManifest.build(registry(), clones, "") as Outcome.Success).value
        val projects = ((json(built) as JsonObject).fields["projects"] as JsonArray).items.map { it as JsonObject }
        val alpha = projects.first { (it.fields["key"] as JsonString).value == "alpha" }
        assertEquals(
            JsonObject.of("place" to JsonString("core"), "branch" to JsonString("main"), "head" to JsonString("abc1234"), "updated" to JsonString("2026-11-07")),
            alpha.fields["state"],
        )
        // Порядок — точки входа, затем журнал решений; лишние файлы клона не попадают.
        assertEquals(JsonArray(listOf("README.md", "TODO.md", "docs/DECISIONS.md").map(::JsonString)), alpha.fields["documents"])
        val beta = projects.first { (it.fields["key"] as JsonString).value == "beta-tool" }
        assertEquals(JsonObject.of("place" to JsonString("support"), "clone" to JsonString("без основной ветки")), beta.fields["state"])
        assertTrue(projects.none { (it.fields["name"] as JsonString).value == "Gamma" }, "архив в манифест не входит")
    }

    @Test
    fun repeatedProjectKeyIsRefused() {
        val reg = registry()
        val twin = reg.copy(projects = reg.projects + reg.projects.first().copy(name = "ALPHA"))
        assertTrue(DpaiManifest.build(twin, emptyMap(), "") is Outcome.Failure)
    }
}
