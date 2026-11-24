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
        assertTrue(script.contains("/etc/ssl/certs/java/cacerts"), "Bionic JDK needs a populated trust store")
        assertTrue(script.contains("DPAI_NATIVE_JDK_CACERTS_MISSING"))
        assertTrue("java-cacerts" in AndroidSdk.packages)
        assertTrue(script.indexOf("COMPAT_MISSING") < script.indexOf("-Xmx128m -version"), "compat preload precedes the first JVM start")
        assertTrue(script.contains("pkg\" install -y openjdk-21"), "prefer Termux's official mirror-selection wrapper")
        assertTrue(script.contains("apt-get\" install -y openjdk-21"), "retain fallback for minimal bootstraps")
        assertTrue(script.contains("APT::Update::Error-Mode=any"), "DNS failure during apt update must fail even if apt uses stale indexes")
        assertTrue(script.contains("DPAI_NATIVE_JDK_APT_UPDATE_FAILED"))
        assertTrue(script.contains("/root/tools/bin/"), "native java/javac must be the default in the Alpine terminal")
        assertTrue(script.contains("DPAI_NATIVE_JDK_PATH_CONFLICT"), "do not overwrite user commands")
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
