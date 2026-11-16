package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.linux.AlpinePackages
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Живая сверка разбора (D-197): настоящая корневая система Alpine (`-Ddpai.test.alpineRootfs`)
 * и вывод самого apk (`-Ddpai.test.apkVersionOut`), снятый в госте так:
 * `apk update && apk version -l '<' > out.txt`. Каждая строка вида `имя-версия < новая`
 * должна стать обновлением. Без свойств тест пропускается.
 */
class EnvUpdatesLiveTest {
    @Test
    fun everyApkLineIsParsed() {
        val root = System.getProperty("dpai.test.alpineRootfs")
        val out = System.getProperty("dpai.test.apkVersionOut")
        assumeTrue(root != null && out != null)
        val inv = (AlpinePackages.read(NioVfs(Path.of(root!!)), "") as Outcome.Success).value!!
        val lines = Files.readAllLines(Path.of(out!!))
        val expected = lines.filter { Regex("\\s<\\s").containsMatchIn(it) }.map { it.trim().split(Regex("\\s+"))[0] }
        assertTrue(expected.isNotEmpty())
        val got = EnvUpdates.parse(lines, inv).map { "${it.name}-${it.installed}" }
        assertEquals(expected.sorted(), got.sorted())
    }
}
