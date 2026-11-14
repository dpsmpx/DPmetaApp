package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Подготовка индекса настоящими sh/Git: пользовательские исключения и исходники сохраняются. */
class ClangdIndexTest {
    private val host = LocalProcessHost()

    private fun run(argv: List<String>) = (host.run(ProcessSpec(argv), 10.seconds) as Outcome.Success).value

    @Test
    fun cacheIsIgnoredLocallyAndPreparationIsIdempotent(@TempDir dir: File) {
        assumeTrue(File("/bin/sh").canExecute())
        val project = File(dir, "мой проект").apply { mkdirs() }
        assertTrue(run(listOf("git", "init", "--quiet", project.path)).ok)
        val build = File(project, "build/debug").apply { mkdirs() }
        File(build, "compile_commands.json").writeText("[]")
        val exclude = File(project, ".git/info/exclude").apply { writeText("my-personal-file") }
        val command = (Clangd.prepareIndex(Clangd.Project(project.path, build.path)) as Outcome.Success).value
        repeat(2) {
            val result = run(command)
            assertTrue(result.ok && "DPAI_CPP_INDEX_READY" in result.stdout, result.toString())
        }
        assertEquals("my-personal-file\n**/.cache/clangd/\n", exclude.readText())
        File(build, ".cache/clangd/index").mkdirs()
        File(build, ".cache/clangd/index/main.idx").writeText("cache")
        assertTrue(run(listOf("git", "-C", project.path, "check-ignore", "build/debug/.cache/clangd/index/main.idx")).ok)
        assertFalse(File(project, ".gitignore").exists())
        assertEquals("[]", File(build, "compile_commands.json").readText())
        assertTrue(build.listFiles()!!.none { it.name.startsWith(".dpmetaapp-index.") })
    }

    @Test
    fun missingDatabaseAndRedirectedExcludesAreRefused(@TempDir dir: File) {
        assumeTrue(File("/bin/sh").canExecute())
        assertTrue(run(listOf("git", "init", "--quiet", dir.path)).ok)
        val exclude = File(dir, ".git/info/exclude")
        val original = exclude.readText()
        val project = Clangd.Project(dir.path, dir.path)
        val command = (Clangd.prepareIndex(project) as Outcome.Success).value
        assertFalse(run(command).ok)
        assertEquals(original, exclude.readText())
        File(dir, "compile_commands.json").writeText("[]")
        val outside = File(dir.parentFile, "exclude-outside-${dir.name}").apply { writeText("keep") }
        try {
            exclude.delete()
            java.nio.file.Files.createSymbolicLink(exclude.toPath(), outside.toPath())
            assertFalse(run(command).ok)
            assertEquals("keep", outside.readText())
        } finally {
            outside.delete()
        }
        assertTrue(Clangd.prepareIndex(Clangd.Project(dir.path, null)) is Outcome.Failure)
    }

}
