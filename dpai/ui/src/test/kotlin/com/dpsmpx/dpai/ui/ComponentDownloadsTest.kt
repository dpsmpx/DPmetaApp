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

/** Реальная оболочка и процессы: кэш не равен успешному HTTP или имени файла. */
class ComponentDownloadsTest {
    @TempDir lateinit var dir: Path

    @Test fun verifiedDownloadsSurviveOfflineRetryCorruptionAndConcurrentInstallers() {
        val library = dir.resolve("cache.sh")
        Files.writeString(library, ComponentDownloads.shell)
        val test = dir.resolve("cache_test.py")
        Files.write(test, checkNotNull(javaClass.getResourceAsStream("component_cache_test.py")).use { it.readBytes() })
        val result = LocalProcessHost().run(ProcessSpec(listOf("python3", test.toString(), library.toString())), 40.seconds)
        assertTrue(result is Outcome.Success, result.toString())
        assertEquals(0, result.value.exitCode, (result.value.stdout + result.value.stderr).joinToString("\n"))
    }
}
