package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Связи папок `dpai.file-links` v1, текст файла, имена и вложения в чат (D-99). */
class FileLinkBookTest {
    private val id = IdKinds.CORRELATION.parse("cor_01J9ZQ3K8M7N6P5R4S3T2V1W0X").valueOrNull()!!
    private val sha = "a".repeat(64)
    private val link = FileLink(
        id, "Проекты", "content://com.android.externalstorage.documents/tree/primary%3AProjects", "Проекты", 1_700_000_000_000,
        mapOf("notes/a.md" to BaseEntry(sha, FileMeta(3, 10), FileMeta(3, 20))),
    )

    @Test
    fun roundTrip() {
        assertEquals(listOf(link), FileLinkBook.fromJson(FileLinkBook.toJson(listOf(link))).valueOrNull())
        val fresh = link.copy(lastSync = null, base = emptyMap())
        assertEquals(listOf(fresh), FileLinkBook.fromJson(FileLinkBook.toJson(listOf(fresh))).valueOrNull())
    }

    @Test
    fun strictReading() {
        val good = FileLinkBook.toJson(listOf(link)).toString()
        for (bad in listOf(
            good.replace("notes/a.md", "../a.md"),
            good.replace(sha, "zz"),
            good.replace("content://", "file:///"),
            good.replace("\"dir\":\"Проекты\"", "\"dir\":\"a/b\""),
            good.replace("\"title\":", "\"x\":1,\"title\":"),
            good.replace("\"version\":1", "\"version\":2"),
        )) {
            assertIs<Outcome.Failure>(FileLinkBook.fromJson(JsonParser().parse(bad).valueOrNull()!!), bad)
        }
        val twice = FileLinkBook.toJson(listOf(link, link.copy(id = IdKinds.CORRELATION.parse("cor_01J9ZQ3K8M7N6P5R4S3T2V1W0Y").valueOrNull()!!)))
        assertIs<Outcome.Failure>(FileLinkBook.fromJson(twice), "каталог копии повторяется")
    }

    @Test
    fun names() {
        assertTrue(FileNames.segmentValid("отчёт 2026.md"))
        for (bad in listOf("", ".", "..", "a/b", "a\\b", "a:b", "a\u0000b", "x".repeat(256))) assertTrue(!FileNames.segmentValid(bad), bad)
        assertEquals("Мои_файлы", FileNames.localDir("Мои/файлы", emptySet()))
        assertEquals("Docs-2", FileNames.localDir("Docs", setOf("Docs")))
        assertEquals("Docs-3", FileNames.localDir("Docs", setOf("Docs", "Docs-2")))
        assertEquals("folder", FileNames.localDir("..", emptySet()))
    }

    @Test
    fun textFiles() {
        assertEquals("привет", TextFile.decode("привет".toByteArray()).valueOrNull())
        assertEquals("x", TextFile.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'x'.code.toByte())).valueOrNull(), "BOM снят")
        for (bad in listOf(byteArrayOf(0x41, 0x00, 0x42), byteArrayOf(0xC3.toByte(), 0x28), ByteArray(11) { 0x41 })) {
            assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(TextFile.decode(bad, max = 10)).errors.single().code)
        }
        assertTrue(TextFile.isImage("фото.JPG"))
        assertTrue(!TextFile.isImage("readme.md"))
    }

    @Test
    fun attachments() {
        assertEquals("Вопрос?\n\nФайл «a.kt»:\n```\nfun x() = 1\n```", ChatAttachments.message(" Вопрос? ", listOf("a.kt" to "fun x() = 1")))
        // Файл с ``` внутри не закрывает блок раньше времени.
        val r = ChatAttachments.render("README.md", "пример:\n```\ncode\n```")
        assertTrue(r.startsWith("Файл «README.md»:\n````\n") && r.endsWith("\n````"), r)
    }
}
