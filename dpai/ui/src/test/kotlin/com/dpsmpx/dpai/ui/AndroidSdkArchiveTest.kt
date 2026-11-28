package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Полная связка четырёх архивов: установка, offline-повтор, ремонт и отказ на повреждении. */
class AndroidSdkArchiveTest {
    @TempDir lateinit var dir: Path

    @Test fun sdkInstallerUsesVerifiedArchivesAcrossIndependentRuns() {
        val script = dir.resolve("sdk.sh")
        Files.writeString(script, AndroidSdk.install(true, true,
            AndroidSdk.Community("https://example.invalid/aapt2", "a1".repeat(32)),
            AndroidSdk.Community("https://example.invalid/ndk.tar", "b2".repeat(32)),
        ).last())
        val fixture = dir.resolve("sdk_test.py")
        Files.write(fixture, checkNotNull(javaClass.getResourceAsStream("android_sdk_archive_test.py")).use { it.readBytes() })
        val result = LocalProcessHost().run(ProcessSpec(listOf("python3", fixture.toString(), script.toString(),
            AndroidSdk.CMDLINE_SHA256, AndroidSdk.ARM64_TOOLS_SHA256, AndroidNativeJdk.PREFIX,
            AndroidSdk.ARM64_TOOL_SHA256.entries.joinToString(",") { "${it.key}:${it.value}" },
        )), 40.seconds)
        assertTrue(result is Outcome.Success, result.toString())
        assertEquals(0, result.value.exitCode, (result.value.stdout + result.value.stderr).joinToString("\n"))
    }
}
