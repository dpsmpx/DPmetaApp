package com.dpsmpx.dpmeta.sync

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpmeta.net.CachedResponse
import com.dpsmpx.dpmeta.net.EtagCache
import java.util.Base64
import java.net.URI

/**
 * Кэш условных запросов между синхронизациями пульта на телефоне (ТЗ, §6.4,
 * A-02): документ `etags` хранилища — адрес → ETag и тело. Ключ уже не
 * содержит токена (только отпечаток, `:core:net`).
 */
class StoredEtagCache(
    private val documents: DocumentStore,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxBytes: Long = MAX_BYTES,
) : EtagCache {
    private val map = LinkedHashMap<String, CachedResponse>(16, 0.75f, true)
    private var bytes = 0L
    private var dirty = false

    init {
        require(maxEntries > 0 && maxBytes > 0) { "пределы кеша должны быть положительными" }
        val doc = (documents.read(KEY) as? Outcome.Success)?.value?.content
        val entries = doc?.fields.orEmpty().entries.sortedBy { (_, value) ->
            ((value as? JsonObject)?.get("order") as? JsonNumber)?.toLongExact() ?: 0L
        }
        for ((k, v) in entries) {
            val o = v as? JsonObject ?: continue
            val etag = (o["etag"] as? JsonString)?.value ?: continue
            val body = (o["body"] as? JsonString)?.value ?: continue
            // K-06: повреждённая строка и слишком большое тело не роняют следующую синхронизацию.
            if (body.length.toLong() > maxBytes * 2) { dirty = true; continue }
            val decoded = runCatching { Base64.getDecoder().decode(body) }.getOrNull()
            if (decoded == null) { dirty = true; continue }
            val value = CachedResponse(etag, decoded, (o["link"] as? JsonString)?.value)
            if (!value.usable || size(k, value) > maxBytes) { dirty = true; continue }
            map[k] = value
            bytes += size(k, value)
            trim()
        }
    }

    @Synchronized
    override fun get(key: String): CachedResponse? {
        alignAuth(key)
        return map[key]?.also { dirty = true }
    }

    @Synchronized
    override fun put(key: String, value: CachedResponse) {
        alignAuth(key)
        remove(key)
        if (!value.usable || size(key, value) > maxBytes) return
        map[key] = value
        bytes += size(key, value)
        dirty = true
        trim()
    }

    private fun size(key: String, value: CachedResponse): Long =
        value.body.size.toLong() + 2L * (key.length.toLong() + value.etag.length + (value.link?.length ?: 0))

    private fun remove(key: String) {
        val previous = map.remove(key) ?: return
        bytes -= size(key, previous)
        dirty = true
    }

    private fun trim() {
        while (map.size > maxEntries || bytes > maxBytes) remove(map.keys.first())
    }

    /** При смене токена одного origin старые закрытые ответы удаляются, публичные остаются. */
    private fun alignAuth(key: String) {
        val current = partition(key) ?: return
        for (previous in map.keys.toList()) {
            val old = partition(previous) ?: continue
            if (old.first == current.first && old.second != null && old.second != current.second) remove(previous)
        }
    }

    private fun partition(key: String): Pair<String, String?>? = runCatching {
        val uri = URI(key.substringBeforeLast('#', key))
        if (uri.scheme == null || uri.host == null) null
        else "${uri.scheme}://${uri.rawAuthority}" to key.substringAfterLast('#', "").takeIf { it.isNotEmpty() }
    }.getOrNull()

    @Synchronized
    fun save(): Outcome<Unit> {
        if (!dirty) return Outcome.success(Unit)
        val fields = linkedMapOf<String, JsonValue>()
        for ((index, entry) in map.entries.withIndex()) {
            val (key, value) = entry
            val row = linkedMapOf<String, JsonValue>("etag" to JsonString(value.etag),
                "body" to JsonString(Base64.getEncoder().encodeToString(value.body)), "order" to JsonNumber.of(index.toLong()))
            value.link?.let { row["link"] = JsonString(it) }
            fields[key] = JsonObject(row)
        }
        val json = JsonObject(fields)
        return when (val r = documents.write(KEY, json)) {
            is Outcome.Failure -> r
            is Outcome.Success -> { dirty = false; Outcome.success(Unit) }
        }
    }

    companion object {
        const val KEY = "etags"
        const val MAX_ENTRIES = 512
        const val MAX_BYTES = 16L * 1024 * 1024
    }
}
