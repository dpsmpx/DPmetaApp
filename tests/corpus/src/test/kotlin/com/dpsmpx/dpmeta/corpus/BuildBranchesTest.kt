package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.git.Clones
import com.dpsmpx.dpmeta.metrics.Rules
import com.dpsmpx.dpmeta.model.ProjectList
import com.dpsmpx.dpai.core.git.RepoRef
import com.dpsmpx.dpmeta.sync.CloneReader
import com.dpsmpx.dpmeta.sync.DirectoryReader
import com.dpsmpx.dpmeta.sync.Got
import com.dpsmpx.dpmeta.github.Branch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.io.TempDir

/** D-176: ветки пользователя не скрываются по соглашениям автора. */
class BuildBranchesTest {
    @TempDir lateinit var root: Path

    @Test
    fun allUserBranchNamesReachSynchronization() {
        val names = listOf("main", "feature", "apk_debug", "linux_debug", "windows_debug", "linux-debug", "apk-build", "apk-builds", "windows-debug-build", "win_debug_builds")
        val directory = Files.createDirectories(root.resolve("example/p"))
        Files.writeString(directory.resolve("branches.json"), names.mapIndexed { i, name ->
            """{"name":"$name","sha":"$i","date":"2026-11-01T00:00:00Z"}"""
        }.joinToString(",", "[", "]"))
        val project = ProjectList().add(RepoRef.parse("example/P").valueOrNull()!!).valueOrNull()!!.forSync().projects.single()
        val reader = CloneReader(Clones(root.resolve("clones").toFile(), Clones.allowList()), DirectoryReader(root), Rules::isExcludedBranch)
        val result = assertIs<Outcome.Success<Got<List<Branch>>>>(reader.branches(project)).value
        assertEquals(names, result.value.map { it.name })
    }
}
