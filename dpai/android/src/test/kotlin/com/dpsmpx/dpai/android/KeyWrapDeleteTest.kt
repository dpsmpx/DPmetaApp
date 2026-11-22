package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.result.Outcome
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class KeyWrapDeleteTest {
    @TempDir lateinit var dir: Path

    @Test fun deleteRemovesCommittedAndInterruptedCopy() {
        val root = dir.resolve("dpai-keys")
        Files.createDirectories(root)
        Files.write(root.resolve("sample.p12"), byteArrayOf(1, 2))
        Files.write(root.resolve("sample.p12.tmp"), byteArrayOf(3, 4))
        assertTrue(KeyWrap.deleteChecked(dir.toFile(), "sample.p12") is Outcome.Success)
        assertFalse(Files.exists(root.resolve("sample.p12")))
        assertFalse(Files.exists(root.resolve("sample.p12.tmp")))
    }

    @Test fun failureToRemoveCopyIsNotReportedAsSuccess() {
        val blocked = dir.resolve("dpai-keys/sample.p12")
        Files.createDirectories(blocked)
        Files.write(blocked.resolve("occupied"), byteArrayOf(1))
        assertTrue(KeyWrap.deleteChecked(dir.toFile(), "sample.p12") is Outcome.Failure)
    }
}
