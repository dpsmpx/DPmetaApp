package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TextFileEditTest {
    @TempDir lateinit var dir: Path

    @Test
    fun savesEmptyFileAndPreservesUtf8BomAndCrLf() {
        val tree = LocalTree(dir)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val original = bom + TextFile.encode("first\r\nsecond\r\n")
        tree.write("code.txt", original)
        assertTrue(TextFileEdit.save(tree, "code.txt", original, "new\r\nsecond\r\n").isSuccess)
        val current = tree.read("code.txt", TextFile.MAX_EDIT_BYTES).valueOrNull()!!
        assertContentEquals(bom + TextFile.encode("new\r\nsecond\r\n"), current)
        assertTrue(TextFileEdit.save(tree, "code.txt", current, "").isSuccess)
        assertContentEquals(bom, tree.read("code.txt", 10).valueOrNull()!!)
    }

    @Test
    fun externalChangeIsNotOverwrittenEvenWhenLengthMatches() {
        val tree = LocalTree(dir)
        val original = TextFile.encode("before")
        tree.write("code.txt", original)
        val external = TextFile.encode("newest")
        tree.write("code.txt", external)
        val failure = assertIs<Outcome.Failure>(TextFileEdit.save(tree, "code.txt", original, "manual"))
        assertEquals(DiagCode.STATE_CONFLICT, failure.errors.single().code)
        assertContentEquals(external, tree.read("code.txt", 100).valueOrNull()!!)
    }

    @Test
    fun missingFileAndOversizedTextDoNotCreateOrOverwrite() {
        val tree = LocalTree(dir)
        assertIs<Outcome.Failure>(TextFileEdit.save(tree, "missing.txt", byteArrayOf(), "new"))
        assertTrue(Files.notExists(dir.resolve("missing.txt")))
        val original = TextFile.encode("before")
        tree.write("code.txt", original)
        assertIs<Outcome.Failure>(TextFileEdit.save(tree, "code.txt", original, "я".repeat(TextFile.MAX_EDIT_BYTES.toInt())))
        assertContentEquals(original, tree.read("code.txt", 100).valueOrNull()!!)
        assertIs<Outcome.Failure>(TextFileEdit.save(tree, "code.txt", original, "bad\u0000text"))
        assertIs<Outcome.Failure>(TextFileEdit.save(tree, "code.txt", original, "bad\uD800"))
        assertContentEquals(original, tree.read("code.txt", 100).valueOrNull()!!)
    }
}
