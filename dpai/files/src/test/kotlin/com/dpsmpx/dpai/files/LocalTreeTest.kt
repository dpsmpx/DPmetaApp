package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Дерево на файловой системе (D-99, T17): ссылки не проходятся, запись — только внутри корня. */
class LocalTreeTest {
    private val base: Path = Files.createTempDirectory("dpai-local-tree")
    private val root: Path = Files.createDirectories(base.resolve("copy"))
    private val outside: Path = Files.createDirectories(base.resolve("app-data"))

    @AfterTest
    fun clean() {
        base.toFile().deleteRecursively()
    }

    private fun code(r: Outcome<*>) = assertIs<Outcome.Failure>(r).errors.single().code

    @Test
    fun writeReadListDelete() {
        val t = LocalTree(root)
        val meta = t.write("notes/a.md", "привет".toByteArray()).valueOrNull()!!
        assertEquals("привет".toByteArray().size.toLong(), meta.size)
        assertEquals("привет", t.read("notes/a.md", 1024).valueOrNull()!!.toString(Charsets.UTF_8))
        assertEquals(listOf("notes"), t.list("").valueOrNull()!!.map { it.name })
        assertEquals(listOf("a.md"), t.list("notes").valueOrNull()!!.map { it.name })
        assertEquals(setOf("notes/a.md"), FileTrees.scan(t).valueOrNull()!!.files.keys)
        assertIs<Outcome.Success<Unit>>(t.delete("notes/a.md"))
        assertFalse(Files.exists(root.resolve("notes/a.md")))
        assertEquals(DiagCode.VALUE_INVALID, code(t.read("../x", 10)))
    }

    /** Ссылка из репозитория на месте временного файла записи не уводит запись за корень (проверка PR #188). */
    @Test
    fun linkAtTempNameIsNotWrittenThrough() {
        val t = LocalTree(root)
        val secret = Files.writeString(outside.resolve("authorized_keys"), "ключ")
        t.write("a.md", "старое".toByteArray())
        Files.createSymbolicLink(root.resolve(FileNames.TMP_PREFIX + "a.md"), secret)
        t.write("a.md", "новое".toByteArray()).valueOrNull()!!
        assertEquals("ключ", Files.readString(secret), "цель ссылки не тронута")
        assertEquals("новое", Files.readString(root.resolve("a.md")))
        assertFalse(Files.isSymbolicLink(root.resolve("a.md")), "на месте файла — файл, а не ссылка")
        assertEquals(listOf("a.md"), root.toFile().list()!!.sorted(), "временного и ссылки не осталось")
    }

    @Test
    fun readLimit() {
        val t = LocalTree(root)
        t.write("big", ByteArray(2000))
        assertEquals(DiagCode.VALUE_INVALID, code(t.read("big", 1000)))
    }

    @Test
    fun symlinksAreNeitherListedNorFollowed() {
        Files.writeString(outside.resolve("secret.txt"), "ключи")
        Files.createSymbolicLink(root.resolve("leak.txt"), outside.resolve("secret.txt"))
        Files.createSymbolicLink(root.resolve("out"), outside)
        val t = LocalTree(root)
        assertTrue(t.list("").valueOrNull()!!.isEmpty(), "ссылки не видны")
        assertTrue(FileTrees.scan(t).valueOrNull()!!.files.isEmpty())
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, code(t.read("leak.txt", 1024)))
        // Запись по ссылке-каталогу ушла бы в данные приложения — отказ.
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, code(t.write("out/evil.txt", "x".toByteArray())))
        assertFalse(Files.exists(outside.resolve("evil.txt")))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, code(t.write("leak.txt", "x".toByteArray())))
        assertEquals("ключи", Files.readString(outside.resolve("secret.txt")))
    }

    @Test
    fun hiddenEntries() {
        Files.createDirectories(root.resolve(".ssh"))
        Files.writeString(root.resolve(".ssh/id"), "k")
        Files.writeString(root.resolve("note.txt"), "n")
        val t = LocalTree(root) { rel -> rel.split('/').contains(".ssh") }
        assertEquals(listOf("note.txt"), t.list("").valueOrNull()!!.map { it.name })
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, code(t.read(".ssh/id", 10)))
    }

    @Test
    fun leftoverTempFilesAreHidden() {
        Files.writeString(root.resolve(FileNames.TMP_PREFIX + "a"), "x")
        assertTrue(LocalTree(root).list("").valueOrNull()!!.isEmpty())
    }
}
