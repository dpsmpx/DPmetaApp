package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidNativeJdkTest {
    @Test fun bootstrapHasFixedVerifiedSourceAndPrivateVirtualPrefix() {
        val script = AndroidNativeJdk.install().last()
        assertEquals(com.dpsmpx.dpai.execution.linux.ProotHost.REAL_USER_MARKER, AndroidNativeJdk.install().first())
        assertTrue(AndroidNativeJdk.BOOTSTRAP_URL.startsWith("https://github.com/termux/termux-packages/releases/download/"))
        assertEquals(64, AndroidNativeJdk.BOOTSTRAP_SHA256.length)
        assertTrue(script.contains("sha256sum -c -"))
        assertTrue(script.contains("SYMLINKS.txt"))
        assertTrue(script.contains("DPAI_NATIVE_JDK_JAVA_FAILED"))
        assertTrue(script.contains("DPAI_NATIVE_JDK_JAVAC_FAILED"))
        assertTrue(script.contains("DPAI_NATIVE_JDK_COMPILE_FAILED"))
        assertTrue(script.contains("DPAI_NATIVE_JDK_FAKE_ROOT"))
        assertTrue(script.contains("DPAI_NATIVE_JDK_REAL_UID"))
        assertTrue(script.contains("DPAI_JDK_ROUNDTRIP_OK"))
        assertTrue(script.contains("DPAI_NATIVE_JDK_OK"))
        assertTrue(script.contains("apt-get\" install -y openjdk-21"))
        assertTrue(AndroidNativeJdk.PREFIX.startsWith("/data/data/com.termux/files/"))
        assertEquals(AndroidNativeJdk.JAVA_HOME, AndroidNativeJdk.buildEnvironment["JAVA_HOME"])
    }

    @Test fun sdkInstallerUsesBionicJdkNotAlpineJvm() {
        val script = AndroidSdk.install(true, false, null, null).last()
        assertTrue(script.contains("JAVA_HOME='${AndroidNativeJdk.JAVA_HOME}'"))
        assertTrue(script.contains("DPAI_NATIVE_JDK_MISSING"))
        assertTrue("openjdk21-jdk" !in AndroidSdk.packages)
    }
}
