package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.automation.AutomationCatalog
import com.dpsmpx.dpai.automation.AutomationInvocation
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.operation.Actor
import com.dpsmpx.dpai.core.operation.ActorKind
import com.dpsmpx.dpai.core.operation.Provenance
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.*
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.LocalTree
import com.dpsmpx.dpai.git.GitStatus
import com.dpsmpx.dpai.core.measure.Measure
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DevelopmentToolsTest {
    @TempDir lateinit var dir: Path
    private val clock = ManualClock()
    private val tree get() = LocalTree(dir)
    private fun file(path: String, text: String) { val f = dir.resolve(path).toFile(); f.parentFile.mkdirs(); f.writeText(text) }
    private fun run(tool: String, values: Map<String, String> = emptyMap(), source: FileTree = tree) =
        DevelopmentTools.run("development.$tool", values, source, "/project", clock)
    private fun status(vararg paths: String) = GitStatus("main", "a".repeat(40), Measure.unknown("test"), Measure.unknown("test"), paths.toList(), emptyList(), emptyList())

    @Test fun structureExcludesSecretsGeneratedTreesAndLinks() {
        file("build.gradle.kts", "plugins {}")
        file("src/App.kt", "class App")
        file(".env", "never expose")
        file(".git/config", "not source")
        file("build/generated/Noise.kt", "class Noise")
        val result = run("structure").valueOrNull()!!
        assertEquals(json(2), result["files"])
        assertEquals(json(false), result["contentSnapshot"])
        assertEquals(json("READ_ONLY"), result["effect"])
        assertFalse(CanonicalJson.write(result).contains("never expose"))
        assertEquals("class App", dir.resolve("src/App.kt").toFile().readText())
    }

    @Test fun relevantFilesAreClearlyLexicalAndKeepUsefulPaths() {
        file("src/AccountStore.kt", "class AccountStore")
        file("src/Unrelated.kt", "class Unrelated")
        val report = run("find_files", mapOf("query" to "account store")).valueOrNull()!!
        assertEquals(json(false), report["semantic"])
        assertTrue(CanonicalJson.write(report).contains("src/AccountStore.kt"))
        assertFalse(CanonicalJson.write(report).contains("src/Unrelated.kt"))
    }

    @Test fun explicitLogUsesTheSameNormalizerWithoutInventingAToolVersionOrSuccess() {
        file("reports/runtime.log", "src/App.kt:7:2: error: unresolved reference 'sample'\nsrc/App.kt:7:2: error: unresolved reference 'sample'")
        val report = run("diagnostics", mapOf("path" to "reports/runtime.log")).valueOrNull()!!
        assertEquals(json(1), report["errors"], "дублированная диагностика объединена")
        val item = (report["items"] as JsonArray).items.single() as JsonObject
        assertEquals(JsonNull, item["toolVersion"])
        assertEquals(json(false), item["rootCauseProven"])
        assertEquals(json(false), report["complete"])
    }

    @Test fun largeResultRemainsValidBoundedJsonAndDisclosesTruncation() {
        val segment = "a".repeat(180)
        repeat(100) { file("$segment/module$it/build.gradle.kts", "plugins {}") }
        val report = run("structure").valueOrNull()!!
        val encoded = CanonicalJson.write(report)
        assertTrue(encoded.length <= 24_000)
        assertTrue(JsonParser().parse(encoded).isSuccess)
        assertEquals(json(true), report["truncated"])
    }

    @Test fun impactFindsTransitiveCandidatesAndRequiresFullVerification() {
        file("a/build.gradle.kts", "dependencies { implementation(project(\":b\")) }")
        file("b/build.gradle.kts", "plugins {}")
        file("a/src/test/A_test.kt", "test")
        val report = DevelopmentTools.run("development.impact", emptyMap(), tree, "/project", clock,
            git = { Outcome.success(DevelopmentTools.GitEvidence(status("b/src/main/B.kt"))) }).valueOrNull()!!
        assertEquals(JsonArray(listOf(json("a"), json("b"))), report["potentiallyAffectedModules"])
        assertEquals(json(true), report["fullVerificationRequired"])
        assertEquals(json(false), report["testSelectionProven"])
        assertEquals(json(false), report["complete"])
        assertTrue(CanonicalJson.write(report).contains("a/src/test/A_test.kt"))
    }

    @Test fun cancellationAndMonotonicDeadlineStopTraversalWithoutWrites() {
        var calls = 0
        val source = object : FileTree by tree {
            override fun list(dir: String) = tree.list(dir).also { calls++; clock.advance(61.seconds) }
            override fun write(path: String, bytes: ByteArray) = error("analysis must not write")
        }
        assertEquals(DiagCode.CANCELLED_BY_USER, run("structure", source = source).errors.single().code)
        assertEquals(1, calls)
        val blocked = DevelopmentTools.run("development.structure", emptyMap(), source, "/project", clock, cancelled = { true })
        assertIs<Outcome.Failure>(blocked)
        assertEquals(1, calls)
    }

    @Test fun reportMayReadBuildOutputButStillRejectsSecretsAndTraversal() {
        file("build/results.xml", "<testsuite tests='0'/>")
        assertIs<Outcome.Success<*>>(run("tests", mapOf("path" to "build/results.xml")))
        for (path in listOf("../outside.xml", "/absolute.xml", "build/.env", "build/.ssh/id_rsa", ".gradle/data.xml")) {
            assertIs<Outcome.Failure>(run("tests", mapOf("path" to path)))
        }
        assertIs<Outcome.Failure>(run("structure", mapOf("unknown" to "value")))
    }

    @Test fun artifactChecksActualBytesAndNeverClaimsSignatureVerification() {
        file("build/output.apk", "synthetic bytes")
        val digest = Hashing.sha256("synthetic bytes").hex
        val ok = run("artifact", mapOf("path" to "build/output.apk", "sha256" to digest)).valueOrNull()!!
        assertEquals(json(true), ok["checksumVerified"])
        assertEquals(json(false), ok["signatureVerified"])
        assertEquals(DiagCode.STATE_CONFLICT, run("artifact", mapOf("path" to "build/output.apk", "sha256" to "0".repeat(64))).errors.single().code)
    }

    @Test fun semanticInputIsRecheckedAfterProviderReturns() {
        file("src/App.kt", "class App")
        val report = DevelopmentTools.run("development.symbol", mapOf("path" to "src/App.kt", "sha256" to Hashing.sha256("class App").hex),
            tree, "/project", clock, semantic = { _, _, _ -> file("src/App.kt", "class Changed"); Outcome.success(JsonObject.of()) })
        assertEquals(DiagCode.STATE_CONFLICT, report.errors.single().code)
    }

    @Test fun duplicateBytesAreNotCalledUnusedComponents() {
        val body = "class Sample { fun run() = Unit }\n"
        file("a/Sample.kt", body); file("b/Sample.kt", body); file("c/Sample.kt", body + "// different")
        val report = run("duplicates").valueOrNull()!!
        assertEquals(json(false), report["semantic"])
        assertEquals(1, (report["groups"] as JsonArray).items.size)
        assertFalse(CanonicalJson.write(report["groups"]!!).contains("c/Sample.kt"))
    }

    @Test fun sharedCatalogKeepsAuthorizationAndRejectsStaleRevisionsBeforeReads() {
        val definitions = DevelopmentTools.describe("/project", TargetPlatform.POSIX, true)
        assertEquals(12, definitions.size)
        assertTrue(definitions.all { it.level == ActionLevel.READ && it.invocation == AutomationInvocation.DIRECT })
        assertFalse(DevelopmentTools.describe("/project", TargetPlatform.POSIX, false).single { it.id == "development.symbol" }.available)
        val catalog = AutomationCatalog.of(definitions).valueOrNull()!!
        val d = definitions.first()
        var reads = 0
        val result = catalog.invoke(d.id, d.revision, JsonObject.of(), Provenance(Actor(ActorKind.AGENT, "test")),
            IdGenerator(clock, RandomSource.seeded(223)), clock, { false },
            { Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "not this project") }, { _, _ -> reads++; Outcome.success(JsonObject.of()) })
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, result.diagnostics.single().code)
        assertEquals(0, reads)
        val stale = catalog.invoke(d.id, "0".repeat(64), JsonObject.of(), Provenance(Actor(ActorKind.USER, "test")),
            IdGenerator(clock, RandomSource.seeded(224)), clock, { false }, { Outcome.success(Unit) },
            { _, _ -> reads++; Outcome.success(JsonObject.of()) })
        assertEquals(DiagCode.STATE_CONFLICT, stale.diagnostics.single().code)
        assertEquals(0, reads)
    }
}
