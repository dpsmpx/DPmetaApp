package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.automation.AutomationCatalog
import com.dpsmpx.dpai.automation.AutomationInvocation
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.files.LocalTree
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AutomationSourcesTest {
    @TempDir lateinit var dir: Path
    private val profile = ManualProfile(ManualProfileKind.CHECK, listOf(ManualStep("Тест", listOf("check", "--mode", "\${MODE}"), 60)),
        params = listOf(ManualParam("MODE", "Режим", listOf("fast", "full"))))

    @Test fun manualProfileKeepsItsParametersAndDoesNotExposeCommands() {
        val defs = ManualAutomations.describe(mapOf(ManualProfileKind.CHECK to profile), null, "/project", TargetPlatform.POSIX)
        val check = defs.first { it.id == "manual.check" }
        assertTrue(check.available)
        assertEquals(listOf("fast", "full"), check.parameters.single().choices)
        assertEquals(60L, check.timeoutSeconds)
        assertFalse(CanonicalJson.write(check.toJson()).contains("--mode"))
        assertFalse(defs.first { it.id == "manual.build" }.available)
        assertEquals(AutomationInvocation.OPEN_UI, defs.first { it.id == "manual.run" }.invocation)
        assertIs<Outcome.Success<*>>(AutomationCatalog.of(defs))
    }

    @Test fun sensitiveChoicesStayInTheNativeProfileAndOutOfTheApiSchema() {
        val value = listOf("sensitive", "choice", "never", "send").joinToString("_")
        val original = profile.copy(params = profile.params + ManualParam("API_KEY", "Ключ", listOf(value)))
        val check = ManualAutomations.describe(mapOf(ManualProfileKind.CHECK to original), null, "/project", TargetPlatform.POSIX).first()
        assertEquals(listOf("MODE"), check.parameters.map { it.name })
        assertFalse(CanonicalJson.write(check.toJson()).contains(value))
        assertEquals(value, original.params.last().choices.single(), "адаптер не переписывает исходный профиль")
    }

    @Test fun sourceRevisionChangesWhenOnlyACommandArgumentChanges() {
        val a = ManualAutomations.describe(mapOf(ManualProfileKind.CHECK to profile), null, "/project", TargetPlatform.POSIX).first()
        val b = ManualAutomations.describe(mapOf(ManualProfileKind.CHECK to profile.copy(steps = listOf(ManualStep("Тест", listOf("other"), 60)))), null, "/project", TargetPlatform.POSIX).first()
        assertNotEquals(a.revision, b.revision)
    }

    @Test fun sameProfileAndPathOnAnotherHostIsNotTheSameRevision() {
        val ids = IdGenerator(ManualClock(), RandomSource.seeded(216))
        val target = TargetForm.validate(ids.next(IdKinds.TARGET), "ПК", "pc.invalid", "22", "user", false, "agent", "/home/user/.claude").valueOrNull()!!
        val project = ProjectForm.validate(ids, target, "/project", "/trees", "main", "").valueOrNull()!!
        val entries = ManualAutomations.describe(mapOf(ManualProfileKind.CHECK to profile), null, project.root, target.platform)
        val a = AutomationContext.bind(entries, project, target).valueOrNull()!!.entries.first().revision
        val b = AutomationContext.bind(entries, project, target.copy(host = "other.invalid")).valueOrNull()!!.entries.first().revision
        assertNotEquals(a, b)
    }

    @Test fun environmentAdapterUsesTheExistingOperationsAndDoesNotRunThem() {
        val entries = EnvironmentAutomations.describe(inApp = true, installed = true, root = "/project")
        assertEquals(LinuxEnvironment.Apk.entries.size + 4, entries.size)
        assertEquals(LinuxEnvironment.Apk.entries.map { "environment.apk.${it.name.lowercase()}" }.toSet(), entries.filter { it.id.startsWith("environment.apk.") }.map { it.id }.toSet())
        assertEquals(AutomationInvocation.DIRECT, entries.single { it.id == "environment.apk.add" }.invocation)
        assertEquals(AutomationInvocation.OPEN_UI, entries.single { it.id == "environment.apk.del" }.invocation)
        assertEquals(com.dpsmpx.dpai.core.operations.ActionLevel.DANGEROUS, entries.single { it.id == "environment.command" }.level)
        assertTrue(EnvironmentAutomations.describe(false, true, "/project").none { it.available })
        assertTrue(EnvironmentAutomations.describe(true, false, "/project").none { it.available })
    }

    @Test fun sharedDefaultsKeepTheFormerApiBehavior() {
        val root = dir.toFile()
        root.resolve("CMakeLists.txt").writeText("project(sample)")
        val check = ProjectProfileDefaults.default(LocalTree(dir), ManualProfileKind.CHECK)!!
        assertEquals("ctest", check.steps.last().command.first())
        assertEquals(2, ProjectProfileDefaults.default(LocalTree(dir), ManualProfileKind.BUILD)!!.steps.size)
        assertEquals(null, ProjectProfileDefaults.default(LocalTree(dir), ManualProfileKind.DEPENDENCIES))
        root.resolve("CMakeLists.txt").delete()
        root.resolve("Makefile").writeText("all:\n\ttrue\n")
        assertEquals(null, ProjectProfileDefaults.default(LocalTree(dir), ManualProfileKind.CHECK))
    }

    @Test fun windowsDefaultsUseItsWrapperWhenBothWrappersExist() {
        dir.resolve("gradlew").toFile().writeText("#!/bin/sh")
        dir.resolve("gradlew.bat").toFile().writeText("@echo off")
        val windows = ProjectProfileDefaults.default(LocalTree(dir), ManualProfileKind.CHECK, TargetPlatform.WINDOWS)!!
        val posix = ProjectProfileDefaults.default(LocalTree(dir), ManualProfileKind.CHECK, TargetPlatform.POSIX)!!
        assertEquals(listOf("cmd.exe", "/c", "gradlew.bat", "check"), windows.steps.single().command)
        assertEquals(listOf("sh", "gradlew", "check"), posix.steps.single().command)
    }

    @Test fun changingASavedParameterChoiceChangesTheAdvertisedDefaultAndRevision() {
        val a = ManualAutomations.describe(mapOf(ManualProfileKind.CHECK to profile), null, "/project", TargetPlatform.POSIX).first()
        val b = ManualAutomations.describe(mapOf(ManualProfileKind.CHECK to profile), null, "/project", TargetPlatform.POSIX,
            mapOf(ManualProfileKind.CHECK to mapOf("MODE" to "full"))).first()
        assertEquals("full", b.parameters.single().defaultValue)
        assertNotEquals(a.revision, b.revision)
    }
}
