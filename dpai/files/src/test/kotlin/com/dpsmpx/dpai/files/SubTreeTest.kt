package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.result.Outcome
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SubTreeTest {
    @TempDir lateinit var dir: Path

    @Test
    fun operationsStayInsideProjectAndKeepHiddenPolicy() {
        Files.createDirectories(dir.resolve("project"))
        val whole = LocalTree(dir) { it.endsWith("/hidden.txt") }
        val tree = SubTree(whole, "project")
        assertTrue(tree.write("src/file.txt", byteArrayOf(1, 2)).isSuccess)
        assertContentEquals(byteArrayOf(1, 2), tree.read("src/file.txt", 100).valueOrNull()!!)
        assertEquals(listOf("src"), tree.list("").valueOrNull()!!.map { it.name })
        for (path in listOf("../outside", "/outside", "src/../../outside", "src\\outside", "")) {
            assertIs<Outcome.Failure>(tree.write(path, byteArrayOf(9)), path)
            assertIs<Outcome.Failure>(tree.delete(path), path)
        }
        assertIs<Outcome.Failure>(tree.write("hidden.txt", byteArrayOf(9)))
        assertTrue(Files.notExists(dir.resolve("outside")))
    }

    @Test
    fun symlinkDoesNotEscapeProject() {
        Files.createDirectories(dir.resolve("project"))
        Files.write(dir.resolve("outside.txt"), byteArrayOf(7))
        Files.createSymbolicLink(dir.resolve("project/link.txt"), dir.resolve("outside.txt"))
        val tree = SubTree(LocalTree(dir), "project")
        assertIs<Outcome.Failure>(tree.read("link.txt", 100))
        assertIs<Outcome.Failure>(tree.write("link.txt", byteArrayOf(8)))
        assertContentEquals(byteArrayOf(7), Files.readAllBytes(dir.resolve("outside.txt")))
    }
}
