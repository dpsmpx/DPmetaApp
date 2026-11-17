package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android SDK по желанию (D-185): официальное по закреплённой сумме, сообщество — по сумме автора. */
class AndroidSdkTest {
    private val sha = "ab".repeat(32)

    @Test
    fun officialPathIsPinnedAndNeedsLicense() {
        val script = AndroidSdk.install(licenses = true, ndk = false, aapt2 = null, ndkCommunity = null).last()
        assertTrue(AndroidSdk.CMDLINE_URL in script && AndroidSdk.CMDLINE_SHA256 in script && "sha256sum -c" in script)
        assertTrue("'${AndroidSdk.PLATFORM}' '${AndroidSdk.BUILD_TOOLS}'" in script)
        assertFalse("--licenses" in script, "согласие только для выбранных SDK-пакетов, не для всех лицензий каталога")
        assertFalse("aapt2FromMavenOverride" in script, "без сборки сообщества — без подмены aapt2")
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
        assertEquals(3, Regex("sha256sum -c").findAll(script).count(), "cmdline-tools, aapt2 и NDK — каждый по сумме")
        assertEquals(null, (AndroidSdk.community("", "", "aapt2") as Outcome.Success).value)
        assertTrue(AndroidSdk.community("http://example.org/a", sha, "aapt2") is Outcome.Failure, "только https")
        assertTrue(AndroidSdk.community("https://example.org/a'; rm -rf /", sha, "aapt2") is Outcome.Failure, "без кавычек и пробелов")
        assertTrue(AndroidSdk.community("https://example.org/a", "xyz", "aapt2") is Outcome.Failure)
        assertEquals(sha, (AndroidSdk.community(" https://example.org/a ", sha.uppercase(), "aapt2") as Outcome.Success).value?.sha256)
    }
}
