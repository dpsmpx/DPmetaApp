package com.dpsmpx.dpai.contexts

/** Производный текст одного владельца/области. Вызывающий сначала хеширует реальные байты, не mtime. */
class ContextTextCache(private val maxChars: Int = 1_000_000, private val maxEntries: Int = 64) {
    init { require(maxChars > 0 && maxEntries > 0) }
    private val entries = LinkedHashMap<String, String>(16, 0.75f, true)
    private var chars = 0
    @Synchronized fun get(binding: ContextBinding, sha256: String): String? = entries[binding.revision + ":" + sha256]
    @Synchronized fun put(binding: ContextBinding, sha256: String, text: String) {
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        if (text.length > maxChars) return
        val key = binding.revision + ":" + sha256
        chars -= entries.remove(key)?.length ?: 0
        entries[key] = text
        chars += text.length
        while (chars > maxChars || entries.size > maxEntries) {
            val oldest = entries.keys.first()
            chars -= entries.remove(oldest)!!.length
        }
    }
}
