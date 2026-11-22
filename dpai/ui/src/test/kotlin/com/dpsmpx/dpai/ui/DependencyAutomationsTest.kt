package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.automation.AutomationCatalog
import com.dpsmpx.dpai.automation.AutomationInvocation
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.project.DependencyTools
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.files.LocalTree
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DependencyAutomationsTest {
    @TempDir lateinit var dir: Path
    private val tree get() = LocalTree(dir)
    private val report get() = dir.resolve(".dp-ide/dependencies/python-report.json").toFile().apply { parentFile.mkdirs() }
    private fun plan(vararg values: Pair<String, String>) = DependencyAutomations.plan(mapOf(*values), TargetPlatform.POSIX, true)

    @Test fun catalogExposesTypedOperationsWithoutEmbeddingTheRecipe() {
        val entries = DependencyAutomations.describe("/project", TargetPlatform.POSIX, true)
        assertIs<Outcome.Success<*>>(AutomationCatalog.of(entries))
        assertTrue(entries.all { it.invocation == AutomationInvocation.DIRECT && it.remote })
        assertEquals(ActionLevel.REVERSIBLE, entries.first().level)
        assertEquals(ActionLevel.READ, entries.last().level)
        assertTrue(entries.first().parameters.first { it.name == "manager" }.required)
        assertFalse(CanonicalJson.write(entries.first().toJson()).contains("dpIdeDependencyReport"), "модель не получает весь Python/Gradle-рецепт")
        val windows = DependencyAutomations.describe("C:/project", TargetPlatform.WINDOWS, false).first()
        assertEquals("python", windows.parameters.first { it.name == "python" }.defaultValue)
        assertEquals(listOf("no"), windows.parameters.first { it.name == "androidSdk" }.choices)
        assertNotEquals(entries.first().revision, windows.revision)
    }

    @Test fun nativePanelAndApiUseTheSameCommandsForEverySupportedOperation() {
        for (manager in DependencyTools.Manager.entries) for (mode in DependencyTools.Mode.entries) {
            if (mode == DependencyTools.Mode.RESTORE && manager != DependencyTools.Manager.PYTHON) continue
            val api = plan("manager" to manager.code, "mode" to mode.code, "python" to "/my venv/bin/python").valueOrNull()!!
            val native = DependencyProfiles.create(manager, mode, "/my venv/bin/python", false, "Локализованная подпись").valueOrNull()!!
            assertEquals(native.steps.single().command, api.profile.steps.single().command)
            assertEquals(AutomationPlan.Scope.PROJECT, api.scope)
            assertEquals(ActionLevel.REVERSIBLE, api.level)
            assertFalse(api.snapshot)
            assertEquals(api.profile, ManualProfile.read(api.profile.toJson()))
            assertEquals(api.profile, api.profile.resolve(emptyMap()).valueOrNull(), "подтверждённые argv не меняются при исполнении")
        }
    }

    @Test fun invalidOperationsAndInterpreterSubstitutionsAreRejectedBeforeExecution() {
        for (bad in listOf(mapOf("manager" to "../python"), mapOf("manager" to "gradle", "mode" to "restore"),
            mapOf("manager" to "python", "androidSdk" to "yes"), mapOf("manager" to "python", "approved" to "yes"),
            mapOf("manager" to "python", "python" to "\${ROOT}/python"), mapOf("manager" to "python", "python" to "python\nother"))) {
            assertIs<Outcome.Failure>(DependencyAutomations.plan(bad, TargetPlatform.POSIX, true))
        }
        assertIs<Outcome.Failure>(DependencyAutomations.plan(mapOf("manager" to "gradle", "androidSdk" to "yes"), TargetPlatform.WINDOWS, false))
        val sdk = plan("manager" to "gradle", "androidSdk" to "yes").valueOrNull()!!
        assertEquals(setOf(ManualNeed.ANDROID_SDK), sdk.profile.needs)
        // Прежняя ручная форма продолжает поддерживать встроенные подстановки пользователя.
        assertIs<Outcome.Success<*>>(DependencyProfiles.create(DependencyTools.Manager.PYTHON, DependencyTools.Mode.REPORT, "\${ROOT}/.venv/bin/python", false))
    }

    @Test fun readerOnlyOpensTheNamedReportAndKeepsServiceFilesHidden() {
        report.writeText("{\"format\":\"sample\"}")
        val apiFiles = ApiProjectFiles(tree, { _, _, _ -> Outcome.success(Unit) }, { _, _ -> Outcome.success(Unit) })
        assertIs<Outcome.Failure>(apiFiles.run("read_file", JsonObject.of("path" to JsonString(".dp-ide/dependencies/python-report.json"))))
        assertIs<Outcome.Failure>(DependencyAutomations.read(tree, mapOf("manager" to "../../credentials")))
        assertIs<Outcome.Failure>(DependencyAutomations.read(tree, mapOf("manager" to "python", "path" to ".dp-ide/private.json")))
        val page = DependencyAutomations.read(tree, mapOf("manager" to "python")).valueOrNull()!!
        assertEquals(JsonBool(true), page["storedReport"])
        assertEquals(JsonBool(false), page["freshnessVerified"])
        assertEquals(JsonString(Hashing.sha256(report.readBytes()).hex), page["sha256"])
        assertEquals(JsonString(report.readText()), page["content"])
    }

    @Test fun changingAReportBetweenPagesRequiresRestartingTheRead() {
        report.writeText("row\n".repeat(8000))
        val first = DependencyAutomations.read(tree, mapOf("manager" to "python")).valueOrNull()!!
        val offset = (first["nextOffset"] as JsonNumber).toLongExact()!!.toString()
        val sha = (first["sha256"] as JsonString).value
        assertIs<Outcome.Failure>(DependencyAutomations.read(tree, mapOf("manager" to "python", "offset" to offset)))
        report.appendText("changed")
        val next = DependencyAutomations.read(tree, mapOf("manager" to "python", "offset" to offset, "sha256" to sha))
        assertEquals(DiagCode.STATE_CONFLICT, next.diagnostics.single().code)
    }

    @Test fun wholeReportIsRedactedBeforeUtf16AndJsonPagination() {
        val secret = "gh" + "p_" + "a".repeat(36)
        val original = "x".repeat(17989) + secret + "\"\\\n\t".repeat(6000) + "😀".repeat(8000)
        report.writeText(original)
        val joined = StringBuilder()
        var offset = "0"
        var sha: String? = null
        do {
            val values = mapOf("manager" to "python", "offset" to offset) + (sha?.let { mapOf("sha256" to it) } ?: emptyMap())
            val page = DependencyAutomations.read(tree, values).valueOrNull()!!
            val encoded = CanonicalJson.write(page)
            assertIs<Outcome.Success<*>>(JsonParser().parse(encoded))
            assertTrue(encoded.length < 31_000)
            assertEquals(JsonBool(true), page["redacted"])
            val text = (page["content"] as JsonString).value
            assertFalse(text.lastOrNull()?.isHighSurrogate() == true)
            joined.append(text)
            sha = (page["sha256"] as JsonString).value
            offset = (page["nextOffset"] as JsonNumber).toLongExact()!!.toString()
        } while (page["complete"] != JsonBool(true))
        assertFalse(secret in joined)
        assertTrue(joined.endsWith("😀".repeat(8000)))
        assertEquals(original, report.readText(), "чтение и редактирование выдачи не меняют файл")
    }

    @Test fun reportsLargerThanEditorLimitAreReadableButEightMiBLimitIsEnforced() {
        report.writeText("x".repeat(2 * 1024 * 1024 + 100))
        assertIs<Outcome.Success<*>>(DependencyAutomations.read(tree, mapOf("manager" to "python")))
        report.writeText("x".repeat(8 * 1024 * 1024 + 1))
        assertIs<Outcome.Failure>(DependencyAutomations.read(tree, mapOf("manager" to "python")))
    }

    @Test fun reportSymlinkCannotReadOutsideTheProject() {
        val outside = Files.createTempFile("dpai-report-outside", ".txt")
        try {
            Files.writeString(outside, "private outside data")
            val path = report.toPath()
            Files.createSymbolicLink(path, outside)
            assertIs<Outcome.Failure>(DependencyAutomations.read(tree, mapOf("manager" to "python")))
        } finally { Files.deleteIfExists(outside) }
    }

    @Test fun serviceDirectorySymlinkCannotExposeAnOutsideReport() {
        val outside = Files.createTempDirectory("dpai-report-directory")
        try {
            val file = outside.resolve("python-report.json")
            Files.writeString(file, "outside service data")
            val base = dir.resolve(".dp-ide").toFile().apply { mkdirs() }.toPath()
            Files.createSymbolicLink(base.resolve("dependencies"), outside)
            assertIs<Outcome.Failure>(DependencyAutomations.read(tree, mapOf("manager" to "python")))
        } finally {
            Files.deleteIfExists(outside.resolve("python-report.json"))
            Files.deleteIfExists(outside)
        }
    }
}
