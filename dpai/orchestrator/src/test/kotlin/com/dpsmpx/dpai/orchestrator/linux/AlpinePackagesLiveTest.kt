package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Живая сверка (D-191): настоящая корневая система Alpine (`-Ddpai.test.alpineRootfs=<каталог>`)
 * и ответ самого `apk` на пробное удаление каждого пакета из `world`
 * (`-Ddpai.test.alpineDel=<файл>`). Файл собирается в госте так:
 *
 * ```sh
 * for n in $(cat /etc/apk/world); do echo "## $n"; apk del -s --no-network "$n" 2>&1 | grep Purging; done
 * ```
 *
 * Без свойств тест пропускается.
 */
class AlpinePackagesLiveTest {
    @Test
    fun matchesApkSimulation() {
        val root = System.getProperty("dpai.test.alpineRootfs")
        val del = System.getProperty("dpai.test.alpineDel")
        assumeTrue(root != null && del != null)
        val inv = (AlpinePackages.read(NioVfs(Path.of(root!!)), "") as Outcome.Success).value!!
        assertEquals(emptyList(), AlpinePackages.problems(inv), "настоящая среда согласована")
        val expected = LinkedHashMap<String, MutableSet<String>>()
        var cur: MutableSet<String>? = null
        for (line in Files.readAllLines(Path.of(del!!))) {
            if (line.startsWith("## ")) cur = sortedSetOf<String>().also { expected[line.removePrefix("## ").trim()] = it }
            Regex("Purging (\\S+) \\(").find(line)?.let { cur!! += it.groupValues[1] }
        }
        assertTrue(expected.isNotEmpty())
        val wrong = expected.mapNotNull { (name, want) ->
            val r = AlpinePackages.removal(inv, name)
            if (!r.allowed) return@mapNotNull null
            val got = r.gone.map { it.name }.toSortedSet()
            if (got != want) "$name: apk $want, здесь $got" else null
        }
        assertEquals(emptyList(), wrong)
    }
}
