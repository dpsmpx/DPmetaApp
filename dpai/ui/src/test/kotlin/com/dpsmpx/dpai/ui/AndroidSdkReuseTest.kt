package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Существующая полная установка распознаётся без архива кэша и без сети. */
class AndroidSdkReuseTest {
    @TempDir lateinit var dir: Path

    private fun write(relative: String, text: String, executable: Boolean = false): Path = dir.resolve(relative).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
        if (executable) check(it.toFile().setExecutable(true))
    }

    private fun fixture(): Pair<String, Map<String, String>> {
        val tool = "#!/bin/sh\nprintf 'zipalign aapt2 fixture\\n'\n"
        val sum = MessageDigest.getInstance("SHA-256").digest(tool.toByteArray()).joinToString("") { "%02x".format(it) }
        val sdk = "home/android-sdk"
        write("$sdk/cmdline-tools/latest/bin/sdkmanager", "#!/bin/sh\nexit 91\n", true)
        write("$sdk/cmdline-tools/latest/source.properties", "Pkg.Revision=20.0\n")
        write("$sdk/platforms/android-37.0/android.jar", "installed platform")
        write("$sdk/platforms/android-37.0/source.properties", "AndroidVersion.ApiLevel=37\n")
        write("$sdk/build-tools/36.1.0/source.properties", "Pkg.Revision=36.1.0\n")
        write("$sdk/build-tools/36.1.0/lib/apksigner.jar", "installed signer")
        for (name in AndroidSdk.ARM64_TOOL_SHA256.keys) write("$sdk/build-tools/36.1.0/$name", tool, true)
        write("$sdk/dpai/aapt2", tool, true)
        write("native/lib/jvm/java-21-openjdk/bin/java", "#!/bin/sh\nexit 0\n", true)
        write("native/lib/libdpai-jvm-compat.so", "")
        write("bin/uname", "#!/bin/sh\nprintf 'aarch64\\n'\n", true)
        write("bin/curl", "#!/bin/sh\ntouch '${dir.resolve("network-called")}'\nexit 99\n", true)
        var script = AndroidSdk.install(true, false, AndroidSdk.Community("https://example.invalid/aapt2", sum), null).last()
            .replace("/root", dir.resolve("home").toString())
            .replace(AndroidNativeJdk.PREFIX, dir.resolve("native").toString())
        for (digest in AndroidSdk.ARM64_TOOL_SHA256.values) script = script.replace(digest, sum)
        return script to mapOf("PATH" to (dir.resolve("bin").toString() + ":" + System.getenv("PATH")),
            "HOME" to dir.resolve("home").toString(), "DPAI_DOWNLOAD_CACHE" to dir.resolve("download-cache").toString(),
            "GRADLE_USER_HOME" to dir.resolve("gradle-home").toString())
    }

    private fun run(script: String, env: Map<String, String>) =
        (LocalProcessHost().run(ProcessSpec(listOf("/bin/sh", "-c", script), environment = env), 15.seconds) as Outcome.Success).value

    @Test fun completeSdkAndOldArm64ToolsAreReusedOffline() {
        val (script, env) = fixture()
        val result = run(script, env)
        assertEquals(0, result.exitCode, (result.stdout + result.stderr).joinToString("\n"))
        assertTrue(result.stdout.any { it.contains("DPAI_PROGRESS installed build-tools-arm64") })
        assertFalse(Files.exists(dir.resolve("network-called")))
        assertTrue(Files.readString(dir.resolve("gradle-home/gradle.properties")).contains("android.aapt2FromMavenOverride="))
    }

    @Test fun damagedInstalledBinaryIsNotAcceptedAsCompleted() {
        val (script, env) = fixture()
        write("home/android-sdk/build-tools/36.1.0/aidl", "damaged", true)
        val result = run(script, env)
        assertTrue(result.exitCode != 0)
        assertTrue(Files.exists(dir.resolve("network-called")), "повреждение требует проверенного архива, транспорт отказал")
        assertEquals("damaged", Files.readString(dir.resolve("home/android-sdk/build-tools/36.1.0/aidl")), "недоверенный ответ не установлен")
    }

    @Test fun oldSdkManagerDoesNotSilentlyKeepOldXmlParser() {
        val (script, env) = fixture()
        write("home/android-sdk/cmdline-tools/latest/source.properties", "Pkg.Revision=13.0\n")
        assertTrue(run(script, env).exitCode != 0)
        assertTrue(Files.exists(dir.resolve("network-called")))
    }
}
