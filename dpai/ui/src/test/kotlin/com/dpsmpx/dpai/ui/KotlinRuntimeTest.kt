package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KotlinRuntimeTest {
    @Test fun knownImportsOfferPinnedJvmCoordinates() {
        val r = KotlinRuntime.diagnose("error: unresolved reference: kotlinx\nimport kotlinx.coroutines.*", 1)
        assertEquals(listOf("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2"), r.maven)
        assertTrue(r.packages.isEmpty())
        assertEquals(listOf("com.google.code.gson:gson:2.13.2"), KotlinRuntime.diagnose("NoClassDefFoundError: com/google/gson/Gson", 1).maven)
    }
    @Test fun androidAndGradleRequireProjectCommandWithoutInstallingBareJvmLibraries() {
        for (text in listOf("DPAI_KOTLIN_PROJECT: /root/project", "error: unresolved reference: androidx\nimport androidx.compose.runtime.*\nimport kotlinx.coroutines.*")) {
            val r = KotlinRuntime.diagnose(text, 1)
            assertTrue(r.projectCommand)
            assertTrue(r.maven.isEmpty())
        }
    }
    @Test fun brokenCompilerOrJavaRequireManagedToolchain() {
        for (text in listOf("DPAI_KOTLIN_TOOLCHAIN: not installed", "kotlinc: not found", "UnsupportedClassVersionError")) {
            val r = KotlinRuntime.diagnose(text, 1)
            assertTrue(r.environmentProblem)
            assertEquals(KotlinRuntime.packages, r.packages)
        }
    }
    @Test fun ordinaryCodeMainAndNetworkErrorsHaveAdvice() {
        for (text in listOf("error: expecting ')'", "no main manifest attribute", "binary version of its metadata", "UnknownHostException", "Could not find artifact", "DPAI_KOTLIN_CHECKSUM: failed")) assertTrue(KotlinRuntime.diagnose(text, 1).advice.isNotEmpty(), text)
    }
    @Test fun coordinatesCannotInjectScriptOrCommandOptions() {
        for (gav in listOf("--repository", "https://evil.invalid/a.jar", "a:b:1\"); println(1)", "a:b:[1,2]", "a:b:1\nother", "a:b")) assertIs<Outcome.Failure>(KotlinRuntime.dependencies("/root", "/root/a.kt", listOf(gav)))
        val plan = KotlinRuntime.dependencies("/root", "/root/a.kt", listOf("g.example:a-jvm:1.2.3")).valueOrNull()!!
        assertEquals("g.example:a-jvm:1.2.3", plan.last())
        assertFalse(plan[2].contains("g.example:a-jvm:1.2.3"))
    }
    @Test fun commandsFitMetadataLimitsAndAllGeneratedShellProgramsParse() {
        for (file in listOf("a.kt", "a.kts", "a.py")) {
            val command = EditorTerminalRun.defaultCommand(file)
            assertTrue(command.length <= 4096, "$file: ${command.length}")
            assertIs<Outcome.Success<List<String>>>(EditorTerminalRun.argv("/root/$file", "/root", command))
        }
        val programs = listOf(KotlinRuntime.defaultCommand(false), KotlinRuntime.defaultCommand(true), KotlinRuntime.installCompiler("/root", "/root/a.kt")[2],
            KotlinRuntime.probe("/root", "/root/a.kt")[2], KotlinRuntime.dependencies("/root", "/root/a.kt", listOf("g:a:1")).valueOrNull()!![2])
        for (script in programs) {
            val result = LocalProcessHost().run(ProcessSpec(listOf("/bin/sh", "-n"), stdin = script), 8.seconds).valueOrNull()!!
            assertTrue(result.ok, result.stderr.joinToString("\n"))
        }
    }
    @Test fun sdkIsVerifiedBeforeExtractionAndFailedResolutionDoesNotPublishNewState() {
        val installer = KotlinRuntime.installCompiler("/root", "/root/a.kt")[2]
        assertTrue(installer.indexOf("sha256sum -c") < installer.indexOf("unzip -q"))
        assertTrue(installer.contains(KotlinRuntime.SHA256))
        val resolver = KotlinRuntime.dependencies("/root", "/root/a.kt", listOf("g:a:1")).valueOrNull()!![2]
        assertTrue(resolver.indexOf("-script") < resolver.indexOf("mv -fT"))
        assertTrue(resolver.contains("dpai.kotlin-libraries.v1"))
    }
}
