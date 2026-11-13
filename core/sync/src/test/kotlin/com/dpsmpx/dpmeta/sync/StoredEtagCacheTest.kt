package com.dpsmpx.dpmeta.sync

import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.net.CachedResponse
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class StoredEtagCacheTest {
    @TempDir lateinit var directory: Path
    private fun documents() = DocumentStore(NioVfs(directory), "cache")
    private fun value(text: String) = CachedResponse("v1", text.toByteArray())

    @Test
    fun leastRecentlyUsedEntryIsEvictedAlsoAfterRestart() {
        val cache = StoredEtagCache(documents(), maxEntries = 2)
        cache.put("a", value("A")); cache.put("b", value("B"))
        cache.get("a")
        assertTrue(cache.save().isSuccess)
        val restored = StoredEtagCache(documents(), maxEntries = 2)
        restored.put("c", value("C"))
        assertNull(restored.get("b"))
        assertEquals("A", restored.get("a")!!.body.toString(Charsets.UTF_8))
        assertEquals("C", restored.get("c")!!.body.toString(Charsets.UTF_8))
    }

    @Test
    fun byteLimitEvictsOldBodiesAndRefusesOneOversizedBody() {
        val cache = StoredEtagCache(documents(), maxBytes = 200)
        for (key in listOf("a", "b", "c")) cache.put(key, value("x".repeat(80)))
        assertNull(cache.get("a"))
        cache.put("huge", value("x".repeat(300)))
        assertNull(cache.get("huge"))
        assertTrue(cache.get("b") != null && cache.get("c") != null)
    }

    @Test
    fun corruptBase64AndEtagAreSkippedAndCleanedWithoutLosingGoodRows() {
        fun row(etag: String, body: String) = JsonObject.of("etag" to JsonString(etag), "body" to JsonString(body))
        documents().write(StoredEtagCache.KEY, JsonObject.of(
            "bad" to row("v1", "not base64"), "header" to row("bad\nheader", "QQ=="),
            "good" to row("v1", Base64.getEncoder().encodeToString("ok".toByteArray())),
        ))
        val cache = StoredEtagCache(documents())
        assertNull(cache.get("bad")); assertNull(cache.get("header"))
        assertEquals("ok", cache.get("good")!!.body.toString(Charsets.UTF_8))
        assertTrue(cache.save().isSuccess)
        assertEquals(setOf("good"), documents().read(StoredEtagCache.KEY).valueOrNull()!!.content.fields.keys)
    }

    @Test
    fun tokenChangeDropsOldPrivateBodiesOfThatOriginAndKeepsPublicOnes() {
        val cache = StoredEtagCache(documents())
        cache.put("https://api.github.com/public", value("public"))
        cache.put("https://api.github.com/private#old", value("private"))
        cache.put("https://raw.githubusercontent.com/data#old", value("other origin"))
        cache.get("https://api.github.com/private#new")
        assertTrue(cache.save().isSuccess)
        val keys = documents().read(StoredEtagCache.KEY).valueOrNull()!!.content.fields.keys
        assertFalse("https://api.github.com/private#old" in keys)
        assertTrue("https://api.github.com/public" in keys)
        assertTrue("https://raw.githubusercontent.com/data#old" in keys)
    }

    @Test
    fun paginationMetadataSurvivesRestartAndUnchangedSaveDoesNotWriteAgain() {
        val link = "<https://api.github.com/repos/x/y?page=2>; rel=\"next\""
        val cache = StoredEtagCache(documents())
        cache.put("a", CachedResponse("v1", "[]".toByteArray(), link))
        assertTrue(cache.save().isSuccess)
        assertTrue(cache.save().isSuccess)
        assertFalse(directory.resolve("cache/etags.prev").toFile().exists())
        assertEquals(link, StoredEtagCache(documents()).get("a")!!.link)
    }
}
