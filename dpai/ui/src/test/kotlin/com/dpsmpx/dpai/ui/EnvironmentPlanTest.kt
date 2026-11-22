package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.linux.ProotHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EnvironmentPlanTest {
    @Test fun packagePlanUsesNativeApkWithoutShellAndRepairsKnownDamage() {
        val plan = EnvironmentPlan.create("environment.apk.add", mapOf("packages" to "openjdk21-jdk python3 git"), listOf("binutils")).valueOrNull()!!
        assertTrue(plan.profile.steps.size > 2)
        assertTrue(plan.profile.steps.take(2).all { ProotHost.needsNoProc(it.command) })
        assertTrue(plan.profile.steps.any { it.command.first() == "java" })
        assertTrue(plan.profile.valid)
        assertTrue(plan.snapshot)
        assertEquals(ActionLevel.REVERSIBLE, plan.level)
        for (bad in listOf("--allow-untrusted", "git;touch", "$(bad)", "../pkg"))
            assertIs<Outcome.Failure>(EnvironmentPlan.create("environment.apk.add", mapOf("packages" to bad)))
    }

    @Test fun arbitraryCommandCannotLowerItsLevelAndSkipSnapshotIsExplicit() {
        val first = EnvironmentPlan.create("environment.command", mapOf("argv" to "[\"echo\",\"a b\"]")).valueOrNull()!!
        assertEquals(listOf("echo", "a b"), first.profile.steps.single().command)
        assertEquals(ActionLevel.DANGEROUS, first.level)
        assertTrue(first.snapshot)
        assertFalse(EnvironmentPlan.create("environment.command", mapOf("argv" to "[\"echo\",\"test\"]", "snapshot" to "skip")).valueOrNull()!!.snapshot)
        assertIs<Outcome.Failure>(EnvironmentPlan.create("environment.command", mapOf("argv" to "java -version")))
        assertIs<Outcome.Failure>(EnvironmentPlan.create("environment.command", mapOf("argv" to "[\"echo\",4]")))
        assertIs<Outcome.Failure>(EnvironmentPlan.create("environment.command", mapOf("argv" to "[\"echo\"]", "timeoutSeconds" to "0")))
    }

    @Test fun sdkVersionsAreTakenFromTheRequestWithoutShellInjection() {
        val plan = EnvironmentPlan.create("environment.android_sdk", mapOf("api" to "37", "buildTools" to "37.0.0")).valueOrNull()!!
        assertTrue(plan.profile.valid)
        assertTrue(plan.sdkLicense)
        val script = plan.profile.steps.single { AndroidSdk.CMDLINE_URL in it.command.last() }.command.last()
        assertTrue(script.contains("'platforms;android-37' 'build-tools;37.0.0'"))
        assertTrue(script.contains(AndroidSdk.CMDLINE_SHA256))
        val probe = plan.profile.steps.last().command.last()
        assertTrue(probe.contains("platforms/android-37/android.jar"))
        assertTrue(probe.contains("build-tools/37.0.0/source.properties"))
        assertEquals(plan.profile, ManualProfile.read(plan.profile.toJson()), "скрипты проходят сохранение без раскрытия shell-переменных")
        assertIs<Outcome.Failure>(EnvironmentPlan.create("environment.android_sdk", mapOf("api" to "37;echo")))
        val current = EnvironmentPlan.create("environment.android_sdk", mapOf("api" to "37.0")).valueOrNull()!!
        assertTrue(current.profile.steps.last().command.last().contains("platforms/android-37.0/android.jar"))
    }

    @Test fun installedSdkIsDiscoveredButExplicitConfigurationWins() {
        assertEquals(emptyMap(), AndroidSdk.discoveredEnv(emptyMap()) { false })
        val discovered = AndroidSdk.discoveredEnv(mapOf("PATH" to "/bin")) { true }
        assertEquals(AndroidSdk.HOME, discovered["ANDROID_HOME"])
        assertEquals(AndroidSdk.HOME, discovered["ANDROID_SDK_ROOT"])
        val own = AndroidSdk.discoveredEnv(mapOf("ANDROID_HOME" to "/custom")) { false }
        assertEquals("/custom", own["ANDROID_SDK_ROOT"])
    }
}
