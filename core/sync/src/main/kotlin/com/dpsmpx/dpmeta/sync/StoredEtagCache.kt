package com.dpsmpx.dpmeta.sync

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpmeta.net.CachedResponse
import com.dpsmpx.dpmeta.net.EtagCache
import java.util.Base64

/**
 * Кэш условных запросов между синхронизациями — `dpmeta-cli sync --live` и
 * пульта на телефоне (ТЗ, §6.4,
 * A-02): документ `etags` хранилища — адрес → ETag и тело. Ключ уже не
 * содержит токена (только отпечаток, `:core:net`).
 */
class StoredEtagCache(private val documents: DocumentStore) : EtagCache {
    private val map = LinkedHashMap<String, CachedResponse>()
    private var dirty = false

    init {
        val doc = (documents.read(KEY) as? Outcome.Success)?.value?.content
        for ((k, v) in doc?.fields.orEmpty()) {
            val o = v as? JsonObject ?: continue
            val etag = (o["etag"] as? JsonString)?.value ?: continue
            val body = (o["body"] as? JsonString)?.value ?: continue
            map[k] = CachedResponse(etag, Base64.getDecoder().decode(body))
        }
    }

    @Synchronized
    override fun get(key: String): CachedResponse? = map[key]

    @Synchronized
    override fun put(key: String, value: CachedResponse) {
        map[key] = value
        dirty = true
    }

    @Synchronized
    fun save(): Outcome<Unit> {
        if (!dirty) return Outcome.success(Unit)
        val json = JsonObject(
            map.toSortedMap().mapValues { (_, v) ->
                JsonObject.of("etag" to JsonString(v.etag), "body" to JsonString(Base64.getEncoder().encodeToString(v.body))) as JsonValue
            },
        )
        return when (val r = documents.write(KEY, json)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(Unit)
        }
    }

    companion object {
        const val KEY = "etags"
    }
}
