package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Индексы зеркала в кеше apk (D-195). Имя файла сверено с кешем apk-tools 3.0.8: индекс
 * `https://dl-cdn.alpinelinux.org/alpine/v3.24/main` для x86_64 лежит как `APKINDEX.630cb808.tar.gz`.
 * Образец индекса — синтетический, в том же виде: подпись и индекс — два члена gzip подряд.
 */
class ApkIndexTest {
    @TempDir
    lateinit var dir: Path

    private fun tarEntry(name: String, body: ByteArray): ByteArray {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h)
        "%011o\u0000".format(body.size).toByteArray().copyInto(h, 124)
        val pad = ByteArray(((body.size + 511) / 512) * 512 - body.size)
        return h + body + pad
    }

    private fun gz(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()

    private val index = "P:git\nV:2.54.1-r0\np:cmd:git=2.54.1-r0\n\nP:git\nV:2.54.0-r0\n\nP:g++\nV:15.2.0-r5\np:cmd:c++=15.2.0-r5 cmd:g++=15.2.0-r5\n"

    @Test
    fun fileNameMatchesApkCache() {
        assertEquals("APKINDEX.630cb808.tar.gz", ApkIndex.fileName("https://dl-cdn.alpinelinux.org/alpine/v3.24/main", "x86_64"))
        assertEquals("APKINDEX.9c5ff2cc.tar.gz", ApkIndex.fileName("https://dl-cdn.alpinelinux.org/alpine/v3.24/community/", "x86_64"))
    }

    @Test
    fun extractsIndexAfterSignatureMember() {
        val signed = gz(tarEntry(".SIGN.RSA.alpine-devel@lists.alpinelinux.org-6165ee59.rsa.pub", ByteArray(256) { 7 })) + gz(tarEntry("DESCRIPTION", "v3.24.3".toByteArray()) + tarEntry("APKINDEX", index.toByteArray()))
        assertEquals(index, ApkIndex.extract(signed))
        assertNull(ApkIndex.extract("не gzip".toByteArray()))
        val idx = ApkIndex.parse(index)
        assertEquals("2.54.1-r0", idx.versions["git"], "у пакета с двумя версиями — старшая")
        assertEquals(listOf("g++"), idx.commands["c++"])
    }

    @Test
    fun readsOnlyCurrentRepositories() {
        val cache = dir.resolve("rootfs/var/cache/apk").also { Files.createDirectories(it) }
        val main = "https://dl-cdn.alpinelinux.org/alpine/v3.24/main"
        val edge = "https://dl-cdn.alpinelinux.org/alpine/edge/main"
        Files.write(cache.resolve(ApkIndex.fileName(main, "aarch64")), gz(tarEntry("APKINDEX", index.toByteArray())))
        Files.write(cache.resolve(ApkIndex.fileName(edge, "aarch64")), gz(tarEntry("APKINDEX", "P:git\nV:9.0-r0\n".toByteArray())))
        val (idx, missing) = ApkIndex.read(NioVfs(dir), "rootfs", listOf(main, "https://dl-cdn.alpinelinux.org/alpine/v3.24/community"), "aarch64")
        assertEquals("2.54.1-r0", idx.versions["git"], "индекс edge в кеше есть, но он не из нынешних репозиториев")
        assertEquals(listOf("https://dl-cdn.alpinelinux.org/alpine/v3.24/community"), missing)
        assertTrue(ApkIndex.read(NioVfs(dir), "rootfs", emptyList(), "aarch64").first.empty)
    }
}
