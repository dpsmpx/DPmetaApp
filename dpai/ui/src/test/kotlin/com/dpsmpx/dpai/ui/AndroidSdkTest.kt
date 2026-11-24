package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Android SDK по желанию (D-185): официальное по закреплённой сумме, сообщество — по сумме автора. */
class AndroidSdkTest {
    @TempDir lateinit var dir: Path
    private val sha = "ab".repeat(32)

    @Test fun explicitNativeJavaHomeStillGetsCompatibilityAndSysrootWithoutOverwritingUserEnvironment() {
        val explicit = listOf("JAVA_HOME" to AndroidNativeJdk.JAVA_HOME, "PATH" to "/custom/bin")
        val env = AndroidSdk.env(setOf(ManualNeed.ANDROID_SDK), explicit).toMap()
        assertEquals(AndroidNativeJdk.COMPAT, env["LD_PRELOAD"])
        assertEquals(AndroidNativeJdk.SYSROOT, env["DPAI_ARM64_SYSROOT"])
        assertEquals("/custom/bin", env["PATH"])
        assertEquals(AndroidNativeJdk.JAVA_HOME, env["JAVA_HOME"])
        val own = AndroidSdk.env(setOf(ManualNeed.ANDROID_SDK), explicit + ("LD_PRELOAD" to "/custom/compat.so")).toMap()
        assertEquals("/custom/compat.so", own["LD_PRELOAD"])
        val other = AndroidSdk.env(setOf(ManualNeed.ANDROID_SDK), listOf("JAVA_HOME" to "/other/jdk")).toMap()
        assertFalse("LD_PRELOAD" in other)
    }

    @Test
    fun officialPathIsPinnedAndNeedsLicense() {
        val script = AndroidSdk.install(licenses = true, ndk = false, aapt2 = null, ndkCommunity = null).last()
        assertTrue(AndroidSdk.CMDLINE_URL in script && AndroidSdk.CMDLINE_SHA256 in script && "sha256sum -c" in script)
        assertTrue("14742923" in AndroidSdk.CMDLINE_URL, "закреплён java-sdkmanager до перехода на Android CLI")
        assertTrue("'${AndroidSdk.PLATFORM}' '${AndroidSdk.BUILD_TOOLS}'" in script)
        assertFalse("--licenses" in script, "согласие только для выбранных SDK-пакетов, не для всех лицензий каталога")
        assertTrue("aapt2FromMavenOverride" in script, "на ARM64 — автоматическая проверенная подмена aapt2")
        assertTrue(AndroidSdk.ARM64_AAPT2_URL in script && AndroidSdk.ARM64_AAPT2_SHA256 in script)
        assertTrue(AndroidSdk.ARM64_TOOLS_URL in script && AndroidSdk.ARM64_TOOLS_SHA256 in script)
        assertTrue("zipalign aidl aapt split-select" in script, "ARM64 инструменты упаковки тоже нужны Gradle")
        val refused = AndroidSdk.install(licenses = false, ndk = false, aapt2 = null, ndkCommunity = null).last()
        assertFalse("--licenses" in refused)
        assertTrue("DPAI_ANDROID_LICENSE" in refused, "без согласия с лицензией — ничего не ставится")
    }

    @Test
    fun communityBuildsAreOptionalAndChecked() {
        val aapt2 = AndroidSdk.Community("https://example.org/aapt2-arm64", sha)
        val ndk = AndroidSdk.Community("https://example.org/ndk-arm64.tar.xz", sha)
        val script = AndroidSdk.install(licenses = true, ndk = true, aapt2 = aapt2, ndkCommunity = ndk).last()
        assertTrue("android.aapt2FromMavenOverride" in script)
        assertTrue("'${AndroidSdk.NDK}'" in script, "x86_64 — официальный NDK")
        assertEquals(4, Regex("sha256sum -c").findAll(script).count(), "cmdline-tools, aapt2, arm-tools и NDK — каждый по сумме")
        assertFalse(AndroidSdk.ARM64_AAPT2_URL in script, "проверенный пользовательский aapt2 заменяет встроенный")
        assertEquals(null, (AndroidSdk.community("", "", "aapt2") as Outcome.Success).value)
        assertTrue(AndroidSdk.community("http://example.org/a", sha, "aapt2") is Outcome.Failure, "только https")
        assertTrue(AndroidSdk.community("https://example.org/a'; rm -rf /", sha, "aapt2") is Outcome.Failure, "без кавычек и пробелов")
        assertTrue(AndroidSdk.community("https://example.org/a", "xyz", "aapt2") is Outcome.Failure)
        assertEquals(sha, (AndroidSdk.community(" https://example.org/a ", sha.uppercase(), "aapt2") as Outcome.Success).value?.sha256)
    }

    @Test fun exactRequestedComponentsAndNativeExecutablesMustWork() {
        val sdk = dir.toFile()
        fun file(path: String, text: String = "") = sdk.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
        fun executable(path: String, code: Int) = file(path, "#!/bin/sh\nexit $code\n").apply { assertTrue(setExecutable(true)) }
        fun probe(ndk: Boolean = false) = LocalProcessHost().run(ProcessSpec(AndroidSdk.probe("platforms;android-37", "build-tools;37.0.0", ndk),
            sdk.path, System.getenv() + ("ANDROID_HOME" to sdk.path)), 10.seconds).valueOrNull()!!
        file("platforms/android-36/android.jar")
        assertTrue(probe().stdout.any { "PLATFORM_MISSING" in it }, "другая установленная платформа не заменяет запрошенную")
        file("platforms/android-37/android.jar")
        file("build-tools/37.0.0/source.properties")
        executable("cmdline-tools/latest/bin/sdkmanager", 0)
        executable("build-tools/37.0.0/aapt2", 126)
        assertFalse(probe().ok, "успех sdkmanager не скрывает несовместимый aapt2")
        executable("build-tools/37.0.0/aapt2", 0)
        executable("build-tools/37.0.0/zipalign", 0)
        assertFalse(probe().ok, "zipalign без корректного вывода не доказывает запуск")
        file("build-tools/37.0.0/zipalign", "#!/bin/sh\necho zipalign\n").setExecutable(true)
        assertTrue(probe().ok)
        assertFalse(probe(ndk = true).ok, "запрошенный NDK обязателен")
        executable("ndk/${AndroidSdk.NDK.substringAfter(';')}/toolchains/llvm/prebuilt/linux-x86_64/bin/clang", 0)
        assertTrue(probe(ndk = true).ok)
    }
}
