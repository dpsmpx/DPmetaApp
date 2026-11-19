package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.model.RepoRef
import com.dpsmpx.dpmeta.model.SyncProject
import com.dpsmpx.dpmeta.sync.DirectoryReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Каталоги — владелец/репозиторий, без clone/dir и перенаправления на чужой клон. */
class DirectoryReaderTest {
    @Test
    fun sameNameRepositoriesOfDifferentOwnersRemainIndependent() {
        val root = Files.createTempDirectory("repos")
        try {
            for (owner in listOf("one", "two")) {
                val file = root.resolve(owner).resolve("app/TODO.md")
                Files.createDirectories(file.parent)
                Files.writeString(file, "# $owner\n")
            }
            val reader = DirectoryReader(root)
            for (owner in listOf("one", "two")) {
                val project = SyncProject("app", RepoRef.parse("$owner/App").valueOrNull()!!)
                assertEquals("# $owner\n", assertIs<Outcome.Success<*>>(reader.file(project, "TODO.md")).let { (it.value as com.dpsmpx.dpmeta.sync.Got<*>).value })
                assertIs<Outcome.Failure>(reader.file(project, "../outside.md"))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
