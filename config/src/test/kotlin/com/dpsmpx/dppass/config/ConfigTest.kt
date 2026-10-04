package com.dpsmpx.dppass.config

import com.dpsmpx.dppass.core.Json
import com.dpsmpx.dppass.core.JsonNumber
import com.dpsmpx.dppass.core.JsonObject
import com.dpsmpx.dppass.core.JsonString
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Outcome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal object Fixtures {
    private val root = File(System.getProperty("dppass.repoRoot") ?: "../")
    fun text(path: String): String = File(root, "tests/data/$path").readText()
}

class ConfigLoaderTest {
    private val loader = ConfigLoader()

    @Test
    fun validMultiLoads() {
        val r = loader.load(Fixtures.text("configs/valid-multi.json"))
        val ir = assertNotNull(r.valueOrNull()).ir
        assertEquals(6, ir.profiles.size)
        assertEquals(TransportClass.UDP_QUIC, ir.profiles.first { it.id.value == "hy2" }.transportClass)
        assertTrue(r.errors.isEmpty())
    }

    @Test
    fun errorsCarryPaths() {
        val r = loader.load(Fixtures.text("configs/invalid-reality-key.json"))
        assertTrue(r is Outcome.Failure)
        val d = r.errors.single()
        assertEquals(DiagCode.REALITY_PUBLIC_KEY_INVALID, d.code)
        assertEquals("$.profiles[0].security.public_key", d.path)
    }

    @Test
    fun limitsAreEnforcedBeforeParsing() {
        val small = ConfigLoader(ConfigLimits(maxBytes = 64))
        assertEquals(listOf(DiagCode.DOCUMENT_TOO_LARGE), small.load(Fixtures.text("configs/valid-minimal.json")).diagnostics.map { it.code })
        assertEquals(listOf(DiagCode.DOCUMENT_TOO_DEEP), loader.load("[".repeat(10_000)).diagnostics.map { it.code })
        assertEquals(listOf(DiagCode.DOCUMENT_EMPTY), loader.load("  \n").diagnostics.map { it.code })
    }

    @Test
    fun syntaxErrorDoesNotQuoteInput() {
        val r = loader.load("""{"format":"dppass.config","secret":"00000000-0000-4000-8000-000000000001" oops}""")
        assertEquals(DiagCode.JSON_SYNTAX, r.errors.single().code)
        assertFalse("00000000" in r.errors.single().message)
    }

    @Test
    fun unknownValuesAreErrorsNotGuesses() {
        val text = Fixtures.text("configs/valid-minimal.json").replace("\"fingerprint\": \"chrome\"", "\"fingerprint\": \"chrom\"")
        assertEquals(listOf(DiagCode.VALUE_UNKNOWN), loader.load(text).errors.map { it.code })
    }

    @Test
    fun foreignDocumentIsNotDppass() {
        assertEquals(DiagCode.FORMAT_NOT_DPPASS, loader.load("""{"outbounds":[]}""").errors.single().code)
    }
}

class MigrationTest {
    private fun doc(version: Int) = Json.obj("format" to Json.str("dppass.config"), "format_version" to Json.num(version))

    @Test
    fun newerVersionIsRejectedAndInputUntouched() {
        val input = doc(7)
        val r = Migrator(currentVersion = 1).toCurrent(input)
        assertEquals(DiagCode.FORMAT_VERSION_UNSUPPORTED, r.errors.single().code)
        assertEquals(doc(7), input)
    }

    @Test
    fun chainAppliesExplicitSteps() {
        val step = ConfigMigration { d ->
            val m = LinkedHashMap(d.fields)
            m["format_version"] = JsonNumber("2")
            m["migrated"] = JsonString("yes")
            Outcome.Success(JsonObject(m))
        }
        val r = Migrator(currentVersion = 2, migrations = mapOf(1 to step)).toCurrent(doc(1))
        val out = assertNotNull(r.valueOrNull())
        assertEquals(1, out.fromVersion)
        assertEquals(JsonString("yes"), out.document["migrated"])
        assertTrue(r.diagnostics.any { it.code == DiagCode.MIGRATION_APPLIED })
    }

    @Test
    fun missingStepIsUnsupportedNotDefaulted() {
        val r = Migrator(currentVersion = 3, migrations = emptyMap()).toCurrent(doc(1))
        assertEquals(DiagCode.FORMAT_VERSION_UNSUPPORTED, r.errors.single().code)
    }

    @Test
    fun failedStepReportsCauseAndKeepsOriginal() {
        val input = doc(1)
        val failing = ConfigMigration { Outcome.failure(DiagCode.FIELD_MISSING, "нет поля x") }
        val r = Migrator(currentVersion = 2, migrations = mapOf(1 to failing)).toCurrent(input)
        val e = r.errors.single()
        assertEquals(DiagCode.MIGRATION_FAILED, e.code)
        assertEquals(DiagCode.FIELD_MISSING, e.causes.single().code)
        assertEquals(doc(1), input)
    }
}

class WriterAndNormalizerTest {
    private val loader = ConfigLoader()

    @Test
    fun fullExportRoundTrips() {
        val ir = loader.load(Fixtures.text("configs/valid-multi.json")).valueOrNull()!!.ir
        val again = loader.load(ConfigWriter.write(ir, ConfigWriter.Mode.FULL)).valueOrNull()!!.ir
        assertEquals(ir, again)
    }

    @Test
    fun redactedExportHasNoSecretsAndCannotActivate() {
        val text = Fixtures.text("configs/valid-multi.json")
        val ir = loader.load(text).valueOrNull()!!.ir
        val exported = ConfigWriter.write(ir)
        for (secret in listOf("00000000-0000-4000-8000-000000000001", "test-password-1", "test-password-2", "01234567")) {
            assertFalse(secret in exported, "в экспорте осталось $secret")
        }
        assertTrue(loader.load(exported).errors.isNotEmpty())
    }

    @Test
    fun normalizationIsIdempotentAndKeepsRuleOrder() {
        val text = Fixtures.text("configs/valid-multi.json")
            .replace(Regex("\"domain_suffix\": \\[\\s*\"example\\.ru\"\\s*]"), "\"domain_suffix\": [\"example.ru\", \"EXAMPLE.ru\"]")
        assertTrue("EXAMPLE.ru" in text, "замена в фикстуре не сработала")
        val r = loader.load(text)
        val ir = r.valueOrNull()!!.ir
        assertTrue(r.diagnostics.any { it.code == DiagCode.RULE_VALUE_DUPLICATE })
        val once = Normalizer.normalize(ir)
        assertEquals(ir, once.ir)
        assertTrue(once.notes.isEmpty())
        assertEquals(listOf("bank-app-direct", "ads-block", "ru-direct", "ru-ip-direct", "quic-block"), ir.routing.rules.map { it.id.value })
    }
}
