package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.formats.RegistryFormat
import com.dpsmpx.dpmeta.sync.DirectoryReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Каталог подсистемы (`dir`, D-46) — в клоне DPmetaApp: сессии кладут его и
 * как `dpmetaapp` (ключ реестра), и как `DPmetaApp` (имя репозитория).
 */
class DirectoryReaderTest {
    private val registry = RegistryFormat.parse(
        """
        schema = 1

        [[repo]]
        name = "DPmetaApp"
        slug = "example/DPmetaApp"
        clone = "dpmetaapp"
        place = "control"

        [[repo]]
        name = "Delta"
        slug = "example/Delta"
        place = "core"
        dir = "delta"
        """.trimIndent(),
    ).let { (it as Outcome.Success).value }

    private fun readsFrom(cloneDir: String) {
        val root = Files.createTempDirectory("clones")
        val file = root.resolve(cloneDir).resolve("delta").resolve("TODO.md")
        Files.createDirectories(file.parent)
        Files.writeString(file, "# TODO\n")
        val r = DirectoryReader(root).file(registry.byName("Delta")!!, "TODO.md")
        assertIs<Outcome.Success<*>>(r, "$cloneDir: ${(r as? Outcome.Failure)?.diagnostics}")
        assertEquals("# TODO\n", (r as Outcome.Success).value.value)
    }

    @Test
    fun subsystemIsReadFromLowercaseClone() = readsFrom("dpmetaapp")

    @Test
    fun subsystemIsReadFromRepositoryNamedClone() = readsFrom("DPmetaApp")
}
