package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidJdkTest {
    @Test fun nativeBionicJdkIsPinnedAndNotAnAlpineOrTermuxDependency() {
        assertEquals("/opt/dpai-jdk21", AndroidJdk.HOME)
        assertEquals("21.0.14", AndroidJdk.VERSION)
        assertTrue(AndroidJdk.URL.startsWith("https://github.com/"))
        assertTrue(Regex("[0-9a-f]{64}").matches(AndroidJdk.SHA256))
        assertFalse(AndroidJdk.packages.any { it.startsWith("openjdk") || it == "termux" })
        assertFalse(AndroidSdk.packages.any { it.startsWith("openjdk") })
    }

    @Test fun installNeverClaimsSuccessBeforeRealJavaAndJavacProbe() {
        val command = AndroidJdk.install()
        assertEquals(listOf("/bin/sh", "-c"), command.take(2))
        val script = command[2]
        assertTrue("sha256sum -c -" in script)
        assertTrue(AndroidJdk.URL in script && AndroidJdk.SHA256 in script)
        assertTrue("/system/bin/linker64" in script)
        assertTrue("uname -m" in script)
        assertTrue("\$JDK/bin/java" in script)
        assertTrue("\$JDK/bin/javac" in script)
        assertTrue("DPAI_JDK_START" in script)
        assertTrue("DPAI_ANDROID_JDK_OK native-bionic" in script)
        assertTrue("DPAI_JDK_CHECKSUM" in script)
        assertTrue("\$STAGE/previous" in script, "замена JDK при ошибке откатывается")
        assertFalse("paxctl" in script || "setenforce" in script)
        assertFalse("/data/data/com.termux" in script)
        assertEquals("-XX:-UseCompressedOops -Xint", AndroidJdk.INTERPRETER_OPTIONS)
    }

    @Test fun explicitJdkSetupAndSdkSetupUseNativeRuntimeBeforeSdkmanager() {
        val native = EnvironmentPlan.create("environment.android_jdk", emptyMap()).valueOrNull()!!
        assertTrue(native.profile.valid)
        assertEquals(3, native.profile.steps.size)
        assertEquals(AndroidJdk.install(), native.profile.steps[1].command)
        assertEquals(AndroidJdk.probe(), native.profile.steps[2].command)
        val sdk = EnvironmentPlan.create("environment.android_sdk", emptyMap()).valueOrNull()!!
        assertTrue(sdk.sdkLicense)
        assertEquals(AndroidJdk.install(), sdk.profile.steps[1].command)
        assertEquals(listOf("java", "-Xmx128m", "-version"), sdk.profile.steps[2].command)
        assertTrue(AndroidSdk.CMDLINE_URL in sdk.profile.steps[3].command.last())
        assertTrue(KotlinRuntime.javaEnvironment.contains("${AndroidJdk.HOME}/bin/java"))
    }

    @Test fun environmentChoosesNativeJdkOnlyWhenInstalledAndPreservesExplicitChoice() {
        val base = mapOf("PATH" to "/root/tools/bin:/usr/bin")
        assertEquals(base, AndroidJdk.environment(base, installed = false, interpreter = false))
        val native = AndroidJdk.environment(base, installed = true, interpreter = false)
        assertEquals(AndroidJdk.HOME, native["JAVA_HOME"])
        assertTrue(native.getValue("PATH").startsWith("${AndroidJdk.HOME}/bin:"))
        assertEquals(null, native["JAVA_TOOL_OPTIONS"])
        val fallback = AndroidJdk.environment(base, installed = true, interpreter = true)
        assertEquals(AndroidJdk.INTERPRETER_OPTIONS, fallback["JAVA_TOOL_OPTIONS"])
        val custom = base + ("JAVA_HOME" to "/my/jdk")
        assertEquals(custom, AndroidJdk.environment(custom, installed = true, interpreter = true))
    }
}
